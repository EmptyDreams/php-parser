package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 对象克隆表达式，不执行复制或查找魔术方法。 */
public record IrClone(IrExpression expression, SourceInfo source) implements IrExpression {
    public IrClone {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
