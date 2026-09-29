package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 保留原始名称的语句标签；不注册或绑定跳转目标，也不合并重复名称。 */
public record IrLabel(String name, SourceInfo source) implements IrStatement {
    public IrLabel {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        Objects.requireNonNull(source, "source");
    }
}
