package top.kmar.php.model;

import java.util.Objects;

/** 顶层具名函数；函数体暂保留原 AST，不将其中的嵌套声明提升到文件索引。 */
public record FunctionDefinition(DeclarationId id, NamespaceSectionId sectionId,
                                 String qualifiedName, FunctionSignature signature,
                                 SyntaxBody body, SourceInfo source) implements TopLevelDeclaration {
    public FunctionDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(source, "source");
    }

    @Override
    public String name() {
        return signature.name();
    }
}
