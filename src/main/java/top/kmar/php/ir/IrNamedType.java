package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 未绑定的具名声明类型；名称主体和限定方式由规范化名称引用保存。 */
public record IrNamedType(IrNameReference name) implements IrTypeName {
    public IrNamedType {
        Objects.requireNonNull(name, "name");
    }

    @Override
    public SourceInfo source() {
        return name.source();
    }
}
