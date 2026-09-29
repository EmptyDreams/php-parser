package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** foreach 的单一来源；明确区分求值表达式与引用迭代需要的可写位置。 */
public sealed interface IrForeachIterable permits IrExpressionIterable, IrWritableIterable {
    SourceInfo source();
}
