package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** trait 方法改名或可见性调整；visibility 为 null 表示不改变，后两项至少提供一项，不执行 trait 合并。 */
public record IrTraitAlias(IrTraitMethodReference method, @Nullable Visibility visibility, @Nullable String newName,
                           SourceInfo source) implements IrTraitAdaptation {
    public IrTraitAlias {
        Objects.requireNonNull(method, "method");
        if (newName != null && newName.isEmpty()) throw new IllegalArgumentException("newName 不能为空字符串");
        if (visibility == null && newName == null) throw new IllegalArgumentException("visibility 和 newName 不能同时省略");
        Objects.requireNonNull(source, "source");
    }
}
