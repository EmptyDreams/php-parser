package top.kmar.php.model;

import java_cup.runtime.AstNode;

import java.util.List;
import java.util.Objects;

/** 声明提取结果；模型集合只读，完整原始 AST 以共享只读引用保留。 */
public record PhpFile(SourceInfo source, AstNode syntax,
                       List<NamespaceSection> namespaceSections, DeclarationIndex declarationIndex) {
    public PhpFile {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(syntax, "syntax");
        namespaceSections = List.copyOf(namespaceSections);
        Objects.requireNonNull(declarationIndex, "declarationIndex");
    }
}
