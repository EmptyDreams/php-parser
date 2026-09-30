package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import java_cup.runtime.symbol.complex.ComplexLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.NodeNamespaceName;
import top.kmar.php.NodeString;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SourceRange;

import java.util.ArrayDeque;
import java.util.List;

/** 声明提取与 IR 转换共享的只读 AST 访问规则；具体上下文决定诊断类型。 */
interface AstReaderContext {
    @Nullable String sourceId();

    RuntimeException error(@Nullable AstNode node, String path, String reason);

    default SourceInfo source(@Nullable AstNode node) {
        if (node != null && node.getLocation() instanceof ComplexLocation location
                && !location.isNoLocation() && location.getStartLine() > 0
                && location.getStartColumn() > 0 && location.getEndColumn() > 0
                && (location.getEndLine() > location.getStartLine()
                || location.getEndLine() == location.getStartLine()
                && location.getEndColumn() >= location.getStartColumn())) {
            return new SourceInfo(sourceId(), new SourceRange(location.getStartLine(),
                    location.getStartColumn(), location.getEndLine(), location.getEndColumn()));
        }
        return new SourceInfo(sourceId(), null);
    }

    /** 沿用声明区段的范围聚合规则，只合并 AST 已知范围，不补齐语法标记。 */
    default SourceInfo span(SourceInfo left, SourceInfo right) {
        var a = left.range();
        var b = right.range();
        if (a == null) return right;
        if (b == null) return left;
        boolean aStartsFirst = a.startLine() < b.startLine()
                || a.startLine() == b.startLine() && a.startColumn() <= b.startColumn();
        boolean aEndsLast = a.endLine() > b.endLine()
                || a.endLine() == b.endLine() && a.endColumn() >= b.endColumn();
        return new SourceInfo(sourceId(), new SourceRange(
                aStartsFirst ? a.startLine() : b.startLine(),
                aStartsFirst ? a.startColumn() : b.startColumn(),
                aEndsLast ? a.endLine() : b.endLine(),
                aEndsLast ? a.endColumn() : b.endColumn()));
    }

    default <T> @NotNull T required(@Nullable T value, @Nullable AstNode node, String path) {
        if (value == null) throw error(node, path, "缺少必需字段");
        return value;
    }

    default String text(@Nullable NodeString token, @Nullable AstNode node, String path) {
        String value = required(required(token, node, path).getValue(), node, path);
        if (value.isEmpty()) throw error(node, path, "必需名称或标记为空");
        return value;
    }

    default <T> List<T> elements(List<T> values, AstNode node, String path) {
        required(values, node, path);
        for (int i = 0; i < values.size(); i++) required(values.get(i), node, path + "[" + i + "]");
        return values;
    }

    default String namespaceName(NodeNamespaceName node, String path) {
        required(node, null, path);
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
