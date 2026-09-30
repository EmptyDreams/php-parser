package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 内置声明类型；原始名称拼写保留在 AST 中。 */
public record IrBuiltinType(BuiltinTypeKind kind, SourceInfo source) implements IrTypeName {
    public IrBuiltinType {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
    }
}
