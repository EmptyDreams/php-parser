package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/**
 * 条件表达式，只计算被选中的分支。
 * thenExpression 为 null 表示短三元 ?:；condition 只计算一次，为真时直接复用其值。
 */
public record IrConditional(IrExpression condition, @Nullable IrExpression thenExpression,
                            IrExpression elseExpression, SourceInfo source) implements IrExpression {
    public IrConditional {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(elseExpression, "elseExpression");
        Objects.requireNonNull(source, "source");
    }
}
