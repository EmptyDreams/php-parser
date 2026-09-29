package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 静态属性读取；类引用和属性名称均可由表达式提供。 */
public record IrStaticPropertyAccess(IrClassReference classReference, IrAccessName property, SourceInfo source)
        implements IrExpression {
    public IrStaticPropertyAccess {
        Objects.requireNonNull(classReference, "classReference");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(source, "source");
    }
}
