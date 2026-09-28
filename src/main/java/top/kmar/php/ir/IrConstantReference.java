package top.kmar.php.ir;

import top.kmar.php.model.NameReference;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 尚未解析到声明的常量名称引用，保留名称限定形式。 */
public record IrConstantReference(NameReference name, SourceInfo source) implements IrExpression {
    public IrConstantReference {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
    }
}
