package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 完整文件的独立 IR；区段按源码排列，空文件仍包含一个空全局区段。 */
public record IrFile(List<IrNamespaceSection> namespaceSections, SourceInfo source) {
    public IrFile {
        namespaceSections = List.copyOf(namespaceSections);
        if (namespaceSections.isEmpty()) throw new IllegalArgumentException("文件至少需要一个命名空间区段");
        Objects.requireNonNull(source, "source");
    }
}
