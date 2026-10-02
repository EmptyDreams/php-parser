package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 变量写入目标，计算名称所需的表达式仍按读取处理。 */
public record IrVariableTarget(IrAccessName name) implements IrAssignmentTarget {
    public IrVariableTarget {
        Objects.requireNonNull(name, "name");
    }

    @Override
    public SourceInfo source() {
        return name.source();
    }
}
