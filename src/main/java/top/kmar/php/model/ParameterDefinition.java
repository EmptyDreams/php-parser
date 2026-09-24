package top.kmar.php.model;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 参数声明；declaredType 为 null 表示未声明类型，defaultValue 为 null 表示没有默认值。
 * 显式的 PHP null 默认值仍使用非 null 的 SyntaxExpression 保存。
 */
public record ParameterDefinition(String name, @Nullable TypeReference declaredType,
                                  boolean byReference, boolean variadic,
                                  @Nullable SyntaxExpression defaultValue, SourceInfo source) {
    public ParameterDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
    }
}
