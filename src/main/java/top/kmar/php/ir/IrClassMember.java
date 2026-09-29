package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** 独立类成员；不携带声明索引、owner 或原始 AST。 */
public sealed interface IrClassMember permits IrMethod, IrProperty, IrClassConstant, IrTraitUse {
    SourceInfo source();
}
