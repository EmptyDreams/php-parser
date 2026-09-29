package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 具名函数或动态 callable 调用；目标与实参仅保存一次，不进行绑定或参数展开。 */
public record IrCall(IrCallTarget target, List<IrArgument> arguments, SourceInfo source)
        implements IrExpression {
    public IrCall {
        Objects.requireNonNull(target, "target");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(source, "source");
    }
}
