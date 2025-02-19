package top.kmar.php.struct

import top.kmar.php.container.ParseResult

data class PhpClass(
    val name: PhpClassName,
    val range: SourceRange,
    val comment: PhpComment?,
    val extends: PhpClassName?,
    val interfaces: List<PhpClassName>,
    val traits: List<PhpClassName>,
    val constants: ConstantMap,
    val variable: VariableMap,
    val functions: List<PhpFunction>
) : IPhpItem {

    companion object {

        @JvmStatic
        internal fun parse(code: String, startIndex: Int): ParseResult<PhpClass> {
            TODO()
        }

    }

}