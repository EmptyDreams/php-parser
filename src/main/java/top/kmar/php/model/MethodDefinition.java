package top.kmar.php.model;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** 方法声明；body 为 null 表示分号方法体，非 null 但无语句的 SyntaxBody 表示空代码块。 */
public record MethodDefinition(DeclarationId id, DeclarationId ownerId,
                               List<Modifier> declaredModifiers, FunctionSignature signature,
                               @Nullable SyntaxBody body, SourceInfo source) implements ClassMember {
    public MethodDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        declaredModifiers = List.copyOf(declaredModifiers);
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(source, "source");
    }

    /** 返回签名中的原始方法名称。 */
    public String name() {
        return signature.name();
    }
}
