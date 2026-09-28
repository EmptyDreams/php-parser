package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 一元表达式；运算符已规范化，操作数的原有树结构不变。 */
public record IrUnary(UnaryOperator operator, IrExpression operand, SourceInfo source)
        implements IrExpression {
    public IrUnary {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(operand, "operand");
        Objects.requireNonNull(source, "source");
    }
}
