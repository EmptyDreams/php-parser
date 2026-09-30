package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 单项顶层常量声明；名称保留原拼写，初始化值只转换结构，不限定名称或求值。 */
public record IrConstantDeclaration(String name, IrExpression value, SourceInfo source) implements IrStatement {
    public IrConstantDeclaration {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
