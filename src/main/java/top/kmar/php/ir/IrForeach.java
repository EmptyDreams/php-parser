package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** foreach；键只能是单个目标，解构仅用于按值迭代，可写来源仅用于引用迭代。 */
public record IrForeach(IrForeachIterable iterable, @Nullable IrAssignmentTarget keyTarget,
                        IrBindingTarget valueTarget, boolean byReference,
                        IrBlock body, SourceInfo source) implements IrStatement {
    public IrForeach {
        Objects.requireNonNull(iterable, "iterable");
        Objects.requireNonNull(valueTarget, "valueTarget");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(source, "source");
        if (byReference && !(valueTarget instanceof IrAssignmentTarget)) {
            throw new IllegalArgumentException("引用 foreach 的值必须是单个写目标");
        }
        if (!byReference && iterable instanceof IrWritableIterable) {
            throw new IllegalArgumentException("按值 foreach 不能使用可写来源");
        }
    }
}
