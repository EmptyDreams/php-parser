package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.NodeIdentifier;
import top.kmar.php.NodeName;
import top.kmar.php.NodeSemiReserved;
import top.kmar.php.ir.IrNameReference;
import top.kmar.php.model.NameForm;

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

    /** 与词法层一致向标准错误流报告警告，继续转换，不修改 AST。 */
    void warn(@Nullable AstNode node, String path, String reason) {
        var range = source(node).range();
        String position = range == null ? "未知位置" : range.startLine() + ":" + range.startColumn()
                + "-" + range.endLine() + ":" + range.endColumn();
        System.err.println("PHP IR 警告 [" + (sourceId == null ? "未知来源" : sourceId)
                + " " + position + "] " + path + ": " + reason);
    }

    IrNameReference name(NodeName node, String path) {
        required(node, null, path);
        String value = namespaceName(required(node.getN(), node, path + ".n"), path + ".n");
        NameForm form = switch (node) {
            case NodeName.FullyQualified ignored -> {
                if (!text(node.getKw(), node, path + ".kw").equals("\\")) {
                    throw error(node, path + ".kw", "全限定名称标记必须为单个反斜杠");
                }
                yield NameForm.FULLY_QUALIFIED;
            }
            case NodeName.Relative ignored -> {
                if (!isNamespaceMarker(text(node.getKw(), node, path + ".kw"))) {
                    throw error(node, path + ".kw", "相对名称标记必须为 namespace");
                }
                yield NameForm.NAMESPACE_RELATIVE;
            }
            case NodeName.Unqualified ignored -> value.indexOf('\\') < 0
                    ? NameForm.UNQUALIFIED : NameForm.QUALIFIED;
            default -> throw error(node, path, "无法识别的名称引用");
        };
        try {
            return new IrNameReference(value, form, source(node));
        } catch (IllegalArgumentException exception) {
            throw error(node, path + ".n", exception.getMessage());
        }
    }

    /** 仅比较 ASCII 大小写；不能把 Unicode 近似字符当作语法关键字。 */
    private static boolean isNamespaceMarker(String value) {
        String expected = "namespace";
        if (value.length() != expected.length()) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character >= 'A' && character <= 'Z') character += 'a' - 'A';
            if (character != expected.charAt(i)) return false;
        }
        return true;
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
