package top.kmar.php.lexer

class SubSingleThreadStream(
    private val parentStream: SingleThreadRandomStream,
    val startIndex: Int,
    val endIndex: Int
) : SingleThreadRandomStream {

    private var pos = startIndex

    override fun pos(index: Int) {
        val realIndex = startIndex + index
        if (realIndex < startIndex || realIndex >= endIndex) {
            throw IndexOutOfBoundsException("设置的位点[$index]超出了流的范围[0, ${endIndex - startIndex})")
        }
        pos = index
    }

    override fun pos(): Int {
        return pos - startIndex
    }

    override fun skip(count: Int) {
        val index = pos + count
        if (index < startIndex || index >= endIndex) throw IndexOutOfBoundsException(
            "The index[${index - startIndex}] is out of bounds[0, $endIndex)"
        )
        pos = index
    }

    override fun hasNext(): Boolean {
        return pos != endIndex
    }

    override fun nextChar(): Int {
        if (pos == endIndex) return -1
        return parentStream.getChar(pos++)
    }

    override fun nextIfWhitespace(): Boolean {
        if (pos == endIndex) return false
        return if (parentStream.getChar(pos).toChar().isWhitespace()) {
            ++pos
            true
        } else {
            false
        }
    }

    override fun nextIfNewLine(): Boolean {
        if (pos == endIndex) return false
        return when (parentStream.getChar(pos)) {
            '\n'.code -> {
                ++pos
                true
            }
            '\r'.code -> {
                val afterIndex = pos + 1
                if (parentStream.getChar(afterIndex) == '\n'.code) {
                    pos = afterIndex
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    override fun getChar(index: Int): Int {
        val realIndex = startIndex + index
        if (realIndex < startIndex || realIndex >= endIndex) return -1
        return parentStream.getChar(realIndex)
    }

    override fun getOffsetChar(offset: Int): Int {
        val realIndex = pos + offset
        if (realIndex < startIndex || realIndex >= endIndex) return -1
        return parentStream.getChar(realIndex)
    }

    override fun subStream(start: Int, end: Int): SingleThreadRandomStream {
        return SubSingleThreadStream(parentStream, startIndex + start, startIndex + end)
    }

    override fun close() { }

}