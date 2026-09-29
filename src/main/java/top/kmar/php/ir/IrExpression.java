package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** 独立表达式模型；短路操作和赋值分别由专门变体表达，保留原语法树的结合关系。 */
public sealed interface IrExpression permits IrLiteral, IrIntegerLiteral, IrFloatLiteral,
        IrStringLiteral, IrStringTemplate, IrMagicConstant,
        IrVariable, IrConstantReference,
        IrAssignment, IrReferenceAssignment, IrDestructuringAssignment,
        IrCompoundAssignment, IrUpdate, IrArrayLiteral, IrIndex,
        IrUnary, IrBinary, IrLogical, IrCoalesce, IrConditional, IrCall,
        IrNew, IrNewAnonymous, IrClone, IrInstanceOf, IrPropertyAccess, IrStaticPropertyAccess,
        IrMethodCall, IrStaticCall, IrClassConstantReference, IrClassName,
        IrIsset, IrEmptyCheck, IrCast, IrErrorSuppress, IrPrint, IrInclude, IrEval, IrExit,
        IrClosure, IrYield, IrYieldFrom {
    SourceInfo source();
}
