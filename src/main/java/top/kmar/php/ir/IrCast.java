package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 显式类型转换，类型别名已归一化；不执行转换或常量折叠。 */
public record IrCast(CastKind kind, IrExpression expression, SourceInfo source) implements IrExpression {
    public IrCast {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
