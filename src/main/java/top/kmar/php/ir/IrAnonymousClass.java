package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 匿名类的独立定义；成员按源码顺序展开，不参与第一阶段命名声明索引。 */
public record IrAnonymousClass(@Nullable IrNameReference parentType, List<IrNameReference> interfaces,
                               List<IrClassMember> members, SourceInfo source) {
    public IrAnonymousClass {
        interfaces = List.copyOf(interfaces);
        members = List.copyOf(members);
        Objects.requireNonNull(source, "source");
    }
}
