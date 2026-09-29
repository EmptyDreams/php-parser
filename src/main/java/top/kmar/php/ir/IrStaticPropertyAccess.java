package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 固定名称的静态属性读取，属性名保留拼写且不包含 $。 */
public record IrStaticPropertyAccess(IrClassReference classReference, String property, SourceInfo source)
        implements IrExpression {
    public IrStaticPropertyAccess {
        Objects.requireNonNull(classReference, "classReference");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(source, "source");
    }
}
