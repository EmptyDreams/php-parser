package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 普通赋值表达式；目标为显式可写位置，不包含复合赋值或引用赋值语义。 */
public record IrAssignment(IrAssignmentTarget target, IrExpression value, SourceInfo source)
        implements IrExpression {
    public IrAssignment {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
