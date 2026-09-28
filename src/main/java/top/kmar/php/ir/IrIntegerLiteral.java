package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 已解码的 64 位有符号整数字面量；原始写法保留在 AST 中。 */
public record IrIntegerLiteral(long value, SourceInfo source) implements IrExpression {
    public IrIntegerLiteral {
        Objects.requireNonNull(source, "source");
    }
}
