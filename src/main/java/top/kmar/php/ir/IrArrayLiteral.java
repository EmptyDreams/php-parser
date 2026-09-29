package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 数组构造表达式；条目保持源码顺序和重复键，不分配自动键或执行键转换。 */
public record IrArrayLiteral(List<IrArrayEntry> entries, SourceInfo source) implements IrExpression {
    public IrArrayLiteral {
        entries = List.copyOf(entries);
        Objects.requireNonNull(source, "source");
    }
}
