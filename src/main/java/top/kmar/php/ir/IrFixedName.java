package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 固定的变量或成员名称，保留大小写，不包含语法上的 $ 前缀。 */
public record IrFixedName(String value, SourceInfo source) implements IrAccessName {
    public IrFixedName {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) throw new IllegalArgumentException("value 不能为空");
        Objects.requireNonNull(source, "source");
    }
}
