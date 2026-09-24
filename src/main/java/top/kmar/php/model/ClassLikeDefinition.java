package top.kmar.php.model;

import java.util.List;
import java.util.Objects;

/** 具名类、接口或 trait；成员保持源码顺序，引用的类型尚未解析。 */
public record ClassLikeDefinition(DeclarationId id, NamespaceSectionId sectionId,
                                  String name, String qualifiedName, ClassLikeKind kind,
                                  List<Modifier> declaredModifiers,
                                  List<NameReference> parentTypes, List<NameReference> interfaces,
                                  List<ClassMember> members, SourceInfo source)
        implements TopLevelDeclaration {
    public ClassLikeDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(kind, "kind");
        declaredModifiers = List.copyOf(declaredModifiers);
        parentTypes = List.copyOf(parentTypes);
        interfaces = List.copyOf(interfaces);
        members = List.copyOf(members);
        Objects.requireNonNull(source, "source");
    }
}
