package top.kmar.php.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * PHP 7.2 属性声明；不引入带类型属性或构造器属性提升语义。
 * initialValue 为 null 表示没有初始化表达式，显式 PHP null 仍由表达式节点保存。
 */
public record PropertyDefinition(DeclarationId id, DeclarationId ownerId, String name,
                                 List<Modifier> declaredModifiers,
                                 @Nullable SyntaxExpression initialValue, SourceInfo source)
        implements ClassMember {
    public PropertyDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(name, "name");
        declaredModifiers = List.copyOf(declaredModifiers);
        Objects.requireNonNull(source, "source");
    }
}
