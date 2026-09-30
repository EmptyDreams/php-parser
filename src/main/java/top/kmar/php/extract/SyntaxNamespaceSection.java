package top.kmar.php.extract;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 仅分段的内部语法视图；不提取声明、导入或分配声明模型 ID。 */
record SyntaxNamespaceSection(String namespaceName, List<LocatedTopStatement> statements,
                              SourceInfo source, SourceInfo bodySource) {
    SyntaxNamespaceSection {
        Objects.requireNonNull(namespaceName, "namespaceName");
        statements = List.copyOf(statements);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(bodySource, "bodySource");
    }
}
