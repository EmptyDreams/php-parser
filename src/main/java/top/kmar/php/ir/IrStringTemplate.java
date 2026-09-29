package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 至少含一个插值的字符串模板；片段按源码顺序保存，纯文本使用 IrStringLiteral。 */
public record IrStringTemplate(List<IrStringPart> parts, SourceInfo source) implements IrExpression {
    public IrStringTemplate {
        parts = List.copyOf(parts);
        Objects.requireNonNull(source, "source");
        if (parts.stream().noneMatch(IrStringInterpolation.class::isInstance)) {
            throw new IllegalArgumentException("字符串模板必须包含至少一个插值片段");
        }
    }
}
