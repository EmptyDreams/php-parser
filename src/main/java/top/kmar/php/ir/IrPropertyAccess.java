package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 实例属性读取，接收者和计算式属性名称分别保存且不求值。 */
public record IrPropertyAccess(IrExpression receiver, IrAccessName property, SourceInfo source)
        implements IrExpression {
    public IrPropertyAccess {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(source, "source");
    }
}
