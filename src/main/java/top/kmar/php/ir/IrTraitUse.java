package top.kmar.php.ir;

import top.kmar.php.model.NameReference;
import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 有序 trait 引用及适配规则；不展开 trait 或解决成员冲突。 */
public record IrTraitUse(List<NameReference> traits, List<IrTraitAdaptation> adaptations, SourceInfo source)
        implements IrClassMember {
    public IrTraitUse {
        traits = List.copyOf(traits);
        if (traits.isEmpty()) throw new IllegalArgumentException("traits 不能为空");
        adaptations = List.copyOf(adaptations);
        Objects.requireNonNull(source, "source");
    }
}
