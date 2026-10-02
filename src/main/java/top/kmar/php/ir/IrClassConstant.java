package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 单个类常量；保存有效可见性和初始化表达式，不验证编译期常量合法性。 */
public record IrClassConstant(String name, Visibility visibility, IrExpression value,
                              SourceInfo source) implements IrClassMember {
    public IrClassConstant {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
