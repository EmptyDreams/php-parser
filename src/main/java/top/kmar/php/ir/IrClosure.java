package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 匿名函数值；保留显式捕获和声明标志，不绑定变量或推导生成器状态。 */
public record IrClosure(List<IrParameter> parameters, @Nullable IrTypeReference returnType,
                        boolean returnsReference, boolean isStatic, List<IrClosureCapture> captures,
                        IrBlock body, SourceInfo source) implements IrExpression {
    public IrClosure {
        parameters = List.copyOf(parameters);
        captures = List.copyOf(captures);
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(source, "source");
    }
}
