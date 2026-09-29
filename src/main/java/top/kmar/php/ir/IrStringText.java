package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 字符串模板中已经解码的连续文本。 */
public record IrStringText(ByteString value, SourceInfo source) implements IrStringPart {
    public IrStringText {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
