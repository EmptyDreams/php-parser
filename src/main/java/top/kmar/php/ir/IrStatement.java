package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** 独立于解析器生成节点的语句，所有变体保留已知来源信息。 */
public sealed interface IrStatement permits IrBlock, IrExpressionStatement, IrReturn, IrEcho, IrIf, IrEmpty {
    SourceInfo source();
}
