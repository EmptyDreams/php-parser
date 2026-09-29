package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 未绑定上下文的魔术常量，不在基础 IR 阶段推算其值。 */
public record IrMagicConstant(MagicConstantKind kind, SourceInfo source) implements IrExpression {
    public IrMagicConstant {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
    }
}
