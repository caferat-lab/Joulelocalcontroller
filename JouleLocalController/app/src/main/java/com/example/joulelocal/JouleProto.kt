package com.example.joulelocal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.random.Random

object JouleProto {
    private const val FIELD_BEGIN_LIVE_FEED = 70
    private const val FIELD_DATA_POINT = 90
    private const val FIELD_START_KEY_EXCHANGE = 120
    private const val FIELD_START_KEY_REPLY = 121
    private const val FIELD_SUBMIT_KEY = 130
    private const val FIELD_SUBMIT_KEY_REPLY = 131
    private const val FIELD_START_PROGRAM = 50
    private const val FIELD_START_PROGRAM_REPLY = 51
    private const val FIELD_STOP = 60
    private const val FIELD_STOP_REPLY = 61

    data class DataPoint(
        val feedId: Long = 0,
        val sequence: Long = 0,
        val bathTempC: Float = 0f,
        val programStep: Long = 0,
        val timeRemaining: Long = 0
    )

    data class Decoded(
        val secretKey: ByteArray? = null,
        val authResult: Long? = null,
        val dataPoint: DataPoint? = null,
        val startResult: Long? = null,
        val stopResult: Long? = null
    )

    private fun varint(v0: Long): ByteArray {
        var v = v0
        val out = ArrayList<Byte>()
        while (v > 0x7f) {
            out.add(((v and 0x7f) or 0x80).toByte())
            v = v ushr 7
        }
        out.add(v.toByte())
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun fieldVarint(field: Int, value: Long) =
        tag(field, 0) + varint(value)

    private fun fieldBytes(field: Int, value: ByteArray) =
        tag(field, 2) + varint(value.size.toLong()) + value

    private fun fieldFixed32(field: Int, value: Int): ByteArray {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        return tag(field, 5) + b.array()
    }

    private fun fieldFloat(field: Int, value: Float): ByteArray =
        fieldFixed32(field, value.toRawBits())

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        System.arraycopy(this, 0, out, 0, size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }

    private fun envelope(innerField: Int, inner: ByteArray): ByteArray {
        val handle = Random.nextInt(1, Int.MAX_VALUE)
        var out = fieldFixed32(1, handle)
        // Proto2 required fields: always present, even when empty.
        out += fieldBytes(5, ByteArray(0))
        out += fieldBytes(6, ByteArray(0))
        out += fieldBytes(innerField, inner)
        return out
    }

    fun startKeyExchange(): ByteArray =
        envelope(FIELD_START_KEY_EXCHANGE, ByteArray(0))

    fun submitKey(secretKey: ByteArray): ByteArray =
        envelope(FIELD_SUBMIT_KEY, fieldBytes(1, secretKey))

    fun beginLiveFeed(feedId: Long = 1): ByteArray =
        envelope(FIELD_BEGIN_LIVE_FEED, fieldVarint(1, feedId))

    fun startCook(
    targetC: Float,
    cookTimeSeconds: Long,
    feedId: Long,
    sequence: Long
): ByteArray {
    var program = fieldFloat(1, targetC)

    // Cook time is expressed in seconds.
    program += fieldVarint(2, cookTimeSeconds)

    // Manual program.
    program += fieldVarint(5, 0)

    var request = fieldBytes(1, program)

    if (feedId != 0L) {
        request += fieldVarint(2, feedId)
    }

    if (sequence != 0L) {
        request += fieldVarint(3, sequence)
    }

    return envelope(FIELD_START_PROGRAM, request)
}

    fun stopCook(feedId: Long, sequence: Long): ByteArray {
        var request = ByteArray(0)
        if (feedId != 0L) request += fieldVarint(2, feedId)
        if (sequence != 0L) request += fieldVarint(3, sequence)
        return envelope(FIELD_STOP, request)
    }

    private data class Field(val number: Int, val wire: Int, val value: Any)

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var p = start
        var result = 0L
        var shift = 0
        while (p < data.size) {
            val b = data[p++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if ((b and 0x80) == 0) return result to p
            shift += 7
            if (shift >= 64) error("varint too long")
        }
        error("truncated varint")
    }

    private fun fields(data: ByteArray): List<Field> {
        val out = mutableListOf<Field>()
        var p = 0
        while (p < data.size) {
            val (t, np) = readVarint(data, p); p = np
            val number = (t ushr 3).toInt()
            val wire = (t and 7).toInt()
            when (wire) {
                0 -> { val (v, q) = readVarint(data, p); p = q; out += Field(number, wire, v) }
                2 -> {
                    val (len, q) = readVarint(data, p); p = q
                    val n = len.toInt()
                    if (n < 0 || p + n > data.size) error("truncated bytes")
                    out += Field(number, wire, data.copyOfRange(p, p + n)); p += n
                }
                5 -> {
                    if (p + 4 > data.size) error("truncated fixed32")
                    out += Field(number, wire, data.copyOfRange(p, p + 4)); p += 4
                }
                1 -> {
                    if (p + 8 > data.size) error("truncated fixed64")
                    out += Field(number, wire, data.copyOfRange(p, p + 8)); p += 8
                }
                else -> error("unsupported wire $wire")
            }
        }
        return out
    }

    private fun bytes(v: Any): ByteArray = v as ByteArray
    private fun long(v: Any): Long = v as Long

    fun decodeStream(data: ByteArray): Decoded {
        var key: ByteArray? = null
        var auth: Long? = null
        var start: Long? = null
        var stop: Long? = null
        var point: DataPoint? = null

        for (f in fields(data)) {
            if (f.wire != 2) continue
            val inner = bytes(f.value)
            when (f.number) {
                FIELD_START_KEY_REPLY -> {
                    var k: ByteArray? = null; var r: Long? = null
                    for (x in fields(inner)) {
                        if (x.number == 1 && x.wire == 2) k = bytes(x.value)
                        if (x.number == 2 && x.wire == 0) r = long(x.value)
                    }
                    key = k
                }
                FIELD_SUBMIT_KEY_REPLY -> {
                    for (x in fields(inner)) if (x.number == 1 && x.wire == 0) auth = long(x.value)
                }
                FIELD_START_PROGRAM_REPLY -> {
                    for (x in fields(inner)) if (x.number == 1 && x.wire == 0) start = long(x.value)
                }
                FIELD_STOP_REPLY -> {
                    for (x in fields(inner)) if (x.number == 1 && x.wire == 0) stop = long(x.value)
                }
                FIELD_DATA_POINT -> {
                    var feed = 0L; var seq = 0L; var temp = 0f; var step = 0L; var remain = 0L
                    for (x in fields(inner)) {
                        when (x.number) {
                            1 -> if (x.wire == 0) feed = long(x.value)
                            2 -> if (x.wire == 0) seq = long(x.value)
                            10 -> if (x.wire == 5) {
                                val bb = ByteBuffer.wrap(bytes(x.value)).order(ByteOrder.LITTLE_ENDIAN)
                                temp = bb.float
                            }
                            11 -> if (x.wire == 0) step = long(x.value)
                            12 -> if (x.wire == 0) remain = long(x.value)
                        }
                    }
                    point = DataPoint(feed, seq, temp, step, remain)
                }
            }
        }
        return Decoded(key, auth, point, start, stop)
    }
}
