package top.kmar.php.ir;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 有序的 declare 指令；body 为 null 表示分号形式，不等同于显式空语句块。 */
public record IrDeclare(List<IrDeclareDirective> directives, @Nullable IrBlock body, SourceInfo source)
        implements IrStatement {
    public IrDeclare {
        directives = List.copyOf(directives);
        if (directives.isEmpty()) throw new IllegalArgumentException("declare 至少需要一个指令");
        Objects.requireNonNull(source, "source");
    }
}
