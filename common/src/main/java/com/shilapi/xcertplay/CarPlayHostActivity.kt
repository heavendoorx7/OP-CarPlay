package com.shilapi.xcertplay

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.P7ViewAreas
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayMediaSessionService
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.MainMediaAudioBuffer
import com.shilapi.xcertplay.media.MediaMetricsMonitor
import com.shilapi.xcertplay.media.MicrophoneGain
import com.shilapi.xcertplay.media.MicrophoneLevelMonitor
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.UsbDeviceId
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
open class CarPlayHostActivity : ComponentActivity() {
    private data class SettingsBaseline(
        val safeAreaSize: DisplaySize?,
        val safeAreaRect: SafeAreaRect?,
        val customIconBytes: ByteArray?,
    )

    private lateinit var airPlayIdentity: AirPlayIdentity

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        localMfiCertificateUri = localMfiCertificateUri.takeIf { it.isNotEmpty() },
        localMfiPrivateKeyUri = localMfiPrivateKeyUri.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = normalizedModel(),
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "xcertplay",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
        ),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        locationReportingEnabled = locationReportingEnabled,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                maybeStartCarPlay()
            } else {
                setStatus("VPN consent was denied")
            }
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            val startGainTest = granted && microphoneGainTestAfterPermission && menuOpen
            microphoneGainTestAfterPermission = false
            requestStartupPrerequisites()
            if (startGainTest) startMicrophoneGainTest()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                locationReportingSwitch?.isChecked = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                externalActivityInProgress = false
                return@registerForActivityResult
            }
            imageCrop.launch(
                Intent(this, ImageCropActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            externalActivityInProgress = false
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }
    private val localMfiCertificatePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            importLocalMfiDocument(uri, certificate = true)
        }
    private val localMfiPrivateKeyPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            importLocalMfiDocument(uri, certificate = false)
        }
    private val localMfiCertificateContentPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            importLocalMfiDocument(uri, certificate = true)
        }
    private val localMfiPrivateKeyContentPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            importLocalMfiDocument(uri, certificate = false)
        }

    private var videoView: TextureView? = null
    private var contentRoot: FrameLayout? = null
    private var p7WindowCompat: P7WindowCompat? = null
    private var p7BarsButton: ImageButton? = null
    private var defaultFullscreenBars = false
    private var defaultFullscreenChoice: View? = null
    private var gestureOverlay: View? = null
    private var disconnectedSettingsButton: View? = null
    private var settingsMenu: View? = null
    private var mfiTargetGroup: RadioGroup? = null
    private var mfiI2cFields: View? = null
    private var mfiRemoteFields: View? = null
    private var mfiLocalFields: View? = null
    private var mfiErrorView: TextView? = null
    private var mfiI2cPathInput: EditText? = null
    private var remoteMfiServerInput: EditText? = null
    private var remoteMfiTokenInput: EditText? = null
    private var localMfiCertificateDocumentView: TextView? = null
    private var localMfiPrivateKeyDocumentView: TextView? = null
    private var settingsBaseline: SettingsBaseline? = null
    private var locationReportingSwitch: Switch? = null
    private var mainMediaAudioBufferSeekBar: SeekBar? = null
    private var mainMediaAudioBufferValueView: TextView? = null
    private var microphoneGainSeekBar: SeekBar? = null
    private var microphoneGainValueView: TextView? = null
    private var microphoneTestButton: Button? = null
    private var microphoneLevelBar: ProgressBar? = null
    private var microphoneLevelValueView: TextView? = null
    private var microphoneLevelMonitor: MicrophoneLevelMonitor? = null
    private var microphoneGainTestAfterPermission = false
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var stageStatusView: TextView? = null
    private var resolutionValueView: TextView? = null
    private var resolutionPreviewView: TextView? = null
    private var hotspotStatusView: TextView? = null
    private var manualHotspotFields: View? = null
    private var manualHotspotErrorView: TextView? = null
    private var iconPreviewView: ImageView? = null
    private var iconStatusView: TextView? = null
    private var safeAreaSummaryView: TextView? = null
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive = false
    private var mediaMetricsOverlay: MediaMetricsOverlayView? = null
    private var mediaMetricsMonitor: MediaMetricsMonitor? = null
    private var externalActivityInProgress = false
    private var sink: AndroidMediaSink? = null
    private var clusterRenderer: ClusterMapRenderer? = null
    private var instrumentController: P7InstrumentController? = null
    private var instrumentStatusView: TextView? = null
    private var instrumentEnabled = true
    private var instrumentBaseline: Triple<Boolean, Int, Boolean>? = null
    private var instrumentZoomSteps = 2
    private var hideNativeLane = true
    private var controller: CarPlayController? = null
    private val viewAreaTransition = ViewAreaTransition()
    private var dynamicViewAreasEnabled = true
    private var viewAreaStrategyRow: View? = null
    private var initialVideoWatchdogSession: AirPlaySession? = null
    private var initialVideoWatchdogTicket = 0L
    private var decodedVideoSize: Pair<Int, Int>? = null
    private var currentSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private val displayResize = DisplayResizeState()
    private val activeDisplaySize: DisplaySize? get() = displayResize.current
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var mainMediaAudioBufferDurationMs = MainMediaAudioBuffer.DEFAULT_DURATION_MS
    @Volatile private var debugLogsEnabled = false
    private var mediaMetricsEnabled = false
    private var audioPacketCaptureEnabled = false
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var hideTopBar = true
    private var hideBottomBar = true
    private var safeAreaDrawOutside = true
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    @Volatile private var microphoneGainPercent = MicrophoneGain.DEFAULT_PERCENT
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.USB_CH341
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var localMfiCertificateUri = ""
    private var localMfiPrivateKeyUri = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var nativeHotspotAutoEnabled = false
    private var nativeHotspotAutoSwitch: android.widget.Switch? = null
    private var manualHotspotSsid = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var hotspotStatus = HotspotStatus(state = "未开启")
    private var menuOpen = false
    private var latestStage = "正在准备 CarPlay"
    private var darkMode = false
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    private var sessionLog: SessionLogFile? = null
    private var gestureSequenceActive = false
    private val fiveFingerPinch = FiveFingerPinchGesture()
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private val shuttingDown = AtomicBoolean(false)
    private var runToken: Long? = null
    private val sessionStopped: Boolean
        get() = shuttingDown.get() || !appRunState.isActive(runToken)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mfiDocumentExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var mfiDocumentImportInProgress = false
    private var mfiDocumentImportTicket = 0
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ScreenLogBuffer(MAX_SCREEN_LOG_LINES)
    private val pendingScreenLogs = ArrayDeque<PendingLog>()
    private val pendingScreenLogsLock = Any()
    private var screenDrainPosted = false
    private val drainScreenLogs = Runnable {
        val pending = synchronized(pendingScreenLogsLock) {
            screenDrainPosted = false
            pendingScreenLogs.toList().also { pendingScreenLogs.clear() }
        }
        if (debugLogsEnabled && !menuOpen) {
            pending.forEach { entry ->
                if (entry.generation == restartGeneration) {
                    appendScreenLog(entry.timestampMillis, entry.message)
                }
            }
        }
    }
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private var logRenderScheduled = false
    private val renderLogLines = Runnable {
        logRenderScheduled = false
        refreshLogView(System.currentTimeMillis())
    }
    private val applyDisplaySize = Runnable { settleMeasuredDisplaySize() }

    private fun settleMeasuredDisplaySize() {
        if (isDestroyed || sessionStopped || !viewAreaTransition.visible) return
        val video = videoView ?: return
        val compat = p7WindowCompat
        val measured = if (compat != null) compat.measuredViewport(video)
            else if (!video.isLayoutRequested && video.width > 0 && video.height > 0)
                DisplaySize(video.width, video.height) else null
        if (measured == null) {
            // Native visibility requests precede padding/layout callbacks on E28.
            // Never negotiate the still-full-size surface covered by native bars.
            mainHandler.removeCallbacks(applyDisplaySize)
            mainHandler.postDelayed(applyDisplaySize, 50L)
            return
        }
        if (measured != displayResize.pending) {
            scheduleDisplaySize(measured.width, measured.height)
            return
        }
        val change = displayResize.settle() ?: return
        appendLog("Viewport ready: ${compat?.viewportDescription ?: "video=${measured.width}x${measured.height}"}")
        applyDisplaySize(change)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    existing?.release()
                    currentSurface = it
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            scheduleDisplaySize(width, height)
            maybeStartCarPlay()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            currentSurface?.let { surface ->
                sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
                surface.release()
            }
            currentSurface = null
            currentSurfaceTexture = null
            appendLog("Texture surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            if (viewAreaTransition.awaitingFrame && updateP7VideoTransform() && viewAreaTransition.frame()) {
                appendLog("ViewArea acknowledged; subsequent video frame received without session restart")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runToken = appRunState.enter()
        if (runToken == null) {
            Toast.makeText(this, "正在结束上次连接，请稍后打开", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }
        if (resources.getBoolean(R.bool.config_p7_display_profile) &&
            Build.MODEL.equals("E28", ignoreCase = true) && Build.VERSION.SDK_INT == 28
        ) {
            val bars = P7WindowPreferences.enter(this, savedInstanceState, intent)
            if ((this is OPCarPlayBarsActivity) != bars) {
                switchP7WindowHost(bars)
                return
            }
        }
        NativeHotspotService.onAppOpen(this)
        initializeSessionLog()
        darkMode = isDarkMode(resources.configuration.uiMode)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        syncViewAreaStrategyChoice()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        p7WindowCompat = P7WindowCompat.attach(this) {
            updateP7BarsButton()
            // Recheck even if a visibility request initially leaves TextureView unchanged.
            mainHandler.removeCallbacks(applyDisplaySize)
            mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
        }
        updateP7BarsButton()
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (menuOpen) {
                        if (safeAreaEditorActive) closeSafeAreaEditor() else cancelSettingsEdits()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadPersistedSettings() {
        instrumentZoomSteps = getSharedPreferences("p7-instrument", MODE_PRIVATE).getInt("zoom_steps", 2).coerceIn(0, 3)
        hideNativeLane = getSharedPreferences("p7-instrument", MODE_PRIVATE).getBoolean("hide_native_lane", true)
        instrumentEnabled = getSharedPreferences("p7-instrument", MODE_PRIVATE)
            .getBoolean("enabled", true) && P7InstrumentCommand.supported()
        dynamicViewAreasEnabled = AirPlayPersistence.loadDynamicViewAreasEnabled(this)
        displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(this)
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        mainMediaAudioBufferDurationMs =
            AirPlayPersistence.loadMainMediaAudioBufferDurationMs(this)
        microphoneGainPercent = AirPlayPersistence.loadMicrophoneGainPercent(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        mediaMetricsEnabled = AirPlayPersistence.loadMediaMetricsEnabled(this)
        audioPacketCaptureEnabled = AirPlayPersistence.loadAudioPacketCaptureEnabled(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable = hasFineLocationPermission()
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        defaultFullscreenBars = P7WindowPreferences.defaultBars(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        localMfiCertificateUri = AirPlayPersistence.loadLocalMfiCertificateUri(this)
        localMfiPrivateKeyUri = AirPlayPersistence.loadLocalMfiPrivateKeyUri(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        nativeHotspotAutoEnabled = NativeHotspotPreferences.enabled(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    private fun requestStartupPrerequisites() {
        if (sessionStopped || isFinishing || isDestroyed) return
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
        } else {
            awaitingVpnConsent = true
            vpnConsent.launch(consent)
        }
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(P7WindowPreferences.MODE_KEY, P7WindowPreferences.currentBars(this))
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (sessionStopped || isFinishing) return
        viewAreaTransition.resume()
        refreshViewAreaAfterLayout()
    }

    private fun refreshViewAreaAfterLayout() {
        videoView?.post {
            if (sessionStopped || !viewAreaTransition.visible || isDestroyed || menuOpen) return@post
            // Use final view bounds after the native split animation has settled.
            videoView?.let { scheduleDisplaySize(it.width, it.height) }
            if (displayResize.pending != null) {
                mainHandler.removeCallbacks(applyDisplaySize)
                mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
            }
            if (displayResize.readyToStart) activeDisplaySize?.let { requestP7ViewArea(it) }
            armInitialVideoWatchdog()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || sessionStopped) return
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        maybeStartCarPlay()
        applyFullscreenMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isFinishing && !sessionStopped) {
            applyFullscreenMode()
            refreshViewAreaAfterLayout()
        }
    }

    override fun onStop() {
        fiveFingerPinch.reset()
        viewAreaTransition.suspend()
        mainHandler.removeCallbacks(applyDisplaySize)
        initialVideoWatchdogSession = null
        ++initialVideoWatchdogTicket
        stopMicrophoneGainTest()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nextDarkMode = isDarkMode(newConfig.uiMode)
        if (nextDarkMode != darkMode) {
            darkMode = nextDarkMode
            syncAirPlayDarkMode()
        }
        applyFullscreenMode()
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    override fun onDestroy() {
        mfiDocumentExecutor.shutdownNow()
        instrumentController?.close()
        instrumentController = null
        // The decoder follows the background CarPlay session across Activity handoffs.
        clusterRenderer = null
        viewAreaTransition.suspend()
        mainHandler.removeCallbacks(applyDisplaySize)
        initialVideoWatchdogSession = null
        ++initialVideoWatchdogTicket
        p7WindowCompat?.close()
        stopMicrophoneGainTest()
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(renderLogLines)
        mainHandler.removeCallbacks(drainScreenLogs)
        currentSurface?.let { surface ->
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
            surface.release()
        }
        currentSurface = null
        currentSurfaceTexture = null
        sessionLog?.append("Activity destroyed")
        sessionLog?.close()
        sessionLog = null
        super.onDestroy()
    }

    private fun buildContentView(): View {
        clusterRenderer = ClusterMapRenderer.obtain(applicationContext, instrumentEnabled)
            .also { it.setHideNativeLane(hideNativeLane) }
        instrumentController = P7InstrumentController(this) { status -> instrumentStatusView?.text = status }
            .also { it.setEnabled(instrumentEnabled) }
        val root = FrameLayout(this).apply {
            setBackgroundColor(NO_VIDEO_BACKGROUND)
        }
        contentRoot = root
        val video = TextureView(this).apply {
            isOpaque = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            surfaceTextureListener = textureListener
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                scheduleDisplaySize(right - left, bottom - top)
            }
        }
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        val log = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            text = ""
        }
        val logScroll = object : ScrollView(this) {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false
            override fun onTouchEvent(event: MotionEvent): Boolean = false
        }.apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            isFillViewport = false
            isFocusable = false
            addView(
                log,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scrollLogsToBottom() }
        }
        val stageStatus = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            setPadding(dp(14), dp(8), dp(14), dp(8))
            text = latestStage
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(170, 0, 0, 0))
            }
        }
        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(Color.rgb(0xA6, 0x7D, 0xF2))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(0xE3, 0xE3, 0xE4))
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            contentDescription = "打开设置"
            setOnClickListener { openSettingsMenu() }
        }
        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        )
        statusParams.setMargins(dp(12), 0, dp(12), dp(12))
        val stageParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END,
        )
        stageParams.setMargins(dp(12), dp(12), dp(12), 0)
        val settingsButtonParams = FrameLayout.LayoutParams(
            dp(56),
            dp(56),
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(dp(16), 0, dp(16), dp(16)) }

        val settings = buildSettingsMenu().apply { visibility = View.GONE }
        val editor = buildSafeAreaEditor().apply { visibility = View.GONE }

        root.addView(video)
        root.addView(
            gestureLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(logScroll, statusParams)
        root.addView(stageStatus, stageParams)
        root.addView(settingsButton, settingsButtonParams)
        p7BarsButton = ImageButton(this).apply {
            minimumWidth = 0
            minimumHeight = 0
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(170, 24, 26, 28))
            }
            visibility = View.GONE
            setOnClickListener { toggleP7SystemBars() }
            // 28dp square is < 1/4 of either previous text-button state.
            // Keep the hit area equal to the button: no invisible CarPlay blocker.
            root.addView(this, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(dp(12), 0, dp(12), dp(12))
            })
        }
        root.addView(
            settings,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateMediaMetricsOverlayLayout(right - left, bottom - top)
            }
        }
        videoView = video
        gestureOverlay = gestureLayer
        disconnectedSettingsButton = settingsButton
        settingsMenu = settings
        safeAreaEditor = editor
        statusView = log
        statusScrollView = logScroll
        stageStatusView = stageStatus
        updateDebugOverlays()
        return root
    }

    private fun buildSettingsMenu(): View {
        // Rebind on opening; category changes only change visibility, never rebuild controls.
        val connection = SettingsPanelView.Page("connection", "连接与热点", "CONNECTION", "连接 iPhone，管理车载热点。")
        val auth = SettingsPanelView.Page("auth", "认证文件", "AUTHENTICATION", "使用内置认证，也可选择自己的认证方式。")
        val display = SettingsPanelView.Page("display", "显示与布局", "DISPLAY", "调整画面、窗口与启动时的显示方式。")
        val audio = SettingsPanelView.Page("audio", "声音与麦克风", "AUDIO", "调整音频缓冲与麦克风增益。")
        val identity = SettingsPanelView.Page("identity", "名称与图标", "IDENTITY", "设置 iPhone 中显示的车辆信息。")
        val general = SettingsPanelView.Page("general", "启动与定位", "GENERAL", "管理开机启动与定位共享。")
        val diagnostics = SettingsPanelView.Page("diagnostics", "高级与诊断", "DIAGNOSTICS", "查看连接状态，辅助排查音视频问题。")
        val instrument = SettingsPanelView.Page("instrument", "仪表导航", "INSTRUMENT", "将 iPhone 的独立地图显示到老 P7 原车仪表导航区域。")
        val instrumentGroup = instrument.group(this, "独立地图", first = true)
        instrumentGroup.addView(settingsSwitchRow(
            label = "显示仪表导航",
            checked = instrumentEnabled,
            description = "保存后生效。主屏可继续显示音乐或其他 CarPlay 应用；不显示中控预览窗。",
        ) { instrumentEnabled = it })
        instrumentGroup.addView(menuText(
            "请先让原车仪表显示导航卡片，再连接 iPhone 并使用 Apple 地图。" +
                "画面按仪表实际请求尺寸适配；断开 CarPlay、关闭本选项或退出 OP 后停止投放。",
            16f, MENU_SECONDARY,
        ))
        instrumentGroup.addView(settingsChoiceRow(
            label = "地图放大",
            options = (0..3).map { it to if (it == 0) "原始范围" else "放大 ${it} 级" },
            selected = instrumentZoomSteps,
        ) { instrumentZoomSteps = it })
        instrumentGroup.addView(menuText("保存并连接后向 iPhone 请求缩放；不裁切图片。实际范围还会受 iPhone 导航状态影响。", 14f, MENU_SECONDARY))
        instrumentGroup.addView(settingsSwitchRow(
            label = "OP 投放时隐藏原车车道提示",
            checked = hideNativeLane,
            description = "投放时直接隐藏蓝色车道条。断开、退出或关闭本项后交回原车；有当前车道缓存时恢复，无缓存时等待原车下一次更新。",
        ) { hideNativeLane = it })
        val permissionGroup = instrument.group(this, "仪表通道")
        instrumentStatusView = menuText(instrumentController?.status ?: "等待连接", 17f, MENU_SECONDARY)
            .also { permissionGroup.addView(it) }
        permissionGroup.addView(menuText(
            "需要已开启的本机 ADB，或已运行的 Shizuku。首次启动请允许 OP；已有授权时自动启动。" +
                "用于原车导航图片通道及可选的车道提示显示，不修改车辆控制设置。",
            14f, MENU_SECONDARY,
        ))
        permissionGroup.addView(Button(this).apply {
            text = "本机 ADB 启动"; tag = "op.action.accent"
            setOnClickListener { instrumentController?.requestAdb() }
        })
        permissionGroup.addView(Button(this).apply {
            text = "通过 Shizuku 启动"
            setOnClickListener { instrumentController?.requestShizuku() }
        })
        var content = auth.group(this, "认证方式")
        content.addView(
            buildMfiTargetSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        content = connection.group(this, "无线连接")
        val wirelessRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wirelessRow.addView(
            menuText("无线 CarPlay", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val wirelessSwitch = Switch(this).apply {
            isChecked = wirelessEnabled
            contentDescription = "使用无线 CarPlay 连接"
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (wirelessEnabled == checked) return@setOnCheckedChangeListener
                wirelessEnabled = checked
                hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "已停止" else "未开启")
                updateHotspotStatusBlock()
                appendLog(
                    "Wireless CarPlay ${if (wirelessEnabled) "已开启" else "已关闭"}; " +
                        "applies when settings close",
                )
                requestStartupPrerequisites()
            }
        }
        wirelessRow.addView(
            wirelessSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            wirelessRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content = connection.group(this, "热点配置")
        content.addView(
            buildHotspotModeSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            menuText("热点状态", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) },
        )
        val hotspotStatusView = menuText("", 16f, MENU_ACCENT)
        content.addView(
            hotspotStatusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        content = general.group(this, "定位")
        content.addView(
            buildLocationReportingSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content = general.group(this, "启动", first = true)
        content.addView(
            settingsSwitchRow(
                label = "开机自动启动",
                checked = autoStartOnBoot,
                description = "车机启动后自动打开 CarPlay",
            ) { checked ->
                autoStartOnBoot = checked
                appendLog("Boot auto-start ${if (checked) "已开启" else "已关闭"}")
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content = audio.group(this, "播放")
        content.addView(
            buildStepSliderSection(
                title = "媒体音频缓冲",
                values = (
                    MainMediaAudioBuffer.MIN_DURATION_MS..MainMediaAudioBuffer.MAX_DURATION_MS
                    step MainMediaAudioBuffer.STEP_DURATION_MS
                    ).toList(),
                selectedValue = mainMediaAudioBufferDurationMs,
                label = ::mainMediaAudioBufferLabel,
                onControlsCreated = { valueView, seekBar ->
                    mainMediaAudioBufferValueView = valueView
                    mainMediaAudioBufferSeekBar = seekBar
                },
                onValueChanged = { value ->
                    mainMediaAudioBufferDurationMs = value
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content = audio.group(this, "麦克风")
        content.addView(
            buildMicrophoneGainSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) },
        )
        if (advancedAudioChannelMappingSupported) {
            content.addView(
                settingsSwitchRow(
                    label = "高级音频通道映射",
                    checked = advancedAudioChannelMapping,
                    description = "按 CarPlay 音频类型分配车载音频通道",
                ) { checked ->
                    advancedAudioChannelMapping = checked
                    appendLog(
                        "Advanced audio channel mapping ${if (checked) "已开启" else "已关闭"}; " +
                            "applies when settings close",
                    )
                    updateResolutionMenu()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(20) },
            )
        }

        content = identity.group(this, "车辆信息")
        content.addView(
            buildIdentitySettingsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content = identity.group(this, "")
        content.addView(
            buildAirPlayIconSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content = identity.group(this, "驾驶位置")
        content.addView(
            buildDrivingSideSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content = display.group(this, "画面质量")

        val resolutionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        resolutionHeader.addView(
            menuText("分辨率", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val resolutionValue = menuText(
            CarPlayDisplayScale.label(displayScaleTenths),
            28f,
            MENU_ACCENT,
            bold = true,
        )
        resolutionHeader.addView(
            resolutionValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            resolutionHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val seekBar = SeekBar(this).apply {
            max = CarPlayDisplayScale.MAX_TENTHS - CarPlayDisplayScale.MIN_TENTHS
            progress = displayScaleTenths - CarPlayDisplayScale.MIN_TENTHS
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        displayScaleTenths = CarPlayDisplayScale.sanitize(
                            CarPlayDisplayScale.MIN_TENTHS + progress,
                        )
                        updateResolutionMenu()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        content.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        val range = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        range.addView(
            menuText("0.3x", 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        range.addView(
            menuText("1.0x", 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            range,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(
            buildStepSliderSection(
                title = "帧率",
                values = (
                    AirPlayDisplaySettings.MIN_FPS..AirPlayDisplaySettings.MAX_FPS
                    step AirPlayDisplaySettings.FPS_STEP
                    ).toList(),
                selectedValue = fps,
                label = { "$it 帧/秒" },
                onValueChanged = { value ->
                    fps = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        if (resources.getBoolean(R.bool.config_p7_dynamic_viewareas)) {
            val strategy = settingsChoiceRow(
                label = "尺寸切换",
                options = listOf(true to "优先不断连（测试）", false to "快速重连（兼容）"),
                selected = dynamicViewAreasEnabled,
            ) { dynamicViewAreasEnabled = it }
            viewAreaStrategyRow = strategy
            content.addView(strategy, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) })
            content.addView(menuText("画面或触控异常时选快速重连，保存后生效。无需换包。", 15f, MENU_SECONDARY))
        }

        content = display.group(this, "画面比例")
        content.addView(
            settingsChoiceRow(
                label = "画面尺寸基准",
                options = listOf(
                    AirPlayPhysicalSizeBasis.WIDTH to "最大宽度",
                    AirPlayPhysicalSizeBasis.HEIGHT to "最大高度",
                ),
                selected = physicalSizeBasis,
            ) { value ->
                physicalSizeBasis = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            buildStepSliderSection(
                title = "基准长度",
                values = (
                    AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..
                        AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM
                    step AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP
                    ).toList(),
                selectedValue = widthPhysicalMm,
                label = { "$it 毫米" },
                onValueChanged = { value ->
                    widthPhysicalMm = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        content = display.group(this, "视频编码")
        val hevcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hevcRow.addView(
            menuText("HEVC (H.265)", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val hevcSwitch = Switch(this).apply {
            isChecked = hevcEnabled
            contentDescription = "使用 HEVC（H.265）视频编码"
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (hevcEnabled == checked) return@setOnCheckedChangeListener
                hevcEnabled = checked
                appendLog(
                    "HEVC (H.265) ${if (hevcEnabled) "已开启" else "已关闭"}; " +
                        "applies when settings close",
                )
                updateResolutionMenu()
            }
        }
        hevcRow.addView(
            hevcSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            hevcRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        val softwareHevcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        softwareHevcRow.addView(
            menuText("HEVC 软件解码", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val softwareHevcSwitch = Switch(this).apply {
            isChecked = hevcSoftwareDecoderEnabled
            contentDescription = "使用 HEVC 软件解码器"
            showText = false
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT, MENU_SECONDARY),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
            )
            setOnCheckedChangeListener { _, checked ->
                if (hevcSoftwareDecoderEnabled == checked) return@setOnCheckedChangeListener
                hevcSoftwareDecoderEnabled = checked
                appendLog(
                    "HEVC software decoder ${if (hevcSoftwareDecoderEnabled) "已开启" else "已关闭"}; " +
                        "applies when settings close",
                )
                updateResolutionMenu()
            }
        }
        softwareHevcRow.addView(
            softwareHevcSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            content.addView(
                softwareHevcRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(16) },
            )
        }

        content = display.group(this, "")
        content.addView(
            buildSafeAreaSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content = display.group(this, "启动时默认全屏模式", first = true)
        content.addView(
            buildFullscreenSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        (defaultFullscreenChoice as? ViewGroup)?.let { row ->
            // The card already supplies the section heading.
            row.getChildAt(0)?.visibility = View.GONE
            ((row.parent as? ViewGroup)?.getChildAt(0) as? TextView)?.visibility = View.GONE
            (0 until row.childCount).map { row.getChildAt(it) }
                .filterIsInstance<RadioGroup>().forEach { it.tag = "op.fullscreen" }
        }
        content = general.group(this, "呼出设置")
        content.addView(menuText("五指向内收拢或三指下滑，可打开设置。分屏时请在 CarPlay 区域内操作。", 15f, MENU_SECONDARY))

        content = diagnostics.group(this, "诊断工具")
        content.addView(
            buildMediaMetricsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            buildAudioPacketCaptureSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) },
        )
        content.addView(
            buildDebugLogsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) },
        )
        content.addView(
            Button(this).apply {
                text = "打开系统蓝牙设置"
                isAllCaps = false
                textSize = 17f
                setTextColor(MENU_BUTTON_TEXT)
                backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
                minHeight = dp(52)
                setOnClickListener { openSystemBluetoothSettings() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            content = diagnostics.group(this, "Android 9 兼容性")
            content.addView(
                menuText(
                    "Android 9 上不支持并隐藏以下设置 " +
                        "（API 28）：\n" +
                        "• Wi-Fi P2P（5 GHz）：可使用原生热点（手动）或仅本地热点。\n" +
                        "• HEVC 软件解码：使用硬件解码。",
                    16f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        content = diagnostics.group(this, "当前状态")
        val preview = menuText("", 17f, MENU_SECONDARY)
        content.addView(
            preview,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        lateinit var panel: SettingsPanelView
        panel = SettingsPanelView(
            this,
            listOf(connection, instrument, auth, display, audio, identity, general, diagnostics),
            onSave = {
                saveSettingsAndReconnect()
                // Validation is unchanged; expose an error even when its tab is hidden.
                if (menuOpen) {
                    when {
                        mfiErrorView?.visibility == View.VISIBLE -> panel.revealError("auth", mfiErrorView)
                        manualHotspotErrorView?.visibility == View.VISIBLE -> panel.revealError("connection", manualHotspotErrorView)
                    }
                }
            },
            onCancel = { cancelSettingsEdits() },
            onExit = { exitApplication() },
        )
        resolutionValueView = resolutionValue
        resolutionPreviewView = preview
        this.hotspotStatusView = hotspotStatusView
        updateHotspotStatusBlock()
        updateResolutionMenu()
        return panel
    }

    private fun syncViewAreaStrategyChoice() {
        val row = viewAreaStrategyRow as? ViewGroup ?: return
        val group = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<RadioGroup>().firstOrNull() ?: return
        (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<RadioButton>()
            .firstOrNull { it.tag == dynamicViewAreasEnabled }?.let { group.check(it.id) }
    }

    private fun rebindSettingsPanel() {
        // Cancel restores the model. Recreate only the form so every control reflects it.
        val previous = settingsMenu ?: return
        val parent = previous.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(previous)
        val params = previous.layoutParams
        val category = (previous as? SettingsPanelView)?.selectedCategory ?: "connection"
        val replacement = buildSettingsMenu().apply { visibility = View.GONE }
        (replacement as? SettingsPanelView)?.select(category, hideKeyboard = false)
        parent.removeView(previous)
        parent.addView(replacement, index, params)
        settingsMenu = replacement
    }

    private fun persistMenuSettings() {
        getSharedPreferences("p7-instrument", MODE_PRIVATE).edit().putBoolean("enabled", instrumentEnabled)
            .putInt("zoom_steps", instrumentZoomSteps).putBoolean("hide_native_lane", hideNativeLane).apply()
        clusterRenderer?.setHideNativeLane(hideNativeLane)
        clusterRenderer?.setEnabled(instrumentEnabled)
        instrumentController?.setEnabled(instrumentEnabled)
        P7WindowPreferences.saveDefault(this, defaultFullscreenBars)
        AirPlayPersistence.saveDynamicViewAreasEnabled(this, dynamicViewAreasEnabled)
        AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)
        AirPlayPersistence.saveMfiTarget(this, mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(this, mfiI2cPath)
        AirPlayPersistence.saveRemoteMfiServer(this, remoteMfiServer)
        AirPlayPersistence.saveRemoteMfiToken(this, remoteMfiToken)
        AirPlayPersistence.saveLocalMfiCertificateUri(this, localMfiCertificateUri)
        AirPlayPersistence.saveLocalMfiPrivateKeyUri(this, localMfiPrivateKeyUri)
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        AirPlayPersistence.saveManualHotspotSsid(this, manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, manualHotspotPassphrase)
        AirPlayPersistence.saveManualHotspotBand(this, manualHotspotBand)
        AirPlayPersistence.saveManualHotspotChannel(this, manualHotspotChannel)
        AirPlayPersistence.saveManualHotspotSecurity(this, manualHotspotSecurity)
        AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
        AirPlayPersistence.saveAutoStartOnBoot(this, autoStartOnBoot)
        if (!NativeHotspotPreferences.setEnabled(this, nativeHotspotAutoEnabled)) {
            Toast.makeText(this, "热点选项保存失败，请重试", Toast.LENGTH_LONG).show()
        }
        NativeHotspotService.reconcile(this, "settings_saved")
        AirPlayPersistence.saveAdvancedAudioChannelMapping(this, advancedAudioChannelMapping)
        AirPlayPersistence.saveMainMediaAudioBufferDurationMs(
            this,
            mainMediaAudioBufferDurationMs,
        )
        AirPlayPersistence.saveMicrophoneGainPercent(this, microphoneGainPercent)
        AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
        AirPlayPersistence.saveFps(this, fps)
        AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
        AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
        AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, hevcSoftwareDecoderEnabled)
        AirPlayPersistence.saveManufacturer(this, manufacturer)
        AirPlayPersistence.saveModel(this, model)
        AirPlayPersistence.saveOemLabel(this, oemLabel)
        AirPlayPersistence.saveDebugLogsEnabled(this, debugLogsEnabled)
        AirPlayPersistence.saveMediaMetricsEnabled(this, mediaMetricsEnabled)
        AirPlayPersistence.saveAudioPacketCaptureEnabled(this, audioPacketCaptureEnabled)
        AirPlayPersistence.saveRightHandDrive(this, rightHandDrive)
        AirPlayPersistence.saveHideTopBar(this, hideTopBar)
        AirPlayPersistence.saveHideBottomBar(this, hideBottomBar)
        AirPlayPersistence.saveSafeAreaDrawOutside(this, safeAreaDrawOutside)
    }

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        return SettingsBaseline(
            safeAreaSize = safeAreaSize,
            safeAreaRect = safeAreaSize?.let {
                AirPlayPersistence.loadSafeAreaRect(this, it.width, it.height)
            },
            customIconBytes = customIconBytes,
        )
    }

    private fun restoreSettingsBaseline() {
        ++mfiDocumentImportTicket
        mfiDocumentImportInProgress = false
        val baseline = settingsBaseline ?: return
        loadPersistedSettings()
        nativeHotspotAutoSwitch?.isChecked = nativeHotspotAutoEnabled
        syncViewAreaStrategyChoice()
        baseline.safeAreaSize?.let { size ->
            baseline.safeAreaRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
        }
        settingsBaseline = null
        locationPermissionAvailable = hasFineLocationPermission()
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "已停止" else "未开启")
        syncMfiSettingsControls()
        syncDefaultFullscreenChoice()
        syncMainMediaAudioBufferControls()
        syncMicrophoneGainControls()
        updateManualHotspotFields()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateHotspotStatusBlock()
        updateResolutionMenu()
        updateDebugOverlays()
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    private fun buildMfiTargetSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val targetChoice = settingsChoiceRow(
            label = "MFi 认证方式",
            options = listOf(
                MfiTarget.USB_CH341 to "USB/CH341",
                MfiTarget.I2C to "I2C",
                MfiTarget.REMOTE to "远程服务",
                MfiTarget.LOCAL_FILES to "本地文件",
            ),
            selected = mfiTarget,
        ) { target ->
            if (mfiTarget == target) return@settingsChoiceRow
            mfiTarget = target
            updateMfiTargetFields()
            appendLog("MFI target: ${mfiTargetLabel(target)}; applies when settings close")
        }
        mfiTargetGroup = (targetChoice as ViewGroup).getChildAt(1) as RadioGroup
        section.addView(
            targetChoice,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val i2cFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    "I2C 设备",
                    mfiI2cPath,
                    onInputCreated = { mfiI2cPathInput = it },
                ) { value ->
                    mfiI2cPath = value
                    mfiErrorView?.visibility = View.GONE
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText("Linux 设备路径，例如 /dev/i2c-1。", 14f, MENU_SECONDARY),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            i2cFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiI2cFields = i2cFields

        val remoteFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    "服务地址",
                    remoteMfiServer,
                    onInputCreated = { remoteMfiServerInput = it },
                ) { value ->
                    remoteMfiServer = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                settingsInputRow(
                    "访问令牌（选填）",
                    remoteMfiToken,
                    password = true,
                    onInputCreated = { remoteMfiTokenInput = it },
                ) { value ->
                    remoteMfiToken = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText(
                    "地址须以 http:// 或 https:// 开头；访问令牌为选填项，" +
                        "用于服务端身份验证。",
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            remoteFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiRemoteFields = remoteFields

        val localFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                localMfiDocumentRow(
                    "证书（.p7b）",
                    localMfiCertificateUri,
                    onDocumentViewCreated = { localMfiCertificateDocumentView = it },
                ) {
                    launchLocalMfiDocumentPicker(certificate = true)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                localMfiDocumentRow(
                    "私钥（.pk8）",
                    localMfiPrivateKeyUri,
                    onDocumentViewCreated = { localMfiPrivateKeyDocumentView = it },
                ) {
                    launchLocalMfiDocumentPicker(certificate = false)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText(
                    "请选择配套的 DER PKCS#7 证书和未加密 DER PKCS#8 私钥。文件将导入应用私有目录，选齐两份后保存生效。",
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            localFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        if (BundledMfiProvider.available(this)) {
            localFields.addView(Button(this).apply {
                text = "使用内置认证文件"
                tag = "op.action.accent"
                setOnClickListener {
                    val builtIn = BundledMfiSelection.documents(packageName)
                    localMfiCertificateUri = builtIn.certificate
                    localMfiPrivateKeyUri = builtIn.privateKey
                    updateLocalMfiDocumentViews()
                    mfiErrorView?.visibility = View.GONE
                }
            })
        }
        mfiLocalFields = localFields
        val error = menuText("", 14f, MENU_DANGER).apply {
            visibility = View.GONE
        }
        section.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        mfiErrorView = error
        updateMfiTargetFields()
        return section
    }

    private fun updateMfiTargetFields() {
        mfiI2cFields?.visibility = if (mfiTarget == MfiTarget.I2C) View.VISIBLE else View.GONE
        mfiRemoteFields?.visibility = if (mfiTarget == MfiTarget.REMOTE) View.VISIBLE else View.GONE
        mfiLocalFields?.visibility = if (mfiTarget == MfiTarget.LOCAL_FILES) View.VISIBLE else View.GONE
        mfiErrorView?.visibility = View.GONE
    }

    private fun localMfiDocumentRow(
        label: String,
        uri: String,
        onDocumentViewCreated: (TextView) -> Unit,
        onChoose: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            menuText(label, 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this@CarPlayHostActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val documentView = menuText(localMfiDocumentLabel(uri), 14f, MENU_SECONDARY)
        onDocumentViewCreated(documentView)
        row.addView(
            documentView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            Button(this@CarPlayHostActivity).apply {
                text = "选择"
                isAllCaps = false
                contentDescription = "选择$label"
                setOnClickListener { onChoose() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(12) },
        )
        addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
    }

    private fun updateLocalMfiDocumentViews() {
        localMfiCertificateDocumentView?.text = localMfiDocumentLabel(localMfiCertificateUri)
        localMfiPrivateKeyDocumentView?.text = localMfiDocumentLabel(localMfiPrivateKeyUri)
    }

    private fun localMfiDocumentLabel(value: String): String {
        if (value.isEmpty()) return "未选择"
        val uri = try {
            Uri.parse(value)
        } catch (_: Exception) {
            return "所选文件不可用"
        }
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
            } ?: uri.lastPathSegment ?: "已选择文件"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "已选择文件"
        }
    }

    private fun launchLocalMfiDocumentPicker(certificate: Boolean) {
        if (mfiDocumentImportInProgress) {
            Toast.makeText(this, "正在读取认证文件，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        externalActivityInProgress = true
        try {
            if (certificate) localMfiCertificatePicker.launch(arrayOf("*/*"))
            else localMfiPrivateKeyPicker.launch(arrayOf("*/*"))
        } catch (_: ActivityNotFoundException) {
            launchLocalMfiContentPicker(certificate)
        } catch (_: SecurityException) {
            launchLocalMfiContentPicker(certificate)
        } catch (failure: RuntimeException) {
            localMfiPickerUnavailable(failure)
        }
    }

    private fun launchLocalMfiContentPicker(certificate: Boolean) {
        try {
            if (certificate) localMfiCertificateContentPicker.launch("*/*")
            else localMfiPrivateKeyContentPicker.launch("*/*")
        } catch (failure: RuntimeException) {
            localMfiPickerUnavailable(failure)
        }
    }

    private fun localMfiPickerUnavailable(failure: RuntimeException) {
        externalActivityInProgress = false
        Log.w(TAG, "Cannot open a local MFi document picker", failure)
        Toast.makeText(this, "系统没有可用的文件选择器，请先安装支持选择文件的文件管理器", Toast.LENGTH_LONG).show()
    }

    private fun importLocalMfiDocument(uri: Uri?, certificate: Boolean) {
        externalActivityInProgress = false
        if (uri == null || isFinishing || isDestroyed) return
        mfiDocumentImportInProgress = true
        val ticket = ++mfiDocumentImportTicket
        (if (certificate) localMfiCertificateDocumentView else localMfiPrivateKeyDocumentView)?.text = "正在读取文件…"
        val appContext = applicationContext
        mfiDocumentExecutor.execute {
            val result = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    LocalMfiDocumentStore.importDocument(input, LocalMfiDocumentProvider.directory(appContext), certificate)
                } ?: error("Document could not be opened")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || ticket != mfiDocumentImportTicket) {
                    result.getOrNull()?.delete()
                    return@runOnUiThread
                }
                mfiDocumentImportInProgress = false
                result.fold(onSuccess = { file ->
                    val imported = LocalMfiDocumentProvider.uri(appContext, file).toString()
                    if (certificate) {
                        if (BundledMfiSelection.assetName(packageName, localMfiPrivateKeyUri) != null) localMfiPrivateKeyUri = ""
                        localMfiCertificateUri = imported
                    } else {
                        if (BundledMfiSelection.assetName(packageName, localMfiCertificateUri) != null) localMfiCertificateUri = ""
                        localMfiPrivateKeyUri = imported
                    }
                    mfiErrorView?.visibility = View.GONE
                }, onFailure = { failure ->
                    // Do not log document contents or its source URI (the file can be a private key).
                    Log.w(TAG, "Local MFi document import failed: ${failure.javaClass.simpleName}")
                    Toast.makeText(this, "无法读取认证文件，请确认文件可访问、非空且不超过 1 MB", Toast.LENGTH_LONG).show()
                })
                updateLocalMfiDocumentViews()
            }
        }
    }

    private fun syncMfiSettingsControls() {
        mfiTargetGroup?.let { group ->
            val button = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<RadioButton>()
                .firstOrNull { it.tag == mfiTarget }
            button?.let { group.check(it.id) }
        }
        if (mfiI2cPathInput?.text?.toString() != mfiI2cPath) {
            mfiI2cPathInput?.setText(mfiI2cPath)
        }
        if (remoteMfiServerInput?.text?.toString() != remoteMfiServer) {
            remoteMfiServerInput?.setText(remoteMfiServer)
        }
        if (remoteMfiTokenInput?.text?.toString() != remoteMfiToken) {
            remoteMfiTokenInput?.setText(remoteMfiToken)
        }
        updateLocalMfiDocumentViews()
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.USB_CH341 -> "USB/CH341"
        MfiTarget.I2C -> "I2C"
        MfiTarget.REMOTE -> "远程服务"
        MfiTarget.LOCAL_FILES -> "本地文件"
    }

    private fun buildIdentitySettingsSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            settingsInputRow("厂商", manufacturer) { value ->
                manufacturer = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsInputRow("设备名称", model) { value ->
                model = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsInputRow("CarPlay 图标名称", oemLabel) { value ->
                oemLabel = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun settingsCategoryHeader(title: String): TextView =
        menuText(title, 16f, MENU_ACCENT, bold = true)

    private fun buildLocationReportingSection(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val row = LinearLayout(this@CarPlayHostActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                menuText("向 iPhone 提供定位", 20f, MENU_SECONDARY),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val switch = Switch(this@CarPlayHostActivity).apply {
                isChecked = locationReportingEnabled
                contentDescription = "向 iPhone 提供车机定位"
                showText = false
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
                )
                setOnCheckedChangeListener { _, checked ->
                    onLocationReportingChanged(checked)
                }
            }
            locationReportingSwitch = switch
            row.addView(
                switch,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(
                    "iPhone 请求 GPS 数据时，向其提供车机的精确位置。",
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting ${if (locationReportingEnabled) "已开启" else "已关闭"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }

    private fun buildDebugLogsSection(): View =
        settingsSwitchRow(
            label = "调试日志",
            checked = debugLogsEnabled,
            description = "在屏幕上显示调试日志",
        ) { checked ->
            debugLogsEnabled = checked
            if (!checked) clearScreenLogs()
            appendLog("Debug logs ${if (debugLogsEnabled) "已开启" else "已关闭"}")
            updateDebugOverlays()
        }

    private fun buildMediaMetricsSection(): View =
        settingsSwitchRow(
            label = "音视频延迟监测",
            checked = mediaMetricsEnabled,
            description = "显示实时画面延迟、帧率和音频延迟",
        ) { checked ->
            mediaMetricsEnabled = checked
            appendLog("Media latency monitor ${if (checked) "已开启" else "已关闭"}")
            updateMediaMetricsOverlay()
        }

    private fun buildAudioPacketCaptureSection(): View =
        settingsSwitchRow(
            label = "音频诊断采集",
            checked = audioPacketCaptureEnabled,
            description = "采集音频数据包，用于排查音频问题",
        ) { checked ->
            audioPacketCaptureEnabled = checked
            appendLog(
                "Audio diagnostic capture ${if (checked) "已开启" else "已关闭"}; " +
                    "applies when settings close",
            )
        }

    private fun buildMicrophoneGainSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText("麦克风增益（0.8–2.0 倍）", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val gainValue = menuText(
            microphoneGainLabel(microphoneGainPercent),
            22f,
            MENU_ACCENT,
            bold = true,
        )
        microphoneGainValueView = gainValue
        header.addView(
            gainValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val gainSlider = SeekBar(this).apply {
            max = (MicrophoneGain.MAX_PERCENT - MicrophoneGain.MIN_PERCENT) /
                MicrophoneGain.STEP_PERCENT
            progress = (microphoneGainPercent - MicrophoneGain.MIN_PERCENT) /
                MicrophoneGain.STEP_PERCENT
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            contentDescription = "麦克风增益"
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        val percent = MicrophoneGain.sanitize(
                            MicrophoneGain.MIN_PERCENT +
                                progress * MicrophoneGain.STEP_PERCENT,
                        )
                        microphoneGainPercent = percent
                        microphoneGainValueView?.text = microphoneGainLabel(percent)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        microphoneGainSeekBar = gainSlider
        controls.addView(
            gainSlider,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val testButton = Button(this).apply {
            text = "测试"
            isAllCaps = false
            textSize = 16f
            setTextColor(MENU_BUTTON_TEXT)
            backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
            minWidth = dp(78)
            contentDescription = "测试麦克风音量"
            setOnClickListener {
                if (microphoneLevelMonitor?.isRunning == true) {
                    stopMicrophoneGainTest()
                } else {
                    startMicrophoneGainTest()
                }
            }
        }
        microphoneTestButton = testButton
        controls.addView(
            testButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(12) },
        )
        section.addView(
            controls,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        val levelHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        levelHeader.addView(
            menuText("峰值音量", 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val levelValue = menuText("0%", 15f, MENU_SECONDARY)
        microphoneLevelValueView = levelValue
        levelHeader.addView(
            levelValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            levelHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        val levelBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(MENU_TRACK_OFF)
            contentDescription = "麦克风峰值音量"
        }
        microphoneLevelBar = levelBar
        section.addView(
            levelBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(14),
            ).apply { topMargin = dp(4) },
        )
        return section
    }

    private fun mainMediaAudioBufferLabel(durationMs: Int): String =
        String.format(
            Locale.US,
            "%d ms",
            MainMediaAudioBuffer.sanitizeDurationMs(durationMs),
        )

    private fun microphoneGainLabel(percent: Int): String =
        String.format(Locale.US, "%.1fx", MicrophoneGain.sanitize(percent) / 100.0)

    private fun syncMainMediaAudioBufferControls() {
        val durationMs = MainMediaAudioBuffer.sanitizeDurationMs(mainMediaAudioBufferDurationMs)
        mainMediaAudioBufferDurationMs = durationMs
        mainMediaAudioBufferValueView?.text = mainMediaAudioBufferLabel(durationMs)
        mainMediaAudioBufferSeekBar?.progress =
            (durationMs - MainMediaAudioBuffer.MIN_DURATION_MS) /
                MainMediaAudioBuffer.STEP_DURATION_MS
    }

    private fun syncMicrophoneGainControls() {
        microphoneGainValueView?.text = microphoneGainLabel(microphoneGainPercent)
        microphoneGainSeekBar?.progress =
            (microphoneGainPercent - MicrophoneGain.MIN_PERCENT) /
                MicrophoneGain.STEP_PERCENT
    }

    private fun startMicrophoneGainTest() {
        if (!menuOpen || handshakeResetInProgress || microphoneLevelMonitor != null) return
        if (!microphoneAvailable) {
            microphoneGainTestAfterPermission = true
            microphoneLevelValueView?.text = "需要麦克风权限"
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        lateinit var monitor: MicrophoneLevelMonitor
        monitor = MicrophoneLevelMonitor(
            context = applicationContext,
            gainPercent = { microphoneGainPercent },
            onPeakPercent = { peak ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor || !menuOpen || isDestroyed) {
                        return@runOnUiThread
                    }
                    microphoneLevelBar?.progress = peak
                    microphoneLevelValueView?.text = "$peak%"
                }
            },
            onStopped = { error ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor) return@runOnUiThread
                    microphoneLevelMonitor = null
                    microphoneTestButton?.text = "测试"
                    microphoneTestButton?.isEnabled = menuOpen && !handshakeResetInProgress
                    if (error != null) {
                        Log.e(TAG, "microphone gain test failed", error)
                        microphoneLevelBar?.progress = 0
                        microphoneLevelValueView?.text =
                            error.message ?: "测试失败"
                    }
                }
            },
        )
        microphoneLevelMonitor = monitor
        microphoneTestButton?.text = "停止"
        microphoneLevelBar?.progress = 0
        microphoneLevelValueView?.text = "正在监听…"
        if (!monitor.start()) {
            microphoneLevelMonitor = null
            microphoneTestButton?.text = "测试"
        }
    }

    private fun stopMicrophoneGainTest() {
        microphoneGainTestAfterPermission = false
        val monitor = microphoneLevelMonitor
        microphoneLevelMonitor = null
        monitor?.close()
        microphoneTestButton?.text = "测试"
        microphoneLevelBar?.progress = 0
        microphoneLevelValueView?.text = "0%"
    }

    private fun openSystemBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            appendLog("System Bluetooth settings are unavailable: ${error.message}")
            Toast.makeText(this, "系统蓝牙设置不可用", Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            appendLog("Cannot open system Bluetooth settings: ${error.message}")
            Toast.makeText(this, "无法打开系统蓝牙设置", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildStepSliderSection(
        title: String,
        values: List<Int>,
        selectedValue: Int,
        label: (Int) -> String,
        onControlsCreated: ((TextView, SeekBar) -> Unit)? = null,
        onValueChanged: (Int) -> Unit,
    ): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText(title, 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val selectedIndex = values.indexOf(selectedValue)
            .takeIf { it >= 0 }
            ?: 0
        val valueView = menuText(label(values[selectedIndex]), 22f, MENU_ACCENT, bold = true)
        header.addView(
            valueView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val seekBar = SeekBar(this).apply {
            max = (values.size - 1).coerceAtLeast(0)
            progress = selectedIndex
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = values.getOrNull(progress) ?: return
                        valueView.text = label(value)
                        if (fromUser) onValueChanged(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        section.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        onControlsCreated?.invoke(valueView, seekBar)
        return section
    }

    private fun buildAirPlayIconSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("CarPlay 图标", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(MENU_TRACK_OFF)
            }
        }
        row.addView(
            preview,
            LinearLayout.LayoutParams(dp(72), dp(72)),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        actions.addView(
            Button(this).apply {
                text = "选择图片"
                isAllCaps = false
                setOnClickListener {
                    externalActivityInProgress = true
                    imagePicker.launch("image/*")
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        actions.addView(
            Button(this).apply {
                text = "恢复默认图标"
                isAllCaps = false
                setOnClickListener {
                    AirPlayPersistence.clearCustomAirPlayIcon(this@CarPlayHostActivity)
                    updateAirPlayIconPreview()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        row.addView(
            actions,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(16) },
        )
        section.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        val status = menuText("", 14f, MENU_SECONDARY)
        section.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        iconPreviewView = preview
        iconStatusView = status
        updateAirPlayIconPreview()
        return section
    }

    private fun buildDrivingSideSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("驾驶位置", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val left = RadioButton(this).apply {
            id = View.generateViewId()
            text = "左舵"
            setTextColor(Color.WHITE)
            isChecked = !rightHandDrive
        }
        val right = RadioButton(this).apply {
            id = View.generateViewId()
            text = "右舵"
            setTextColor(Color.WHITE)
            isChecked = rightHandDrive
        }
        group.addView(left)
        group.addView(right)
        group.setOnCheckedChangeListener { _, checkedId ->
            rightHandDrive = checkedId == right.id
            updateResolutionMenu()
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun syncDefaultFullscreenChoice() {
        val row = defaultFullscreenChoice as? ViewGroup ?: return
        val group = (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<RadioGroup>().firstOrNull() ?: return
        (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<RadioButton>()
            .firstOrNull { it.tag == defaultFullscreenBars }?.let { group.check(it.id) }
    }

    private fun buildFullscreenSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("全屏显示", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (resources.getBoolean(R.bool.config_p7_display_profile) &&
            Build.VERSION.SDK_INT == 28 && Build.MODEL.equals("E28", ignoreCase = true)
        ) {
            defaultFullscreenChoice = settingsChoiceRow(
                label = "启动时默认全屏模式",
                options = listOf(false to "完全全屏", true to "显示系统栏"),
                selected = defaultFullscreenBars,
            ) { defaultFullscreenBars = it }.also { section.addView(it) }
            section.addView(
                menuText("保存后，下次重新启动应用时使用所选模式。右下角小图标可临时切换，不改变默认值；分屏保持原有效果。", 15f, MENU_SECONDARY),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(10) },
            )
            return section
        }
        section.addView(
            settingsSwitchRow(
                label = "隐藏顶部状态栏",
                checked = hideTopBar,
                description = "隐藏状态栏",
            ) { checked ->
                hideTopBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = "隐藏底部栏",
                checked = hideBottomBar,
                description = "隐藏底部导航栏",
            ) { checked ->
                hideBottomBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun buildSafeAreaSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("安全显示区域", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val summary = menuText("", 15f, MENU_ACCENT)
        section.addView(
            summary,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(
            Button(this).apply {
                text = "调整"
                isAllCaps = false
                setOnClickListener { openSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            Button(this).apply {
                text = "重置"
                isAllCaps = false
                setOnClickListener { resetSafeAreaForCurrentSize() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        section.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = "允许背景延伸到安全区域外",
                checked = safeAreaDrawOutside,
                description = "允许 CarPlay 在安全区域外绘制背景",
            ) { checked ->
                safeAreaDrawOutside = checked
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        safeAreaSummaryView = summary
        updateSafeAreaSummary()
        return section
    }

    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            menuText("安全显示区域", 24f, Color.WHITE, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(8))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            Button(this).apply {
                text = "取消"
                isAllCaps = false
                setOnClickListener { closeSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            Button(this).apply {
                text = "保存"
                isAllCaps = false
                setOnClickListener { saveSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        safeAreaEditorView = editor
        return overlay
    }

    private fun settingsInputRow(
        label: String,
        value: String,
        password: Boolean = false,
        numeric: Boolean = false,
        onInputCreated: ((EditText) -> Unit)? = null,
        onChanged: (String) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            EditText(this@CarPlayHostActivity).apply {
                setText(value)
                textSize = 18f
                setTextColor(Color.WHITE)
                setHintTextColor(MENU_SECONDARY)
                backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
                minHeight = dp(48)
                isSingleLine = true
                inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    password -> InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                addTextChangedListener(afterTextChanged(onChanged))
                onInputCreated?.invoke(this)
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(12) },
        )
    }

    private fun settingsSwitchRow(
        label: String,
        checked: Boolean,
        description: String,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            Switch(this@CarPlayHostActivity).apply {
                isChecked = checked
                contentDescription = description
                showText = false
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT_TRACK, MENU_TRACK_OFF),
                )
                setOnCheckedChangeListener { _, value -> onChanged(value) }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun afterTextChanged(onChanged: (String) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                onChanged(text?.toString().orEmpty())
            }
        }

    private fun buildHotspotModeSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("Wi-Fi 连接方式", 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val group = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val modes = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(WirelessHotspotMode.WIFI_P2P to "Wi-Fi 直连（5 GHz）")
            }
            add(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT to "仅本地热点")
            add(WirelessHotspotMode.MANUAL to "原生热点（手动）")
        }
        var selectedId = View.NO_ID
        for ((mode, label) in modes) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 18f
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                tag = mode
                isChecked = wirelessHotspotMode == mode
            }
            if (wirelessHotspotMode == mode) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val selected = radioGroup.findViewById<RadioButton>(checkedId)
                ?.tag as? WirelessHotspotMode
                ?: return@setOnCheckedChangeListener
            if (wirelessHotspotMode == selected) return@setOnCheckedChangeListener
            wirelessHotspotMode = selected
            hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "已停止" else "未开启")
            updateHotspotStatusBlock()
            updateManualHotspotFields()
            appendLog(
                "Wi-Fi session mode: ${hotspotModeLabel(wirelessHotspotMode)}; " +
                    "applies when settings close",
            )
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val manualFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        manualFields.addView(
            menuText(
                "填写原车热点已有的名称和密码。沿用原车配置（包括已有的 5 GHz），" +
                    "不修改频段或信道；不确定频段时请选择“自动”。",
                14f,
                MENU_SECONDARY,
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) },
        )
        manualFields.addView(
            settingsInputRow("热点名称（SSID）", manualHotspotSsid) { value ->
                manualHotspotSsid = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        manualFields.addView(
            settingsInputRow(
                label = "热点密码",
                value = manualHotspotPassphrase,
                password = true,
            ) { value ->
                manualHotspotPassphrase = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        if (NativeHotspotPreferences.supported()) {
            val autoRow = settingsSwitchRow(
                label = "自动开启原车热点",
                checked = nativeHotspotAutoEnabled,
                description = "完成一次授权后，打开 OP、系统开机或唤醒时尝试恢复热点。" +
                    "退出 OP 后暂停到下次打开或重启；关闭本选项不会关闭已开启的热点。",
            ) { checked -> nativeHotspotAutoEnabled = checked }
            nativeHotspotAutoSwitch = (autoRow as? ViewGroup)?.let { row ->
                (0 until row.childCount).map { row.getChildAt(it) }.filterIsInstance<android.widget.Switch>().firstOrNull()
            }
            manualFields.addView(autoRow)
            manualFields.addView(menuText(
                "默认关闭，保存后生效。完成一次授权后，开机或唤醒时后台尝试。" +
                    "退出 OP 后暂停到下次打开或重启。关闭选项不会关闭已开启的热点。",
                14f, MENU_SECONDARY,
            ))
            val status = menuText(NativeHotspotJournal.status(this), 14f, MENU_SECONDARY)
            manualFields.addView(status)
            manualFields.addView(Button(this).apply {
                text = "自动热点授权"
                tag = "op.action.accent"
                setOnClickListener {
                    NativeHotspotAuthorization.show(this@CarPlayHostActivity) {
                        status.text = NativeHotspotJournal.status(this@CarPlayHostActivity)
                    }
                }
            })
            manualFields.addView(Button(this).apply {
                text = "检查热点状态／重试"
                setOnClickListener {
                    if (NativeHotspotPreferences.allowed(this@CarPlayHostActivity)) {
                        NativeHotspotService.reconcile(this@CarPlayHostActivity, "user_retry")
                        status.text = "正在检查，约 30 秒内完成。"
                    } else {
                        status.text = "请先开启自动恢复选项并保存设置。\n" + NativeHotspotJournal.status(this@CarPlayHostActivity)
                    }
                    status.postDelayed({ status.text = NativeHotspotJournal.status(this@CarPlayHostActivity) }, 2_000)
                    status.postDelayed({ status.text = NativeHotspotJournal.status(this@CarPlayHostActivity) }, 31_000)
                }
            })
        }
        val advancedFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        advancedFields.addView(
            settingsChoiceRow(
                label = "已有热点的频段",
                options = listOf(
                    ManualHotspotBand.AUTO to "自动",
                    ManualHotspotBand.GHZ_2_4 to "2.4 GHz",
                    ManualHotspotBand.GHZ_5 to "5 GHz",
                ),
                selected = manualHotspotBand,
            ) { value ->
                manualHotspotBand = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        advancedFields.addView(
            settingsInputRow(
                label = "信道（0 为自动）",
                value = manualHotspotChannel.toString(),
                numeric = true,
            ) { value ->
                manualHotspotChannel = value.toIntOrNull() ?: -1
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )


        advancedFields.addView(
            settingsChoiceRow(
                label = "安全类型",
                options = listOf(
                    ManualHotspotSecurity.OPEN to "开放（无密码）",
                    ManualHotspotSecurity.WPA2 to "WPA2",
                    ManualHotspotSecurity.WPA3_TRANSITION to "WPA3 过渡模式",
                    ManualHotspotSecurity.WPA3 to "WPA3",
                ),
                selected = manualHotspotSecurity,
            ) { value ->
                manualHotspotSecurity = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(Button(this).apply {
            text = "频段、信道与安全类型   ＋"
            tag = "op.action.disclosure"
            setOnClickListener {
                val expanded = advancedFields.visibility != View.VISIBLE
                advancedFields.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) "频段、信道与安全类型   −" else "频段、信道与安全类型   ＋"
            }
        })
        manualFields.addView(advancedFields)

        val error = menuText("", 14f, Color.rgb(0xff, 0x7a, 0x7a)).apply {
            visibility = View.GONE
        }
        manualFields.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        section.addView(
            manualFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        manualHotspotFields = manualFields
        manualHotspotErrorView = error
        updateManualHotspotFields()
        return section
    }

    private fun updateManualHotspotFields() {
        val visible = wirelessHotspotMode == WirelessHotspotMode.MANUAL
        manualHotspotFields?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) manualHotspotErrorView?.visibility = View.GONE
    }

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiDocumentImportInProgress -> "正在读取认证文件，请稍候"
            mfiTarget == MfiTarget.I2C && mfiI2cPath.isBlank() ->
                "请填写 I2C 设备路径"
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                "请填写远程服务地址"
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                "远程地址须以 http:// 或 https:// 开头"
            mfiTarget == MfiTarget.LOCAL_FILES && localMfiCertificateUri.isBlank() ->
                "请选择本地证书"
            mfiTarget == MfiTarget.LOCAL_FILES && localMfiPrivateKeyUri.isBlank() ->
                "请选择本地私钥"
            '\u0000' in mfiI2cPath -> "I2C 路径含有无效字符"
            '\u0000' in remoteMfiServer -> "远程地址含有无效字符"
            '\u0000' in remoteMfiToken -> "访问令牌含有无效字符"
            '\u0000' in localMfiCertificateUri -> "证书路径含有无效字符"
            '\u0000' in localMfiPrivateKeyUri -> "私钥路径含有无效字符"
            else -> null
        }
        mfiErrorView?.text = error.orEmpty()
        mfiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> "请填写热点名称"
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                "热点名称不能超过 32 个 UTF-8 字节"
            '\u0000' in manualHotspotSsid -> "热点名称含有无效字符"
            manualHotspotChannel !in 0..196 -> "信道须为 0（自动）或 1–196"
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                "所选频段不支持该信道"
            '\u0000' in manualHotspotPassphrase -> "热点密码含有无效字符"
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                "开放热点不能填写密码"
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                "WPA2/WPA3 密码须为 8–63 个字符"
            else -> null
        }
        manualHotspotErrorView?.text = error.orEmpty()
        manualHotspotErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> "Wi-Fi 直连（5 GHz）"
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "仅本地热点"
        WirelessHotspotMode.MANUAL -> "原生热点（手动）"
    }

    private fun menuText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = "正在启动")
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = "就绪",
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = "等待已配对的 iPhone")
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = "正在连接蓝牙")
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = "运行中")
            CarPlayStatus.WirelessActive ->
                hotspotStatus.copy(state = "已连接")
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = "正在启动 AirPlay 服务")
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = "错误")
            else -> return
        }
        updateHotspotStatusBlock()
    }

    private fun updateHotspotStatusBlock() {
        if (!wirelessEnabled) {
            hotspotStatusView?.text = "无线热点：未使用"
            return
        }
        val status = hotspotStatus
        hotspotStatusView?.text = buildString {
            append("无线热点：").append(status.state)
            status.ssid?.let { append("\n名称：").append(it) }
            status.backend?.let { append("\n连接方式：").append(it) }
            status.band?.let { append("\n频段：").append(it) }
            status.channel?.let {
                append("\n信道：").append(if (it == 0) "自动" else it.toString())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> settingsChoiceRow(
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this@CarPlayHostActivity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(this@CarPlayHostActivity).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val value = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? T ?: return@setOnCheckedChangeListener
            onSelected(value)
        }
        addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun updateResolutionMenu() {
        resolutionValueView?.text = CarPlayDisplayScale.label(displayScaleTenths)
        val native = activeDisplaySize ?: currentActivitySize()
        val resolution = if (native == null) {
            "协商分辨率：等待画面尺寸"
        } else {
            val negotiated = CarPlayDisplayScale.apply(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScaleTenths,
            )
            "协商分辨率：${native.width} × ${native.height} → " +
                "${negotiated.widthPixels} x ${negotiated.heightPixels}"
        }
        val transport = if (!hevcEnabled) {
            "H.264"
        } else {
            "HEVC (H.265, ${if (hevcSoftwareDecoderEnabled) "软件解码" else "硬件解码"})"
        }
        val fullscreen = if (p7WindowCompat != null) {
            if (p7WindowCompat?.showBarsFullscreen == true) "全屏显示两栏；分屏保留两栏" else "完全全屏；分屏保留两栏"
        } else buildString {
            append(if (hideTopBar) "顶部隐藏" else "顶部显示")
            append(", ")
            append(if (hideBottomBar) "底部隐藏" else "底部显示")
        }
        resolutionPreviewView?.text = buildString {
            append(resolution).append('\n')
            append("设备：").append(normalizedManufacturer()).append(" / ")
                .append(normalizedModel()).append('\n')
            append("图标名称：").append(oemLabel.ifBlank { "（未设置）" }).append('\n')
            append("帧率：").append(fps).append(" 帧/秒\n")
            append("最大检测尺寸：")
                .append(maximumDetectedWidthPixels).append(" x ")
                .append(maximumDetectedHeightPixels).append(" 像素\n")
            append("尺寸基准：")
                .append(
                    when (physicalSizeBasis) {
                        AirPlayPhysicalSizeBasis.WIDTH -> "最大宽度"
                        AirPlayPhysicalSizeBasis.HEIGHT -> "最大高度"
                    },
                )
                .append(" = ").append(widthPhysicalMm).append(" 毫米\n")
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                append("CarPlay 布局尺寸：")
                    .append(physical.widthMm).append(" x ")
                    .append(physical.heightMm).append(" 毫米\n")
            }
            append("驾驶位置：").append(if (rightHandDrive) "右舵" else "左舵").append('\n')
            append("系统栏：").append(fullscreen).append('\n')
            append("视频编码：").append(transport).append('\n')
            append("提供定位：")
                .append(if (locationReportingEnabled) "已开启" else "已关闭")
                .append('\n')
            if (advancedAudioChannelMappingSupported) {
                append("音频通道：")
                    .append(if (advancedAudioChannelMapping) "车载音频通道" else "通用兼容模式")
                    .append('\n')
            }
            append(safeAreaSummary())
        }
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        val physical = resolvePhysicalSize(size)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = size.width,
            heightPixels = size.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
        )
        val scaledDisplay = CarPlayDisplayScale.apply(
            baseDisplay,
            displayScaleTenths,
        )
        val ordinaryDisplay = scaledDisplay.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
        )
        val mode = P7ViewAreas.mode(size.width, size.height)
        val display = if (resources.getBoolean(R.bool.config_p7_dynamic_viewareas) &&
            p7WindowCompat != null && wirelessEnabled &&
            wirelessHotspotMode == WirelessHotspotMode.MANUAL && dynamicViewAreasEnabled &&
            mode != null && P7ViewAreas.supportsSafeArea(ordinaryDisplay.safeArea)
        ) {
            val canvasSize = DisplaySize(2400, 1200)
            val canvasPhysical = resolvePhysicalSize(canvasSize)
            P7ViewAreas.configure(baseDisplay.copy(widthPixels = 2400, heightPixels = 1200,
                widthPhysicalMm = canvasPhysical.widthMm, heightPhysicalMm = canvasPhysical.heightMm),
                displayScaleTenths, mode)
        } else ordinaryDisplay
        appendLog("Display strategy: viewAreas=${display.viewAreas.size} initial=${display.initialViewArea}; " +
            "dynamicPreference=$dynamicViewAreasEnabled safeArea=${ordinaryDisplay.safeArea}")
        return AirPlayConfig(
            deviceName = normalizedModel(),
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "950.7.1",
            main = display,
            cluster = if (instrumentEnabled && P7InstrumentCommand.supported())
                com.shilapi.xcertplay.airplay.ClusterLabDisplay.config(
                com.shilapi.xcertplay.airplay.ClusterLabDisplay.Profile.P7_CENTERED,
                    com.shilapi.xcertplay.airplay.ClusterLabDisplay.Content.MAP,
                ) else null,
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = microphoneAvailable,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray {
        if (!resources.getBoolean(R.bool.config_p7_display_profile)) {
            return resources.openRawResource(R.raw.placeholder_icon).use { it.readBytes() }
        }
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val icon = requireNotNull(getDrawable(R.drawable.p7_xpeng_icon))
        icon.setBounds(0, 0, 256, 256)
        icon.draw(android.graphics.Canvas(bitmap))
        return java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun updateAirPlayIconPreview() {
        val preview = iconPreviewView ?: return
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: defaultAirPlayIconBytes().let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        preview.setImageBitmap(bitmap)
        iconStatusView?.text =
            if (customBitmap != null) "自选正方形图标" else "默认小鹏图标"
    }

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun safeAreaSummary(): String {
        val size = currentActivitySize() ?: return "安全显示区域：等待窗口尺寸"
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            "安全显示区域：${size.width} × ${size.height}（整个窗口）"
        } else {
            "安全显示区域：${mapping.width} × ${mapping.height}，位置 " +
                "(${mapping.left}, ${mapping.top}) in ${size.width} x ${size.height}"
        }
    }

    private fun updateSafeAreaSummary() {
        safeAreaSummaryView?.text = safeAreaSummary()
    }

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        settingsMenu?.visibility = View.GONE
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            return
        }
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank {
            if (resources.getBoolean(R.bool.config_p7_display_profile))
                AirPlayPersistence.DEFAULT_P7_DEVICE_NAME else AirPlayPersistence.DEFAULT_MODEL
        }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
    ): AndroidMediaSink = AndroidMediaSink(
        context = applicationContext,
        surface = null,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
        advancedAudioChannelMapping = advancedAudioChannelMapping,
        mainMediaAudioBufferDurationMs = mainMediaAudioBufferDurationMs,
        microphoneGainPercent = microphoneGainPercent,
        mediaMetricsMonitor = mediaMetricsMonitor,
        onScreenStreamActiveChanged = { type, active ->
            onScreenStreamStateChanged(controllerGeneration, type, active)
        },
        onFirstVideoFrame = { type, width, height ->
            onFirstVideoFrame(controllerGeneration, type, width, height)
        },
    )

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            override fun onClusterContentOffered(session: AirPlaySession, content: Set<com.shilapi.xcertplay.airplay.ClusterLabDisplay.Content>) {
                runOnUiThread {
                    if (!isDestroyed && !sessionStopped && controllerGeneration == restartGeneration) {
                        clusterRenderer?.offered(content)
                    }
                }
            }
            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (isDestroyed || sessionStopped || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    clusterRenderer?.session(true)
                    instrumentController?.session(true)
                    mainHandler.postDelayed({ applyInstrumentZoomIfReady() }, 1500)
                    activeDisplaySize?.let { requestP7ViewArea(it) }
                    armInitialVideoWatchdog()
                    syncAirPlayDarkMode()
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    if (activeAirPlaySession != null && activeAirPlaySession !== session) return@runOnUiThread
                    if (activeAirPlaySession === session) {
                        clusterRenderer?.session(false)
                        instrumentController?.session(false)
                        activeAirPlaySession = null
                        decodedVideoSize = null
                        viewAreaTransition.cancel()
                    }
                    if (sessionStopped || isDestroyed || menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    if (controller?.isDisplayReconfiguring() == true) {
                        appendLog("Previous display session ended; waiting for the new video size")
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage("CarPlay 连接已断开，正在重新连接")
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (sessionStopped || isDestroyed || menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage("传输异常，正在重新连接")
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                // Keep timing/geometry diagnostics; protocol hex floods rotate the
                // bounded file and can contain authentication or personal payloads.
                if (resources.getBoolean(R.bool.config_p7_display_profile) && message.startsWith(PROTOCOL_TRACE_PREFIX)) return
                val now = System.currentTimeMillis()
                appendFileLog(message, now)
                if (!debugLogsEnabled || message.startsWith(PROTOCOL_TRACE_PREFIX)) return
                synchronized(pendingScreenLogsLock) {
                    if (!debugLogsEnabled) return
                    pendingScreenLogs.addLast(PendingLog(controllerGeneration, now, message))
                    if (pendingScreenLogs.size > MAX_SCREEN_LOG_LINES) pendingScreenLogs.removeFirst()
                    if (!screenDrainPosted) {
                        screenDrainPosted = true
                        mainHandler.post(drainScreenLogs)
                    }
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = { status ->
        if (!sessionStopped && !isDestroyed && !menuOpen && controllerGeneration == restartGeneration) {
            updateHotspotStatus(status)
            val description = status.describe()
            setConnectionStage(description)
            when (status) {
                is CarPlayStatus.Failed -> reconnectAfterLoss(description)
                else -> Unit
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        if (sessionStopped || isFinishing || isDestroyed) return false
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        controller = snapshot.controller
        sink = snapshot.sink
        clusterRenderer?.bind(snapshot.sink)
        clusterRenderer?.session(snapshot.controller.currentAirPlaySession() != null)
        instrumentController?.session(snapshot.controller.currentAirPlaySession() != null)
        snapshot.sink.setMediaMetricsMonitor(mediaMetricsMonitor)
        activeAirPlaySession = snapshot.controller.currentAirPlaySession()
        viewAreaTransition.requireResync()
        val mediaState = snapshot.sink.screenStateSnapshot()
        decodedVideoSize = mediaState.decodedSizes[SCREEN_TYPE_MAIN]
        activeScreenStreamTypes.clear()
        activeScreenStreamTypes.addAll(mediaState.activeTypes)
        if (snapshot.width > 0 && snapshot.height > 0) {
            displayResize.restore(DisplaySize(snapshot.width, snapshot.height))
        }
        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(generation, type, active)
        }
        snapshot.sink.setFirstVideoFrameListener { type, width, height ->
            onFirstVideoFrame(generation, type, width, height)
        }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "正在恢复后台 CarPlay 服务"
            } else {
                "正在恢复后台 CarPlay 会话"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                "CarPlay 服务正在运行"
            } else {
                "CarPlay 会话正在运行"
            },
        )
        updateDebugOverlays()
        // The replacement window can have finished layout before the old session
        // is adopted. Reobserve after restore(), otherwise cached old bounds win.
        videoView?.post { videoView?.let { scheduleDisplaySize(it.width, it.height) } }
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (sessionStopped || menuOpen || handshakeResetInProgress || controller != null) return
        if (!displayResize.readyToStart || currentSurface?.isValid != true) return
        // A fresh P7 install uses Local files + Manual, whose required fields
        // are intentionally empty. Guide setup before constructing strict config.
        if (!validateMfiSettings() || !validateManualHotspotSettings()) {
            openSettingsMenu()
            setConnectionStage("请先选择认证文件并填写已有热点信息")
            return
        }
        val controllerGeneration = restartGeneration
        val config = try {
            createRuntimeConfig()
        } catch (error: IllegalArgumentException) {
            openSettingsMenu()
            mfiErrorView?.text = error.message ?: "请检查连接设置"
            mfiErrorView?.visibility = View.VISIBLE
            setConnectionStage("连接设置尚未完成")
            return
        }
        val airPlayConfig = createAirPlayConfig(size)
        decodedVideoSize = null
        viewAreaTransition.cancel()
        videoView?.setTransform(android.graphics.Matrix())
        val locationProvider: Iap2LocationProvider? =
            if (config.locationReportingEnabled) {
                AndroidCarPlayLocationProvider(this)
            } else {
                null
            }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${CarPlayDisplayScale.label(displayScaleTenths)}) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "软件解码" else "硬件解码"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "已开启" else "已关闭"} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${CarPlayDisplayScale.label(displayScaleTenths)} " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
        )
        sink = renderer
        clusterRenderer?.bind(renderer)
        currentSurface?.let(::attachSurface)
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider,
        )
        controller = next
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height)
        next.start()
    }

    private fun armInitialVideoWatchdog() {
        val session = activeAirPlaySession ?: return
        if (!viewAreaTransition.visible || menuOpen || !dynamicViewAreasEnabled ||
            session.mainDisplay.viewAreas.isEmpty() || decodedVideoSize != null ||
            initialVideoWatchdogSession === session) return
        initialVideoWatchdogSession = session
        val ticket = ++initialVideoWatchdogTicket
        // Start only after the phone has connected, never while waiting for it.
        mainHandler.postDelayed({
            if (ticket == initialVideoWatchdogTicket && initialVideoWatchdogSession === session && activeAirPlaySession === session &&
                viewAreaTransition.visible && !isDestroyed && !sessionStopped && !menuOpen &&
                decodedVideoSize == null && dynamicViewAreasEnabled) {
                rejectP7ViewArea("connected multi-view session produced no decoded frame")
            }
        }, 12_000L)
    }

    private fun syncAirPlayDarkMode() {
        val session = activeAirPlaySession ?: return
        val night = darkMode
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!audioPacketCaptureEnabled) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || sessionStopped) return
        val size = DisplaySize(width, height)
        when (displayResize.observe(size)) {
            DisplayResizeState.TimerAction.KEEP -> Unit
            DisplayResizeState.TimerAction.CANCEL -> {
                mainHandler.removeCallbacks(applyDisplaySize)
                maybeStartCarPlay()
            }
            DisplayResizeState.TimerAction.REPLACE -> {
                mainHandler.removeCallbacks(applyDisplaySize)
                mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
            }
        }
    }

    private fun applyDisplaySize(change: DisplayResizeState.Change) {
        if (sessionStopped) return
        val size = change.current
        val previous = change.previous
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress || displayResize.restarting) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else {
            val currentController = controller
            val currentSink = sink
            if (requestP7ViewArea(size)) return
            if (resources.getBoolean(R.bool.config_p7_display_profile) &&
                currentController != null && currentSink != null
            ) {
                val display = createAirPlayConfig(size).main
                if (currentController.reconfigureWirelessDisplay(display,
                        directBootstrap = resources.getBoolean(R.bool.config_p7_fast_reconnect))) {
                    currentSink.setVideoDimensions(display.widthPixels, display.heightPixels)
                    CarPlayBackgroundSession.store(currentController, currentSink, size.width, size.height)
                    setConnectionStage("正在适配画面尺寸…")
                    val path = if (resources.getBoolean(R.bool.config_p7_fast_reconnect)) "direct wireless bootstrap" else "retaining wireless listener"
                    appendLog("Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}; $path")
                    return
                }
            }
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    /** Experimental path: keep audio, iAP, listener and screen socket alive. */
    private fun requestP7ViewArea(size: DisplaySize): Boolean {
        if (isDestroyed || sessionStopped || menuOpen) return false
        val session = activeAirPlaySession ?: return false
        if (!resources.getBoolean(R.bool.config_p7_dynamic_viewareas) ||
            session.mainDisplay.viewAreas.isEmpty() || !dynamicViewAreasEnabled) return false
        // A background surface cannot report frames; defer rather than reconnect.
        if (!viewAreaTransition.visible) return true
        val index = P7ViewAreas.mode(size.width, size.height)
        if (index == null) {
            rejectP7ViewArea("unsupported window geometry")
            return true
        }
        val target = session.mainDisplay.viewAreas[index]
        if (!viewAreaTransition.pending && !viewAreaTransition.needsResync && session.mainViewArea == target) {
            updateP7VideoTransform()
            return true
        }
        val ticket = viewAreaTransition.begin(index) ?: return true
        appendLog("ViewArea request index=$index viewport=${target.width}x${target.height}; preserving active session")
        dispatchP7ViewArea(session, index, size, ticket)
        mainHandler.postDelayed({
            if (viewAreaTransition.isCurrent(ticket) && session === activeAirPlaySession &&
                !isDestroyed && !menuOpen && !sessionStopped) rejectP7ViewArea("acknowledgement/frame timeout")
        }, 2_000L)
        return true
    }

    private fun dispatchP7ViewArea(session: AirPlaySession, index: Int, size: DisplaySize, ticket: Long) {
        if (!viewAreaTransition.isCurrent(ticket) || session !== activeAirPlaySession ||
            isDestroyed || menuOpen || sessionStopped) return
        airPlayCommandExecutor.execute {
            session.sendTouch(emptyList())
            val sent = session.updateMainViewArea(index) { status ->
                runOnUiThread {
                    if (!viewAreaTransition.isCurrent(ticket) || session !== activeAirPlaySession ||
                        isDestroyed || sessionStopped || menuOpen) return@runOnUiThread
                    if (status !in 200..299) {
                        rejectP7ViewArea("phone response=$status")
                    } else {
                        updateP7VideoTransform()
                        viewAreaTransition.acknowledge(ticket)
                        controller?.let { activeController -> sink?.let { activeSink ->
                            CarPlayBackgroundSession.store(activeController, activeSink, size.width, size.height)
                        } }
                        airPlayCommandExecutor.execute { session.requestMainKeyFrame() }
                    }
                }
            }
            // RECORD can precede the event socket by a few milliseconds. Retry
            // within the original deadline instead of declaring incompatibility.
            if (!sent) mainHandler.postDelayed({ dispatchP7ViewArea(session, index, size, ticket) }, 100L)
        }
    }

    private fun rejectP7ViewArea(reason: String) {
        if (isDestroyed || sessionStopped || menuOpen || !viewAreaTransition.visible) return
        viewAreaTransition.cancel()
        dynamicViewAreasEnabled = false
        AirPlayPersistence.saveDynamicViewAreasEnabled(this, false)
        syncViewAreaStrategyChoice()
        appendLog("ViewArea fallback: $reason; saved fast reconnect strategy")
        videoView?.setTransform(android.graphics.Matrix())
        val size = activeDisplaySize
        val activeController = controller
        val activeSink = sink
        if (size != null && activeController != null && activeSink != null) {
            val ordinary = createAirPlayConfig(size).main
            if (activeController.reconfigureWirelessDisplay(ordinary, directBootstrap = true)) {
                activeSink.setVideoDimensions(ordinary.widthPixels, ordinary.heightPixels)
                CarPlayBackgroundSession.store(activeController, activeSink, size.width, size.height)
                setConnectionStage("已改用快速重连，正在恢复画面…")
                return
            }
        }
        restartCarPlay("当前手机未确认动态尺寸，已改用快速重连")
    }

    private fun updateP7VideoTransform(): Boolean {
        val view = videoView ?: return false
        val session = activeAirPlaySession ?: return false
        val area = session.mainViewArea ?: return false
        val decoded = decodedVideoSize ?: return false
        val crop = P7ViewAreas.crop(session.mainDisplay, area, decoded.first, decoded.second) ?: return false
        val matrix = android.graphics.Matrix().apply {
            setScale(crop.scaleX, crop.scaleY)
            postTranslate(crop.offsetX * view.width, crop.offsetY * view.height)
        }
        view.setTransform(matrix)
        return true
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (sessionStopped || isFinishing || isDestroyed) return
        if (!displayResize.readyToStart || currentSurface?.isValid != true) return
        if (controller == null && adoptBackgroundSession()) return
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            sessionStopped ||
            menuOpen ||
            handshakeResetInProgress ||
            controller != null
        ) {
            return
        }
        startCarPlay(size)
    }

    private fun reconnectAfterLoss(reason: String) {
        if (sessionStopped || menuOpen || handshakeResetInProgress) return
        if (controller?.isDisplayReconfiguring() == true) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        val generation = restartGeneration
        val delayMillis = if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            RECONNECT_DELAY_MILLIS
        }
        appendLog("$reason; retrying in ${delayMillis}ms")
        mainHandler.postDelayed(
            {
                reconnectScheduled = false
                if (
                    sessionStopped ||
                    menuOpen ||
                    handshakeResetInProgress ||
                    controller?.isDisplayReconfiguring() == true ||
                    generation != restartGeneration
                ) {
                    return@postDelayed
                }
                restartCarPlay("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (sessionStopped || menuOpen || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        if (displayResize.restarting) return
        if (controller == null) {
            maybeStartCarPlay()
            return
        }
        if (!displayResize.beginRestart()) return
        appendLog(reason)
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        teardownExecutor.execute {
            try {
                oldController?.close()
                oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            } finally {
                try {
                    oldSink?.close()
                } finally {
                    runOnUiThread {
                        displayResize.finishRestart()
                        if (!sessionStopped && !isDestroyed && generation == restartGeneration) {
                            // A split animation can finish while the old transport is closing.
                            // Re-read the view and wait for its final bounds before negotiating.
                            videoView?.let { scheduleDisplaySize(it.width, it.height) }
                            maybeStartCarPlay()
                        }
                    }
                }
            }
        }
    }

    private fun openSettingsMenu() {
        if (menuOpen || sessionStopped) return
        fiveFingerPinch.reset()
        stopMicrophoneGainTest()
        syncViewAreaStrategyChoice()
        viewAreaTransition.cancel()
        settingsBaseline = captureSettingsBaseline()
        instrumentBaseline = Triple(instrumentEnabled, instrumentZoomSteps, hideNativeLane)
        menuOpen = true
        handshakeResetInProgress = true
        startAfterHandshakeReset = false
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "已停止" else "未开启")
        updateHotspotStatusBlock()
        val generation = ++restartGeneration
        controller?.sendTouch(emptyList())
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        activeScreenStreamTypes.clear()
        setConnectionStage("完成设置后重新连接")
        gestureOverlay?.visibility = View.GONE
        clusterRenderer?.session(false)
        instrumentController?.session(false)
        rebindSettingsPanel()
        settingsMenu?.visibility = View.VISIBLE
        syncMainMediaAudioBufferControls()
        syncMicrophoneGainControls()
        microphoneTestButton?.isEnabled = false
        updateDebugOverlays()
        clearScreenLogs()
        appendLog("Settings opened; CarPlay handshake reset")
        updateResolutionMenu()
        teardownExecutor.execute {
            try {
                oldController?.close()
                oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            } finally {
                try {
                    oldSink?.close()
                } finally {
                    runOnUiThread {
                        if (sessionStopped || generation != restartGeneration) {
                            return@runOnUiThread
                        }
                        handshakeResetInProgress = false
                        microphoneTestButton?.isEnabled = menuOpen
                        if (!menuOpen && startAfterHandshakeReset) {
                            startAfterHandshakeReset = false
                            maybeStartCarPlay()
                        }
                    }
                }
            }
        }
    }

    private fun saveSettingsAndReconnect() {
        if (!menuOpen) return
        if (!validateMfiSettings()) return
        if (!validateManualHotspotSettings()) return
        persistMenuSettings()
        instrumentBaseline = null
        settingsBaseline = null
        finishSettingsMenu("Settings saved")
    }

    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        restoreSettingsBaseline()
        instrumentBaseline?.let { (enabled, zoom, hideLane) ->
            instrumentEnabled = enabled; instrumentZoomSteps = zoom; hideNativeLane = hideLane
        }
        instrumentBaseline = null
        finishSettingsMenu("Settings changes discarded")
    }

    private fun finishSettingsMenu(prefix: String) {
        if (!menuOpen || sessionStopped) return
        stopMicrophoneGainTest()
        menuOpen = false
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        updateDebugOverlays()
        clearScreenLogs()
        appendLog(
            "$prefix; starting a fresh handshake at " +
                "${CarPlayDisplayScale.label(displayScaleTenths)} with " +
                (if (hevcEnabled) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else {
            maybeStartCarPlay()
        }
    }

    private fun exitApplication() {
        if (!appRunState.beginExit(runToken)) return
        instrumentController?.close()
        instrumentController = null
        ClusterMapRenderer.stop()
        clusterRenderer = null
        NativeHotspotService.pause(this)
        shuttingDown.set(true)
        restoreSettingsBaseline()
        stopMicrophoneGainTest()
        restartGeneration += 1
        reconnectScheduled = false
        startAfterHandshakeReset = false
        viewAreaTransition.suspend()
        p7WindowCompat?.close()
        mainHandler.removeCallbacksAndMessages(null)
        // The active session can belong to a different host after a mode handoff.
        val background = CarPlayBackgroundSession.snapshot()
        val controllers = listOfNotNull(controller, background?.controller).distinct()
        val sinks = listOfNotNull(sink, background?.sink).distinct()
        CarPlayBackgroundSession.clear()
        controller = null
        sink = null
        P7WindowPreferences.endSession()
        Log.i(TAG, "User exit: stopping session and removing all app tasks")
        // Queued settings/reconnect teardown must finish before a new manual launch
        // may start services. Never kill the process: E28 restores remaining hosts.
        teardownExecutor.execute {
            try {
                controllers.forEach { old ->
                    runCatching {
                        old.close()
                        val clean = old.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
                        Log.i(TAG, "User exit: controller closed clean=$clean")
                    }.onFailure { Log.w(TAG, "User exit: controller cleanup failed", it) }
                }
                sinks.forEach { old ->
                    runCatching { old.close() }
                        .onFailure { Log.w(TAG, "User exit: media cleanup failed", it) }
                }
            } finally {
                airPlayCommandExecutor.shutdownNow()
                runOnUiThread {
                    try {
                        listOf(CarPlayMediaSessionService::class.java, CarPlayVpnService::class.java)
                            .forEach { service ->
                                runCatching { applicationContext.stopService(Intent(applicationContext, service)) }
                                    .onFailure { Log.w(TAG, "User exit: service stop failed", it) }
                            }
                    } finally {
                        appRunState.finishExit()
                        Log.i(TAG, "User exit complete; waiting for a new launch")
                    }
                }
                teardownExecutor.shutdown()
            }
        }
        returnHomeAndRemoveAppTasks()
    }

    private fun returnHomeAndRemoveAppTasks() {
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "User exit: could not open launcher", it) }
        // finishAndRemoveTask() on a non-root Activity only finishes that Activity
        // on this firmware. AppTask removes the whole stack, including old bar hosts.
        runCatching {
            getSystemService(ActivityManager::class.java).appTasks.forEach { task ->
                runCatching { task.finishAndRemoveTask() }
                    .onFailure { Log.w(TAG, "User exit: task removal failed", it) }
            }
        }.onFailure { Log.w(TAG, "User exit: app task enumeration failed", it) }
        finishAffinity()
        finishAndRemoveTask()
    }

    private fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true
        val pinchAction = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> FiveFingerPinchGesture.Action.DOWN
            MotionEvent.ACTION_POINTER_DOWN -> FiveFingerPinchGesture.Action.POINTER_DOWN
            MotionEvent.ACTION_MOVE -> FiveFingerPinchGesture.Action.MOVE
            MotionEvent.ACTION_POINTER_UP -> FiveFingerPinchGesture.Action.POINTER_UP
            MotionEvent.ACTION_UP -> FiveFingerPinchGesture.Action.UP
            MotionEvent.ACTION_CANCEL -> FiveFingerPinchGesture.Action.CANCEL
            else -> null
        }
        if (pinchAction != null) {
            val points = (0 until event.pointerCount).map {
                FiveFingerPinchGesture.Point(event.getPointerId(it), event.getX(it), event.getY(it))
            }
            val pinch = fiveFingerPinch.update(pinchAction, points, event.eventTime, resources.displayMetrics.density)
            if (pinch.started) {
                gestureSequenceActive = false
                gestureTracking = false
                controller?.sendTouch(emptyList())
            }
            if (pinch.openSettings) {
                appendLog("Five-finger pinch opened settings")
                openSettingsMenu()
            }
            if (pinch.consumed) return true
        }
        // Do not send stale coordinates while a viewport command is unconfirmed.
        if (viewAreaTransition.pending) return true

        // Single-finger input always reaches CarPlay, including top-left Back buttons.
        // The legacy edge shortcut captured that entire region before detecting a swipe.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == THREE_FINGER_COUNT && !gestureSequenceActive) {
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    controller?.sendTouch(emptyList())
                    appendLog("Three-finger swipe tracking started")
                    return true
                }
            }
        }

        if (gestureSequenceActive) {
            if (!gestureTracking || event.pointerCount != THREE_FINGER_COUNT) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    deltaY >= dp(THREE_FINGER_SWIPE_DISTANCE_DP) &&
                    deltaY >= deltaX * THREE_FINGER_SWIPE_DIRECTION_RATIO
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                    openSettingsMenu()
                    return true
                }
            }
            return true
        }

        val contacts = CarPlayTouchMapper.contacts(event, view.width, view.height)
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (isDestroyed || sessionStopped || generation != restartGeneration) return@runOnUiThread
            clusterRenderer?.active(type, active)
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            updateDebugOverlays()
        }
    }

    private fun onFirstVideoFrame(generation: Int, type: Int, width: Int, height: Int) {
        runOnUiThread {
            if (isDestroyed || sessionStopped || generation != restartGeneration) return@runOnUiThread
            clusterRenderer?.decoded(type, width, height)
            if (type == com.shilapi.xcertplay.airplay.ClusterLabDisplay.TYPE)
                mainHandler.postDelayed({ applyInstrumentZoomIfReady() }, 500)
            if (type != SCREEN_TYPE_MAIN) return@runOnUiThread
            decodedVideoSize = width to height
            if (activeAirPlaySession?.mainViewArea != null && !updateP7VideoTransform()) {
                // A cropped stream may change SPS just after the command ACK.
                // Let the existing bounded transition wait for the new size.
                if (viewAreaTransition.pending) return@runOnUiThread
                rejectP7ViewArea("unexpected decoded canvas ${width}x$height")
                return@runOnUiThread
            }
            controller?.confirmDisplayFrame(width, height)
            appendLog("Decoded video ready: ${width}x$height")
        }
    }

    private fun applyInstrumentZoomIfReady() {
        if (isDestroyed || sessionStopped || menuOpen || !instrumentEnabled) return
        val current = activeAirPlaySession ?: return
        if (sink?.screenStateSnapshot()?.decodedSizes?.containsKey(com.shilapi.xcertplay.airplay.ClusterLabDisplay.TYPE) != true) return
        val steps = instrumentZoomSteps
        airPlayCommandExecutor.execute { runCatching { current.applyInitialClusterZoom(steps) } }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    private fun setConnectionStage(message: String) {
        latestStage = message
        stageStatusView?.text = message
        updateDebugOverlays()
    }

    private fun updateDebugOverlays() {
        updateP7BarsButton()
        val showLogs = debugLogsEnabled && !menuOpen
        statusScrollView?.visibility = if (showLogs) View.VISIBLE else View.GONE
        disconnectedSettingsButton?.visibility =
            if (!menuOpen && activeScreenStreamTypes.isEmpty()) View.VISIBLE else View.GONE
        val showStage = !debugLogsEnabled &&
            !menuOpen &&
            activeScreenStreamTypes.isEmpty()
        stageStatusView?.visibility = if (showStage) View.VISIBLE else View.GONE
        updateMediaMetricsOverlay()
    }

    private fun updateMediaMetricsOverlay() {
        val root = contentRoot ?: return
        if (!mediaMetricsEnabled) {
            sink?.setMediaMetricsMonitor(null)
            mediaMetricsOverlay?.let(root::removeView)
            mediaMetricsOverlay = null
            mediaMetricsMonitor = null
            return
        }
        val monitor = mediaMetricsMonitor ?: MediaMetricsMonitor().also {
            mediaMetricsMonitor = it
        }
        sink?.setMediaMetricsMonitor(monitor)
        // The frame-rate axis follows the configured target, which the settings menu can change
        // while the overlay stays alive.
        val overlay = mediaMetricsOverlay ?: MediaMetricsOverlayView(
            this,
            monitor,
            fpsAxisMax = { fps.toFloat() },
        ).also {
            mediaMetricsOverlay = it
            val settingsIndex = root.indexOfChild(settingsMenu)
            root.addView(
                it,
                if (settingsIndex >= 0) settingsIndex else root.childCount,
                mediaMetricsOverlayLayoutParams(root.width, root.height),
            )
        }
        overlay.visibility = if (!menuOpen && !safeAreaEditorActive) View.VISIBLE else View.GONE
        updateMediaMetricsOverlayLayout(root.width, root.height)
    }

    private fun updateMediaMetricsOverlayLayout(screenWidth: Int, screenHeight: Int) {
        val overlay = mediaMetricsOverlay ?: return
        val desired = mediaMetricsOverlayLayoutParams(screenWidth, screenHeight)
        val current = overlay.layoutParams
        if (current.width != desired.width || current.height != desired.height) {
            overlay.layoutParams = desired
        }
    }

    private fun mediaMetricsOverlayLayoutParams(
        screenWidth: Int,
        screenHeight: Int,
    ): FrameLayout.LayoutParams {
        val fallback = resources.displayMetrics
        val panelWidth = (screenWidth.takeIf { it > 0 } ?: fallback.widthPixels) / 3
        val panelHeight = (screenHeight.takeIf { it > 0 } ?: fallback.heightPixels) / 3
        return FrameLayout.LayoutParams(panelWidth, panelHeight, Gravity.TOP or Gravity.START)
    }

    private fun appendLog(message: String) {
        val now = System.currentTimeMillis()
        appendFileLog(message, now)
        if (debugLogsEnabled && !menuOpen) appendScreenLog(now, message)
    }

    private fun appendScreenLog(timestampMillis: Long, message: String) {
        logLines.add(timestampMillis, formattedLogLine(message, timestampMillis))
        if (!logRenderScheduled) {
            logRenderScheduled = true
            mainHandler.postDelayed(renderLogLines, LOG_RENDER_INTERVAL_MILLIS)
        }
    }

    private fun appendFileLog(message: String, timestampMillis: Long) =
        sessionLog?.appendTimestamped(message, timestampMillis)

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val baseDirectory = getExternalFilesDir(null) ?: filesDir
        val logFile = File(File(baseDirectory, "logs"), "xcertplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "xcertplay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
    }

    private fun refreshLogView(nowMillis: Long) {
        if (!debugLogsEnabled || menuOpen) return
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        logLines.expireBefore(cutoff)
        statusView?.text = logLines.renderedText()
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstTimestampMillis?.let { oldest ->
            val delay = (oldest + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun clearScreenLogs() {
        synchronized(pendingScreenLogsLock) {
            pendingScreenLogs.clear()
            screenDrainPosted = false
        }
        mainHandler.removeCallbacks(drainScreenLogs)
        logLines.clear()
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(renderLogLines)
        logRenderScheduled = false
        statusView?.text = ""
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateP7BarsButton() {
        val compat = p7WindowCompat
        p7BarsButton?.apply {
            visibility = if (compat?.isFullscreen == true && !menuOpen) View.VISIBLE else View.GONE
            val showBars = compat?.showBarsFullscreen == true
            setImageResource(if (showBars) R.drawable.ic_enter_fullscreen else R.drawable.ic_show_system_bars)
            contentDescription = if (showBars) "切换为完全全屏" else "显示系统栏"
            tooltipText = contentDescription
            (layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                val margin = dp(if (activeScreenStreamTypes.isEmpty()) 84 else 12)
                if (params.bottomMargin != margin) { params.bottomMargin = margin; layoutParams = params }
            }
        }
    }

    private fun toggleP7SystemBars() {
        val compat = p7WindowCompat ?: return
        if (!compat.isFullscreen || menuOpen) return
        controller?.sendTouch(emptyList())
        val show = !compat.showBarsFullscreen
        P7WindowPreferences.select(this, show)
        appendLog("System bars requested: visible=$show; switching native component policy before measuring")
        switchP7WindowHost(show)
    }

    private fun switchP7WindowHost(showBars: Boolean) {
        if (sessionStopped || isFinishing || isDestroyed) return
        val target = if (showBars) OPCarPlayBarsActivity::class.java else OPCarPlayActivity::class.java
        startActivity(Intent(this, target).putExtra(P7WindowPreferences.MODE_KEY, showBars)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun applyFullscreenMode() {
        if (sessionStopped || isFinishing || isDestroyed) return
        p7WindowCompat?.let { compat ->
            // E28 needs the native and Android policies to agree. The generic
            // show() path used to re-show bars over a correctly sized video.
            compat.requestDock()
            window.decorView.post { if (!sessionStopped && !isFinishing && !isDestroyed) compat.requestDock() }
            return
        }
        val hideTop = hideTopBar && !isInMultiWindowMode && p7WindowCompat == null
        val hideBottom = hideBottomBar && !isInMultiWindowMode && p7WindowCompat == null
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        p7WindowCompat?.requestDock()
        // Vendor ActivityProxy also applies flags during lifecycle/config changes.
        window.decorView.post { if (!sessionStopped && !isFinishing && !isDestroyed) p7WindowCompat?.requestDock() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> "正在准备 MFi 认证"
        CarPlayStatus.WaitingForMfi -> "等待 MFi 认证设备"
        CarPlayStatus.RequestingMfiPermission -> "正在请求 MFi 设备的 USB 权限"
        CarPlayStatus.MfiReady -> "MFi 认证已就绪"
        CarPlayStatus.StartingHotspot -> "正在准备无线热点"
        is CarPlayStatus.HotspotReady ->
            "热点已就绪：$ssid，$band，" +
                "channel ${if (channel == 0) "auto" else channel}"
        CarPlayStatus.WaitingForPairedIphone -> "等待已配对的 iPhone"
        CarPlayStatus.ConnectingBluetooth -> "正在连接蓝牙"
        CarPlayStatus.RunningWireless -> "正在建立无线 CarPlay 连接"
        CarPlayStatus.WirelessActive -> "无线 CarPlay 已连接"
        CarPlayStatus.DiscoveringIphone -> "正在查找 iPhone"
        CarPlayStatus.WaitingForIphone -> "等待通过 USB 连接 iPhone"
        CarPlayStatus.RequestingIphonePermission -> "正在请求 iPhone 的 USB 权限"
        CarPlayStatus.WaitingForReenumeration -> "等待重新识别 iPhone"
        CarPlayStatus.SelectingConfiguration -> "正在选择 CarPlay 配置"
        CarPlayStatus.OpeningDataPaths -> "正在打开 USB 数据通道"
        CarPlayStatus.Pairing -> "正在与 iPhone 配对"
        CarPlayStatus.ConnectingControl -> "正在建立 iAP2 控制连接"
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) "正在启动 AirPlay 服务" else "正在连接 NCM/AirPlay 网络"
        CarPlayStatus.RunningControl -> "CarPlay 控制连接运行中"
        CarPlayStatus.ControlEnded -> "CarPlay 控制连接已结束"
        is CarPlayStatus.Failed -> "连接失败：${message}"
    }

    private companion object {
        val appRunState = AppRunState()
        const val TAG = "xcertplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val LOG_RENDER_INTERVAL_MILLIS = 100L
        const val MAX_SCREEN_LOG_LINES = 100
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
        const val THREE_FINGER_COUNT = 3
        const val THREE_FINGER_SWIPE_DISTANCE_DP = 72
        const val THREE_FINGER_SWIPE_DIRECTION_RATIO = 1.15f
        const val MAX_SETTINGS_MENU_WIDTH_PX = 1200
        val MENU_BACKGROUND = Color.rgb(12, 16, 19)
        val MENU_SECONDARY = Color.rgb(170, 180, 190)
        val MENU_ACCENT = Color.rgb(127, 205, 154)
        val MENU_ACCENT_TRACK = Color.rgb(78, 143, 102)
        val MENU_TRACK_OFF = Color.rgb(64, 74, 80)
        val MENU_BUTTON_TEXT = Color.rgb(8, 17, 11)
        val MENU_DANGER = Color.rgb(190, 45, 45)
        val NO_VIDEO_BACKGROUND = Color.rgb(0x16, 0x16, 0x18)
    }

    private data class PendingLog(
        val generation: Int,
        val timestampMillis: Long,
        val message: String,
    )
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
private object CarPlayBackgroundSession {
    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        return Snapshot(currentController, currentSink, width, height)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int) {
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        width = 0
        height = 0
    }
}
