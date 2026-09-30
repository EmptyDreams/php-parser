package top.kmar.php.ir;

import top.kmar.php.model.NameReference;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 未绑定的具名声明类型；名称限定方式和拼写由名称引用保存。 */
public record IrNamedType(NameReference name) implements IrTypeName {
    public IrNamedType {
        Objects.requireNonNull(name, "name");
    }

    @Override
    public SourceInfo source() {
        return name.source();
    }
}
