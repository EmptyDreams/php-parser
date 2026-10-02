package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.Modifier;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 具名类声明的原位结构；保留修饰符、继承和成员顺序，不注册类或检查继承合法性。 */
public record IrClassDeclaration(String name, List<Modifier> declaredModifiers,
                                 @Nullable IrNameReference parentType, List<IrNameReference> interfaces,
                                 List<IrClassMember> members, SourceInfo source) implements IrStatement {
    public IrClassDeclaration {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        declaredModifiers = List.copyOf(declaredModifiers);
        interfaces = List.copyOf(interfaces);
        members = List.copyOf(members);
        Objects.requireNonNull(source, "source");
    }
}
