package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 调用结果可用作后续属性／下标写入基底或引用赋值来源，本身不是可直接赋值的目标。 */
public record IrExpressionWriteBase(IrExpression expression, SourceInfo source) implements IrWriteBase {
    public IrExpressionWriteBase {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
