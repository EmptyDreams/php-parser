package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 有序的删除目标，不提前读值；转换器保证目标访问链不含追加下标。 */
public record IrUnset(List<IrAssignmentTarget> targets, SourceInfo source) implements IrStatement {
    public IrUnset {
        targets = List.copyOf(targets);
        if (targets.isEmpty()) throw new IllegalArgumentException("unset 至少需要一个目标");
        Objects.requireNonNull(source, "source");
    }
}
