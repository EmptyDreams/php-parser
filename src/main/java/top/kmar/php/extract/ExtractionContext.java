package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import java_cup.runtime.symbol.complex.ComplexLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.*;
import top.kmar.php.model.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** 一次提取独享的状态及 CUP 字段适配，不包含全局可变注册表。 */
final class ExtractionContext {
    private final @Nullable String sourceId;
    private long nextDeclarationId = 1;

    ExtractionContext(@Nullable String sourceId) {
        this.sourceId = sourceId;
    }

    DeclarationId nextId() {
        return new DeclarationId(nextDeclarationId++);
    }

    SourceInfo source(@Nullable AstNode node) {
        if (node != null && node.getLocation() instanceof ComplexLocation location
                && !location.isNoLocation() && location.getStartLine() > 0
                && location.getStartColumn() > 0 && location.getEndColumn() > 0
                && (location.getEndLine() > location.getStartLine()
                || location.getEndLine() == location.getStartLine()
                && location.getEndColumn() >= location.getStartColumn())) {
            return new SourceInfo(sourceId, new SourceRange(location.getStartLine(),
                    location.getStartColumn(), location.getEndLine(), location.getEndColumn()));
        }
        return new SourceInfo(sourceId, null);
    }

    /** 仅合并 AST 已知范围，不尝试猜测未标记的关键字、括号或分号。 */
    SourceInfo span(SourceInfo left, SourceInfo right) {
        var a = left.range();
        var b = right.range();
        if (a == null) return right;
        if (b == null) return left;
        boolean aStartsFirst = a.startLine() < b.startLine()
                || a.startLine() == b.startLine() && a.startColumn() <= b.startColumn();
        boolean aEndsLast = a.endLine() > b.endLine()
                || a.endLine() == b.endLine() && a.endColumn() >= b.endColumn();
        return new SourceInfo(sourceId, new SourceRange(
                aStartsFirst ? a.startLine() : b.startLine(),
                aStartsFirst ? a.startColumn() : b.startColumn(),
                aEndsLast ? a.endLine() : b.endLine(),
                aEndsLast ? a.endColumn() : b.endColumn()));
    }

    DeclarationExtractionException error(@Nullable AstNode node, String path, String message) {
        return new DeclarationExtractionException(source(node), path, message);
    }

    <T> @NotNull T required(@Nullable T value, @Nullable AstNode node, String path) {
        if (value == null) throw error(node, path, "缺少必需字段");
        return value;
    }

    String text(@Nullable NodeString token, @Nullable AstNode node, String path) {
        String value = required(required(token, node, path).getValue(), node, path);
        if (value.isEmpty()) throw error(node, path, "必需名称或标记为空");
        return value;
    }

    <T> List<T> elements(List<T> values, AstNode node, String path) {
        required(values, node, path);
        for (int i = 0; i < values.size(); i++) required(values.get(i), node, path + "[" + i + "]");
        return values;
    }

    String namespaceName(NodeNamespaceName node) {
        required(node, null, "namespace_name");
        var parts = new ArrayDeque<String>();
        NodeNamespaceName current = node;
        while (current instanceof NodeNamespaceName.Nested) {
            parts.addFirst(text(current.getPart(), current, "namespace_name.part"));
            current = required(current.getParent(), current, "namespace_name.parent");
        }
        if (!(current instanceof NodeNamespaceName.Part)) {
            throw error(current, "namespace_name", "无法识别的名称结构");
        }
        parts.addFirst(text(current.getName(), current, "namespace_name.name"));
        return String.join("\\", parts);
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
