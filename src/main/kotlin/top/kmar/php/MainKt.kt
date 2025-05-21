package top.kmar.php

import java_cup.runtime.DefaultSymbolFactory
import java.io.File

fun main() {
    val file = File("D:\\Workspace\\jvm\\php-parser\\src\\test\\resources\\test.php")
    val tokenStream = PhpLexer(file.bufferedReader())
    val parser = PhpParser(tokenStream, DefaultSymbolFactory())
    val sym = parser.parse()
    println(sym)
//    val file = File("D:\\Workspace\\jvm\\php-parser\\src\\test\\resources\\test.php")
//    file.bufferedReader().use { reader ->
//        val lexer = PhpLexer(reader)
//        do {
//            val token = lexer.next_token()
//            println(token.value)
//            println("----------------------------")
//        } while (!lexer.yyatEOF())
//    }
}