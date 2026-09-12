package io.adhush.android

import android.content.Context
import android.hardware.ConsumerIrManager
import io.adhush.core.ControlError
import io.adhush.core.KeySender
import io.adhush.core.SharpIr
import io.adhush.core.TvKey

/** The phone's own infrared blaster, speaking Sharp's protocol. One-way: nothing comes back. */
class IrKeySender(context: Context, private val address: Int, private val volumeUp: Int, private val volumeDown: Int, private val mute: Int = 0x17) : KeySender {
    private val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    val available: Boolean get() = ir?.hasIrEmitter() == true

    private fun code(key: TvKey) = when (key) { TvKey.VOLUME_UP -> volumeUp; TvKey.VOLUME_DOWN -> volumeDown; TvKey.MUTE -> mute }

    override fun press(key: TvKey, times: Int) {
        val emitter = ir?.takeIf { it.hasIrEmitter() } ?: throw ControlError("this phone has no infrared blaster")
        val pattern = SharpIr.press(address, code(key))
        repeat(times) {
            emitter.transmit(SharpIr.CARRIER_HZ, pattern)
            Thread.sleep(PRESS_GAP_MS)   // the set counts distinct presses, not a held key
        }
    }

    companion object { const val PRESS_GAP_MS = 60L }
}
