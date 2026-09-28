package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/**
 * 短路逻辑表达式；先计算 left，AND 仅在其为真时计算 right，OR 仅在其为假时计算 right。
 * 条件与结果采用 PHP 的布尔语义，不能按普通二元节点同时求值两个子树。
 */
public record IrLogical(LogicalOperator operator, IrExpression left, IrExpression right, SourceInfo source)
        implements IrExpression {
    public IrLogical {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(source, "source");
    }
}
