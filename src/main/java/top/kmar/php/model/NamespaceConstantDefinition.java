package top.kmar.php.model;

import java.util.List;
import java.util.Objects;

/** 命名空间中的 const 声明，不包括调用 define() 产生的运行时常量。 */
public record NamespaceConstantDefinition(DeclarationId id, NamespaceSectionId sectionId,
                                          String name, String qualifiedName,
                                          SyntaxExpression value, SourceInfo source)
        implements TopLevelDeclaration, ConstantDefinition {
    public NamespaceConstantDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }

    @Override
    public List<Modifier> declaredModifiers() {
        return List.of();
    }
}
