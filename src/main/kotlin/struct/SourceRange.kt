package top.kmar.php.struct

/**
 * 标记一段代码在文件中的位置
 *
 * @property startLineInclude 起始行号
 * @property endLineExclude 结束行号
 * @property startCharInclude 起始字符在文件中的下标
 * @property endCharExclude 结束字符在文件中的下标
 */
data class SourceRange(
    val startLineInclude: Int,
    val endLineExclude: Int,
    val startCharInclude: Int,
    val endCharExclude: Int
)