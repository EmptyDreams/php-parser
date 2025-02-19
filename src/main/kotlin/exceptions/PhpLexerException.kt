package top.kmar.php.exceptions

import top.kmar.php.lexer.SingleThreadRandomStream
import top.kmar.php.lexer.extractStringConst

class PhpLexerException(
    stream: SingleThreadRandomStream,
    message: String = ""
) : RuntimeException(handleMessage(message, stream)) {

    companion object {

        @JvmStatic
        private fun handleMessage(message: String, stream: SingleThreadRandomStream): String {
            return if (message.isNotEmpty()) {
                "$message at [${stream.pos()}]: ${stream.extractStringConst(32)}"
            } else {
                "Php has lexer error at [${stream.pos()}]: ${stream.extractStringConst(32)}"
            }
        }

    }

}