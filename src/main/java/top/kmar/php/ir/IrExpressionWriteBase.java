package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 用作后续属性或下标写入基底的表达式结果，本身不是可直接赋值的目标。 */
public record IrExpressionWriteBase(IrExpression expression, SourceInfo source) implements IrWriteBase {
    public IrExpressionWriteBase {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
