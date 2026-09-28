package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/**
 * 保留原始文本的字符串、布尔或 null 字面量；不在模型构造时解码字符串。
 * 数值分别使用 {@link IrIntegerLiteral} 和 {@link IrFloatLiteral} 保存解码后的值。
 */
public record IrLiteral(LiteralKind kind, String lexeme, SourceInfo source) implements IrExpression {
    public IrLiteral {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(lexeme, "lexeme");
        Objects.requireNonNull(source, "source");
    }
}
