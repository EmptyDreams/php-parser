package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 具名函数声明的原位结构；名称不限定、不注册符号，函数体不提升到外层语句序列。 */
public record IrFunctionDeclaration(String name, List<IrParameter> parameters,
                                    @Nullable IrTypeReference returnType, boolean returnsReference,
                                    IrBlock body, SourceInfo source) implements IrStatement {
    public IrFunctionDeclaration {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        parameters = List.copyOf(parameters);
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(source, "source");
    }
}
