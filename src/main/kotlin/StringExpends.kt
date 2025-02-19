package top.kmar.php

/**
 * 查找字符串中首次出现的空白字符的下标
 *
 * @param startIndex 搜索的起始位置（包含）
 */
fun String.indexOfWhitespace(startIndex: Int = 0): Int {
    for (i in startIndex..<length) {
        if (this[i].isWhitespace()) {
            return i
        }
    }
    return -1
}

/**
 * 查找字符串中首次出现的非空白字符的下标
 *
 * @param startIndex 搜索的起始位置（包含）
 */
fun String.indexOfNotWhitespace(startIndex: Int = 0): Int {
    for (i in startIndex..<length) {
        if (!this[i].isWhitespace()) {
            return i
        }
    }
    return -1
}

/**
 * 查找字符串中首次出现的空白字符或左花括号的下标
 */
fun String.indexOfWhitespaceOrCurly(startIndex: Int): Int {
    for (i in startIndex..<length) {
        val char = this[i]
        if (char == '{' || char.isWhitespace()) return i
    }
    return -1
}

/**
 * 查找字符串中首次出现的空白字符或分号（;）的下标
 */
fun String.indexOfWhitespaceOrSemicolon(startIndex: Int): Int {
    for (i in startIndex..<length) {
        val char = this[i]
        if (char == ';' || char.isWhitespace()) return i
    }
    return -1
}

/**
 * 查找字符串中首次出现的非数字字符（`_` 视为数字字符）的下标
 */
fun String.indexOfNotNumber(startIndex: Int): Int {
    for (i in startIndex..<length) {
        val char = this[i]
        if (char < '0' || (char > '9' && char != '_')) return i
    }
    return -1
}

/**
 * 查找字符串中首次出现的非十六进制数字字符（`_` 视为数字字符）的下标
 */
fun String.indexOfNotHexNumber(startIndex: Int): Int {
    for (i in startIndex..<length) {
        val char = this[i]
        if (
            char < '0' || (
                char > '9' && (
                    char != '_' && ((char >= 'a' && char < 'e') || (char >= 'A' && char < 'E'))
                )
            )
        ) {
            return i
        }
    }
    return -1
}

/**
 * 查找字符串中首次出现的空白字符或等于号（=）的下标
 */
fun String.indexOfWhitespaceOrEq(startIndex: Int): Int {
    for (i in startIndex..<length) {
        val char = this[i]
        if (char == '=' || char.isWhitespace()) return i
    }
    return -1
}

/**
 * 查找字符串中首次出现的非空白字符且非等号（=）的下标
 */
fun String.indexOfNotWhitespaceAndEq(startIndex: Int): Int {
    for (i in startIndex..<length) {
        val char = this[i]
        if (char != '=' && !char.isWhitespace()) return i
    }
    return -1
}

/**
 * 将字符串从首次出现的空白符处分割（不包含空白符）
 *
 * @param startIndex 搜索和切割的起始位点
 * @param limit 截取的最大长度
 * @return 如果没有找到空白符
 */
fun String.substringBeforeWhitespace(startIndex: Int = 0, limit: Int = 16): String {
    for (i in startIndex..<length) {
        if (this[i].isWhitespace()) {
            return substringAndLimit(startIndex, i, limit)
        }
    }
    return substringAtAndLimit(startIndex, limit)
}

/**
 * 截取字符串从 [startIndex]（包含）开始的所有字符，但限制最长长度，超出 [limit] 后省略
 */
fun String.substringAtAndLimit(startIndex: Int, limit: Int): String {
    return if (length - startIndex <= limit) substring(startIndex)
    else substring(startIndex, startIndex + limit - 2) + "..."
}

/**
 * 截取字符串从 [startIndex]（包含）到 [endIndex]（不包含）之间的所有字符，但限制最长长度，超出 [limit] 后省略
 */
fun String.substringAndLimit(startIndex: Int, endIndex: Int, limit: Int): String {
    return if (endIndex - startIndex <= limit) substring(startIndex, endIndex)
    else substring(startIndex, startIndex + limit) + "..."
}