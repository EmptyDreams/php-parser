package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/**
 * 保留 break 的层级表达式；null 表示省略，由上层解释为一层。
 * 本节点不保证层级表达式符合 PHP 7.2 的语义限制，也不绑定或验证跳转目标。
 */
public record IrBreak(@Nullable IrExpression levels, SourceInfo source) implements IrStatement {
    public IrBreak {
        Objects.requireNonNull(source, "source");
    }
}
