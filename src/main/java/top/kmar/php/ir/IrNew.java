package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 非匿名类实例化，类引用可动态提供；构造实参保持源码顺序，不解析构造方法。 */
public record IrNew(IrClassReference classReference, List<IrArgument> arguments, SourceInfo source)
        implements IrExpression {
    public IrNew {
        Objects.requireNonNull(classReference, "classReference");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(source, "source");
    }
}
