package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 计算式类引用，不求值、不绑定类，也不将字符串表达式解释为特殊类名。 */
public record IrDynamicClassReference(IrExpression expression, SourceInfo source) implements IrClassReference {
    public IrDynamicClassReference {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
