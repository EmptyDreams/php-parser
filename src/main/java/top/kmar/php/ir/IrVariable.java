package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 变量读取，名称可为固定名称或计算式名称，与可写目标分开表示。 */
public record IrVariable(IrAccessName name, SourceInfo source) implements IrExpression {
    public IrVariable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
    }
}
