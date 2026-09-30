package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.NodeIdentifier;
import top.kmar.php.NodeName;
import top.kmar.php.NodeSemiReserved;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.NameReference;

/** 单次转换使用的 CUP 适配器；来源和诊断不依赖声明提取状态。 */
final class ConversionContext implements AstReaderContext {
    private final @Nullable String sourceId;

    ConversionContext(@Nullable String sourceId) {
        this.sourceId = sourceId;
    }

    @Override
    public @Nullable String sourceId() {
        return sourceId;
    }

    @Override
    public SyntaxConversionException error(@Nullable AstNode node, String path, String reason) {
        return new SyntaxConversionException(source(node), path, reason);
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

}
