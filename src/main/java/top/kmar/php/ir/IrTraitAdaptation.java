package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** trait 的优先选择或别名规则。 */
public sealed interface IrTraitAdaptation permits IrTraitPrecedence, IrTraitAlias {
    SourceInfo source();
}
