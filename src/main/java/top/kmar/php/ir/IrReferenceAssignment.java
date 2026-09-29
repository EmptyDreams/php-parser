package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 引用赋值；来源保留可寻址位置或调用结果，不转换为普通读值或模拟引用绑定。 */
public record IrReferenceAssignment(IrAssignmentTarget target, IrWriteBase reference,
                                    SourceInfo source) implements IrExpression {
    public IrReferenceAssignment {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(source, "source");
    }
}
