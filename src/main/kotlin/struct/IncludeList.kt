package top.kmar.php.struct

import top.kmar.php.container.ParseResult

class IncludeList : IPhpItem {

    companion object {

        @JvmStatic
        fun parse(code: String, startIndex: Int): ParseResult<IncludeList> {
            TODO()
        }

    }

    enum class Type {

        REQUIRE, INCLUDE, REQUIRE_ONCE

    }

}