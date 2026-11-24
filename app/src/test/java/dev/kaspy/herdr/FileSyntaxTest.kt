package dev.kaspy.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSyntaxTest {
    private fun tokens(filename: String, text: String) =
        highlightFileSyntax(filename, text).map { text.substring(it.start, it.end) to it.kind }

    @Test
    fun distinguishesQuotedPropertiesStringsAndComments() {
        val json = """{"🚀": "http://x/#ok", "n": -3.5e+2, "b": true}"""
        assertEquals(
            listOf(
                "\"🚀\"" to SyntaxKind.PROPERTY,
                "\"http://x/#ok\"" to SyntaxKind.STRING,
                "\"n\"" to SyntaxKind.PROPERTY,
                "-3.5e+2" to SyntaxKind.NUMBER,
                "\"b\"" to SyntaxKind.PROPERTY,
                "true" to SyntaxKind.KEYWORD,
            ),
            tokens("settings.JSON", json),
        )
        assertEquals(
            listOf("foo-bar" to SyntaxKind.PROPERTY, "'it''s # text'" to SyntaxKind.STRING, "# comment" to SyntaxKind.COMMENT),
            tokens("nested/settings.yml", "foo-bar: 'it''s # text' # comment"),
        )
    }

    @Test
    fun handlesRepresentativeLanguageConstructs() {
        assertEquals(
            listOf("def" to SyntaxKind.KEYWORD, "str" to SyntaxKind.TYPE, "'''a\n#b'''" to SyntaxKind.STRING,
                "# end" to SyntaxKind.COMMENT),
            tokens("main.py", "def f(x: str):\n  '''a\n#b''' # end"),
        )
        assertEquals(
            listOf("int" to SyntaxKind.TYPE, "0xFF" to SyntaxKind.NUMBER, "/*ok*/" to SyntaxKind.COMMENT),
            tokens("main.cpp", "int n = 0xFF; /*ok*/"),
        )
        assertEquals(
            listOf("const" to SyntaxKind.KEYWORD, "string" to SyntaxKind.TYPE, "\"hi\"" to SyntaxKind.STRING),
            tokens("index.tsx", "const s: string = \"hi\""),
        )
        assertEquals(
            listOf("let" to SyntaxKind.KEYWORD, "/*ok*/" to SyntaxKind.COMMENT, "true" to SyntaxKind.KEYWORD),
            tokens("index.mjs", "let x = /*ok*/ true;"),
        )
        assertEquals(
            listOf("fn" to SyntaxKind.KEYWORD, "str" to SyntaxKind.TYPE, "/* a /* b */ c */" to SyntaxKind.COMMENT,
                "r#\"a#b\"" + "#" to SyntaxKind.STRING),
            tokens("main.rs", "fn f<'a>(_: &'a str) { /* a /* b */ c */ r#\"a#b\"#; }"),
        )
        for (filename in listOf("example.bash", "run.sh", ".bashrc", ".bash_profile", ".profile")) {
            assertEquals(
                listOf("if" to SyntaxKind.KEYWORD, "\"#literal\"" to SyntaxKind.STRING, "then" to SyntaxKind.KEYWORD,
                    "# shell comment" to SyntaxKind.COMMENT),
                tokens(filename, "if \"#literal\"; then # shell comment"),
            )
        }
    }

    @Test
    fun marksLogSeverityWithoutMarkingSubstrings() {
        assertEquals(
            listOf("ERROR" to SyntaxKind.LOG_ERROR, "WARN" to SyntaxKind.LOG_WARN, "INFO" to SyntaxKind.LOG_INFO,
                "debug" to SyntaxKind.LOG_DEBUG),
            tokens("application.log.1", "[ERROR] WARNING_SIGN WARN INFO debug ERROR_code"),
        )
        assertTrue(tokens("notes.txt", "true \"foo\" # comment 42").isEmpty())
    }
}
