package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 空合并表达式；left 存在且非 null 时直接使用其值，否则才计算 right。 */
public record IrCoalesce(IrExpression left, IrExpression right, SourceInfo source) implements IrExpression {
    public IrCoalesce {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(source, "source");
    }
}
