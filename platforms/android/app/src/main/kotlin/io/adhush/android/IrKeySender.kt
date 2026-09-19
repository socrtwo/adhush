package io.adhush.android

import android.content.Context
import android.hardware.ConsumerIrManager
import io.adhush.core.ControlError
import io.adhush.core.IrCodeSet
import io.adhush.core.IrCodeSets
import io.adhush.core.IrProtocol
import io.adhush.core.KeySender
import io.adhush.core.TvKey

/** The phone's own infrared blaster, speaking whichever brand's protocol the code set names (ADR 0025). One-way: nothing comes back. */
class IrKeySender(context: Context, val codes: IrCodeSet) : KeySender {
    private val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    val available: Boolean get() = ir?.hasIrEmitter() == true

    override fun press(key: TvKey, times: Int) {
        val emitter = ir?.takeIf { it.hasIrEmitter() } ?: throw ControlError("this phone has no infrared blaster")
        val pattern = codes.pattern(key)
        repeat(times) {
            emitter.transmit(codes.protocol.carrierHz, pattern)
            Thread.sleep(PRESS_GAP_MS)   // the set counts distinct presses, not a held key
        }
    }

    companion object {
        const val PRESS_GAP_MS = 60L

        /** The set the owner chose: a named brand, or Sharp with the codes typed on the TV page. */
        fun codesFor(settings: Settings): IrCodeSet =
            IrCodeSets.byName(settings.irCodeSet)?.takeIf { settings.irCodeSet != "sharp" }
                ?: IrCodeSet("sharp", "Sharp", IrProtocol.SHARP, settings.irAddress, settings.irVolumeUp, settings.irVolumeDown, 0x17, 0x16)

        fun fromSettings(context: Context, settings: Settings) = IrKeySender(context, codesFor(settings))
    }
}
