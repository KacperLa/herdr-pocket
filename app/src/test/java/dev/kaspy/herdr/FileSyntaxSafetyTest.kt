package dev.kaspy.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class FileSyntaxSafetyTest {
    private val files = listOf("data.yaml", "data.json", "boot.log", "app.py", "app.cpp",
        "app.js", "app.ts", "app.rs", ".bashrc", "plain.txt")

    @Test
    fun arbitraryAndIncompletePreviewTextNeverProducesInvalidComposeOffsets() {
        val random = Random(20261123)
        val samples = listOf(
            "", "\uFEFF", "😀\uD83D", "const a = \"😀unterminated",
            "/* nested /* comments? */", "\"\"\"unclosed\n# comment", "'''\n\\\n",
            "[ERROR]\r\nDEBUG\tINFO\u2028WARN", "x: |\"\n'\n\u200D", "r###\"incomplete",
        ) + List(32) {
            val alphabet = "\t\r\n<>{}[]'\":#?!*/\\._abz019@-${'$'}😀é中"
            buildString { repeat(random.nextInt(1, 700)) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }
        for (file in files) for (sample in samples) {
            val spans = highlightFileSyntax(file, sample)
            assertEquals("Non-deterministic spans in $file", spans, highlightFileSyntax(file, sample))
            var end = 0
            for (span in spans) {
                assertTrue("Invalid span $span in $file, text length ${sample.length}",
                    span.start >= end && span.end > span.start && span.end <= sample.length)
                end = span.end
            }
        }
    }

    @Test
    fun longSupportedPreviewCooperatesWithCancellation() {
        class Cancelled : RuntimeException()
        var checks = 0
        try {
            highlightFileSyntax("app.py", "k = 10 # text\n".repeat(7000)) {
                if (++checks == 2) throw Cancelled()
            }
            fail("Expected lexer to observe cancellation while scanning")
        } catch (_: Cancelled) {
            assertEquals(2, checks)
        }
    }
}
