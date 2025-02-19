package top.kmar.php.lexer

class CharPrefixSingleThreadStream(
    private val prefix: Char,
    private val stream: SingleThreadRandomStream
) : SingleThreadRandomStream {

    private var pos = 0

    override fun pos(index: Int) {
        if (index == 0) pos = 0
        else {
            stream.pos(index - 1)
            pos = index
        }
    }

    override fun pos(): Int {
        return pos
    }

    override fun skip(count: Int) {
        if (count == 0) return
        if (count > 0) {
            if (pos == 0) {
                if (count == 1) {
                    pos = 1
                } else {
                    stream.skip(count - 1)
                    pos = count
                }
            } else {
                stream.skip(count)
                pos += count
            }
        } else {
            val afterIndex = pos + count
            if (afterIndex < 0) throw IndexOutOfBoundsException(
                "The index[${-count}] is out of bounds"
            ) else if (afterIndex == 0) {
                stream.pos(0)
                pos = 0
            } else {
                stream.skip(count)
                pos = afterIndex
            }
        }
    }

    override fun hasNext(): Boolean {
        if (pos == 0) return true
        return stream.hasNext()
    }

    override fun nextChar(): Int {
        return if (pos == 0) {
            pos = 1
            prefix.code
        } else {
            ++pos
            stream.nextChar()
        }
    }

    override fun nextIfWhitespace(): Boolean {
        return if (pos == 0) {
            if (prefix.isWhitespace()) {
                ++pos
                true
            } else {
                false
            }
        } else {
            stream.nextIfWhitespace()
        }
    }

    override fun nextIfNewLine(): Boolean {
        return if (pos == 0) {
            if (prefix == '\n') {
                ++pos
                true
            } else {
                false
            }
        } else {
            stream.nextIfNewLine()
        }
    }

    override fun getChar(index: Int): Int {
        return if (index == 0) prefix.code else stream.getChar(index - 1)
    }

    override fun getOffsetChar(offset: Int): Int {
        val afterIndex = pos + offset
        if (afterIndex == 0) return prefix.code
        else if (afterIndex > 0) return stream.getChar(offset - 1)
        throw IndexOutOfBoundsException("The index[$offset] is out of bounds")
    }

    override fun close() { }

}