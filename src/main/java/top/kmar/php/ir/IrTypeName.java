package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** 已分类但尚未绑定的声明类型名称；来源仅覆盖名称自身，不包含可空标记。 */
public sealed interface IrTypeName permits IrBuiltinType, IrSpecialType, IrNamedType {
    SourceInfo source();
}
