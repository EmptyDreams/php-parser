package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 引用 foreach 从可写位置取得迭代源；不另存同一访问链的读取表达式。 */
public record IrWritableIterable(IrAssignmentTarget target, SourceInfo source) implements IrForeachIterable {
    public IrWritableIterable {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
    }
}
