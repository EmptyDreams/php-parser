package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 具名 trait 声明的原位结构；成员完整保留，不注册 trait 或执行成员合并。 */
public record IrTraitDeclaration(String name, List<IrClassMember> members, SourceInfo source)
        implements IrStatement {
    public IrTraitDeclaration {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        members = List.copyOf(members);
        Objects.requireNonNull(source, "source");
    }
}
