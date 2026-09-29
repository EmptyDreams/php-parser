package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.Modifier;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** trait 方法别名或修饰符调整；至少显式提供一项，不校验成员合法性。 */
public record IrTraitAlias(IrTraitMethodReference method, @Nullable Modifier modifier, @Nullable String newName,
                           SourceInfo source) implements IrTraitAdaptation {
    public IrTraitAlias {
        Objects.requireNonNull(method, "method");
        if (newName != null && newName.isEmpty()) throw new IllegalArgumentException("newName 不能为空字符串");
        if (modifier == null && newName == null) throw new IllegalArgumentException("modifier 和 newName 不能同时省略");
        Objects.requireNonNull(source, "source");
    }
}
