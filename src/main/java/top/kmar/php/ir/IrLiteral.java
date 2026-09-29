package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/**
 * 保留原始文本的布尔或 null 字面量。
 * 数值和字符串分别使用 {@link IrIntegerLiteral}、{@link IrFloatLiteral} 与
 * {@link IrStringLiteral} 保存解码后的值。
 */
public record IrLiteral(LiteralKind kind, String lexeme, SourceInfo source) implements IrExpression {
    public IrLiteral {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(lexeme, "lexeme");
        Objects.requireNonNull(source, "source");
    }
}
