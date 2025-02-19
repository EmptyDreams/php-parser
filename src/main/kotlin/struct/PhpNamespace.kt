package top.kmar.php.struct

import top.kmar.php.container.ParseResult

data class PhpNamespace(
    val list: List<String>
) : IPhpItem {

    companion object {

        /**
         * 解析 namespace 语句
         *
         * @param startIndex 应当指向 `n` 字符的位置
         */
        @JvmStatic
        fun parse(code: String, startIndex: Int): ParseResult<PhpNamespace> {
            TODO()
        }

    }

}