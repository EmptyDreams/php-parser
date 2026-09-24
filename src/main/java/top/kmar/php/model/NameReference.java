package top.kmar.php.model;

import java.util.Objects;

/** 未绑定的名称引用；spelling 保留原拼写以及反斜杠、namespace 等语法前缀。 */
public record NameReference(String spelling, NameForm form, SourceInfo source) {
    public NameReference {
        Objects.requireNonNull(spelling, "spelling");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(source, "source");
    }
}
