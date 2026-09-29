package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** 函数调用目标，区分需要名称解析的函数引用和提供 callable 值的表达式。 */
public sealed interface IrCallTarget permits IrNamedCallTarget, IrExpressionCallTarget {
    SourceInfo source();
}
