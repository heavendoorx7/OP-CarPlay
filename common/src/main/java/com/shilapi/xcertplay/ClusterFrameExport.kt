package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * On-device frames for the explicitly run shell test. No TCP port, file recording,
 * vehicle API, permission grant, or independent automatic vehicle output.
 */
class ClusterFrameExport(
    private val socketName: String,
    private val capture: (Int, Int) -> Pair<Long, Bitmap>?,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val count = AtomicLong()
    val exportedFrames: Long get() = count.get()
    @Volatile private var stopped = false
    @Volatile private var listener: LocalServerSocket? = null
    @Volatile private var client: LocalSocket? = null
    private val worker = Thread(::serve, "op-cluster-local-frames").apply {
        isDaemon = true
        start()
    }

    private fun serve() {
        try {
            val server = LocalServerSocket(socketName)
            listener = server
            if (stopped) { server.close(); return }
            while (!stopped) {
                val socket = server.accept()
                client = socket
                // The helper is an existing ADB/Shizuku shell, not an arbitrary local app.
                if (socket.peerCredentials.uid != 2000) {
                    socket.close()
                    client = null
                    continue
                }
                val deadline = Runnable { runCatching { socket.close() } }
                main.postDelayed(deadline, 30_000)
                try {
                    socket.soTimeout = 2500
                    val input = DataInputStream(socket.inputStream)
                    val output = DataOutputStream(socket.outputStream)
                    require(input.readInt() == 0x4f504331)
                    val width = input.readInt()
                    val height = input.readInt()
                    require(width in 64..1024 && height in 64..1024)
                    while (!stopped && input.readInt() == 1) {
                        val task = FutureTask { capture(width, height) }
                        main.post(task)
                        val frame = try {
                            task.get(1500, TimeUnit.MILLISECONDS)
                        } catch (error: Exception) {
                            // If capture already ran, retrieve and recycle its result on the UI thread.
                            main.post { runCatching { task.get()?.second?.recycle() } }
                            throw error
                        }
                        if (frame == null) {
                            output.writeLong(-1)
                            output.writeInt(0)
                            output.flush()
                            continue
                        }
                        val bytes = ByteArrayOutputStream()
                        try {
                            check(frame.second.compress(Bitmap.CompressFormat.JPEG, 70, bytes))
                        } finally { frame.second.recycle() }
                        check(bytes.size() in 4..1_048_576)
                        output.writeLong(frame.first)
                        output.writeInt(bytes.size())
                        bytes.writeTo(output)
                        output.flush()
                        count.incrementAndGet()
                    }
                } catch (_: Exception) {
                    // Result is recorded by the shell helper; no map or exception text is logged.
                } finally {
                    main.removeCallbacks(deadline)
                    runCatching { socket.close() }
                    client = null
                }
            }
        } catch (_: Exception) {
            // Duplicate/closed socket is a failed export, never a reason to restart CarPlay.
        } finally { runCatching { listener?.close() }; listener = null }
    }

    override fun close() {
        stopped = true
        runCatching { client?.close() }
        runCatching { listener?.close() }
    }
}
