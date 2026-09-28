package top.kmar.php.ir;

import top.kmar.php.model.NameReference;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 按名称发起的函数调用；实参保持原顺序，函数名称尚未解析或绑定。 */
public record IrCall(NameReference name, List<IrExpression> arguments, SourceInfo source)
        implements IrExpression {
    public IrCall {
        Objects.requireNonNull(name, "name");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(source, "source");
    }
}
