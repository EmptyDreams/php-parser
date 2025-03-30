package top.kmar.php.exceptions

import java.io.IOException
import java.io.UncheckedIOException

class LexerException(
    line: Int,
    column: Int,
    message: String,
) : UncheckedIOException(
    IOException("Here is a lexer error at line $line, column $column: $message")
)