package top.kmar.php.lexer

import top.kmar.php.container.KeywordsTrie
import top.kmar.php.exceptions.PhpLexerException
import java.util.*

class TokenStream(
    private val stream: SingleThreadRandomStream
) {

    init {
        val stream = this.stream
        if (
            stream.nextChar() != '<'.code || stream.nextChar() != '?'.code ||
            stream.nextChar() != 'p'.code || stream.nextChar() != 'h'.code ||
            stream.nextChar() != 'p'.code
        ) {
            throw PhpLexerException(stream.subStream(0, 8), "PHP 文件应当以 `<?php` 开头")
        }
        @Suppress("ControlFlowWithEmptyBody")
        while (stream.nextIfWhitespace()) { }
    }

    private val stateStack = ArrayList<State>(8).apply {
        add(State.CODE)
    }
    private val stateAdditionStack = ArrayList<SingleThreadRandomStream>(4)
    private val tokenCache = ArrayDeque<PhpToken>(8)

    fun nextToken(): PhpToken? {
        if (tokenCache.isNotEmpty()) return tokenCache.removeFirst()
        val stream = this.stream
        if (!stream.hasNext()) return null
        var startIndex: Int
        when (val lastState = stateStack.last()) {
            State.CODE, State.STRING_INLINE_CODE -> {
                @Suppress("ControlFlowWithEmptyBody")
                while (stream.nextIfWhitespace() || stream.nextIfNewLine()) {}
                if (!stream.hasNext()) return null
                startIndex = stream.pos()
                val firstChar = stream.nextChar()
                if (firstChar == '$'.code) {
                    matchVarName()
                    return PhpToken(
                        stream.subStream(startIndex, stream.pos()),
                        TokenType.VAR_NAME
                    )
                } else if (firstChar.isSplitToken()) {
                    val isStringInsertEnd = lastState == State.STRING_INLINE_CODE && firstChar == '}'.code
                    if (isStringInsertEnd) stateStack.removeLast()
                    return PhpToken(
                        stream.subStream(startIndex, stream.pos()),
                        if (isStringInsertEnd) TokenType.STRING_INSERT_DELIMITER else TokenType.DELIMITER
                    )
                } else {
                    when (firstChar) {
                        '/'.code -> {
                            when (stream.getOffsetChar(1)) {
                                '/'.code -> {
                                    stream.skip(1)
                                    stream.forEachStream { it != '\n' }
                                    return PhpToken(stream.subStream(startIndex, stream.pos()), TokenType.COMMENT)
                                }
                                '*'.code -> {
                                    stream.skip(1)
                                    stream.forEachStream {
                                        it != '*' || stream.getOffsetChar(0) != '/'.code
                                    }
                                    stream.skip(1)
                                    return PhpToken(stream.subStream(startIndex, stream.pos()), TokenType.COMMENT)
                                }
                            }
                        }
                        '#'.code -> {
                            stream.forEachStream { it != '\n' }
                            return PhpToken(stream.subStream(startIndex, stream.pos()), TokenType.COMMENT)
                        }
                        '\''.code -> {
                            stateStack.add(State.SINGLE_STRING)
                            return PhpToken(stream.subStream(startIndex, startIndex + 1), TokenType.STRING_DELIMITER)
                        }
                        '\"'.code -> {
                            stateStack.add(State.DOUBLE_STRING)
                            return PhpToken(stream.subStream(startIndex, startIndex + 1), TokenType.STRING_DELIMITER)
                        }
                        '`'.code -> {
                            stateStack.add(State.SHELL)
                            return PhpToken(stream.subStream(startIndex, startIndex + 1), TokenType.STRING_DELIMITER)
                        }
                        '0'.code, '1'.code, '2'.code, '3'.code, '4'.code, '5'.code, '6'.code, '7'.code, '8'.code, '9'.code -> {
                            val numberParser = matchNumber(-1, false)
                            return PhpToken(
                                numberParser.first,
                                if (numberParser.second) TokenType.DOUBLE else TokenType.INTEGER
                            )
                        }
                    }
                    val isOperator = firstChar.isOperator()
                    if (isOperator) {
                        if (firstChar == '<'.code) {
                            if (stream.getOffsetChar(0) == '<'.code && stream.getOffsetChar(1) == '<'.code) {
                                val startChar = stream.getOffsetChar(2)
                                stream.skip(3)
                                val endIndex: Int
                                when (startChar) {
                                    '\''.code -> {
                                        stream.forEachStream { it != '\'' }
                                        endIndex = stream.pos()
                                        stateAdditionStack.add(stream.subStream(startIndex + 4, endIndex - 1))
                                        if (!stream.nextIfNewLine()) {
                                            throw PhpLexerException(
                                                stream.subStream(startIndex, stream.pos() + 8),
                                                "无效的 HEREDOC 标签"
                                            )
                                        }
                                        stateStack.add(State.NEW_DOC)
                                    }
                                    '"'.code -> {
                                        @Suppress("DuplicatedCode")
                                        stream.forEachStream { it != '\"' }
                                        endIndex = stream.pos()
                                        stateAdditionStack.add(stream.subStream(startIndex + 4, endIndex - 1))
                                        if (!stream.nextIfNewLine()) {
                                            throw PhpLexerException(
                                                stream.subStream(startIndex, stream.pos() + 8),
                                                "无效的 HEREDOC 标签"
                                            )
                                        }
                                        stateStack.add(State.HEAR_DOC)
                                    }
                                    else -> {
                                        stream.forEachStream { it != '\n' && it != '\r' }
                                        endIndex = stream.pos()
                                        stateAdditionStack.add(stream.subStream(startIndex + 3, endIndex - 1))
                                        if (!stream.nextIfNewLine()) {
                                            throw PhpLexerException(
                                                stream.subStream(startIndex, stream.pos() + 8),
                                                "无效的 HEREDOC 标签"
                                            )
                                        }
                                        stateStack.add(State.HEAR_DOC)
                                    }
                                }
                                return PhpToken(stream.subStream(startIndex, endIndex), TokenType.STRING_DELIMITER)
                            }
                        } else if (firstChar == '-'.code || firstChar == '+'.code) {
                            val secondChar = stream.getOffsetChar(0)
                            if (secondChar >= '0'.code && secondChar <= '9'.code) {
                                val numberParser = matchNumber(0, firstChar == '-'.code)
                                return PhpToken(
                                    numberParser.first,
                                    if (numberParser.second) TokenType.DOUBLE else TokenType.INTEGER
                                )
                            }
                        }
                    }
                    var keywordsNode = if (firstChar >= 'a'.code && firstChar <= 'z'.code) {
                        KeywordsTrie.next(firstChar.toChar())
                    } else {
                        null
                    }
                    stream.forEachStreamBefore {
                        if (it.isWhitespace() || it.code.isSplitToken()) false
                        else if (keywordsNode != null) {
                            keywordsNode = when {
                                it >= 'a' && it <= 'z' -> keywordsNode.next(it)
                                it == '_' -> keywordsNode.nextUnderline()
                                else -> null
                            }
                            !it.code.isOperator()
                        } else {
                            isOperator == it.code.isOperator()
                        }
                    }
                    if (keywordsNode != null && stream.getOffsetChar(0).toChar().isWhitespace()) {
                        var nextKeywords = keywordsNode.nextSpace()
                        if (nextKeywords != null) {
                            stream.skip(1)
                            stream.forEachStreamBefore { it.isWhitespace() }
                            var offset = 0
                            do {
                                val charCode = stream.getOffsetChar(offset)
                                val char = charCode.toChar()
                                if (charCode.isOperator() || char.isWhitespace()) break
                                ++offset
                                nextKeywords = when {
                                    char >= 'a' && char <= 'z' -> nextKeywords!!.next(char)
                                    char == '_' -> nextKeywords!!.nextUnderline()
                                    else -> null
                                }
                            } while (nextKeywords != null)
                            if (nextKeywords?.isEnd == true) {
                                stream.skip(offset)
                                keywordsNode = nextKeywords
                            }
                        }
                    }
                    return PhpToken(
                        stream.subStream(startIndex, stream.pos()),
                        when {
                            isOperator -> TokenType.OPERATOR
                            keywordsNode?.isEnd == true -> TokenType.KEYWORDS
                            else -> TokenType.IDENTIFIER
                        }
                    )
                }
            }
            State.SINGLE_STRING -> {
                startIndex = stream.pos()
                val firstChar = stream.nextChar()
                if (firstChar == '\''.code) {
                    stateStack.removeLast()
                    return PhpToken(stream.subStream(startIndex, startIndex + 1), TokenType.STRING_DELIMITER)
                }
                stream.forEachStreamBefore {
                    when (it) {
                        '\'' -> false
                        '\\' -> {
                            when (stream.getOffsetChar(0)) {
                                '\''.code, '\\'.code -> stream.skip(1)
                                else -> {}
                            }
                            true
                        }
                        else -> true
                    }
                }
                return PhpToken(stream.subStream(startIndex, stream.pos()), TokenType.STRING)
            }
            State.DOUBLE_STRING, State.SHELL -> {
                startIndex = stream.pos()
                val firstChar = stream.nextChar()
                val endFlagChar = if (lastState == State.DOUBLE_STRING) '"' else '`'
                if (firstChar == endFlagChar.code) {
                    stateStack.removeLast()
                    return PhpToken(stream.subStream(startIndex, startIndex + 1), TokenType.STRING_DELIMITER)
                } else if (firstChar == '$'.code) {
                    if (stream.getOffsetChar(0) == '{'.code) {
                        stateStack.add(State.STRING_INLINE_CODE)
                        stream.skip(1)
                        return PhpToken(
                            stream.subStream(startIndex, startIndex + 2),
                            TokenType.STRING_INSERT_DELIMITER
                        )
                    } else {
                        matchVarName()
                        return PhpToken(stream.subStream(startIndex, stream.pos()), TokenType.VAR_NAME)
                    }
                } else if (firstChar == '{'.code && stream.getOffsetChar(0) == '{'.code) {
                    stateStack.add(State.STRING_INLINE_CODE)
                    stream.skip(1)
                    return PhpToken(
                        stream.subStream(startIndex, startIndex + 2),
                        TokenType.STRING_INSERT_DELIMITER
                    )
                }
                stream.forEachStreamBefore {
                    when (it) {
                        '"', '$' -> false
                        '\\' -> {
                            val pos = stream.pos()
                            val escape = matchStringEscape(1)
                            if (escape != null) {
                                tokenCache.add(PhpToken(escape, TokenType.STRING))
                                return PhpToken(stream.subStream(startIndex, pos), TokenType.STRING)
                            }
                            true
                        }
                        '{' -> {
                            @Suppress("DuplicatedCode")
                            if (stream.getOffsetChar(1) == '$'.code) {
                                val pos = stream.pos()
                                stream.skip(2)
                                tokenCache.add(
                                    PhpToken(stream.subStream(pos, pos + 2), TokenType.STRING_INSERT_DELIMITER)
                                )
                                stateStack.add(State.STRING_INLINE_CODE)
                                return PhpToken(stream.subStream(startIndex, pos), TokenType.STRING)
                            }
                            true
                        }
                        else -> true
                    }
                }
                return PhpToken(stream.subStream(startIndex, stream.pos()), TokenType.STRING)
            }
            State.HEAR_DOC -> {
                startIndex = stream.pos()
                val endFlag = stateAdditionStack.last()
                val isLineStart = stream.getOffsetChar(-1) == '\n'.code
                val firstChar = stream.nextChar()
                if (firstChar == '$'.code) {
                    if (stream.getOffsetChar(0) == '{'.code) {
                        stateStack.add(State.STRING_INLINE_CODE)
                        stream.skip(1)
                        return PhpToken(
                            stream.subStream(startIndex, stream.pos()),
                            TokenType.STRING_INSERT_DELIMITER
                        )
                    }
                    matchVarName()
                    return PhpToken(stream.subStream(startIndex, stream.pos()), TokenType.VAR_NAME)
                } else if (isLineStart) {
                    var endChar = endFlag.getOffsetChar(0)
                    if (endChar == firstChar) {
                        var offset = 0
                        endChar = endFlag.getOffsetChar(1)
                        while (endChar != -1) {
                            val char = stream.getOffsetChar(offset++)
                            if (char != endChar) {
                                offset = -1
                                break
                            }
                            endChar = stream.getOffsetChar(offset + 1)
                        }
                        if (offset != -1 && stream.getOffsetChar(offset++) == ';'.code) {
                            stateStack.removeLast()
                            stateAdditionStack.removeLast()
                            stream.skip(offset)
                            return PhpToken(
                                stream.subStream(startIndex, startIndex + offset),
                                TokenType.STRING_DELIMITER
                            )
                        }
                    }
                }
                stream.forEachStreamBefore {
                    when (it) {
                        '\n' -> {
                            val pos = stream.pos()
                            if (tryMatchDocEnd(endFlag, 1)) {
                                stateStack.removeLast()
                                stateAdditionStack.removeLast()
                                val next = PhpToken(stream.subStream(pos + 1, stream.pos()), TokenType.STRING_DELIMITER)
                                if (startIndex == pos) return next
                                tokenCache.add(next)
                                return PhpToken(
                                    stream.subStream(startIndex, pos),
                                    TokenType.STRING
                                )
                            } else {
                                true
                            }
                        }
                        '$' -> false
                        '\\' -> {
                            val pos = stream.pos()
                            val escape = matchStringEscape(startIndex)
                            if (escape != null) {
                                tokenCache.add(PhpToken(escape, TokenType.STRING))
                                return PhpToken(stream.subStream(startIndex, pos), TokenType.STRING)
                            }
                            true
                        }
                        '{' -> {
                            @Suppress("DuplicatedCode")
                            if (stream.getOffsetChar(1) == '$'.code) {
                                val pos = stream.pos()
                                stream.skip(2)
                                tokenCache.add(
                                    PhpToken(stream.subStream(pos, pos + 2), TokenType.STRING_INSERT_DELIMITER)
                                )
                                stateStack.add(State.STRING_INLINE_CODE)
                                return PhpToken(stream.subStream(startIndex, pos), TokenType.STRING)
                            }
                            true
                        }
                        else -> true
                    }
                }
                return PhpToken(stream.subStream(startIndex, stream.pos()), TokenType.STRING)
            }
            State.NEW_DOC -> {
                startIndex = stream.pos()
                val endFlag = stateAdditionStack.last()
                stream.forEachStream {
                    if (it == '\n') {
                        val pos = stream.pos()
                        if (tryMatchDocEnd(endFlag, 0)) {
                            stateStack.removeLast()
                            stateAdditionStack.removeLast()
                            val next = PhpToken(stream.subStream(pos, stream.pos()), TokenType.STRING_DELIMITER)
                            if (startIndex == pos - 1) return next
                            tokenCache.add(next)
                            return PhpToken(stream.subStream(startIndex, pos - 1), TokenType.STRING)
                        }
                    }
                    true
                }
                throw PhpLexerException(stream, "The end of the new_doc was not found")
            }
        }
    }

    /**
     * 从流中匹配一个数字，并返回一个 [SingleThreadRandomStream] 对象，该对象包含了匹配到的数字字符，然后将 [stream] 移动到数字的下一个字符
     *
     * @param offset 偏移量，应当使得 `getCharOffset(offset)` 返回数字的第一个字符
     * @param isNegative 是否为负数
     *
     * @return `second` 表示该数字是否是一个浮点数
     */
    private fun matchNumber(offset: Int, isNegative: Boolean): Pair<SingleThreadRandomStream, Boolean> {
        val startIndex = stream.pos() + offset
        val firstChar = stream.getOffsetChar(offset)
        if (firstChar == '0'.code) {
            when (val secondChar = stream.getOffsetChar(offset + 1)) {
                'x'.code -> {
                    stream.skip(offset + 2)
                    var number = 0L
                    while (true) {
                        val char = stream.getOffsetChar(0)
                        number = when {
                            char >= '0'.code && char <= '9'.code -> (number shl 4) or (char - '0'.code).toLong()
                            char >= 'a'.code && char <= 'f'.code -> (number shl 4) or (char - 'a'.code + 10).toLong()
                            char >= 'A'.code && char <= 'F'.code -> (number shl 4) or (char - 'A'.code + 10).toLong()
                            char == '_'.code -> number
                            else -> break
                        }
                        stream.skip(1)
                    }
                    if (isNegative) number = -number
                    return StringSingleThreadStream(number.toString()) to false
                }
                'b'.code -> {
                    stream.skip(offset + 2)
                    var number = 0L
                    while (true) {
                        val char = stream.getOffsetChar(0)
                        number = when (char) {
                            '0'.code, '1'.code -> (number shl 1) or (char - '0'.code).toLong()
                            '_'.code -> number
                            else -> break
                        }
                        stream.skip(1)
                    }
                    if (isNegative) number = -number
                    return StringSingleThreadStream(number.toString()) to false
                }
                '0'.code, '1'.code, '2'.code, '3'.code, '4'.code, '5'.code, '6'.code, '7'.code, '8'.code, '9'.code -> {
                    stream.skip(offset + 2)
                    val index = stream.pos() - 1
                    var number = (secondChar - '0'.code).toLong()
                    while (true) {
                        val char = stream.getOffsetChar(0)
                        number = when {
                            char >= '0'.code && char < '8'.code -> (number shl 3) or (char - '0'.code).toLong()
                            char == '_'.code -> number
                            char == '.'.code -> {
                                stream.pos(index)
                                return matchNumber(0, isNegative)
                            }
                            else -> break
                        }
                        stream.skip(1)
                    }
                    if (isNegative) number = -number
                    return StringSingleThreadStream(number.toString()) to false
                }
                else -> {
                    stream.skip(offset + 1)
                    return CharSingleThreadStream('0') to false
                }
            }
        } else if (firstChar > '0'.code && firstChar <= '9'.code) {
            stream.skip(offset + 1)
            var isDouble = false
            stream.forEachStreamBefore {
                if ((it >= '0' && it <= '9') || it == '_') true
                else if (it == '.') {
                    if (isDouble) throw PhpLexerException(
                        stream.subStream(startIndex, stream.pos()),
                        "The number cannot be a double"
                    )
                    isDouble = true
                    true
                } else false
            }
            val subStream = stream.subStream(startIndex, stream.pos())
            return if (isNegative) {
                CharPrefixSingleThreadStream('-', subStream)
            } else {
                subStream
            } to isDouble
        } else {
            throw PhpLexerException(
                stream.subStream(startIndex, startIndex + 1),
                "The first character of a number must be a digit"
            )
        }
    }

    /**
     * 尝试匹配文档的结束符，如果匹配成功则将读取位点移动到匹配字符的后一位，否则不做任何事情
     *
     * @param endStream 文档结束符（该函数不会修改 [endStream] 的读取位点）
     * @param streamOffset [stream] 的偏移量，需要保证 `stream.getOffsetChar(streamOffset)` 读取的是文档一行的第一个字符
     */
    private fun tryMatchDocEnd(endStream: SingleThreadRandomStream, streamOffset: Int): Boolean {
        var offset = 1
        var endChar = endStream.getOffsetChar(0)
        var char = stream.getOffsetChar(streamOffset)
        while (endChar == char) {
            endChar = endStream.getOffsetChar(offset)
            char = stream.getOffsetChar(streamOffset + offset)
            if (endChar == -1) {
                stream.skip(streamOffset + offset)
                return true
            }
            ++offset
        }
        return false
    }

    /**
     * 匹配字符串转义，如果匹配成功则将读取位点移动到转义字符的后一位，否则不做任何事情
     *
     * 该函数假设下一个读取的字符是 `\`
     *
     * @param startIndex 字符串读取的起始位置，用于在遇到错误时抛出异常，无其它作用
     */
    private fun matchStringEscape(startIndex: Int): SingleThreadRandomStream? {
        return when (val char = stream.getOffsetChar(1)) {
            '"'.code, 'n'.code, 'r'.code, 't'.code,
            'v'.code, 'e'.code, 'f'.code, '\\'.code, '$'.code -> {
                stream.skip(1)
                CharSingleThreadStream(char.toChar())
            }
            '0'.code, '1'.code, '2'.code, '3'.code, '4'.code, '5'.code, '6'.code, '7'.code -> {
                val offset1 = stream.getOffsetChar(2)
                if (offset1 >= '0'.code && offset1 < '8'.code) {
                    val offset2 = stream.getOffsetChar(3)
                    if (offset2 >= '0'.code && offset2 < '8'.code) {
                        val code = ((char - '0'.code) shl 6) or ((offset1 - '0'.code) shl 3) or (offset2 - '0'.code)
                        if (code > 0xFF) throw PhpLexerException(
                            stream.subStream(stream.pos(), startIndex + 4),
                            "The octal escape code is too large"
                        )
                        stream.skip(3)  // \[0-7][0-7][0-7]
                        CharSingleThreadStream(code.toChar())
                    } else {
                        stream.skip(2)  // \[0-7][0-7]
                        val code = ((char - '0'.code) shl 3) or (offset1 - '0'.code)
                        CharSingleThreadStream(code.toChar())
                    }
                } else {
                    stream.skip(1)  // \[0-7]
                    CharSingleThreadStream((char - '0'.code).toChar())
                }
            }
            'x'.code -> {
                val offset1 = stream.getOffsetChar(2)
                if (offset1.isHexDigit()) {
                    val offset2 = stream.getOffsetChar(3)
                    if (offset2.isHexDigit()) {
                        stream.skip(3)  // \x[0-9a-fA-F][0-9a-fA-F]
                        val code = (offset1.toChar().digitToInt(16) shl 4) or offset2.toChar().digitToInt(16)
                        CharSingleThreadStream(code.toChar())
                    } else {
                        stream.skip(2)  // \x[0-9a-fA-F]
                        CharSingleThreadStream(offset1.toChar().digitToInt(16).toChar())
                    }
                } else {
                    null
                }
            }
            'u'.code -> {
                if (stream.getOffsetChar(2) == '{'.code) {
                    stream.skip(3)
                    var code = 0
                    stream.forEachStream { next ->
                        if (next == '}') {
                            false
                        } else if (next.code.isHexDigit()) {
                            if (code >= 0xFFFFF) throw PhpLexerException(
                                stream.subStream(startIndex, stream.pos()),
                                "UTF-8 escape code is too large"
                            )
                            code = (code shl 4) or next.digitToInt(16)
                            true
                        } else {
                            throw PhpLexerException(
                                stream.subStream(startIndex - 1, stream.pos() + 3),
                                "Invalid UTF-8 escape code"
                            )
                        }
                    }
                    CharSingleThreadStream(code.toChar())
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /**
     * 匹配变量名，并将读取位点移动到变量名的后一位，如果当前位点的字符不是合法的变量名，则不会移动读取位点
     *
     * 该函数假设下一次读取的字符是 `$` 后的第一个字符
     */
    private fun matchVarName() {
        val stream = this.stream
        val firstChar = stream.getOffsetChar(0)
        if (
            !(
                (firstChar >= 'a'.code && firstChar <= 'z'.code) ||
                (firstChar >= 'A'.code && firstChar <= 'Z'.code) ||
                firstChar == '_'.code ||
                (firstChar >= 0x80 && firstChar <= 0xFF)
            )
        ) {
            return
        }
        stream.skip(1)
        stream.forEachStreamBefore {
            (it >= 'a' && it <= 'z') || (it >= 'A' && it <= 'Z') ||
            (it >= '0' && it <= '9') ||
            it == '_' ||
            (it >= 0x80.toChar() && it <= 0xFF.toChar())
        }
    }

    enum class State {

        CODE,
        SINGLE_STRING,
        DOUBLE_STRING,
        STRING_INLINE_CODE,
        HEAR_DOC,
        NEW_DOC,
        SHELL

    }

    companion object {

        @JvmStatic
        private fun Int.isHexDigit(): Boolean {
            return (this >= '0'.code && this <= '9'.code) ||
                    (this >= 'a'.code && this <= 'f'.code) ||
                    (this >= 'A'.code && this <= 'F'.code)
        }

        @JvmStatic
        private fun Int.isOperator(): Boolean {
            return when (this) {
                '+'.code, '-'.code, '*'.code, '/'.code, '%'.code,
                '='.code, '<'.code, '>'.code, '!'.code,
                '&'.code, '|'.code, '^'.code, '~'.code,
                '?'.code, ':'.code, '.'.code -> {
                    true
                }
                else -> false
            }
        }

        @JvmStatic
        private fun Int.isSplitToken(): Boolean {
            return when (this) {
                ';'.code, ','.code, '['.code, ']'.code, '('.code, ')'.code, '{'.code, '}'.code -> {
                    true
                }
                else -> false
            }
        }

    }

}

data class PhpToken(
    val stream: SingleThreadRandomStream,
    val type: TokenType
)

enum class TokenType {

    KEYWORDS,
    /** 注释 */
    COMMENT,
    /** 单引号字符串 */
    STRING,
    /** 字符串界定符 */
    STRING_DELIMITER,
    /** 字符串插值界定符 */
    STRING_INSERT_DELIMITER,
    /** 整数 */
    INTEGER,
    /** 浮点数 */
    DOUBLE,
    /** 运算符 */
    OPERATOR,
    /** 分隔符（分号、逗号、各类括号） */
    DELIMITER,
    /** 变量名（`$` 开头） */
    VAR_NAME,
    /** 标识符 */
    IDENTIFIER

}