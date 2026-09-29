package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 一个 case 或 default；condition 为 null 表示 default，空 body 仍保留为独立分支。 */
public record IrSwitchCase(@Nullable IrExpression condition, IrBlock body, SourceInfo source) {
    public IrSwitchCase {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(source, "source");
    }
}
