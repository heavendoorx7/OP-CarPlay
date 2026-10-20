package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** In-memory frames over a local socket. Only the existing shell UID can read them. */
internal class InstrumentFrameServer(
    private val capture: (Int,Int) -> Pair<Long,Bitmap>?,
    private val hideNativeLane: () -> Boolean,
) : AutoCloseable {
    @Volatile private var closed=false
    @Volatile private var server: LocalServerSocket?=null
    @Volatile private var client: LocalSocket?=null
    init { Thread(::serve,"op-instrument-frames").apply { isDaemon=true;start() } }

    private fun serve() {
        try {
            val listener=LocalServerSocket(P7InstrumentCommand.FRAME_SOCKET);server=listener
            if(closed)return
            while(!closed) {
                val socket=listener.accept();client=socket
                try {
                    if(socket.peerCredentials.uid!=2000)continue
                    socket.soTimeout=3000
                    val input=DataInputStream(socket.inputStream);val output=DataOutputStream(socket.outputStream)
                    require(input.readInt()==0x4f504332)
                    output.writeInt(0x4f504332);output.flush()
                    while(!closed) {
                        when(input.readInt()) {
                            0 -> break
                            2 -> {output.writeInt(1);output.flush()}
                            3 -> {output.writeInt(if(hideNativeLane())1 else 0);output.flush()}
                            1 -> {
                                val width=input.readInt();val height=input.readInt()
                                require(width in 64..1024 && height in 64..1024)
                                val frame=capture(width,height)
                                if(frame==null) {output.writeLong(-1);output.writeInt(0);output.flush();continue}
                                val bytes=ByteArrayOutputStream()
                                try {check(frame.second.compress(Bitmap.CompressFormat.JPEG,70,bytes))}
                                finally {frame.second.recycle()}
                                check(bytes.size() in 4..1_048_576)
                                output.writeLong(frame.first);output.writeInt(bytes.size());bytes.writeTo(output);output.flush()
                            }
                            else -> break
                        }
                    }
                } catch (_: Exception) { /* No map pixels, routes or exception messages are logged. */ }
                finally {runCatching{socket.close()};client=null}
            }
        } catch (_: Exception) { }
        finally {runCatching{server?.close()};server=null}
    }
    override fun close() {closed=true;runCatching{client?.close()};runCatching{server?.close()}}
}
