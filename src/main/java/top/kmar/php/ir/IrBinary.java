package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 非短路二元表达式；左右子树保留优先级和结合性，模型构造不计算结果。 */
public record IrBinary(BinaryOperator operator, IrExpression left, IrExpression right, SourceInfo source)
        implements IrExpression {
    public IrBinary {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(source, "source");
    }
}
