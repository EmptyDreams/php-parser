package top.kmar.php.model;

import java.util.List;
import java.util.Objects;

/** 类型中的常量声明；修饰符保留源码中明确给出的顺序和重复项。 */
public record ClassConstantDefinition(DeclarationId id, DeclarationId ownerId, String name,
                                      List<Modifier> declaredModifiers,
                                      SyntaxExpression value, SourceInfo source)
        implements ClassMember, ConstantDefinition {
    public ClassConstantDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(name, "name");
        declaredModifiers = List.copyOf(declaredModifiers);
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
