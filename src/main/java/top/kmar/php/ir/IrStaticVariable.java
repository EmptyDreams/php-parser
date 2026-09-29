package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 一个局部静态变量；名称不含 $，initializer 为 null 表示省略初始化表达式。 */
public record IrStaticVariable(String name, @Nullable IrExpression initializer, SourceInfo source) {
    public IrStaticVariable {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        Objects.requireNonNull(source, "source");
    }
}
