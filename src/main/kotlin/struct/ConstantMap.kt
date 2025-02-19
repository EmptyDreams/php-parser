package top.kmar.php.struct

import top.kmar.php.container.IntAnyPair
import top.kmar.php.indexOfNotWhitespace
import top.kmar.php.indexOfNotWhitespaceAndEq
import top.kmar.php.indexOfWhitespaceOrEq
import java.util.*

class ConstantMap : IPhpItem {

    private val map = HashMap<String, IPhpConstantValue>()

    fun parse(code: String, startIndex: Int): Int {
        val nameStartIndex = code.indexOfNotWhitespace(startIndex + 6)
        val nameEndIndex = code.indexOfWhitespaceOrEq(nameStartIndex + 1)
        val name = code.substring(nameStartIndex, nameEndIndex)
        val valueStartIndex = if (code[nameStartIndex] == '=') {
            nameStartIndex + 1
        } else {
            code.indexOfNotWhitespaceAndEq(nameEndIndex + 1)
        }
        val value = parseValue(code, valueStartIndex)
        map[name] = value.any
        return value.int
    }

    companion object {

        @JvmStatic
        private fun parseValue(code: String, startIndex: Int): IntAnyPair<IPhpConstantValue> {
            TODO()
        }

    }

}

sealed interface IPhpConstantValue : Comparable<IPhpConstantValue> {

    val isArray: Boolean
    val code: Int

}

class PhpConstantString(val value: String) : IPhpConstantValue {

    override val isArray: Boolean
        get() = false
    override val code: Int
        get() = 1

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is PhpConstantString) return false
        return value == other.value
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override fun compareTo(other: IPhpConstantValue): Int {
        if (other !is PhpConstantString) return code.compareTo(other.code)
        return value.compareTo(other.value)
    }

    override fun toString(): String {
        return "'$value'"
    }

}

class PhpConstantInt(val value: PhpInt) : IPhpConstantValue {

    override val isArray: Boolean
        get() = false
    override val code: Int
        get() = 2

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is PhpConstantInt) return false
        return value == other.value
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override fun compareTo(other: IPhpConstantValue): Int {
        if (other !is PhpConstantInt) return code.compareTo(other.code)
        return value.compareTo(other.value)
    }

    override fun toString(): String {
        return value.toString()
    }

}

class PhpConstantFloat(val value: Float) : IPhpConstantValue {

    override val isArray: Boolean
        get() = false
    override val code: Int
        get() = 3

    override fun equals(other: Any?): Boolean {
        if (other === this) return true
        if (other !is PhpConstantFloat) return false
        return value == other.value
    }

    override fun hashCode(): Int {
        return value.hashCode()
    }

    override fun compareTo(other: IPhpConstantValue): Int {
        if (other !is PhpConstantFloat) return code.compareTo(other.code)
        return value.compareTo(other.value)
    }

    override fun toString(): String {
        return value.toString()
    }

}

enum class PhpConstantBool : IPhpConstantValue {

    FALSE, TRUE;

    override val isArray: Boolean
        get() = false
    override val code: Int
        get() = 4

    override fun compareTo(other: IPhpConstantValue): Int {
        if (other !is PhpConstantBool) return code.compareTo(other.code)
        return ordinal.compareTo(other.ordinal)
    }

    override fun toString(): String {
        return if (this === TRUE) "true" else "false"
    }

}

class PhpConstantArray : IPhpConstantValue {

    override val isArray: Boolean
        get() = true
    override val code: Int
        get() = 5

    private val map = TreeMap<Comparable<Any>, IPhpConstantValue>()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhpConstantArray) return false
        return map == other.map
    }

    override fun hashCode(): Int {
        if (map.isEmpty()) return 0
        return map.size * 31 + map.firstEntry().hashCode()
    }

    override fun compareTo(other: IPhpConstantValue): Int {
        if (this === other) return 0
        if (other !is PhpConstantArray) return code.compareTo(other.code)
        val first = map.size.compareTo(other.map.size)
        if (first != 0) return first
        val thisItor = map.iterator()
        val otherItor = other.map.iterator()
        while (thisItor.hasNext()) {
            val thisEntry = thisItor.next()
            val otherEntry = otherItor.next()
            val (thisKey, thisValue) = thisEntry
            val (otherKey, otherValue) = otherEntry
            val keyCompare = thisKey.compareTo(otherKey)
            if (keyCompare != 0) return keyCompare
            val valueCompare = thisValue.compareTo(otherValue)
            if (valueCompare != 0) return valueCompare
        }
        return 0
    }

}