package top.kmar.php.struct

@JvmInline
value class PhpInt(val value: Long) : Comparable<PhpInt> {

    override fun compareTo(other: PhpInt): Int {
        return value.compareTo(other.value)
    }

    companion object {

        @JvmStatic
        fun String.toPhpInt(startIndex: Int, endIndex: Int, radix: Int): PhpInt {
            TODO()
        }

    }

}