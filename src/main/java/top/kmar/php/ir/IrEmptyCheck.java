package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 空值检查，保留特殊读取语义；不提前读取操作数或展开为布尔取反。 */
public record IrEmptyCheck(IrExpression expression, SourceInfo source) implements IrExpression {
    public IrEmptyCheck {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
