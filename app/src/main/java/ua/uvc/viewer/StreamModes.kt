package ua.uvc.viewer

import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera

data class ModeUi(
    val type: Int,
    val width: Int,
    val height: Int,
    val format: String,
    val fps: List<Int>,
    val activeFps: Int?,
) {
    val key: String = "$type:$width:$height"
    val title: String = "${width}×${height}"
}

internal fun Size.fpsChoices(): List<Int> {
    val listed = fpsList?.mapNotNull { (it as? Number)?.toInt() }.orEmpty()
    return (listed + fps).filter { it > 0 }.distinct().sorted()
}

internal fun preferredFps(choices: List<Int>, fallback: Int): Int {
    if (choices.isEmpty()) return fallback.coerceIn(1, 30)
    for (wanted in intArrayOf(30, 15, 20, 25, 10, 5)) {
        if (wanted in choices) return wanted
    }
    return choices.filter { it <= 30 }.maxOrNull() ?: choices.min()
}

internal fun formatName(type: Int): String = when (type) {
    UVCCamera.UVC_VS_FRAME_MJPEG -> "MJPEG"
    UVCCamera.UVC_VS_FRAME_UNCOMPRESSED -> "YUYV"
    else -> "FMT $type"
}

internal fun tune(size: Size): Size {
    val copy = size.clone()
    copy.fps = preferredFps(size.fpsChoices(), size.fps)
    return copy
}

internal fun fallbackSize(step: Int): Size {
    return when (step) {
        0 -> Size(UVCCamera.UVC_VS_FRAME_MJPEG, 640, 480, 30, arrayListOf(30))
        1 -> Size(UVCCamera.UVC_VS_FRAME_MJPEG, 640, 480, 15, arrayListOf(15))
        else -> Size(UVCCamera.UVC_VS_FRAME_MJPEG, 352, 288, 30, arrayListOf(30))
    }
}

internal fun qualityLadder(sizes: List<Size>): List<Size> {
    val mjpeg = sizes.filter { it.type == UVCCamera.UVC_VS_FRAME_MJPEG }
    val pool = mjpeg.ifEmpty { sizes }
    return pool
        .groupBy { it.width to it.height }
        .values
        .map { tune(it.first()) }
        .sortedByDescending { it.width * it.height }
}

internal fun sameStream(wanted: Size, actual: Size?): Boolean {
    return actual != null &&
        wanted.type == actual.type &&
        wanted.width == actual.width &&
        wanted.height == actual.height &&
        wanted.fps == actual.fps
}

internal fun pickModerate(sizes: List<Size>): Size? {
    val mjpeg = sizes.filter { it.type == UVCCamera.UVC_VS_FRAME_MJPEG }
    val pool = mjpeg.ifEmpty { sizes }
    if (pool.isEmpty()) return null
    pool.firstOrNull { it.width == 1280 && it.height == 720 }?.let { return tune(it) }
    val bounded = pool.filter { it.width * it.height <= 1920 * 1080 }
    val chosen = bounded.maxByOrNull { it.width * it.height } ?: pool.minByOrNull { it.width * it.height }
    return chosen?.let(::tune)
}

internal fun shouldDownscale(current: Size?): Boolean {
    if (current == null) return false
    return current.width * current.height > 1920 * 1080
}

internal fun groupModes(sizes: List<Size>, active: Size?): List<ModeUi> {
    return sizes
        .groupBy { Triple(it.type, it.width, it.height) }
        .map { (key, group) ->
            val fps = group.flatMap { it.fpsChoices() }.distinct().sorted()
            val selected = active != null &&
                active.type == key.first &&
                active.width == key.second &&
                active.height == key.third
            ModeUi(
                type = key.first,
                width = key.second,
                height = key.third,
                format = formatName(key.first),
                fps = fps.ifEmpty { listOf(group.first().fps) },
                activeFps = if (selected) active.fps else null,
            )
        }
        .sortedWith(
            compareBy<ModeUi> { if (it.format == "MJPEG") 0 else 1 }
                .thenByDescending { it.width * it.height },
        )
}

internal fun List<Size>.findMode(type: Int, width: Int, height: Int, fps: Int): Size? {
    val matches = filter { it.type == type && it.width == width && it.height == height }
    val exact = matches.firstOrNull { fps in it.fpsChoices() || it.fps == fps } ?: return null
    return exact.clone().also { it.fps = fps }
}

internal fun modeLabel(size: Size?): String? {
    size ?: return null
    return "${size.width}×${size.height} ${formatName(size.type)} · ${size.fps} к/с"
}
