package top.kmar.php.ir;

import top.kmar.php.model.Modifier;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 单个类常量；保留初始化表达式，不验证编译期常量合法性。 */
public record IrClassConstant(String name, List<Modifier> declaredModifiers, IrExpression value,
                              SourceInfo source) implements IrClassMember {
    public IrClassConstant {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        declaredModifiers = List.copyOf(declaredModifiers);
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
