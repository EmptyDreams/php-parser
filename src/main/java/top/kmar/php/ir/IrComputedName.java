package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 由表达式提供的查找名称；即使表达式是字符串字面量，也不折叠为固定名称。 */
public record IrComputedName(IrExpression expression, SourceInfo source) implements IrAccessName {
    public IrComputedName {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
