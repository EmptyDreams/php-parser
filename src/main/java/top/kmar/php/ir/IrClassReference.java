package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** 尚未绑定的类引用，区分具名类、依赖上下文的特殊类名称和计算式类引用。 */
public sealed interface IrClassReference permits IrNamedClassReference, IrSpecialClassReference,
        IrDynamicClassReference {
    SourceInfo source();
}
