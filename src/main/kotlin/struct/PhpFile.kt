package top.kmar.php.struct

data class PhpFile(
    val path: String,
    val classes: List<PhpClass>,
    val functions: List<PhpFunction>,
    val constants: ConstantMap,
    val variables: VariableMap,
    val includes: IncludeList
) : IPhpItem {

    companion object {

        @JvmStatic
        fun parse(code: String, startIndex: Int, endIndex: Int): PhpFile {
            TODO()
        }

    }

}