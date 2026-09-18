package io.adhush.core

import java.io.ByteArrayOutputStream

/**
 * Just enough protocol-buffers to speak Android TV's remote protocol (ADR
 * 0026): varints, length-delimited fields, nested messages. A parsed
 * message is a map of field number to the values seen, each a varint or
 * bytes; the callers know which they expect.
 */
object MiniProto {
    class Writer {
        private val out = ByteArrayOutputStream()
        fun varint(field: Int, value: Long): Writer { tag(field, 0); putVarint(value); return this }
        fun int(field: Int, value: Int): Writer = varint(field, value.toLong())
        fun bool(field: Int, value: Boolean): Writer = varint(field, if (value) 1 else 0)
        fun bytes(field: Int, value: ByteArray): Writer { tag(field, 2); putVarint(value.size.toLong()); out.write(value); return this }
        fun string(field: Int, value: String): Writer = bytes(field, value.toByteArray(Charsets.UTF_8))
        fun message(field: Int, value: Writer): Writer = bytes(field, value.toByteArray())
        fun toByteArray(): ByteArray = out.toByteArray()
        private fun tag(field: Int, wire: Int) = putVarint(((field shl 3) or wire).toLong())
        private fun putVarint(v: Long) { var x = v; while (true) { if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); return }; out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
    }

    sealed class Value { class Varint(val v: Long) : Value(); class Bytes(val v: ByteArray) : Value() }

    class Message(val fields: Map<Int, List<Value>>) {
        fun has(field: Int) = fields.containsKey(field)
        fun long(field: Int): Long? = (fields[field]?.firstOrNull() as? Value.Varint)?.v
        fun int(field: Int): Int? = long(field)?.toInt()
        fun bytes(field: Int): ByteArray? = (fields[field]?.firstOrNull() as? Value.Bytes)?.v
        fun string(field: Int): String? = bytes(field)?.toString(Charsets.UTF_8)
        fun message(field: Int): Message? = bytes(field)?.let { parse(it) }
    }

    fun parse(data: ByteArray): Message {
        val fields = LinkedHashMap<Int, MutableList<Value>>()
        var pos = 0
        fun varint(): Long { var shift = 0; var result = 0L; while (true) { val b = data[pos++].toInt() and 0xFF; result = result or ((b and 0x7F).toLong() shl shift); if (b and 0x80 == 0) return result; shift += 7 } }
        while (pos < data.size) {
            val key = varint().toInt()
            val field = key ushr 3
            val list = fields.getOrPut(field) { ArrayList() }
            when (key and 7) {
                0 -> list.add(Value.Varint(varint()))
                1 -> { pos += 8; list.add(Value.Varint(0)) }
                2 -> { val n = varint().toInt(); list.add(Value.Bytes(data.copyOfRange(pos, pos + n))); pos += n }
                5 -> { pos += 4; list.add(Value.Varint(0)) }
                else -> throw IllegalArgumentException("protobuf: wire type ${key and 7}")
            }
        }
        return Message(fields)
    }

    /** A message on the wire is its varint length, then its bytes. */
    fun framed(body: ByteArray): ByteArray = Writer().toByteArray().let { val w = ByteArrayOutputStream(); var x = body.size.toLong(); while (true) { if (x and 0x7FL.inv() == 0L) { w.write(x.toInt()); break }; w.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; w.write(body); w.toByteArray() }

    fun readFrame(input: java.io.InputStream): ByteArray? {
        var shift = 0; var len = 0L
        while (true) { val b = input.read(); if (b < 0) return null; len = len or ((b and 0x7F).toLong() shl shift); if (b and 0x80 == 0) break; shift += 7 }
        val out = ByteArray(len.toInt()); var off = 0
        while (off < out.size) { val n = input.read(out, off, out.size - off); if (n < 0) return null; off += n }
        return out
    }
}
