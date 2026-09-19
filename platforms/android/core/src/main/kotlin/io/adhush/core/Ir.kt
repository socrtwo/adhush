package io.adhush.core

/**
 * Sharp's infrared protocol, as a pulse pattern for Android's ConsumerIrManager.
 *
 * 38 kHz carrier; every bit is a 320 µs mark followed by a 680 µs space for 0 or
 * a 1680 µs space for 1; 15 bits, least significant first: 5 address bits, 8
 * command bits, an expansion bit and a check bit; a trailing 320 µs mark; then,
 * after a 40 ms gap, the same frame with the command, expansion and check bits
 * inverted (the address is not). A receiver accepts a key only when both halves
 * agree, which is why one "press" is always the pair.
 *
 * The defaults are the command bytes Sharp TVs have used for decades (address
 * 1): they are a starting point, editable in the app, because a phone cannot
 * learn a code from the remote.
 */
object SharpIr {
    const val CARRIER_HZ = 38_000
    const val MARK_US = 320
    const val ZERO_SPACE_US = 680
    const val ONE_SPACE_US = 1680
    const val GAP_US = 40_000

    data class Codes(
        val address: Int = 1,
        val volumeUp: Int = 0x14,
        val volumeDown: Int = 0x15,
        val mute: Int = 0x17,
        val power: Int = 0x16,
    )

    /** One press: frame, gap, inverted frame — as alternating on/off microseconds, starting with on. */
    fun press(address: Int, command: Int, expansion: Int = 1, check: Int = 0): IntArray {
        require(address in 0..31) { "address is 5 bits: $address" }
        require(command in 0..255) { "command is 8 bits: $command" }
        val first = frame(address, command, expansion, check)
        val second = frame(address, command.inv() and 0xFF, expansion.inv() and 1, check.inv() and 1)
        return first + intArrayOf(GAP_US) + second
    }

    private fun frame(address: Int, command: Int, expansion: Int, check: Int): IntArray {
        val bits = ArrayList<Int>(15)
        for (i in 0 until 5) bits.add((address shr i) and 1)
        for (i in 0 until 8) bits.add((command shr i) and 1)
        bits.add(expansion and 1); bits.add(check and 1)
        val out = IntArray(bits.size * 2 + 1)
        for ((i, b) in bits.withIndex()) { out[2 * i] = MARK_US; out[2 * i + 1] = if (b == 1) ONE_SPACE_US else ZERO_SPACE_US }
        out[out.size - 1] = MARK_US
        return out
    }

    /** The 15-bit word LIRC-style listings print (address low, then command, expansion, check). */
    fun word(address: Int, command: Int, expansion: Int = 1, check: Int = 0): Int =
        (address and 0x1F) or ((command and 0xFF) shl 5) or ((expansion and 1) shl 13) or ((check and 1) shl 14)
}

// ---- Every other brand's infrared (ADR 0025) --------------------------------

/** The pulse encodings the common brands use; each gives an on/off pattern in microseconds, starting with on. */
enum class IrProtocol(val carrierHz: Int) {
    SHARP(38_000), NEC(38_000), SAMSUNG(38_000), SIRC12(40_000), RC5(36_000), PANASONIC(37_000);
}

/** One brand's codes for the four keys AdHush uses; [address] means what each protocol means by it. */
data class IrCodeSet(
    val name: String,
    val brand: String,
    val protocol: IrProtocol,
    val address: Int,
    val volumeUp: Int,
    val volumeDown: Int,
    val mute: Int,
    val power: Int,
) {
    fun code(key: TvKey): Int = when (key) { TvKey.VOLUME_UP -> volumeUp; TvKey.VOLUME_DOWN -> volumeDown; TvKey.MUTE -> mute }
    fun pattern(key: TvKey): IntArray = pattern(code(key))
    fun pattern(command: Int): IntArray = when (protocol) {
        IrProtocol.SHARP -> SharpIr.press(address, command)
        IrProtocol.NEC -> NecIr.press(address, command)
        IrProtocol.SAMSUNG -> SamsungIr.press(address, command)
        IrProtocol.SIRC12 -> SircIr.press(address, command)
        IrProtocol.RC5 -> Rc5Ir.press(address, command)
        IrProtocol.PANASONIC -> PanasonicIr.press(address, command)
    }
}

object IrCodeSets {
    /** The sets worth trying, in the order the wizard tries them when the brand is unknown. */
    val KNOWN: List<IrCodeSet> = listOf(
        IrCodeSet("sharp", "Sharp", IrProtocol.SHARP, address = 1, volumeUp = 0x14, volumeDown = 0x15, mute = 0x17, power = 0x16),
        IrCodeSet("samsung", "Samsung", IrProtocol.SAMSUNG, address = 0x07, volumeUp = 0x07, volumeDown = 0x0B, mute = 0x0F, power = 0x02),
        IrCodeSet("lg", "LG", IrProtocol.NEC, address = 0x04, volumeUp = 0x02, volumeDown = 0x03, mute = 0x09, power = 0x08),
        IrCodeSet("vizio", "Vizio", IrProtocol.NEC, address = 0x04, volumeUp = 0x02, volumeDown = 0x03, mute = 0x09, power = 0x08),   // Vizio sets answer LG's codes
        IrCodeSet("sony", "Sony", IrProtocol.SIRC12, address = 1, volumeUp = 18, volumeDown = 19, mute = 20, power = 21),
        IrCodeSet("philips", "Philips", IrProtocol.RC5, address = 0, volumeUp = 16, volumeDown = 17, mute = 13, power = 12),
        IrCodeSet("panasonic", "Panasonic", IrProtocol.PANASONIC, address = 0x80, volumeUp = 0x20, volumeDown = 0x21, mute = 0x32, power = 0x3D),
        IrCodeSet("toshiba", "Toshiba", IrProtocol.NEC, address = 0x40, volumeUp = 0x1A, volumeDown = 0x1E, mute = 0x10, power = 0x12),
    )

    fun byName(name: String): IrCodeSet? = KNOWN.firstOrNull { it.name == name }

    /** The sets for a brand first (a Roku TV is usually a TCL or Hisense, tried last), then the rest. */
    fun ordered(brand: String?): List<IrCodeSet> {
        val b = brand?.lowercase() ?: return KNOWN
        val first = KNOWN.filter { it.brand.lowercase() == b || b.contains(it.brand.lowercase()) }
        return first + KNOWN.filterNot { it in first }
    }
}

/** NEC: 9 ms / 4.5 ms leader, 560 µs marks, 560 / 1690 µs spaces, 32 bits LSB first: address, ~address, command, ~command. */
object NecIr {
    const val LEADER_MARK_US = 9000
    const val LEADER_SPACE_US = 4500
    const val MARK_US = 560
    const val ZERO_SPACE_US = 560
    const val ONE_SPACE_US = 1690

    fun press(address: Int, command: Int): IntArray {
        require(address in 0..255 && command in 0..255)
        val bits = lsbFirst(address, 8) + lsbFirst(address.inv() and 0xFF, 8) + lsbFirst(command, 8) + lsbFirst(command.inv() and 0xFF, 8)
        return intArrayOf(LEADER_MARK_US, LEADER_SPACE_US) + pulses(bits, MARK_US, ZERO_SPACE_US, ONE_SPACE_US) + intArrayOf(MARK_US)
    }
}

/** Samsung's NEC variant: 4.5 ms / 4.5 ms leader, the 8-bit address sent twice un-inverted, then command and ~command. */
object SamsungIr {
    const val LEADER_US = 4500

    fun press(address: Int, command: Int): IntArray {
        require(address in 0..255 && command in 0..255)
        val bits = lsbFirst(address, 8) + lsbFirst(address, 8) + lsbFirst(command, 8) + lsbFirst(command.inv() and 0xFF, 8)
        return intArrayOf(LEADER_US, LEADER_US) + pulses(bits, NecIr.MARK_US, NecIr.ZERO_SPACE_US, NecIr.ONE_SPACE_US) + intArrayOf(NecIr.MARK_US)
    }
}

/** Sony SIRC, 12-bit: 2.4 ms leader, 1 = 1.2 ms mark, 0 = 0.6 ms mark, 0.6 ms spaces; 7 command bits then 5 device bits, LSB first; sent three times 45 ms apart. */
object SircIr {
    const val LEADER_MARK_US = 2400
    const val SPACE_US = 600
    const val ONE_MARK_US = 1200
    const val ZERO_MARK_US = 600
    const val PERIOD_US = 45_000
    const val REPEATS = 3

    fun frame(device: Int, command: Int): IntArray {
        require(device in 0..31 && command in 0..127)
        val bits = lsbFirst(command, 7) + lsbFirst(device, 5)
        val out = ArrayList<Int>()
        out.add(LEADER_MARK_US); out.add(SPACE_US)
        for (b in bits) { out.add(if (b == 1) ONE_MARK_US else ZERO_MARK_US); out.add(SPACE_US) }
        return out.toIntArray()
    }

    fun press(device: Int, command: Int): IntArray {
        val f = frame(device, command)
        val gap = PERIOD_US - f.sum()
        val out = ArrayList<Int>()
        repeat(REPEATS) { i -> out.addAll(f.toList()); if (i < REPEATS - 1) out[out.size - 1] = out[out.size - 1] + gap }
        return out.toIntArray()
    }
}

/** Philips RC-5: 14 Manchester bits of 889 µs half-bits — two start bits, a toggle, 5 address bits, 6 command bits, MSB first. */
object Rc5Ir {
    const val HALF_US = 889
    private var toggle = 0

    fun press(address: Int, command: Int): IntArray {
        require(address in 0..31 && command in 0..63)
        toggle = toggle xor 1
        val bits = listOf(1, 1, toggle) + msbFirst(address, 5) + msbFirst(command, 6)
        // Manchester: 1 = space then mark, 0 = mark then space.
        val states = ArrayList<Boolean>()
        for (b in bits) { if (b == 1) { states.add(false); states.add(true) } else { states.add(true); states.add(false) } }
        // Merge equal neighbours; the pattern must start with a mark, so a leading space is dropped.
        val out = ArrayList<Int>()
        var i = 0
        while (i < states.size && !states[i]) i++
        var current = true; var run = 0
        while (i < states.size) {
            if (states[i] == current) run += HALF_US else { out.add(run); current = states[i]; run = HALF_US }
            i++
        }
        out.add(run)
        return out.toIntArray()
    }
}

/** Panasonic (Kaseikyo): 3.5 ms / 1.75 ms leader, 435 µs marks, 435 / 1300 µs spaces, 48 bits LSB first: vendor 0x2002, device, sub-device, command, parity. */
object PanasonicIr {
    const val LEADER_MARK_US = 3500
    const val LEADER_SPACE_US = 1750
    const val MARK_US = 435
    const val ZERO_SPACE_US = 435
    const val ONE_SPACE_US = 1300
    const val VENDOR = 0x2002

    fun press(device: Int, command: Int, subDevice: Int = 0): IntArray {
        require(device in 0..255 && command in 0..255 && subDevice in 0..255)
        val parity = device xor subDevice xor command
        val bits = lsbFirst(VENDOR and 0xFF, 8) + lsbFirst((VENDOR shr 8) and 0xFF, 8) + lsbFirst(device, 8) + lsbFirst(subDevice, 8) + lsbFirst(command, 8) + lsbFirst(parity, 8)
        return intArrayOf(LEADER_MARK_US, LEADER_SPACE_US) + pulses(bits, MARK_US, ZERO_SPACE_US, ONE_SPACE_US) + intArrayOf(MARK_US)
    }
}

internal fun lsbFirst(value: Int, n: Int): List<Int> = (0 until n).map { (value shr it) and 1 }
internal fun msbFirst(value: Int, n: Int): List<Int> = (n - 1 downTo 0).map { (value shr it) and 1 }
internal fun pulses(bits: List<Int>, mark: Int, zero: Int, one: Int): IntArray {
    val out = IntArray(bits.size * 2)
    for ((i, b) in bits.withIndex()) { out[2 * i] = mark; out[2 * i + 1] = if (b == 1) one else zero }
    return out
}
