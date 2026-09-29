package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 尚未绑定的类常量引用，常量名保留源码拼写。 */
public record IrClassConstantReference(IrClassReference classReference, String constantName, SourceInfo source)
        implements IrExpression {
    public IrClassConstantReference {
        Objects.requireNonNull(classReference, "classReference");
        Objects.requireNonNull(constantName, "constantName");
        Objects.requireNonNull(source, "source");
    }
}
