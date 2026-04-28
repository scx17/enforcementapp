package com.hdcollection.enforcement.sip

/**
 * G.711 μ-law (PCMU, RFC 1890 PT=0) 编解码。
 * 8kHz 16-bit linear PCM ↔ 8-bit μ-law。
 * 双端协议：与平台 IntercomService.G711 算法一致。
 */
object G711 {
    private const val SIGN_BIT = 0x80
    private const val QUANT_MASK = 0xf
    private const val SEG_SHIFT = 4
    private const val BIAS = 0x84
    private const val CLIP = 32635

    fun linearToMuLaw(pcmIn: Short): Byte {
        var sample = pcmIn.toInt()
        val sign = (sample shr 8) and 0x80
        if (sign != 0) sample = -sample
        if (sample > CLIP) sample = CLIP
        sample += BIAS
        val seg = segEnd(sample)
        val uval = (seg shl SEG_SHIFT) or ((sample shr (seg + 3)) and QUANT_MASK)
        return ((uval or sign).inv() and 0xff).toByte()
    }

    fun muLawToLinear(mu: Byte): Short {
        val u = mu.toInt().inv() and 0xff
        val sign = u and SIGN_BIT
        val exponent = (u shr 4) and 0x07
        val mantissa = u and QUANT_MASK
        var sample = ((mantissa shl 3) + BIAS) shl exponent
        sample -= BIAS
        return (if (sign != 0) -sample else sample).toShort()
    }

    /** PCM 16-bit byte 数组 → μ-law byte 数组（pcm 长度按字节，2 字节 1 sample LE） */
    fun encodePcm16Bytes(pcm: ByteArray, offset: Int, byteCount: Int): ByteArray {
        val samples = byteCount / 2
        val out = ByteArray(samples)
        for (i in 0 until samples) {
            val lo = pcm[offset + i * 2].toInt() and 0xff
            val hi = pcm[offset + i * 2 + 1].toInt()
            val s = ((hi shl 8) or lo).toShort()
            out[i] = linearToMuLaw(s)
        }
        return out
    }

    /** μ-law byte 数组 → PCM 16-bit byte 数组（LE） */
    fun decodeToPcm16Bytes(mu: ByteArray, offset: Int, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = muLawToLinear(mu[offset + i]).toInt()
            out[i * 2] = (s and 0xff).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun segEnd(sample: Int): Int {
        for (seg in 0 until 8) if (sample <= (0xff shl seg)) return seg
        return 7
    }
}
