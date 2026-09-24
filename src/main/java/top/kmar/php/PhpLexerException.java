package top.kmar.php;

import java_cup.runtime.symbol.complex.ComplexLocation;

/**
 * 词法错误（非法八进制、文件未以 &lt;?php 开头等）。
 * <p>对应 zend 中 E_COMPILE_ERROR / ParseError 的词法部分。
 */
public class PhpLexerException extends RuntimeException {

    private final ComplexLocation location;

    public PhpLexerException(String message, ComplexLocation location) {
        super(location + " " + message);
        this.location = location;
    }

    public ComplexLocation getLocation() {
        return location;
    }
}
