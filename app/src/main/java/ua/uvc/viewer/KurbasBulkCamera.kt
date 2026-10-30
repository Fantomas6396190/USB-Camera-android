package ua.uvc.viewer

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bulk YUY2 path used by Kurbas 640 for VID 5474 PID FFFF.
 * One UsbRequest completion is one video payload. The probe is a single
 * SET, GET and COMMIT. Other cameras stay on the UVC library.
 */
internal class KurbasBulkCamera(
    private val device: UsbDevice,
    private val open: () -> UsbDeviceConnection?,
    private val onReady: (width: Int, height: Int) -> Unit,
    private val onFrame: (ByteBuffer) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onFailed: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var connection: UsbDeviceConnection? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val worker = Thread({ run() }, "kurbas-usb")
        thread = worker
        worker.start()
    }

    fun stop() {
        running.set(false)
        connection?.close()
        thread?.join(1500)
        thread = null
    }

    private fun run() {
        Process.setThreadPriority(-8)
        var conn: UsbDeviceConnection? = null
        try {
            conn = open() ?: throw IllegalStateException("Не вдалося відкрити камеру")
            connection = conn
            stream(conn)
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "stream", error)
                onFailed(error.message ?: "Помилка камери")
            }
        } finally {
            running.set(false)
            conn?.close()
            if (connection === conn) connection = null
        }
    }

    private fun stream(conn: UsbDeviceConnection) {
        val streaming = findStreaming(device) ?: throw IllegalStateException("Немає bulk-відеопотоку")
        val control = findControl(device)
        if (control != null && !conn.claimInterface(control, true)) {
            Log.w(TAG, "control interface not claimed")
        }
        if (!conn.claimInterface(streaming.intf, true)) {
            throw IllegalStateException("Інтерфейс зайнятий")
        }
        conn.setInterface(streaming.intf)
        val detected = readMode(conn)
        val mode = detected?.mode ?: Mode(1, 1, 640, 512, 200_000)
        val bcd = detected?.bcdUvc ?: 0x0150
        val probe = negotiate(conn, streaming.intf.id, mode, bcd)
        val frameBytes = mode.width * mode.height * 2
        val readLen = readLength(probe.payload, frameBytes, streaming.endpoint)
        Log.i(
            TAG,
            "bcd=%04X format=${mode.formatIndex} frame=${mode.frameIndex} interval=${mode.interval} payload=$readLen video=${probe.frameBytes}",
        )
        onStatus("YUY2 ${mode.width}×${mode.height}")
        onReady(mode.width, mode.height)
        val frame = ByteArray(frameBytes)
        var got = 0
        var lastFid = -1
        val buffers = Array(QUEUE) { ByteBuffer.allocateDirect(readLen) }
        val requests = Array(QUEUE) { UsbRequest() }
        try {
            for (index in requests.indices) {
                val request = requests[index]
                if (!request.initialize(conn, streaming.endpoint)) {
                    throw IllegalStateException("Не вдалося поставити USB-запит")
                }
                buffers[index].clear()
                if (!request.queue(buffers[index], readLen)) {
                    throw IllegalStateException("Черга USB порожня")
                }
            }
            var quietSince = android.os.SystemClock.elapsedRealtime()
            while (running.get()) {
                val done = conn.requestWait()
                if (done == null) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - quietSince > 2000) {
                        onStatus("Чекаю кадри…")
                        quietSince = now
                    }
                    if (!running.get()) break
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                val index = requests.indexOf(done)
                if (index < 0) continue
                val buffer = buffers[index]
                val count = buffer.position()
                if (count >= 2) {
                    val packet = ByteArray(count)
                    buffer.flip()
                    buffer.get(packet)
                    val step = takePacket(packet, count, frame, got, lastFid)
                    got = step.got
                    lastFid = step.lastFid
                    for (ready in step.frames) onFrame(ByteBuffer.wrap(ready))
                }
                buffer.clear()
                if (running.get() && !done.queue(buffer, readLen)) {
                    throw IllegalStateException("Не вдалося поставити USB-запит")
                }
            }
        } finally {
            requests.forEach { request ->
                runCatching { request.cancel() }
                runCatching { request.close() }
            }
        }
    }

    private class Step(val got: Int, val lastFid: Int, val frames: List<ByteArray>)

    private fun takePacket(packet: ByteArray, count: Int, frame: ByteArray, gotIn: Int, lastFidIn: Int): Step {
        val header = packet[0].toInt() and 0xFF
        if (header < 2 || header >= count) return Step(gotIn, lastFidIn, emptyList())
        val flags = packet[1].toInt() and 0xFF
        val fid = flags and 1
        var got = gotIn
        val frames = ArrayList<ByteArray>(2)
        if (lastFidIn >= 0 && fid != lastFidIn && got > 0) {
            publish(frame, got)?.let { frames.add(it) }
            got = 0
        }
        val copy = minOf(count - header, frame.size - got)
        if (copy > 0) {
            System.arraycopy(packet, header, frame, got, copy)
            got += copy
        }
        val eof = flags and 2 != 0
        if (got > 0 && (eof || got >= frame.size)) {
            publish(frame, got)?.let { frames.add(it) }
            got = 0
        }
        return Step(got, fid, frames)
    }

    private fun publish(frame: ByteArray, got: Int): ByteArray? {
        if (got < frame.size / 2) return null
        return frame.copyOf(got)
    }

    private fun readLength(payload: Int, frameBytes: Int, endpoint: UsbEndpoint): Int {
        val cap = frameBytes + 2048
        if (payload in 128..cap) return payload
        if (payload in cap until 2_097_153) return payload
        return maxOf(endpoint.maxPacketSize, 512) * 32
    }

    private fun negotiate(conn: UsbDeviceConnection, interfaceId: Int, mode: Mode, bcd: Int): Probe {
        val lengths = when {
            bcd >= 0x0150 -> intArrayOf(48)
            bcd >= 0x0110 -> intArrayOf(34)
            bcd > 0 -> intArrayOf(26)
            else -> intArrayOf(48, 34, 26)
        }
        var reason = "Камера не прийняла формат"
        for (length in lengths) {
            val block = ByteArray(length)
            block[0] = 1
            block[2] = mode.formatIndex.toByte()
            block[3] = mode.frameIndex.toByte()
            putLe32(block, 4, mode.interval)
            val set = control(conn, 0x21, 0x01, 0x0100, interfaceId, block)
            if (set != length) {
                reason = "Камера відхилила запит формату"
                continue
            }
            val get = control(conn, 0xA1, 0x81, 0x0100, interfaceId, block)
            if (get != length) {
                reason = "Камера не відповіла на запит формату"
                continue
            }
            val commit = control(conn, 0x21, 0x01, 0x0200, interfaceId, block)
            if (commit != length) {
                reason = "Камера не підтвердила формат"
                continue
            }
            val frameBytes = le32(block, 18)
            val payload = le32(block, 22)
            Log.i(TAG, "probe $length frameBytes=$frameBytes payload=$payload")
            return Probe(frameBytes, payload)
        }
        throw IllegalStateException(reason)
    }

    private fun readMode(conn: UsbDeviceConnection): Detected? {
        val config = readConfig(conn) ?: return null
        var inStream = false
        var yuy2 = false
        var formatIndex = 0
        var bcd = 0
        var mode: Mode? = null
        var offset = 0
        while (offset + 2 <= config.size) {
            val size = config[offset].toInt() and 0xFF
            if (size < 2 || offset + size > config.size) break
            val type = config[offset + 1].toInt() and 0xFF
            if (type == 4 && size >= 7) {
                val clazz = config[offset + 5].toInt() and 0xFF
                val subclass = config[offset + 6].toInt() and 0xFF
                inStream = clazz == 14 && subclass == 2
            } else if (type == 0x24) {
                val subtype = config[offset + 2].toInt() and 0xFF
                if (inStream && subtype == 4 && size >= 23) {
                    formatIndex = config[offset + 3].toInt() and 0xFF
                    yuy2 = config.copyOfRange(offset + 5, offset + 21).contentEquals(YUY2)
                } else if (inStream && subtype == 5 && yuy2 && size >= 26) {
                    val width = le16(config, offset + 5)
                    val height = le16(config, offset + 7)
                    val frameIndex = config[offset + 3].toInt() and 0xFF
                    val interval = frameInterval(config, offset, size)
                    val found = Mode(formatIndex, frameIndex, width, height, interval)
                    if (mode == null || (width == 640 && height == 512)) mode = found
                } else if (!inStream && subtype == 1 && size >= 5) {
                    bcd = le16(config, offset + 3)
                }
            }
            offset += size
        }
        val chosen = mode ?: if (bcd != 0) Mode(1, 1, 640, 512, 200_000) else return null
        return Detected(chosen, bcd)
    }

    private fun frameInterval(data: ByteArray, start: Int, size: Int): Int {
        val count = data[start + 25].toInt() and 0xFF
        if (count == 0 || size < 38) return 200_000
        val available = minOf(count, (size - 26) / 4)
        var best = le32(data, start + 26)
        var distance = kotlin.math.abs(best - 200_000)
        for (index in 1 until available) {
            val value = le32(data, start + 26 + index * 4)
            val next = kotlin.math.abs(value - 200_000)
            if (next < distance) {
                best = value
                distance = next
            }
        }
        return if (best > 0) best else 200_000
    }

    private fun readConfig(conn: UsbDeviceConnection): ByteArray? {
        val header = ByteArray(9)
        if (control(conn, 0x80, 0x06, 0x0200, 0, header) < 9) return null
        val total = le16(header, 2).coerceIn(9, 4096)
        val body = ByteArray(total)
        val got = control(conn, 0x80, 0x06, 0x0200, 0, body)
        if (got < 9) return null
        return if (got == body.size) body else body.copyOf(got)
    }

    private fun control(
        conn: UsbDeviceConnection,
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
    ): Int = conn.controlTransfer(requestType, request, value, index, buffer, buffer.size, 1000)

    private class Mode(
        val formatIndex: Int,
        val frameIndex: Int,
        val width: Int,
        val height: Int,
        val interval: Int,
    )

    private class Probe(val frameBytes: Int, val payload: Int)
    private class Detected(val mode: Mode, val bcdUvc: Int)
    private class StreamIf(val intf: UsbInterface, val endpoint: UsbEndpoint)

    companion object {
        private const val TAG = "UvcViewer"
        private const val QUEUE = 4
        private val YUY2 = byteArrayOf(
            0x59, 0x55, 0x59, 0x32, 0x00, 0x00, 0x10, 0x00,
            0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71,
        )

        fun matches(device: UsbDevice): Boolean =
            device.vendorId == 0x5474 && device.productId == 0xFFFF

        private fun findControl(device: UsbDevice): UsbInterface? {
            for (index in 0 until device.interfaceCount) {
                val item = device.getInterface(index)
                if (item.interfaceClass == 14 && item.interfaceSubclass == 1) return item
            }
            return null
        }

        private fun findStreaming(device: UsbDevice): StreamIf? {
            for (index in 0 until device.interfaceCount) {
                val item = device.getInterface(index)
                if (item.interfaceClass != 14 || item.interfaceSubclass != 2) continue
                for (endpointIndex in 0 until item.endpointCount) {
                    val endpoint = item.getEndpoint(endpointIndex)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_IN
                    ) {
                        return StreamIf(item, endpoint)
                    }
                }
            }
            return null
        }

        private fun le16(data: ByteArray, offset: Int): Int {
            return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        }

        private fun le32(data: ByteArray, offset: Int): Int {
            return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
        }

        private fun putLe32(data: ByteArray, offset: Int, value: Int) {
            data[offset] = value.toByte()
            data[offset + 1] = (value shr 8).toByte()
            data[offset + 2] = (value shr 16).toByte()
            data[offset + 3] = (value shr 24).toByte()
        }
    }
}
