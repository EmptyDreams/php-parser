package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 按值 foreach；keyTarget 为 null 表示省略键目标，本轮不表示引用或解构目标。 */
public record IrForeach(IrExpression iterable, @Nullable IrAssignmentTarget keyTarget,
                        IrAssignmentTarget valueTarget, IrBlock body, SourceInfo source) implements IrStatement {
    public IrForeach {
        Objects.requireNonNull(iterable, "iterable");
        Objects.requireNonNull(valueTarget, "valueTarget");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(source, "source");
    }
}
