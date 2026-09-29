package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 生成器委托表达式；保留输入和返回值边界，不展开为 foreach 或模拟迭代。 */
public record IrYieldFrom(IrExpression expression, SourceInfo source) implements IrExpression {
    public IrYieldFrom {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
