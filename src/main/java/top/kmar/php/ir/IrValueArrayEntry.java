package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 按值构造的数组条目；省略键与显式 PHP null 键不同。 */
public record IrValueArrayEntry(@Nullable IrExpression key, IrExpression value, SourceInfo source)
        implements IrArrayEntry {
    public IrValueArrayEntry {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
