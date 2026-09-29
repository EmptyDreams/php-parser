package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 文件包含表达式；保留包含种类，不解析路径、加载文件或执行代码。 */
public record IrInclude(IncludeKind kind, IrExpression expression, SourceInfo source) implements IrExpression {
    public IrInclude {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
