package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 单个标准 declare 指令；保存规范化种类和原值表达式，不执行指令或校验值。 */
public record IrDeclareDirective(DeclareDirectiveKind kind, IrExpression value, SourceInfo source) {
    public IrDeclareDirective {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
