package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 解构赋值；右侧表达式只保存一次，不在基础 IR 中展开为多次下标读取。 */
public record IrDestructuringAssignment(IrDestructuringPattern pattern, IrExpression value,
                                        SourceInfo source) implements IrExpression {
    public IrDestructuringAssignment {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
