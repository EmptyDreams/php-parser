package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 反引号命令表达式；保留命令内容，不执行命令或降级为普通函数调用。 */
public record IrShellExec(IrExpression command, SourceInfo source) implements IrExpression {
    public IrShellExec {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(source, "source");
    }
}
