package top.kmar.php.struct

import top.kmar.php.container.ParseResult

data class PhpFunction(
    val a: Int
) : IPhpItem {

    companion object {

        @JvmStatic
        fun parse(code: String, startIndex: Int): ParseResult<PhpFunction> {
            TODO()
        }

    }

}