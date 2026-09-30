package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 显式 PHP null 字面量；不同于可选字段中表示缺省的 Java null。 */
public record IrNullLiteral(SourceInfo source) implements IrExpression {
    public IrNullLiteral {
        Objects.requireNonNull(source, "source");
    }
}
