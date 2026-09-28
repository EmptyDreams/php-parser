package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 显式空语句，保留其在语句序列中的位置。 */
public record IrEmpty(SourceInfo source) implements IrStatement {
    public IrEmpty {
        Objects.requireNonNull(source, "source");
    }
}
