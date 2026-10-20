package ua.uvc.viewer

import android.app.Application
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.opengl.renderer.MirrorMode
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.UVCParam
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

enum class Phase {
    Idle,
    Permission,
    Opening,
    Preview,
    Error,
}

data class DeviceUi(val id: String, val label: String)

data class Notice(val id: Long, val text: String)

sealed interface ControlUi {
    val id: String

    data class Slider(
        override val id: String,
        val title: String,
        val value: Int,
        val enabled: Boolean = true,
    ) : ControlUi

    data class Toggle(
        override val id: String,
        val title: String,
        val value: Boolean,
    ) : ControlUi

    data class Choice(
        override val id: String,
        val title: String,
        val value: Int,
        val options: List<Pair<Int, String>>,
    ) : ControlUi
}

data class ViewerState(
    val phase: Phase = Phase.Idle,
    val notice: Notice? = null,
    val deviceLabel: String? = null,
    val devices: List<DeviceUi> = emptyList(),
    val modeLabel: String? = null,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val modes: List<ModeUi> = emptyList(),
    val controls: List<ControlUi> = emptyList(),
    val rotation: Int = 0,
    val mirrored: Boolean = false,
)

class UvcViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ViewerState())
    val state: StateFlow<ViewerState> = _state

    private var cameraHelper: CameraHelper? = null
    private var previewSurface: Surface? = null
    private var streamTexture: SurfaceTexture? = null
    private var currentDeviceId: String? = null
    private var connectInProgress = false
    private var pendingDevice: UsbDevice? = null
    private val knownDevices = linkedMapOf<String, UsbDevice>()
    private var autoStep = 0
    private var ladder: List<Size> = emptyList()
    private var ladderIndex = 0
    private var userRequested = false
    private var retuned = false
    private var lastGood: Size? = null
    private var exposureLimits: IntArray? = null
    private var backlightLimits: IntArray? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameBusy = AtomicBoolean(false)
    private val framesEnabled = AtomicBoolean(false)
    @Volatile private var frameWidth = 0
    @Volatile private var frameHeight = 0
    private var frameCopy = ByteArray(0)
    private var modeSwitch = 0
    private var previewWatch = 0
    @Volatile private var sawFrame = false
    var onVideoFrame: ((ByteArray, Int, Int) -> Unit)? = null

    private val frameCallback = IFrameCallback { frame ->
        deliverFrame(frame)
    }

    private val callback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            Log.i(TAG, "attach ${device.deviceName}")
            refreshDevices()
            knownDevices[device.deviceName] = device
            pendingDevice = device
            if (currentDeviceId != null || connectInProgress) {
                _state.update { it.copy(devices = deviceListUi()) }
                return
            }
            if (hasUsbPermission(device)) {
                beginConnect(device)
            } else {
                _state.update {
                    it.copy(
                        phase = Phase.Permission,
                        deviceLabel = device.displayLabel(),
                        devices = deviceListUi(),
                        notice = null,
                    )
                }
            }
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            Log.i(TAG, "device open ${device.deviceName} first=$isFirstOpen")
            currentDeviceId = device.deviceName
            connectInProgress = false
            _state.update {
                it.copy(phase = Phase.Opening, deviceLabel = device.displayLabel())
            }
            refreshDevices()
            openCamera(fallbackSize(autoStep))
        }

        override fun onCameraOpen(device: UsbDevice) {
            val helper = cameraHelper ?: return
            val supported = helper.supportedSizeList.orEmpty()
            val current = helper.previewSize
            Log.i(TAG, "camera open current=$current list=" + supported.joinToString { it.toString() })
            if (!userRequested && ladder.isEmpty()) {
                ladder = qualityLadder(supported)
                ladderIndex = 0
            }
            retuned = true
            val size = if (userRequested) current else ladder.getOrNull(ladderIndex) ?: current
            autoStep = 0
            userRequested = false
            applyPreviewConfig(helper)
            val controls = _state.value.controls.ifEmpty { readControls() }
            if (size != null) {
                beginPreview(helper, size)
                publishOpened(device, size, supported, controls)
                watchForFrames(helper, device, supported)
            } else {
                publishOpened(device, current, supported, controls)
            }
        }

        override fun onCameraClose(device: UsbDevice) {
            framesEnabled.set(false)
            previewSurface?.let { surface ->
                runCatching { cameraHelper?.removeSurface(surface) }
            }
            _state.update {
                val phase = if (it.phase == Phase.Preview) Phase.Opening else it.phase
                it.copy(
                    phase = phase,
                    previewWidth = 0,
                    previewHeight = 0,
                    modeLabel = null,
                    controls = emptyList(),
                )
            }
        }

        override fun onDeviceClose(device: UsbDevice) {
            if (device.deviceName == currentDeviceId) {
                currentDeviceId = null
            }
            connectInProgress = false
        }

        override fun onDetach(device: UsbDevice) {
            Log.i(TAG, "detach ${device.deviceName}")
            val wasCurrent = device.deviceName == currentDeviceId
            if (pendingDevice?.deviceName == device.deviceName) pendingDevice = null
            if (wasCurrent) {
                currentDeviceId = null
                lastGood = null
                autoStep = 0
                ladder = emptyList()
                ladderIndex = 0
                userRequested = false
                retuned = false
            }
            connectInProgress = false
            refreshDevices()
            if (knownDevices.isEmpty()) {
                _state.update {
                    it.copy(
                        phase = Phase.Idle,
                        deviceLabel = null,
                        modeLabel = null,
                        previewWidth = 0,
                        previewHeight = 0,
                        modes = emptyList(),
                        controls = emptyList(),
                    )
                }
            } else if (wasCurrent || _state.value.phase != Phase.Preview) {
                val next = knownDevices.values.first()
                pendingDevice = next
                _state.update {
                    it.copy(phase = Phase.Permission, deviceLabel = next.displayLabel())
                }
            }
        }

        override fun onCancel(device: UsbDevice) {
            Log.i(TAG, "permission cancelled ${device.deviceName}")
            connectInProgress = false
            pendingDevice = device
            if (device.deviceName == currentDeviceId) currentDeviceId = null
            _state.update {
                it.copy(
                    phase = Phase.Permission,
                    deviceLabel = device.displayLabel(),
                    notice = Notice(SystemClock.elapsedRealtime(), text(R.string.denied)),
                )
            }
        }

        override fun onError(device: UsbDevice, e: CameraException) {
            Log.e(TAG, "camera error code=${e.code}", e)
            viewModelScope.launch {
                if (userRequested) {
                    userRequested = false
                    val restore = lastGood
                    notice(text(R.string.mode_failed))
                    if (restore != null) openCamera(restore) else showOpenFailure(e)
                    return@launch
                }
                val next = ladder.getOrNull(ladderIndex + 1)
                if (next != null) {
                    ladderIndex += 1
                    openCamera(next)
                } else if (autoStep < 2) {
                    autoStep += 1
                    openCamera(fallbackSize(autoStep))
                } else {
                    showOpenFailure(e)
                }
            }
        }
    }

    init {
        try {
            cameraHelper = CameraHelper().also { it.setStateCallback(callback) }
            refreshDevices()
        } catch (error: Throwable) {
            Log.e(TAG, "CameraHelper init failed", error)
            _state.update {
                it.copy(phase = Phase.Error, notice = Notice(SystemClock.elapsedRealtime(), text(R.string.native_failed)))
            }
        }
    }

    fun onUsbIntent(device: UsbDevice?) {
        if (device == null) return
        pendingDevice = device
        knownDevices[device.deviceName] = device
        beginConnect(device)
    }

    fun requestAccess() {
        val device = pendingDevice ?: knownDevices.values.firstOrNull() ?: return
        beginConnect(device)
    }

    fun selectDevice(id: String) {
        val device = knownDevices[id] ?: return
        beginConnect(device)
    }

    private fun beginConnect(device: UsbDevice) {
        val helper = cameraHelper ?: return
        pendingDevice = device
        knownDevices[device.deviceName] = device
        autoStep = 0
        ladder = emptyList()
        ladderIndex = 0
        retuned = false
        userRequested = false
        connectInProgress = true
        _state.update {
            it.copy(
                phase = Phase.Permission,
                deviceLabel = device.displayLabel(),
                notice = null,
                devices = deviceListUi(),
            )
        }
        Log.i(TAG, "request permission ${device.deviceName}")
        helper.selectDevice(device)
    }

    fun onSurfaceReady(surface: Surface) = Unit

    fun onSurfaceDestroyed() = Unit

    fun selectMode(type: Int, width: Int, height: Int, fps: Int) {
        val helper = cameraHelper ?: return
        if (!helper.isCameraOpened) return
        val size = helper.supportedSizeList.orEmpty().findMode(type, width, height, fps) ?: return
        if (sameStream(size, helper.previewSize)) return
        userRequested = true
        retuned = true
        previewWatch += 1
        modeSwitch += 1
        _state.update {
            it.copy(
                modeLabel = modeLabel(size),
                previewWidth = size.width,
                previewHeight = size.height,
                modes = groupModes(helper.supportedSizeList.orEmpty(), size),
            )
        }
        beginPreview(helper, size)
    }

    fun rotate() {
        val helper = cameraHelper ?: return
        val next = (_state.value.rotation + 90) % 360
        _state.update { it.copy(rotation = next) }
        if (helper.isCameraOpened) applyPreviewConfig(helper)
    }

    fun toggleMirror() {
        val helper = cameraHelper ?: return
        val mirrored = !_state.value.mirrored
        _state.update { it.copy(mirrored = mirrored) }
        if (helper.isCameraOpened) applyPreviewConfig(helper)
    }

    fun setSlider(id: String, value: Int) {
        val control = cameraHelper?.getUVCControl() ?: return
        val percent = value.coerceIn(0, 100)
        runCatching {
            when (id) {
                "brightness" -> control.setBrightnessPercent(percent)
                "contrast" -> control.setContrastPercent(percent)
                "saturation" -> control.setSaturationPercent(percent)
                "sharpness" -> control.setSharpnessPercent(percent)
                "gamma" -> control.setGammaPercent(percent)
                "gain" -> control.setGainPercent(percent)
                "whiteBalance" -> control.setWhiteBalancePercent(percent)
                "focus" -> control.setFocusAbsolutePercent(percent)
                "zoom" -> control.setZoomAbsolutePercent(percent)
                "exposure" -> percentToValue(exposureLimits, percent)?.let(control::setExposureTimeAbsolute)
                "backlight" -> percentToValue(backlightLimits, percent)?.let(control::setBacklightComp)
            }
        }
        _state.update { state ->
            state.copy(
                controls = state.controls.map { item ->
                    if (item is ControlUi.Slider && item.id == id) item.copy(value = percent) else item
                },
            )
        }
    }

    fun setToggle(id: String, enabled: Boolean) {
        val control = cameraHelper?.getUVCControl() ?: return
        runCatching {
            when (id) {
                "whiteBalanceAuto" -> control.setWhiteBalanceAuto(enabled)
                "focusAuto" -> control.setFocusAuto(enabled)
                "exposureAuto" -> control.setExposureTimeAuto(enabled)
            }
        }
        publishControls()
    }

    fun setChoice(id: String, value: Int) {
        val control = cameraHelper?.getUVCControl() ?: return
        if (id == "powerline") {
            runCatching { control.setPowerlineFrequency(value) }
        }
        _state.update { state ->
            state.copy(
                controls = state.controls.map { item ->
                    if (item is ControlUi.Choice && item.id == id) item.copy(value = value) else item
                },
            )
        }
    }

    fun resetControls() {
        val control = cameraHelper?.getUVCControl() ?: return
        runCatching {
            if (control.isBrightnessEnable) control.resetBrightness()
            if (control.isContrastEnable) control.resetContrast()
            if (control.isSaturationEnable) control.resetSaturation()
            if (control.isSharpnessEnable) control.resetSharpness()
            if (control.isGammaEnable) control.resetGamma()
            if (control.isGainEnable) control.resetGain()
            if (control.isWhiteBalanceEnable) control.resetWhiteBalance()
            if (control.isWhiteBalanceAutoEnable) control.resetWhiteBalanceAuto()
            if (control.isFocusAbsoluteEnable) control.resetFocusAbsolute()
            if (control.isFocusAutoEnable) control.resetFocusAuto()
            if (control.isZoomAbsoluteEnable) control.resetZoomAbsolute()
            if (control.isExposureTimeAbsoluteEnable) control.resetExposureTimeAbsolute()
            if (control.isAutoExposureModeEnable) control.resetAutoExposureMode()
            if (control.isBacklightCompEnable) control.resetBacklightComp()
            if (control.isPowerlineFrequencyEnable) control.resetPowerlineFrequency()
        }
        publishControls()
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    override fun onCleared() {
        val helper = cameraHelper
        cameraHelper = null
        onVideoFrame = null
        runCatching { helper?.setFrameCallback(null, 0) }
        runCatching { helper?.release() }
        previewSurface?.release()
        previewSurface = null
        streamTexture?.release()
        streamTexture = null
        super.onCleared()
    }

    private fun beginPreview(helper: CameraHelper, size: Size) {
        framesEnabled.set(false)
        frameBusy.set(false)
        sawFrame = false
        runCatching { helper.setFrameCallback(null, 0) }
        runCatching { helper.stopPreview() }
        runCatching { helper.setPreviewSize(size) }
        armFrames(size)
        runCatching { helper.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_RGB565) }
        startPicture(helper, size)
        framesEnabled.set(frameWidth > 0 && frameHeight > 0)
        lastGood = size.clone()
    }

    private fun watchForFrames(helper: CameraHelper, device: UsbDevice, supported: List<Size>) {
        val watch = ++previewWatch
        mainHandler.postDelayed({
            if (watch != previewWatch || userRequested || sawFrame) return@postDelayed
            if (!helper.isCameraOpened) return@postDelayed
            val next = ladder.getOrNull(ladderIndex + 1) ?: return@postDelayed
            ladderIndex += 1
            Log.i(TAG, "no frames, step down to $next")
            beginPreview(helper, next)
            publishOpened(device, next, supported, _state.value.controls)
            watchForFrames(helper, device, supported)
        }, 1600)
    }

    private fun startPicture(helper: CameraHelper, size: Size?) {
        val width = (size?.width ?: 640).coerceAtLeast(2)
        val height = (size?.height ?: 480).coerceAtLeast(2)
        if (streamTexture == null || previewSurface == null) {
            val texture = SurfaceTexture(false)
            texture.setDefaultBufferSize(width, height)
            streamTexture = texture
            previewSurface = Surface(texture)
        } else {
            streamTexture?.setDefaultBufferSize(width, height)
        }
        previewSurface?.let { surface ->
            runCatching { helper.addSurface(surface, false) }
        }
        runCatching { helper.startPreview() }
    }

    private fun armFrames(size: Size?) {
        frameWidth = size?.width ?: 0
        frameHeight = size?.height ?: 0
    }

    private fun deliverFrame(frame: ByteBuffer) {
        if (!framesEnabled.get()) return
        if (!frameBusy.compareAndSet(false, true)) return
        val width = frameWidth
        val height = frameHeight
        val bytes = width * height * 2
        if (width <= 0 || height <= 0) {
            frameBusy.set(false)
            return
        }
        val source = frame.duplicate()
        source.clear()
        if (source.remaining() < bytes) {
            frameBusy.set(false)
            return
        }
        sawFrame = true
        var copy = frameCopy
        if (copy.size != bytes) {
            copy = ByteArray(bytes)
            frameCopy = copy
        }
        source.get(copy)
        mainHandler.post {
            try {
                if (framesEnabled.get()) onVideoFrame?.invoke(copy, width, height)
            } finally {
                frameBusy.set(false)
            }
        }
    }

    private fun openCamera(size: Size) {
        val helper = cameraHelper ?: return
        val param = UVCParam(size, 0)
        Log.i(TAG, "open $size")
        runCatching { helper.openCamera(param) }
            .onFailure { error ->
                Log.e(TAG, "openCamera threw", error)
                showOpenFailure(null)
            }
    }

    private fun publishOpened(
        device: UsbDevice,
        current: Size?,
        supported: List<Size>,
        controls: List<ControlUi> = readControls(),
    ) {
        _state.update {
            it.copy(
                phase = Phase.Preview,
                deviceLabel = device.displayLabel(),
                modeLabel = modeLabel(current),
                previewWidth = current?.width ?: 0,
                previewHeight = current?.height ?: 0,
                modes = groupModes(supported, current),
                controls = controls,
            )
        }
    }

    private fun showOpenFailure(error: CameraException?) {
        connectInProgress = false
        val message = if (error?.code == CameraException.CAMERA_OPEN_ERROR_BUSY) {
            text(R.string.busy)
        } else {
            text(R.string.open_failed)
        }
        _state.update { it.copy(phase = Phase.Error, notice = Notice(SystemClock.elapsedRealtime(), message)) }
    }

    private fun applyPreviewConfig(helper: CameraHelper) {
        val rotation = _state.value.rotation
        val mirror = if (_state.value.mirrored) MirrorMode.MIRROR_HORIZONTAL else MirrorMode.MIRROR_NORMAL
        runCatching {
            helper.previewConfig = helper.previewConfig.setRotation(rotation).setMirror(mirror)
        }
    }

    private fun readControls(): List<ControlUi> {
        val control = cameraHelper?.getUVCControl() ?: return emptyList()
        val items = mutableListOf<ControlUi>()
        fun slider(id: String, title: String, enabled: Boolean, value: Int, editable: Boolean = true) {
            if (!enabled) return
            items += ControlUi.Slider(id, title, value.coerceIn(0, 100), editable)
        }
        safe(control::isBrightnessEnable) { slider("brightness", text(R.string.brightness), true, control.brightnessPercent) }
        safe(control::isContrastEnable) { slider("contrast", text(R.string.contrast), true, control.contrastPercent) }
        safe(control::isSaturationEnable) { slider("saturation", text(R.string.saturation), true, control.saturationPercent) }
        safe(control::isSharpnessEnable) { slider("sharpness", text(R.string.sharpness), true, control.sharpnessPercent) }
        safe(control::isGammaEnable) { slider("gamma", text(R.string.gamma), true, control.gammaPercent) }
        safe(control::isGainEnable) { slider("gain", text(R.string.gain), true, control.gainPercent) }
        safe(control::isWhiteBalanceAutoEnable) {
            items += ControlUi.Toggle("whiteBalanceAuto", text(R.string.white_balance_auto), control.whiteBalanceAuto)
        }
        safe(control::isWhiteBalanceEnable) {
            val auto = runCatching { control.isWhiteBalanceAutoEnable && control.whiteBalanceAuto }.getOrDefault(false)
            slider("whiteBalance", text(R.string.white_balance), true, control.whiteBalancePercent, !auto)
        }
        safe(control::isFocusAutoEnable) {
            items += ControlUi.Toggle("focusAuto", text(R.string.focus_auto), control.focusAuto)
        }
        safe(control::isFocusAbsoluteEnable) {
            val auto = runCatching { control.isFocusAutoEnable && control.focusAuto }.getOrDefault(false)
            slider("focus", text(R.string.focus), true, control.focusAbsolutePercent, !auto)
        }
        safe(control::isZoomAbsoluteEnable) { slider("zoom", text(R.string.zoom), true, control.zoomAbsolutePercent) }
        safe(control::isAutoExposureModeEnable) {
            items += ControlUi.Toggle("exposureAuto", text(R.string.exposure_auto), control.isExposureTimeAuto)
        }
        safe(control::isExposureTimeAbsoluteEnable) {
            exposureLimits = control.updateExposureTimeAbsoluteLimit()
            val auto = runCatching { control.isAutoExposureModeEnable && control.isExposureTimeAuto }.getOrDefault(false)
            val percent = valueToPercent(exposureLimits, control.exposureTimeAbsolute)
            slider("exposure", text(R.string.exposure), true, percent, !auto)
        }
        safe(control::isBacklightCompEnable) {
            backlightLimits = control.updateBacklightCompLimit()
            slider("backlight", text(R.string.backlight), true, valueToPercent(backlightLimits, control.backlightComp))
        }
        safe(control::isPowerlineFrequencyEnable) {
            val current = control.powerlineFrequency
            items += ControlUi.Choice(
                id = "powerline",
                title = text(R.string.powerline),
                value = current,
                options = listOf(
                    0 to text(R.string.powerline_off),
                    1 to text(R.string.powerline_50),
                    2 to text(R.string.powerline_60),
                    3 to text(R.string.powerline_auto),
                ),
            )
        }
        return items
    }

    private fun publishControls() {
        _state.update { it.copy(controls = readControls()) }
    }

    private fun refreshDevices() {
        cameraHelper?.deviceList.orEmpty().forEach { knownDevices[it.deviceName] = it }
        usbManager()?.deviceList?.values.orEmpty()
            .filter { it.looksLikeUvc() }
            .forEach { knownDevices[it.deviceName] = it }
        val manager = usbManager()
        if (manager != null) {
            knownDevices.keys.retainAll(manager.deviceList.keys)
        }
        if (pendingDevice != null && pendingDevice?.deviceName !in knownDevices) {
            pendingDevice = knownDevices.values.firstOrNull()
        }
        _state.update { it.copy(devices = deviceListUi()) }
    }

    private fun deviceListUi(): List<DeviceUi> =
        knownDevices.values.map { DeviceUi(it.deviceName, it.displayLabel()) }

    private fun usbManager(): UsbManager? =
        getApplication<Application>().getSystemService(UsbManager::class.java)

    private fun hasUsbPermission(device: UsbDevice): Boolean =
        usbManager()?.hasPermission(device) == true

    private fun UsbDevice.looksLikeUvc(): Boolean {
        if (deviceClass == 14 || deviceClass == 239) return true
        for (index in 0 until interfaceCount) {
            if (getInterface(index).interfaceClass == 14) return true
        }
        return false
    }

    private fun notice(message: String) {
        _state.update { it.copy(notice = Notice(SystemClock.elapsedRealtime(), message)) }
    }

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    private fun UsbDevice.displayLabel(): String = try {
        listOfNotNull(manufacturerName, productName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
            .ifBlank { deviceName }
    } catch (_: SecurityException) {
        deviceName
    }

    private inline fun safe(enabled: () -> Boolean, block: () -> Unit) {
        if (runCatching(enabled).getOrDefault(false)) {
            runCatching(block).onFailure { Log.w(TAG, "control read failed", it) }
        }
    }

    companion object {
        private const val TAG = "UvcViewer"

        private fun valueToPercent(limits: IntArray?, value: Int): Int {
            if (limits == null || limits.size < 2) return 0
            val range = kotlin.math.abs(limits[1] - limits[0])
            if (range == 0) return 0
            return ((value - limits[0]) * 100f / range).toInt().coerceIn(0, 100)
        }

        private fun percentToValue(limits: IntArray?, percent: Int): Int? {
            if (limits == null || limits.size < 2) return null
            val range = kotlin.math.abs(limits[1] - limits[0])
            return (percent.coerceIn(0, 100) / 100f * range).toInt() + limits[0]
        }
    }
}
