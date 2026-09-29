package top.kmar.php.ir;

import top.kmar.php.model.NameReference;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 尚未解析或绑定的具名函数引用，保留名称限定形式。 */
public record IrNamedCallTarget(NameReference name, SourceInfo source) implements IrCallTarget {
    public IrNamedCallTarget {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
    }
}
