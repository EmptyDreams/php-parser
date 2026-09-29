package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 作为 foreach 来源的表达式结果，包括普通值迭代和引用迭代中的临时值。 */
public record IrExpressionIterable(IrExpression expression, SourceInfo source) implements IrForeachIterable {
    public IrExpressionIterable {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
