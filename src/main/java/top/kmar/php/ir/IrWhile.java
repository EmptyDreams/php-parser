package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 前置条件循环；普通体和冒号体统一表示为语句块。 */
public record IrWhile(IrExpression condition, IrBlock body, SourceInfo source) implements IrStatement {
    public IrWhile {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(source, "source");
    }
}
