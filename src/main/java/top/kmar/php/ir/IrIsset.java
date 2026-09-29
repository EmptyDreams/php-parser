package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 有序的 isset 检查；操作数具有特殊读取及从左到右短路语义，不是普通调用实参。 */
public record IrIsset(List<IrExpression> expressions, SourceInfo source) implements IrExpression {
    public IrIsset {
        expressions = List.copyOf(expressions);
        if (expressions.isEmpty()) throw new IllegalArgumentException("isset 至少需要一个表达式");
        Objects.requireNonNull(source, "source");
    }
}
