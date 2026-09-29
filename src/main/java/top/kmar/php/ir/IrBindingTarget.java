package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** 接收一个值的单个写目标或解构模式；模式本身不是可寻址位置。 */
public sealed interface IrBindingTarget permits IrAssignmentTarget, IrDestructuringPattern {
    SourceInfo source();
}
