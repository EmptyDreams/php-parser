package top.kmar.php.model;

import java_cup.runtime.AstNode;

import java.util.List;
import java.util.Objects;

/** 类型中的 trait 使用项；适配规则保留为共享只读 AST，不执行方法合并。 */
public record TraitUseDefinition(DeclarationId id, DeclarationId ownerId,
                                 List<NameReference> traits, AstNode adaptations, SourceInfo source)
        implements ClassMember {
    public TraitUseDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        traits = List.copyOf(traits);
        Objects.requireNonNull(adaptations, "adaptations");
        Objects.requireNonNull(source, "source");
    }
}
