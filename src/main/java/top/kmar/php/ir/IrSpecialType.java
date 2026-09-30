package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** self 或 parent 声明类型；实际指向由上层结合所属类确定。 */
public record IrSpecialType(SpecialTypeKind kind, SourceInfo source) implements IrTypeName {
    public IrSpecialType {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
    }
}
