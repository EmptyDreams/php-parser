package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** 尚未绑定的类引用，区分具名类和依赖上下文的特殊类名称。 */
public sealed interface IrClassReference permits IrNamedClassReference, IrSpecialClassReference {
    SourceInfo source();
}
