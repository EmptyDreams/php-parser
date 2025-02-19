package top.kmar.php.lexer

class StringSingleThreadStream(private val str: String) : SingleThreadRandomStream {

    private var pos = 0

    override fun pos(index: Int) {
        if (index < 0 || index >= str.length) {
            throw IndexOutOfBoundsException("设置的位置[$index]超出字符串范围[0, ${str.length})")
        }
        pos = index
    }

    override fun pos(): Int {
        return pos
    }

    override fun skip(count: Int) {
        val index = pos + count
        if (index < 0 || index > str.length) throw IndexOutOfBoundsException(
            "The index[$index = $pos + $count] is out of bounds[0, ${str.length})"
        )
        pos = index
    }

    override fun hasNext(): Boolean {
        return pos != str.length
    }

    override fun nextChar(): Int {
        if (pos == str.length) return -1
        return str[pos++].code
    }

    override fun nextIfWhitespace(): Boolean {
        if (pos == str.length) return false
        return if (str[pos].isWhitespace()) {
            ++pos
            true
        } else {
            false
        }
    }

    override fun nextIfNewLine(): Boolean {
        if (pos == str.length) return false
        return when (str[pos]) {
            '\n' -> {
                ++pos
                true
            }
            '\r' -> {
                val afterIndex = pos + 1
                if (afterIndex != str.length && str[afterIndex] == '\n') {
                    pos += 2
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    override fun getChar(index: Int): Int {
        if (index < 0 || index >= str.length) return -1
        return str[index].code
    }

    override fun getOffsetChar(offset: Int): Int {
        val index = pos + offset
        if (index < 0 || index >= str.length) return -1
        return str[index].code
    }

    override fun close() { }

}