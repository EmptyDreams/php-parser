package top.kmar.php.ir;

import top.kmar.php.model.ImportDeclaration;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 一条命名空间导入语句；分组导入展开为有序条目，不提升、绑定名称或合并重复项。 */
public record IrUse(List<ImportDeclaration> imports, SourceInfo source) implements IrStatement {
    public IrUse {
        imports = List.copyOf(imports);
        if (imports.isEmpty()) throw new IllegalArgumentException("use 至少需要一个导入项");
        Objects.requireNonNull(source, "source");
    }
}
