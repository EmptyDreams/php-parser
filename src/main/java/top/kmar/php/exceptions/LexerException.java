package top.kmar.php.exceptions;

public class LexerException extends RuntimeException {

    public LexerException(int line, int column, String message) {
        super("Here is a lexer error at line " + line + ", column " + column + ": " + message);
    }

}