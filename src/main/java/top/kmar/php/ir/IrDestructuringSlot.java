package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 解构的有序槽位；target 为 null 表示跳过，跳过槽不能带键。 */
public record IrDestructuringSlot(@Nullable IrExpression key, @Nullable IrBindingTarget target,
                                  SourceInfo source) {
    public IrDestructuringSlot {
        Objects.requireNonNull(source, "source");
        if (target == null && key != null) {
            throw new IllegalArgumentException("解构跳过槽不能带键");
        }
    }
}
