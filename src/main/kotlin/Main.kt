package top.kmar.php

import top.kmar.php.lexer.StringSingleThreadStream
import top.kmar.php.lexer.PhpLexer
import top.kmar.php.lexer.forEachStreamConst
import java.io.File

fun main() {
    val code = File("test.php").readText()
    val tokens = PhpLexer(
        StringSingleThreadStream(code)
    )
    var token = tokens.nextToken()
    while (token != null) {
        print(token.type)
        print(": ")
        token.stream.forEachStreamConst {
            print(it)
            true
        }
        println()
        token = tokens.nextToken()
    }
}