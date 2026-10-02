package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 显式 trait 方法的优先规则；被排除的 trait 保留源码顺序和重复项。 */
public record IrTraitPrecedence(IrTraitMethodReference method, List<IrNameReference> insteadOf, SourceInfo source)
        implements IrTraitAdaptation {
    public IrTraitPrecedence {
        Objects.requireNonNull(method, "method");
        if (method.trait() == null) throw new IllegalArgumentException("method 必须指定 trait");
        insteadOf = List.copyOf(insteadOf);
        if (insteadOf.isEmpty()) throw new IllegalArgumentException("insteadOf 不能为空");
        Objects.requireNonNull(source, "source");
    }
}
