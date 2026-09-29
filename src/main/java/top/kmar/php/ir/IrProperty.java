package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.Modifier;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 单个属性；名称不含 $，initialValue 为 null 表示省略初始化。 */
public record IrProperty(String name, List<Modifier> declaredModifiers, @Nullable IrExpression initialValue,
                         SourceInfo source) implements IrClassMember {
    public IrProperty {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        declaredModifiers = List.copyOf(declaredModifiers);
        Objects.requireNonNull(source, "source");
    }
}
