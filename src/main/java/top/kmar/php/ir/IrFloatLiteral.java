package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 已解码的双精度浮点字面量；允许无穷大、NaN 和有符号零，不保存原始文本。 */
public record IrFloatLiteral(double value, SourceInfo source) implements IrExpression {
    public IrFloatLiteral {
        Objects.requireNonNull(source, "source");
    }
}
