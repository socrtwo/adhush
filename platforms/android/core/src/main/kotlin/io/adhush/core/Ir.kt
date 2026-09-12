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
