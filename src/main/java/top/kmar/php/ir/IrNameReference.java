package top.kmar.php.ir;

import top.kmar.php.model.NameForm;
import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 未绑定的名称引用；名称主体不含外层限定前缀，来源仍覆盖完整源码名称。 */
public record IrNameReference(String value, NameForm form, SourceInfo source) {
    public IrNameReference {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(source, "source");
        if (value.isEmpty() || value.startsWith("\\") || value.endsWith("\\") || value.contains("\\\\")) {
            throw new IllegalArgumentException("名称主体不能为空或包含空名称段");
        }
        boolean qualified = value.indexOf('\\') >= 0;
        if (form == NameForm.UNQUALIFIED && qualified || form == NameForm.QUALIFIED && !qualified) {
            throw new IllegalArgumentException("名称主体与限定形式不一致");
        }
    }
}
