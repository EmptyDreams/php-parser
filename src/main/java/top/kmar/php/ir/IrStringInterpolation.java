package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 字符串模板中的一个插值表达式，保留其求值位置而不提前字符串化。 */
public record IrStringInterpolation(IrExpression expression, SourceInfo source) implements IrStringPart {
    public IrStringInterpolation {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(source, "source");
    }
}
