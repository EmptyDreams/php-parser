package top.kmar.php

fun main() {
    PhpLexer.main(arrayOf("D:\\Workspace\\jvm\\php-parser\\src\\test\\resources\\test.php"))
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