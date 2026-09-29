package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** 可写访问链的基底或引用赋值的来源，区分单个可写位置与调用产生的表达式结果。 */
public sealed interface IrWriteBase permits IrAssignmentTarget, IrExpressionWriteBase {
    SourceInfo source();
}
