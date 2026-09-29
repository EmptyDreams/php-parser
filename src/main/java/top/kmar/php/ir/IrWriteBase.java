package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** 可写访问链的基底，区分可直接赋值的目标与仅可承接后续访问的表达式结果。 */
public sealed interface IrWriteBase permits IrAssignmentTarget, IrExpressionWriteBase {
    SourceInfo source();
}
