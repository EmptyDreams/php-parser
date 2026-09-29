package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** yield 表达式；裸 yield 的键和值都省略，显式 PHP null 仍是独立字面量节点。 */
public record IrYield(@Nullable IrExpression key, @Nullable IrExpression value,
                      SourceInfo source) implements IrExpression {
    public IrYield {
        if (key != null && value == null) throw new IllegalArgumentException("有键的 yield 必须有值");
        Objects.requireNonNull(source, "source");
    }
}
