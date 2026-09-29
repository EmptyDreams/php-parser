package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 局部静态变量声明；保留条目顺序和重复名称，不创建存储或执行初始化。 */
public record IrStaticVariables(List<IrStaticVariable> variables, SourceInfo source) implements IrStatement {
    public IrStaticVariables {
        variables = List.copyOf(variables);
        if (variables.isEmpty()) throw new IllegalArgumentException("static 至少需要一个变量");
        Objects.requireNonNull(source, "source");
    }
}
