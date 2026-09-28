package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/**
 * 保留原始文本的字面量，不在模型构造时解码字符串或解析数值。
 * FLOAT 沿用词法分类，因此其原文也可能是超出整数范围的整数字面量。
 */
public record IrLiteral(LiteralKind kind, String lexeme, SourceInfo source) implements IrExpression {
    public IrLiteral {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(lexeme, "lexeme");
        Objects.requireNonNull(source, "source");
    }
}
