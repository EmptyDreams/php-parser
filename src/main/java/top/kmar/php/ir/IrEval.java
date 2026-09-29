package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** eval 表达式；不执行或重新解析操作数代表的代码。 */
public record IrEval(IrExpression expression, SourceInfo source) implements IrExpression {
    public IrEval {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
