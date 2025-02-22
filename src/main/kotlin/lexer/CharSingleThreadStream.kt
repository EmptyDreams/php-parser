package top.kmar.php.lexer

class CharSingleThreadStream(
    private val char: Char
) : SingleThreadRandomStream {

    private var index = 0

    override fun pos(index: Int) {
        if (index != 0 && index != 1) throw IndexOutOfBoundsException("CharSingleThreadStream only support index 0/1")
        this.index = index
    }

    override fun pos(): Int {
        return index
    }

    override fun skip(count: Int) {
        when (count) {
            0 -> {}
            1 -> {
                if (index != 0) throw IndexOutOfBoundsException("The pos[${index + 1}] is out of bounds")
                index = 1
            }
            -1 -> {
                if (index != 1) throw IndexOutOfBoundsException("The pos[${index - 1}] is out of bounds")
                index = 0
            }
            else -> throw IndexOutOfBoundsException("The pos[${index + count}] is out of bounds")
        }
    }

    override fun hasNext(): Boolean {
        return index == 0
    }

    override fun nextChar(): Int {
        if (index == 0) {
            index = 1
            return char.code
        } else {
            return -1
        }
    }

    override fun nextIfWhitespace(): Boolean {
        if (index != 0) return false
        return if (char.isWhitespace()) {
            index = 1
            true
        } else {
            false
        }
    }

    override fun nextIfNewLine(): Boolean {
        if (index != 0) return false
        return if (char == '\n') {
            index = 1
            true
        } else {
            false
        }
    }

    override fun getChar(index: Int): Int {
        return if (index == 0) char.code else -1
    }

    override fun getOffsetChar(offset: Int): Int {
        val index = this.index + offset
        return if (index == 0) char.code else -1
    }

    override fun close() { }

    override fun convertToString(startIndex: Int, endIndex: Int, capacity: Int): String {
        val left = startIndex.coerceAtLeast(0)
        val right = if (endIndex == -1) 1 else endIndex.coerceAtMost(1)
        return if (left == right) "" else char.toString()
    }

}