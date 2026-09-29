package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.NameReference;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** trait 方法引用；trait 为 null 表示只写了方法名，不推断其所属 trait。 */
public record IrTraitMethodReference(@Nullable NameReference trait, String method, SourceInfo source) {
    public IrTraitMethodReference {
        Objects.requireNonNull(method, "method");
        if (method.isEmpty()) throw new IllegalArgumentException("method 不能为空");
        Objects.requireNonNull(source, "source");
    }
}
