package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 有序的 global 变量绑定声明；保留动态名称，不执行全局符号查找或引用绑定。 */
public record IrGlobal(List<IrVariableTarget> variables, SourceInfo source) implements IrStatement {
    public IrGlobal {
        variables = List.copyOf(variables);
        if (variables.isEmpty()) throw new IllegalArgumentException("global 至少需要一个变量");
        Objects.requireNonNull(source, "source");
    }
}
