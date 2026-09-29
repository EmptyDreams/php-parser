package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 固定名称的实例属性读取，属性名保留拼写且不包含 $。 */
public record IrPropertyAccess(IrExpression receiver, String property, SourceInfo source)
        implements IrExpression {
    public IrPropertyAccess {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(source, "source");
    }
}
