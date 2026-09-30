package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.Modifier;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 类方法；body 为 null 表示分号形式，修饰符保留显式顺序和重复项。 */
public record IrMethod(String name, List<Modifier> declaredModifiers, List<IrParameter> parameters,
                       @Nullable IrTypeReference returnType, boolean returnsReference,
                       @Nullable IrBlock body, SourceInfo source) implements IrClassMember {
    public IrMethod {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        declaredModifiers = List.copyOf(declaredModifiers);
        parameters = List.copyOf(parameters);
        Objects.requireNonNull(source, "source");
    }
}
