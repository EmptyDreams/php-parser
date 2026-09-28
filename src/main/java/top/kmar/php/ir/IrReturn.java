package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 返回语句；value 为 null 表示未写返回表达式，区别于显式返回 PHP null 的字面量节点。 */
public record IrReturn(@Nullable IrExpression value, SourceInfo source) implements IrStatement {
    public IrReturn {
        Objects.requireNonNull(source, "source");
    }
}
