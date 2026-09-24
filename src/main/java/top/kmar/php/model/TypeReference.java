package top.kmar.php.model;

import java.util.Objects;

/** 源码中的类型声明；保留可空标记，不执行类型推断或类名绑定。 */
public record TypeReference(NameReference name, boolean nullable, SourceInfo source) {
    public TypeReference {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
    }
}
