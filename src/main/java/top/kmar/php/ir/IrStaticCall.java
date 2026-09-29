package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 固定名称的静态方法调用，类引用不绑定，实参保持源码顺序。 */
public record IrStaticCall(IrClassReference classReference, String method, List<IrExpression> arguments,
                           SourceInfo source) implements IrExpression {
    public IrStaticCall {
        Objects.requireNonNull(classReference, "classReference");
        Objects.requireNonNull(method, "method");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(source, "source");
    }
}
