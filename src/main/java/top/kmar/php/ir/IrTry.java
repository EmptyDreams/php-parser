package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/**
 * try 及有序 catch、可选 finally；finallyBlock 为 null 表示缺省，不等同于显式空块。
 * 仅表达解析所得结构，不额外要求必须包含 catch 或 finally。
 */
public record IrTry(IrBlock body, List<IrCatch> catches, @Nullable IrBlock finallyBlock, SourceInfo source)
        implements IrStatement {
    public IrTry {
        Objects.requireNonNull(body, "body");
        catches = List.copyOf(catches);
        Objects.requireNonNull(source, "source");
    }
}
