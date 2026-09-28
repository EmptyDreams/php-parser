package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** 赋值的可写目标，与读取变量值的表达式分开表示。 */
public sealed interface IrAssignmentTarget permits IrVariableTarget {
    SourceInfo source();
}
