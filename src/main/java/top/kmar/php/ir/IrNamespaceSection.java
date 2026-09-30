package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 命名空间语法区段；空名称表示全局区段，导入与声明留在 body 中且同名区段不合并。 */
public record IrNamespaceSection(String namespaceName, IrBlock body, SourceInfo source) {
    public IrNamespaceSection {
        Objects.requireNonNull(namespaceName, "namespaceName");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(source, "source");
    }
}
