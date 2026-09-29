package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 固定名称的实例方法调用，接收者和各实参仅保存一次，实参保持源码顺序。 */
public record IrMethodCall(IrExpression receiver, String method, List<IrExpression> arguments, SourceInfo source)
        implements IrExpression {
    public IrMethodCall {
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(method, "method");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(source, "source");
    }
}
