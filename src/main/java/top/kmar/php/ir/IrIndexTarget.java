package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/**
 * 简单变量根链中的可写下标；index 为 null 表示 [] 追加，不是读取缺省下标。
 * 目标链和各下标表达式只保存一次，由上层按写入或读改写语义使用。
 */
public record IrIndexTarget(IrAssignmentTarget base, @Nullable IrExpression index, SourceInfo source)
        implements IrAssignmentTarget {
    public IrIndexTarget {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(source, "source");
    }
}
