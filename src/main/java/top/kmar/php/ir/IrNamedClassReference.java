package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 具名类引用，保留名称主体的拼写及限定形式，不解析实际声明。 */
public record IrNamedClassReference(IrNameReference name, SourceInfo source) implements IrClassReference {
    public IrNamedClassReference {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
    }
}
