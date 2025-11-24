package top.kmar.php

import java_cup.runtime.symbol.complex.ComplexSymbolFactory
import java.io.File

object KPhpParser {

    @JvmStatic
    fun parse(file: File) {
        file.bufferedReader().use {
            println(it.readText())
            println("=".repeat(10))
        }
        file.bufferedReader().use {
            val lexer = PhpLexer(it, ComplexSymbolFactory(PhpSymbols.TERMINAL_NAMES, PhpSymbols.NON_TERMINAL_NAMES))
            do {
                val token = lexer.next_token()
                println(PhpSymbols.TERMINAL_NAMES[token.sym] + ": " + token)
                println("----------------------------")
            } while (!lexer.yyatEOF())
        }
    }

}

fun main() {
    val file = File("D:\\Workspace\\jvm\\php-parser\\src\\test\\resources\\test.php")
    val root = KPhpParser.parse(file)
    println(root)
//    val tokenStream = PhpLexer(file.bufferedReader())
//    val parser = PhpParser(tokenStream, DefaultSymbolFactory())
//    val sym = parser.parse()
//    println(sym)
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