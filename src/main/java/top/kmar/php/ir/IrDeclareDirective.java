package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 单个 declare 指令；标准指令规范化为枚举，unknownName 仅为未知指令保留原名，不执行设置或校验值。 */
public record IrDeclareDirective(DeclareDirectiveKind kind, @Nullable String unknownName,
                                 IrExpression value, SourceInfo source) {
    public IrDeclareDirective {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
        if (kind == DeclareDirectiveKind.UNKNOWN) {
            Objects.requireNonNull(unknownName, "unknownName");
            if (unknownName.isEmpty()) throw new IllegalArgumentException("未知 declare 指令名称不能为空");
        } else if (unknownName != null) {
            throw new IllegalArgumentException("标准 declare 指令不能携带未知名称");
        }
    }

    /** 标准指令的便捷构造器。 */
    public IrDeclareDirective(DeclareDirectiveKind kind, IrExpression value, SourceInfo source) {
        this(kind, null, value, source);
    }
}
