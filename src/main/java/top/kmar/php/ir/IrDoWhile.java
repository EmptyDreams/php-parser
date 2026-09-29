package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 后置条件循环；独立于 while 保留先执行循环体的语义。 */
public record IrDoWhile(IrBlock body, IrExpression condition, SourceInfo source) implements IrStatement {
    public IrDoWhile {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(source, "source");
    }
}
