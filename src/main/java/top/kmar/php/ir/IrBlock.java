package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 按源码顺序排列的语句块；空列表表示存在但不含语句的块。 */
public record IrBlock(List<IrStatement> statements, SourceInfo source) implements IrStatement {
    public IrBlock {
        statements = List.copyOf(statements);
        Objects.requireNonNull(source, "source");
    }
}
