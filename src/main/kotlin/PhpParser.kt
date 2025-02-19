package top.kmar.php

import top.kmar.php.struct.PhpFile

object PhpParser {

    @JvmStatic
    fun parser(code: String): PhpFile? {
        if (!code.startsWith("<?php")) return null
        val endIndex = if (code.endsWith("?>")) code.length - 2 else code.length
        return PhpFile.parse(code, 5, endIndex)
    }

    /**
     * 检查一个字符串是否为关键字
     *
     * 保留字也会被判定为关键字
     */
    @JvmStatic
    fun isKeyword(word: String): Boolean {
        return keywords.contains(word)
    }

    @JvmStatic
    internal val keywords = hashSetOf(
        "if", "else", "elseif", "endif", "switch", "case", "default",                       // 条件语句
        "break", "continue", "return",                                                      // 流程控制
        "for", "foreach", "while", "do", "goto",                                            // 循环与跳转
        "function", "class", "interface", "trait", "namespace", "use",                      // 声明语句
        "extends", "implements", "instanceof",                                              // 类关系
        "new", "clone", "exit", "die", "static", "self", "parent",                          // 其它
        "public", "protected", "private",                                                   // 权限控制
        "int", "float", "string", "bool", "array", "callable", "iterable", "object", "void",// 类型
        "require", "include", "require_once", "include_once",                               // 文件关系
        "echo", "print", "empty", "isset", "unset", "list", "eval", "global",               // 内置函数
        "final", "abstract",                                                                // 类/方法类型
        "const",                                                                            // 常量
        "and", "or", "xor",                                                                 // 运算符
        "try", "catch", "finally", "throw",                                                 // 异常处理
        "enum", "match", "fn", "readonly",                                                  // 保留字
        "declare"
    )

}