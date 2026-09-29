package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** PHP 7.2 的 throw 语句；保留表达式，不验证运行时异常类型或执行异常传播。 */
public record IrThrow(IrExpression expression, SourceInfo source) implements IrStatement {
    public IrThrow {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
