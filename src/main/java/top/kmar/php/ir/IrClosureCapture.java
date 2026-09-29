package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 一个显式 use 捕获项；名称不含 $，引用标记不意味着已完成变量绑定。 */
public record IrClosureCapture(String name, boolean byReference, SourceInfo source) {
    public IrClosureCapture {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        Objects.requireNonNull(source, "source");
    }
}
