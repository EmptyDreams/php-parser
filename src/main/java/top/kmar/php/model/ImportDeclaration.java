package top.kmar.php.model;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** 单项命名空间导入；targetName 为无开头反斜杠的完整目标名，declaredAlias 为 null 表示未写别名。 */
public record ImportDeclaration(ImportKind kind, String targetName,
                                @Nullable String declaredAlias, SourceInfo source) {
    public ImportDeclaration {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(targetName, "targetName");
        Objects.requireNonNull(source, "source");
    }

    /** 返回显式别名；未写别名时返回目标名的最后一段。 */
    public String alias() {
        return declaredAlias != null ? declaredAlias : targetName.substring(targetName.lastIndexOf('\\') + 1);
    }
}
