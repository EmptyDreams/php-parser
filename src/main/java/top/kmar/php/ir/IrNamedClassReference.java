package top.kmar.php.ir;

import top.kmar.php.model.NameReference;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 具名类引用，保留原拼写及名称限定形式，不解析实际声明。 */
public record IrNamedClassReference(NameReference name, SourceInfo source) implements IrClassReference {
    public IrNamedClassReference {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
    }
}
