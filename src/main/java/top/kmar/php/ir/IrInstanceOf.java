package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 表达式与类引用之间的 instanceof 判断，类引用可以动态提供，保留原语法分组。 */
public record IrInstanceOf(IrExpression expression, IrClassReference classReference, SourceInfo source)
        implements IrExpression {
    public IrInstanceOf {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(classReference, "classReference");
        Objects.requireNonNull(source, "source");
    }
}
