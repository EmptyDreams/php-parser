package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** echo 语句；表达式保持原顺序，后续执行应按此顺序求值并输出。 */
public record IrEcho(List<IrExpression> expressions, SourceInfo source) implements IrStatement {
    public IrEcho {
        expressions = List.copyOf(expressions);
        Objects.requireNonNull(source, "source");
    }
}
