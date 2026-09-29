package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** self、parent 或 static 类引用，其实际指向由上层结合上下文处理。 */
public record IrSpecialClassReference(SpecialClassKind kind, SourceInfo source) implements IrClassReference {
    public IrSpecialClassReference {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
    }
}
