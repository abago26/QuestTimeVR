package com.questtime.vr
import org.junit.Test
import java.io.File
class ScratchOpcodeTest {
    @Test fun which() {
        for (name in listOf("lincoln9.mov", "joshua25.mov")) {
            val f = File("../../reference/testdata/$name")
            if (!f.isFile) continue
            val data = f.readBytes()
            val counts = HashMap<Int, Int>()
            for (t in MovParser.parseTracks(data).filter { it.format.trim() == "smc" }) {
                for (r in t.sampleRanges()) {
                    val s = data.copyOfRange(r.first.toInt(), r.first.toInt() + r.second)
                    // walk opcodes the same way the decoder does, counting highs
                    var pos = 4
                    val across = (t.width + 3) / 4; val down = (t.height + 3) / 4
                    var block = 0; val total = across * down
                    while (block < total && pos < s.size) {
                        val op = s[pos++].toInt() and 0xFF
                        val high = op and 0xF0; val low = op and 0x0F
                        counts[high] = (counts[high] ?: 0) + 1
                        fun cnt(long: Boolean) = if (long) 1 + (s[pos++].toInt() and 0xFF) else 1 + low
                        when (high) {
                            0x00, 0x10 -> block += cnt(high == 0x10)
                            0x20, 0x30 -> block += cnt(high == 0x30)
                            0x40, 0x50 -> block += 2 * cnt(high == 0x50)
                            0x60, 0x70 -> { val n = cnt(high == 0x70); pos++; block += n }
                            0x80, 0x90 -> { val n = 1 + low
                                if (high == 0x80) pos += 2 else pos++
                                pos += 2 * n; block += n }
                            0xA0, 0xB0 -> { val n = 1 + low
                                if (high == 0xA0) pos += 4 else pos++
                                pos += 4 * n; block += n }
                            0xC0, 0xD0 -> { val n = 1 + low
                                if (high == 0xC0) pos += 8 else pos++
                                pos += 6 * n; block += n }
                            0xE0 -> { val n = 1 + low; pos += 16 * n; block += n }
                            else -> { println("  !! unknown 0x${high.toString(16)}"); block = total }
                        }
                    }
                }
            }
            println("$name opcodes used: " +
                counts.entries.sortedBy { it.key }.joinToString { "0x%02x=%d".format(it.key, it.value) })
        }
    }
}
