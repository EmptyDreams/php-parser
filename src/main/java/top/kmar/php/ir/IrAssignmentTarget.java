package top.kmar.php.ir;

/** 赋值的可写目标，与读取变量值的表达式分开表示。 */
public sealed interface IrAssignmentTarget extends IrWriteBase
        permits IrVariableTarget, IrIndexTarget, IrPropertyTarget, IrStaticPropertyTarget {
}
