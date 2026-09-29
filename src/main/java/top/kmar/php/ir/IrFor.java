package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/**
 * 保留 for 的三组有序表达式；条件为空表示无条件限制，非空时由最后一项决定条件值。
 * 前面的条件表达式仍需保留，不能将列表改写为逻辑与或丢弃可能存在的副作用。
 */
public record IrFor(List<IrExpression> initializers, List<IrExpression> conditions,
                    List<IrExpression> updates, IrBlock body, SourceInfo source) implements IrStatement {
    public IrFor {
        initializers = List.copyOf(initializers);
        conditions = List.copyOf(conditions);
        updates = List.copyOf(updates);
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(source, "source");
    }
}
