package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** ::class 类名表达式，保留类引用，不提前求值为字符串。 */
public record IrClassName(IrClassReference classReference, SourceInfo source) implements IrExpression {
    public IrClassName {
        Objects.requireNonNull(classReference, "classReference");
        Objects.requireNonNull(source, "source");
    }
}
