package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 下标读取；方括号与花括号统一表示，index 不可缺省，不预判容器的运行时类型。 */
public record IrIndex(IrExpression base, IrExpression index, SourceInfo source) implements IrExpression {
    public IrIndex {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(source, "source");
    }
}
