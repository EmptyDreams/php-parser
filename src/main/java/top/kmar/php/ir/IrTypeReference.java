package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** IR 的显式类型声明；nullable 仅表示源码中的 ?，不根据参数默认值推导可空性。 */
public record IrTypeReference(IrTypeName type, boolean nullable, SourceInfo source) {
    public IrTypeReference {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
    }
}
