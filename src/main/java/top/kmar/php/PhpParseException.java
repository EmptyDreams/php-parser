package top.kmar.php;

/** 语法错误（CUP 解析失败） */
public class PhpParseException extends RuntimeException {

    public PhpParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
