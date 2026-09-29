package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

/** 字符串模板中的有序文本或插值片段，不负责求值和字符串化。 */
public sealed interface IrStringPart permits IrStringText, IrStringInterpolation {
    SourceInfo source();
}
