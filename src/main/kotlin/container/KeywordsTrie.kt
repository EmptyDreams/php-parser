package top.kmar.php.container

object KeywordsTrie {

    @JvmStatic
    private val root = Node(false).apply {
        insert("abstract")
        insert("and")
        insert("array")
        insert("as")
        insert("break")
        insert("callable")
        insert("case")
        insert("catch")
        insert("class")
        insert("clone")
        insert("const")
        insert("continue")
        insert("declare")
        insert("default")
        insert("die")
        insert("do")
        insert("echo")
        insert("else")
        insert("elseif")
        insert("empty")
        insert("enddeclare")
        insert("endfor")
        insert("endforeach")
        insert("endif")
        insert("endswitch")
        insert("endwhild")
        insert("enum")
        insert("extends")
        insert("final")
        insert("finally")
        insert("fn")
        insert("for")
        insert("foreach")
        insert("function")
        insert("global")
        insert("goto")
        insert("if")
        insert("implements")
        insert("include")
        insert("include_once")
        insert("instanceof")
        insert("insteadof")
        insert("interface")
        insert("isset")
        insert("list")
        insert("match")
        insert("namespace")
        insert("new")
        insert("or")
        insert("print")
        insert("private")
        insert("protected")
        insert("public")
        insert("readonly")
        insert("require")
        insert("require_once")
        insert("return")
        insert("static")
        insert("switch")
        insert("throw")
        insert("trait")
        insert("try")
        insert("unset")
        insert("use")
        insert("var")
        insert("while")
        insert("xor")
        insert("yield")
        insert("yield from")
    }

    @JvmStatic
    fun next(char: Char): ICharTrieNode? {
        return root.next(char)
    }

    private class Node(
        override var isEnd: Boolean,
        val children: Array<Node?> = arrayOfNulls(28)
    ) : ICharTrieNode {

        fun insert(keyword: String) {
            var treeNode = this
            for ((i, char) in keyword.withIndex()) {
                val index = when (char) {
                    '_' -> 26
                    ' ' -> 27
                    else -> char - 'a'
                }
                var child = treeNode.children[index]
                if (child == null) {
                    child = Node(false)
                    treeNode.children[index] = child
                }
                if (i == keyword.length - 1) child.isEnd = true
                else treeNode = child
            }
        }

        override fun next(char: Char): ICharTrieNode? {
            return children[char - 'a']
        }

        override fun nextUnderline(): ICharTrieNode? {
            return children[26]
        }

        override fun nextSpace(): ICharTrieNode? {
            return children[27]
        }

    }

}

interface ICharTrieNode {

    val isEnd: Boolean

    fun next(char: Char): ICharTrieNode?

    fun nextUnderline(): ICharTrieNode?

    fun nextSpace(): ICharTrieNode?

}