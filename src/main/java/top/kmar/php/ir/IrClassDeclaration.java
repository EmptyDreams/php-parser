package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 具名类声明的原位结构；修饰符已规范化，不从成员推导隐式抽象状态或检查继承合法性。 */
public record IrClassDeclaration(String name, boolean isAbstract, boolean isFinal,
                                 @Nullable IrNameReference parentType, List<IrNameReference> interfaces,
                                 List<IrClassMember> members, SourceInfo source) implements IrStatement {
    public IrClassDeclaration {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        if (isAbstract && isFinal) throw new IllegalArgumentException("类不能同时为 abstract 和 final");
        interfaces = List.copyOf(interfaces);
        members = List.copyOf(members);
        Objects.requireNonNull(source, "source");
    }
}
