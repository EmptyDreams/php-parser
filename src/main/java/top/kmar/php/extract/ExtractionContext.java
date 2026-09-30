package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.*;
import top.kmar.php.model.*;

import java.util.ArrayList;
import java.util.List;

/** 一次提取独享的状态及 CUP 字段适配，不包含全局可变注册表。 */
final class ExtractionContext implements AstReaderContext {
    private final @Nullable String sourceId;
    private long nextDeclarationId = 1;

    ExtractionContext(@Nullable String sourceId) {
        this.sourceId = sourceId;
    }

    DeclarationId nextId() {
        return new DeclarationId(nextDeclarationId++);
    }

    @Override
    public @Nullable String sourceId() {
        return sourceId;
    }

    @Override
    public DeclarationExtractionException error(@Nullable AstNode node, String path, String message) {
        return new DeclarationExtractionException(source(node), path, message);
    }

    String namespaceName(NodeNamespaceName node) {
        return namespaceName(node, "namespace_name");
    }

    NameReference name(NodeName node) {
        required(node, null, "name");
        String value = namespaceName(required(node.getN(), node, "name.n"));
        return switch (node) {
            case NodeName.FullyQualified ignored -> new NameReference(text(node.getKw(), node, "name.kw") + value,
                NameForm.FULLY_QUALIFIED, source(node));
            case NodeName.Relative ignored -> new NameReference(text(node.getKw(), node, "name.kw") + "\\" + value,
                NameForm.NAMESPACE_RELATIVE, source(node));
            case NodeName.Unqualified ignored -> new NameReference(value, value.indexOf('\\') < 0 ? NameForm.UNQUALIFIED
                : NameForm.QUALIFIED, source(node));
            default -> throw error(node, "name", "无法识别的名称引用");
        };
    }

    String identifier(NodeIdentifier node) {
        required(node, null, "identifier");
        if (node instanceof NodeIdentifier.Identifier) {
            return text(node.getName(), node, "identifier.name");
        }
        if (node instanceof NodeIdentifier.SemiReservedIdentifier) {
            var keyword = required(node.getKw(), node, "identifier.kw");
            if (keyword instanceof NodeSemiReserved.Reserved) {
                var reserved = required(keyword.getKeyword(), keyword, "identifier.kw.keyword");
                return text(reserved.getKw(), reserved, "identifier.kw.keyword.kw");
            }
            // 若干关键字共享匿名生成变体，只读取稳定的类型化字段。
            return text(keyword.getKw(), keyword, "identifier.kw.kw");
        }
        throw error(node, "identifier", "无法识别的标识符结构");
    }

    SyntaxExpression expression(NodeExpr node) {
        required(node, null, "expression");
        return new SyntaxExpression(node, source(node));
    }

    SyntaxBody body(List<? extends AstNode> statements, AstNode origin) {
        return new SyntaxBody(new ArrayList<>(elements(statements, origin, "body.statements")), source(origin));
    }

    String qualifiedName(String namespaceName, String name) {
        return namespaceName.isEmpty() ? name : namespaceName + "\\" + name;
    }
}
