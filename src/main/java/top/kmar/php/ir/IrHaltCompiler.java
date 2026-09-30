package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 保留 __halt_compiler 标记的位置；词法已忽略的尾随数据不恢复为 IR 内容。 */
public record IrHaltCompiler(SourceInfo source) implements IrStatement {
    public IrHaltCompiler {
        Objects.requireNonNull(source, "source");
    }
}
