package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 已解码的 PHP 字符串字面量；保存字节值，原始引号和转义仍由 AST 保留。 */
public record IrStringLiteral(ByteString value, SourceInfo source) implements IrExpression {
    public IrStringLiteral {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
