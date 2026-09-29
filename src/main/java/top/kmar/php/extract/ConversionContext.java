package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import java_cup.runtime.symbol.complex.ComplexLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.NodeIdentifier;
import top.kmar.php.NodeName;
import top.kmar.php.NodeNamespaceName;
import top.kmar.php.NodeSemiReserved;
import top.kmar.php.NodeString;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.NameReference;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SourceRange;

import java.util.ArrayDeque;
import java.util.List;

/** 单次转换使用的 CUP 适配器；来源和诊断不依赖声明提取状态。 */
final class ConversionContext {
    private final @Nullable String sourceId;

    ConversionContext(@Nullable String sourceId) {
        this.sourceId = sourceId;
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

    SyntaxConversionException error(@Nullable AstNode node, String path, String reason) {
        return new SyntaxConversionException(source(node), path, reason);
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

    NameReference name(NodeName node, String path) {
        required(node, null, path);
        String value = namespaceName(required(node.getN(), node, path + ".n"), path + ".n");
        return switch (node) {
            case NodeName.FullyQualified ignored -> new NameReference(
                    text(node.getKw(), node, path + ".kw") + value, NameForm.FULLY_QUALIFIED, source(node));
            case NodeName.Relative ignored -> new NameReference(
                    text(node.getKw(), node, path + ".kw") + "\\" + value,
                    NameForm.NAMESPACE_RELATIVE, source(node));
            case NodeName.Unqualified ignored -> new NameReference(value,
                    value.indexOf('\\') < 0 ? NameForm.UNQUALIFIED : NameForm.QUALIFIED, source(node));
            default -> throw error(node, path, "无法识别的名称引用");
        };
    }

    /** 成员标识符也可以是文法允许的半保留字，保留原拼写。 */
    String identifier(NodeIdentifier node, String path) {
        required(node, null, path);
        if (node instanceof NodeIdentifier.Identifier) {
            return text(node.getName(), node, path + ".name");
        }
        if (node instanceof NodeIdentifier.SemiReservedIdentifier) {
            var keyword = required(node.getKw(), node, path + ".kw");
            if (keyword instanceof NodeSemiReserved.Reserved) {
                var reserved = required(keyword.getKeyword(), keyword, path + ".kw.keyword");
                return text(reserved.getKw(), reserved, path + ".kw.keyword.kw");
            }
            // 若干关键字共享匿名生成变体，不依赖其哈希名称。
            return text(keyword.getKw(), keyword, path + ".kw.kw");
        }
        throw error(node, path, "无法识别的成员标识符结构");
    }

    private String namespaceName(NodeNamespaceName node, String path) {
        var parts = new ArrayDeque<String>();
        StringBuilder pathBuilder = new StringBuilder(path);
        while (node instanceof NodeNamespaceName.Nested) {
            parts.addFirst(text(node.getPart(), node, pathBuilder + ".part"));
            node = required(node.getParent(), node, pathBuilder + ".parent");
            pathBuilder.append(".parent");
        }
        path = pathBuilder.toString();
        if (!(node instanceof NodeNamespaceName.Part)) {
            throw error(node, path, "无法识别的名称结构");
        }
        parts.addFirst(text(node.getName(), node, path + ".name"));
        return String.join("\\", parts);
    }
}
