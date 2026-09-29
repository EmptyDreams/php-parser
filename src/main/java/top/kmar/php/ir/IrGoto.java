package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 尚未绑定跳转目标的 goto；保留标签拼写，不验证控制流跨越或生成跳转指令。 */
public record IrGoto(String label, SourceInfo source) implements IrStatement {
    public IrGoto {
        Objects.requireNonNull(label, "label");
        if (label.isEmpty()) throw new IllegalArgumentException("label 不能为空");
        Objects.requireNonNull(source, "source");
    }
}
