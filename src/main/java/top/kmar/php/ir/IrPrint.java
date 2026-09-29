package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** print 表达式；保留操作数，不输出内容或预先生成返回值。 */
public record IrPrint(IrExpression expression, SourceInfo source) implements IrExpression {
    public IrPrint {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
