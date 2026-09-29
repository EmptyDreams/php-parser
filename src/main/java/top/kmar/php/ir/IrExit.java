package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** exit/die 表达式；null 表示省略参数，与显式 PHP null 字面量不同，不执行退出操作。 */
public record IrExit(@Nullable IrExpression expression, SourceInfo source) implements IrExpression {
    public IrExit {
        Objects.requireNonNull(source, "source");
    }
}
