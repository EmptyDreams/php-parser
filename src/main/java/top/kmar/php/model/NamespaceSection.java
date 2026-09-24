package top.kmar.php.model;

import java.util.List;
import java.util.Objects;

/** 一个独立导入环境的命名空间区段；空 namespaceName 表示全局空间。 */
public record NamespaceSection(NamespaceSectionId id, String namespaceName,
                                List<ImportDeclaration> imports,
                                List<TopLevelDeclaration> declarations,
                                SyntaxBody body, SourceInfo source) {
    public NamespaceSection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(namespaceName, "namespaceName");
        imports = List.copyOf(imports);
        declarations = List.copyOf(declarations);
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(source, "source");
    }
}
