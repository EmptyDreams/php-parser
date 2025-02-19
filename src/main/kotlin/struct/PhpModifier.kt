package top.kmar.php.struct

@JvmInline
value class PhpModifier(
    val value: Int
) {

    val isPublic: Boolean
        get() = (value and PUBLIC) == PUBLIC
    val isProtected: Boolean
        get() = (value and PROTECTED) == PROTECTED
    val isPrivate: Boolean
        get() = (value and PRIVATE) == PRIVATE
    val isStatic: Boolean
        get() = (value and STATIC) == STATIC
    val isAbstract: Boolean
        get() = (value and ABSTRACT) == ABSTRACT
    val isFinal: Boolean
        get() = (value and FINAL) == FINAL

    fun toPublic(): PhpModifier {
        return PhpModifier((value and -8) or PUBLIC)
    }

    fun toProtected(): PhpModifier {
        return PhpModifier((value and -8) or PROTECTED)
    }

    fun toPrivate(): PhpModifier {
        return PhpModifier((value and -8) or PRIVATE)
    }

    fun toStatic(): PhpModifier {
        return PhpModifier((value and -17) or STATIC)
    }

    fun toAbstract(): PhpModifier {
        return PhpModifier((value and -41) or ABSTRACT)
    }

    fun toFinal(): PhpModifier {
        return PhpModifier((value and -17) or FINAL)
    }

    companion object {

        private const val PUBLIC = 0b1
        private const val PROTECTED = 0b10
        private const val PRIVATE = 0b100
        private const val STATIC = 0b1000
        private const val ABSTRACT = 0b10000
        private const val FINAL = 0b100000

    }

}