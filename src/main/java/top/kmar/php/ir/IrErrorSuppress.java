package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 错误抑制表达式，保留 @ 的作用范围；不影响转换阶段的结构诊断。 */
public record IrErrorSuppress(IrExpression expression, SourceInfo source) implements IrExpression {
    public IrErrorSuppress {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
