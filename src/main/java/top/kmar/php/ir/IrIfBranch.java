package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** if 或 elseif 的一个条件和对应语句块，条件结果按 PHP 的布尔语义判断。 */
public record IrIfBranch(IrExpression condition, IrBlock body, SourceInfo source) {
    public IrIfBranch {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(source, "source");
    }
}
