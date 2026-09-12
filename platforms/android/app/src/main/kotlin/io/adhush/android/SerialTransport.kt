package io.adhush.android

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import io.adhush.core.Aquos
import io.adhush.core.AquosTransport
import io.adhush.core.ControlError

/**
 * The set's RS-232C port through a USB-OTG serial cable (FTDI, Prolific,
 * CH340, CP210x — whatever usb-serial-for-android recognises). Sharp's serial
 * protocol is the network one without the login: 9600 8N1, four-character
 * command, four-character parameter, CR; the set answers OK or ERR. The port
 * is opened lazily and reopened after any failure. Not thread-safe by itself;
 * the service serialises calls.
 */
class SerialTransport(private val context: Context, private val timeoutMs: Int = 2000) : AquosTransport, AutoCloseable {
    @Volatile var trace: ((String) -> Unit)? = null
    private var port: UsbSerialPort? = null

    /** The first serial adapter plugged in, or null. */
    fun device() = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()?.device
    private val manager get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Synchronized
    override fun exchange(payload: ByteArray): ByteArray {
        var attempt = 0
        while (true) {
            try {
                val p = port ?: open().also { port = it }
                p.write(payload, timeoutMs)
                val buf = ByteArray(256)
                val out = ArrayList<Byte>()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val n = p.read(buf, (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(50))
                    if (n > 0) { for (i in 0 until n) out.add(buf[i]); if (buf[n - 1] == '\r'.code.toByte() || buf[n - 1] == '\n'.code.toByte()) break }
                }
                val reply = out.toByteArray()
                trace?.invoke(Aquos.escape(payload) + " -> " + Aquos.escape(reply))
                return reply
            } catch (e: Exception) {
                runCatching { port?.close() }; port = null
                trace?.invoke("serial: ${e.message}")
                if (++attempt >= 2) throw ControlError("serial cable: ${e.message}", e)
            }
        }
    }

    private fun open(): UsbSerialPort {
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
            ?: throw ControlError("no USB serial adapter found — plug the cable into the phone (OTG)")
        if (!manager.hasPermission(driver.device)) throw ControlError("USB permission not granted — press Test TV in the app to allow it")
        val conn = manager.openDevice(driver.device) ?: throw ControlError("could not open the USB serial adapter")
        val p = driver.ports[0]
        p.open(conn)
        p.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        trace?.invoke("serial open: ${driver.javaClass.simpleName} 9600 8N1")
        return p
    }

    @Synchronized override fun close() { runCatching { port?.close() }; port = null }
}
