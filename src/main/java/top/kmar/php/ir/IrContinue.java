package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/**
 * 保留 continue 的层级表达式；null 表示省略，由上层解释为一层。
 * 转换片段时不检查循环上下文、层级是否合法或是否超出嵌套深度。
 */
public record IrContinue(@Nullable IrExpression levels, SourceInfo source) implements IrStatement {
    public IrContinue {
        Objects.requireNonNull(source, "source");
    }
}
