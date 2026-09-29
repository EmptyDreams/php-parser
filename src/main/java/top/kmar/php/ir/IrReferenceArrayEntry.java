package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 引用构造的数组条目；保留被引用的单个可写位置，不接受普通临时值。 */
public record IrReferenceArrayEntry(@Nullable IrExpression key, IrAssignmentTarget target, SourceInfo source)
        implements IrArrayEntry {
    public IrReferenceArrayEntry {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
    }
}
