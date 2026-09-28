package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 将表达式作为语句执行，表达式的返回值不构成该语句的结果。 */
public record IrExpressionStatement(IrExpression expression, SourceInfo source) implements IrStatement {
    public IrExpressionStatement {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
