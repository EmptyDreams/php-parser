package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 实例方法调用，接收者、方法名称和各实参仅保存一次，实参保持源码顺序。 */
public record IrMethodCall(IrExpression receiver, IrAccessName method, List<IrArgument> arguments, SourceInfo source)
        implements IrExpression {
    public IrMethodCall {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(method, "method");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(source, "source");
    }
}
