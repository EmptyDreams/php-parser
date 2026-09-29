package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 复合赋值；保留一次目标定位，不展开为会重复计算下标的读取与赋值。 */
public record IrCompoundAssignment(CompoundAssignmentOperator operator, IrAssignmentTarget target,
                                   IrExpression value, SourceInfo source) implements IrExpression {
    public IrCompoundAssignment {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
