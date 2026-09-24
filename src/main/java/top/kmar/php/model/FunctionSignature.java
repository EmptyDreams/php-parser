package top.kmar.php.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** 函数和方法共用的签名；参数保持原声明顺序，returnType 为 null 表示未声明返回类型。 */
public record FunctionSignature(String name, List<ParameterDefinition> parameters,
                                @Nullable TypeReference returnType, boolean returnsReference) {
    public FunctionSignature {
        Objects.requireNonNull(name, "name");
        parameters = List.copyOf(parameters);
    }
}
