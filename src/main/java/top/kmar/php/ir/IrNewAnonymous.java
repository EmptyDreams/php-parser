package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 匿名类实例化；构造实参与类定义分开存储，不生成名称或解析构造方法。 */
public record IrNewAnonymous(IrAnonymousClass definition, List<IrArgument> arguments, SourceInfo source)
        implements IrExpression {
    public IrNewAnonymous {
        Objects.requireNonNull(definition, "definition");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(source, "source");
    }
}
