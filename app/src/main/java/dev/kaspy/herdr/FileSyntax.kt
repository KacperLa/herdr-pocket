package dev.kaspy.herdr

internal enum class SyntaxKind {
    COMMENT, STRING, NUMBER, KEYWORD, TYPE, PROPERTY, LOG_ERROR, LOG_WARN, LOG_INFO, LOG_DEBUG,
}

/** UTF-16 indices into the unmodified preview; end is exclusive. */
internal data class SyntaxSpan(val start: Int, val end: Int, val kind: SyntaxKind)

private enum class SyntaxLanguage { YAML, JSON, LOG, PYTHON, CPP, JS, TS, RUST, SHELL }

private val booleanWords = setOf("true", "false", "null")
private val commonCodeWords = ("if else for while do return break continue switch case default try catch throw " +
    "class new const import export static public private protected finally").split(' ').toSet()
private val pythonWords = ("False None True and as assert async await break class continue def del elif else " +
    "except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield " +
    "match case").split(' ').toSet()
private val cppWords = ("alignas alignof asm auto constexpr consteval constinit delete enum extern friend goto " +
    "inline mutable namespace noexcept nullptr operator register requires sizeof struct template this thread_local " +
    "typedef typename union using virtual volatile override final concept").split(' ').toSet() + commonCodeWords + booleanWords
private val jsWords = ("async await case debugger delete extends function in instanceof let of super this typeof " +
    "undefined var void with yield").split(' ').toSet() + commonCodeWords + booleanWords
private val tsWords = ("abstract any as asserts declare enum implements infer interface is keyof module namespace " +
    "never override readonly satisfies type unknown").split(' ').toSet() + jsWords
private val rustWords = ("as async await break const continue crate dyn else enum extern fn for if impl in " +
    "let loop match mod move mut pub ref return self Self static struct super trait type unsafe use where while " +
    "true false").split(' ').toSet()
private val rustTypes = ("bool char f32 f64 i8 i16 i32 i64 i128 isize str u8 u16 u32 u64 u128 usize " +
    "String Vec Option Result Box").split(' ').toSet()
private val shellWords = "if then elif else fi for while until do done case esac function in select time".split(' ').toSet()
private val pythonTypes = "bool bytes dict float int list object range set str tuple type".split(' ').toSet()
private val cppTypes = "bool char char8_t char16_t char32_t double float int long short signed unsigned void wchar_t size_t string vector".split(' ').toSet()
private val tsTypes = "bigint boolean number object string symbol void".split(' ').toSet()

internal fun highlightFileSyntax(
    filename: String,
    text: String,
    checkCancelled: () -> Unit = {},
): List<SyntaxSpan> {
    val name = filename.substringAfterLast('/').lowercase()
    val language = if (name in setOf(".profile", ".bash_profile", ".bash_logout", ".bash_aliases", ".zprofile",
        ".zlogin", ".zlogout", ".zshenv", ".kshrc")) SyntaxLanguage.SHELL else when (name.substringAfterLast('.', "")) {
        "yaml", "yml" -> SyntaxLanguage.YAML
        "json", "jsonl", "ndjson" -> SyntaxLanguage.JSON
        "log", "logs", "out", "err" -> SyntaxLanguage.LOG
        "py", "pyw", "pyi" -> SyntaxLanguage.PYTHON
        "c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx", "inl" -> SyntaxLanguage.CPP
        "js", "jsx", "mjs", "cjs" -> SyntaxLanguage.JS
        "ts", "tsx", "mts", "cts" -> SyntaxLanguage.TS
        "rs" -> SyntaxLanguage.RUST
        "sh", "bash", "zsh", "ksh", "fish", "bashrc", "zshrc" -> SyntaxLanguage.SHELL
        else -> if (name.substringBeforeLast('.').endsWith(".log")) SyntaxLanguage.LOG else return emptyList()
    }
    val limit = minOf(text.length, 128 * 1024)
    val spans = ArrayList<SyntaxSpan>()
    var at = 0
    var nextCheck = 0

    fun check(index: Int) {
        if (index >= nextCheck) {
            checkCancelled()
            nextCheck = index + 512
        }
    }

    fun char(index: Int) = if (index < limit) text[index] else '\u0000'
    fun wordStart(c: Char) = c == '_' || c == '$' || c.isLetter()
    fun wordPart(c: Char) = wordStart(c) || c.isDigit()
    fun propertyAfter(end: Int): Boolean {
        var next = end
        while (char(next) == ' ' || char(next) == '\t') {
            check(next++)
        }
        if (char(next) != ':') return false
        return language != SyntaxLanguage.YAML || next + 1 == limit ||
            char(next + 1).isWhitespace() || char(next + 1) in ",[]{}"
    }

    val slashComments = language == SyntaxLanguage.CPP || language == SyntaxLanguage.JS || language == SyntaxLanguage.TS ||
        language == SyntaxLanguage.RUST
    val keywordWords = when (language) {
        SyntaxLanguage.PYTHON -> pythonWords
        SyntaxLanguage.CPP -> cppWords
        SyntaxLanguage.JS -> jsWords
        SyntaxLanguage.TS -> tsWords
        SyntaxLanguage.RUST -> rustWords
        SyntaxLanguage.SHELL -> shellWords
        SyntaxLanguage.JSON, SyntaxLanguage.YAML -> booleanWords
        SyntaxLanguage.LOG -> emptySet()
    }
    val typeWords = when (language) {
        SyntaxLanguage.PYTHON -> pythonTypes
        SyntaxLanguage.CPP -> cppTypes
        SyntaxLanguage.TS -> tsTypes
        SyntaxLanguage.RUST -> rustTypes
        else -> emptySet()
    }
    val propertyLanguage = language == SyntaxLanguage.JSON || language == SyntaxLanguage.YAML
    while (at < limit && spans.size < 8192) {
        check(at)
        val start = at
        val c = char(at++)
        val next = char(at)
        val kind = when {
            c == '#' && (language == SyntaxLanguage.PYTHON ||
                ((language == SyntaxLanguage.YAML || language == SyntaxLanguage.SHELL) &&
                    (start == 0 || text[start - 1].isWhitespace() || text[start - 1] in "[{,;")) ||
                language == SyntaxLanguage.CPP) || c == '/' && next == '/' && slashComments -> {
                while (at < limit && char(at) != '\n' && char(at) != '\r') check(at++)
                if (language == SyntaxLanguage.CPP && c == '#') SyntaxKind.KEYWORD else SyntaxKind.COMMENT
            }
            c == '/' && next == '*' && slashComments -> {
                at++
                var depth = 1
                while (at < limit && depth > 0) {
                    check(at)
                    val here = char(at++)
                    if (here == '*' && char(at) == '/') {
                        at++
                        depth--
                    } else if (language == SyntaxLanguage.RUST && here == '/' && char(at) == '*') {
                        at++
                        depth++
                    }
                }
                SyntaxKind.COMMENT
            }
            language == SyntaxLanguage.RUST && c == 'r' && (next == '#' || next == '"') -> {
                while (char(at) == '#') check(at++)
                val hashes = at - start - 1
                if (char(at) != '"' || hashes > 255) {
                    // r#identifier is a raw identifier, not a string.
                    while (wordPart(char(at))) check(at++)
                    null
                } else {
                    at++
                    while (at < limit) {
                        check(at)
                        if (char(at++) != '"') continue
                        var matched = 0
                        while (matched < hashes && char(at) == '#') {
                            check(at++)
                            matched++
                        }
                        if (matched == hashes) break
                    }
                    SyntaxKind.STRING
                }
            }
            language == SyntaxLanguage.RUST && c == '\'' && wordStart(next) && char(at + 1) != '\'' -> {
                // Lifetimes and labels are not character literals.
                while (wordPart(char(at))) check(at++)
                null
            }
            c == '"' && language != SyntaxLanguage.LOG ||
                c == '\'' && language != SyntaxLanguage.JSON && language != SyntaxLanguage.LOG ||
                c == '`' && (language == SyntaxLanguage.JS || language == SyntaxLanguage.TS || language == SyntaxLanguage.SHELL) -> {
                val triple = language == SyntaxLanguage.PYTHON && next == c && char(at + 1) == c
                val multiline = triple || c == '`' || language == SyntaxLanguage.YAML ||
                    language == SyntaxLanguage.SHELL
                if (triple) at += 2
                val escapes = c != '\'' || (language != SyntaxLanguage.YAML && language != SyntaxLanguage.SHELL)
                while (at < limit) {
                    check(at)
                    val here = char(at)
                    if (!multiline && (here == '\n' || here == '\r')) break
                    at++
                    if (here == '\\' && escapes && at < limit) {
                        at++
                    } else if (here == c) {
                        if (triple) {
                            if (char(at) != c || char(at + 1) != c) continue
                            at += 2
                        } else if (language == SyntaxLanguage.YAML && c == '\'' && char(at) == c) {
                            at++
                            continue
                        }
                        break
                    }
                }
                if (propertyLanguage && propertyAfter(at)) SyntaxKind.PROPERTY else SyntaxKind.STRING
            }
            (c in '0'..'9' || (c == '-' && next in '0'..'9') ||
                (c == '.' && next in '0'..'9' && language != SyntaxLanguage.JSON)) && language != SyntaxLanguage.LOG -> {
                val digitsStart = if (c == '-') at else start
                if (char(digitsStart) == '0' && char(digitsStart + 1) in "xXoObB" && language != SyntaxLanguage.JSON) {
                    at = digitsStart + 2
                    val digits = when (char(digitsStart + 1).lowercaseChar()) {
                        'x' -> "0123456789abcdefABCDEF_"
                        'o' -> "01234567_"
                        else -> "01_"
                    }
                    while (char(at) in digits) check(at++)
                } else {
                    fun digitHere() = char(at) in '0'..'9' || (char(at) == '_' && !propertyLanguage)
                    while (digitHere()) check(at++)
                    if (c != '.' && char(at) == '.' && char(at + 1) in '0'..'9') {
                        at++
                        while (digitHere()) check(at++)
                    }
                    if (char(at) in "eE") {
                        val exponentStart = at++
                        if (char(at) in "+-") at++
                        val exponentDigits = at
                        while (digitHere()) check(at++)
                        if (at == exponentDigits) at = exponentStart
                    }
                }
                if (language == SyntaxLanguage.YAML && propertyAfter(at)) SyntaxKind.PROPERTY else SyntaxKind.NUMBER
            }
            wordStart(c) -> {
                while (wordPart(char(at)) || language == SyntaxLanguage.YAML && char(at) == '-') check(at++)
                val word = if (at - start <= 24) text.substring(start, at) else ""
                when {
                    language == SyntaxLanguage.LOG -> when (word.uppercase()) {
                        "ERROR", "ERR", "FATAL", "CRITICAL", "SEVERE", "PANIC" -> SyntaxKind.LOG_ERROR
                        "WARN", "WARNING" -> SyntaxKind.LOG_WARN
                        "INFO", "NOTICE" -> SyntaxKind.LOG_INFO
                        "DEBUG", "TRACE", "VERBOSE" -> SyntaxKind.LOG_DEBUG
                        else -> null
                    }
                    language == SyntaxLanguage.YAML && propertyAfter(at) -> SyntaxKind.PROPERTY
                    word in typeWords -> SyntaxKind.TYPE
                    word in keywordWords -> SyntaxKind.KEYWORD
                    else -> null
                }
            }
            else -> null
        }
        if (kind != null) spans.add(SyntaxSpan(start, at, kind))
    }
    return spans
}
