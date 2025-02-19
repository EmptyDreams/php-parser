package top.kmar.php.container

import top.kmar.php.struct.IPhpItem

data class ParseResult<T : IPhpItem>(
    val index: Int,
    val value: T
)