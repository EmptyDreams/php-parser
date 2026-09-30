package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 独立 IR 的形参；名称不含 $，默认值为 null 表示省略，而不是 PHP null 字面量。 */
public record IrParameter(String name, @Nullable IrTypeReference declaredType, boolean byReference,
                          boolean variadic, @Nullable IrExpression defaultValue, SourceInfo source) {
    public IrParameter {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        Objects.requireNonNull(source, "source");
    }
}
