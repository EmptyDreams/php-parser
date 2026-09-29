package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 静态方法调用，类引用与方法名称不绑定，实参保持源码顺序。 */
public record IrStaticCall(IrClassReference classReference, IrAccessName method, List<IrArgument> arguments,
                           SourceInfo source) implements IrExpression {
    public IrStaticCall {
        Objects.requireNonNull(classReference, "classReference");
        Objects.requireNonNull(method, "method");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(source, "source");
    }
}
