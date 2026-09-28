package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 简单变量的读取表达式，name 不包含开头的 $。 */
public record IrVariable(String name, SourceInfo source) implements IrExpression {
    public IrVariable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
    }
}
