package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 已规范化的布尔字面量；原始大小写和限定写法保留在 AST 中。 */
public record IrBooleanLiteral(boolean value, SourceInfo source) implements IrExpression {
    public IrBooleanLiteral {
        Objects.requireNonNull(source, "source");
    }
}
