package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 单个调用实参；unpack 表示 ... 解包，保留输入表达式而不实际展开参数。 */
public record IrArgument(IrExpression expression, boolean unpack, SourceInfo source) {
    public IrArgument {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
