package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 按值数组条目；key 为 null 表示未写键，与显式的 PHP null 键不同。 */
public record IrArrayEntry(@Nullable IrExpression key, IrExpression value, SourceInfo source) {
    public IrArrayEntry {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
