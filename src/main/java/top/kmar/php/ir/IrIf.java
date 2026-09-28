package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/**
 * if 与 elseif 构成的有序分支链；依次检查条件，执行首个满足条件的分支。
 * elseBlock 为 null 表示没有 else，存在的空块仍保留为非 null 的 IrBlock。
 */
public record IrIf(List<IrIfBranch> branches, @Nullable IrBlock elseBlock, SourceInfo source)
        implements IrStatement {
    public IrIf {
        branches = List.copyOf(branches);
        if (branches.isEmpty()) throw new IllegalArgumentException("if 至少需要一个条件分支");
        Objects.requireNonNull(source, "source");
    }
}
