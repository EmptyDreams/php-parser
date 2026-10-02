package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 类方法；修饰符已规范化，body 为 null 表示分号形式，不校验主体与抽象标志的关联。 */
public record IrMethod(String name, Visibility visibility, boolean isStatic, boolean isAbstract, boolean isFinal,
                       List<IrParameter> parameters,
                       @Nullable IrTypeReference returnType, boolean returnsReference,
                       @Nullable IrBlock body, SourceInfo source) implements IrClassMember {
    public IrMethod {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        Objects.requireNonNull(visibility, "visibility");
        if (isAbstract && isFinal) throw new IllegalArgumentException("方法不能同时为 abstract 和 final");
        if (isAbstract && visibility == Visibility.PRIVATE) {
            throw new IllegalArgumentException("abstract 方法不能为 private");
        }
        parameters = List.copyOf(parameters);
        Objects.requireNonNull(source, "source");
    }
}
