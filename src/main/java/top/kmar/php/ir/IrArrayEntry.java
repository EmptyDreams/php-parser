package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

/** 有序数组条目；key 为 null 表示未写键，值条目与引用条目由不同类型表示。 */
public sealed interface IrArrayEntry permits IrValueArrayEntry, IrReferenceArrayEntry {
    @Nullable IrExpression key();
    SourceInfo source();
}
