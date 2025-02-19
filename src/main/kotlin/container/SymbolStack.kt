package top.kmar.php.container

class SymbolStack(
    private val code: String
) {

    private val stack = ArrayList<Type>();
    private var docStartIndex = -1
    private var docLength = -1

    val top: Type
        get() = stack.last()

    val topOrNull: Type?
        get() = stack.lastOrNull()

    fun pop() {
        stack.removeLast()
    }

    fun pushNamespace() {
        stack.add(Type.NAMESPACE)
    }

    fun pushMultiComment() {
        stack.add(Type.MULTI_COMMENT)
    }

    fun pushSingleComment() {
        stack.add(Type.SINGLE_COMMENT)
    }

    fun pushSingleQuote() {
        stack.add(Type.SINGLE_QUOTE)
    }

    fun pushDoubleQuote() {
        stack.add(Type.DOUBLE_QUOTE)
    }

    /**
     * 推动 hear doc 标记到栈顶
     *
     * @param startIndex hear doc 开始下标（指向 doc 名称的第一个字符）
     * @param length 名称长度
     */
    fun pushHearDoc(startIndex: Int, length: Int) {
        stack.add(Type.HEAR_DOC)
        docStartIndex = startIndex
        docLength = length
    }

    /**
     * 推动 new doc 标记到栈顶
     *
     * @param startIndex new doc 开始下标（指向 doc 名称的第一个字符）
     * @param length 名称长度
     */
    fun pushNewDoc(startIndex: Int, length: Int) {
        stack.add(Type.NEW_DOC)
        docStartIndex = startIndex
        docLength = length
    }

    /**
     * 检查当前位置是否是字符串的结尾
     *
     * @return 如果是则返回结束位置的下标（不包含），否则返回 [index]
     * @throws AssertionError 如果栈顶不是单引号或双引号
     */
    fun isStringEnd(index: Int): Int {
        assert(topOrNull == Type.SINGLE_QUOTE || topOrNull == Type.DOUBLE_QUOTE) {
            AssertionError("栈顶[$topOrNull]不是单引号或双引号")
        }
        val code = this.code
        if (top == Type.SINGLE_QUOTE) {
            if (code[index] == '\'' && code[index - 1] != '\\') return index + 1
        } else {
            if (code[index] == '"' && code[index - 1] != '\\') return index + 1
        }
        return index
    }

    /**
     * 检查当前位置是否是 doc 的结尾
     *
     * @return 如果是则返回结束位置的下标（不包含），否则返回 [index]
     * @throws AssertionError 如果栈顶不是 hear_doc 或 new_doc
     */
    fun isDocEnd(index: Int): Int {
        assert(topOrNull == Type.HEAR_DOC || topOrNull == Type.NEW_DOC) {
            AssertionError("栈顶[$topOrNull]不是 hear_doc 或 new_doc")
        }
        val code = this.code
        val length = docLength
        val endIndex = index + length
        // 结束标记不在开头或长度超出文件范围，肯定不是结尾
        if (code[index - 1] != '\n' || endIndex > code.length) return index
        if (endIndex != code.length) {
            val char = code[endIndex]
            // 结束标记行内包含除了 `;` 外的其它字符，说明不是结尾
            if (char != ';' && char != '\n' && char != '\r') return index
        }
        val nameStartIndex = docStartIndex
        for (i in index..<endIndex) {
            if (code[i] != code[nameStartIndex + i]) return index
        }
        pop()
        return endIndex
    }

    enum class Type {

        /** 多行注释 */
        MULTI_COMMENT,
        /** 单行注释 */
        SINGLE_COMMENT,
        /** 单引号 */
        SINGLE_QUOTE,
        /** 双引号 */
        DOUBLE_QUOTE,
        /** <<<"?xxx"? */
        HEAR_DOC,
        /** <<<'xxx' */
        NEW_DOC,
        NAMESPACE

    }

}