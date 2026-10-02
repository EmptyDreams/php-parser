package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 具名接口声明的原位结构；方法修饰符包含隐式 abstract，父接口和成员保留顺序，不注册接口。 */
public record IrInterfaceDeclaration(String name, List<IrNameReference> parentTypes,
                                     List<IrClassMember> members, SourceInfo source) implements IrStatement {
    public IrInterfaceDeclaration {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        parentTypes = List.copyOf(parentTypes);
        members = List.copyOf(members);
        Objects.requireNonNull(source, "source");
    }
}
