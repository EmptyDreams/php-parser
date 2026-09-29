package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 实例属性写入目标，接收者沿写链处理，属性名称始终按读取计算且仅保存一次。 */
public record IrPropertyTarget(IrWriteBase receiver, IrAccessName property, SourceInfo source)
        implements IrAssignmentTarget {
    public IrPropertyTarget {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(source, "source");
    }
}
