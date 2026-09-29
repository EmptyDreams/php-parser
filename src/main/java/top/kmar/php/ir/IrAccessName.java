package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** 变量或成员的查找名称；计算式名称保留表达式，不提前求值或转换为字符串。 */
public sealed interface IrAccessName permits IrFixedName, IrComputedName {
    SourceInfo source();
}
