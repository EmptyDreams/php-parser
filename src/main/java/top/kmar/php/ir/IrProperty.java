package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 单个属性；可见性和 static 已规范化，名称不含 $，initialValue 为 null 表示省略初始化。 */
public record IrProperty(String name, Visibility visibility, boolean isStatic, @Nullable IrExpression initialValue,
                         SourceInfo source) implements IrClassMember {
    public IrProperty {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(source, "source");
    }
}
