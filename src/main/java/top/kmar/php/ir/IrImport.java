package top.kmar.php.ir;

import top.kmar.php.model.ImportKind;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 单项命名空间导入；目标不含开头反斜杠，alias 为已确定的有效别名，不进行名称绑定。 */
public record IrImport(ImportKind kind, String targetName, String alias, SourceInfo source) {
    public IrImport {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(source, "source");
        if (targetName.isEmpty()) throw new IllegalArgumentException("targetName 不能为空");
        if (alias.isEmpty()) throw new IllegalArgumentException("alias 不能为空");
    }
}
