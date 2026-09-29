package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 自增或自减；操作符保留前缀／后缀区别，目标只出现一次，不在此阶段执行运算。 */
public record IrUpdate(UpdateOperator operator, IrAssignmentTarget target, SourceInfo source)
        implements IrExpression {
    public IrUpdate {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
    }
}
