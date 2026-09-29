package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 由表达式提供的 callable；不检查其运行时类型或改写成具名函数引用。 */
public record IrExpressionCallTarget(IrExpression expression, SourceInfo source) implements IrCallTarget {
    public IrExpressionCallTarget {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
