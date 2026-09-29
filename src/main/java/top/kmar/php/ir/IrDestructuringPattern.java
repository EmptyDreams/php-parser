package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 独立的解构模式；保留嵌套、键与跳过位置，不作为写链基底或单个更新目标。 */
public record IrDestructuringPattern(List<IrDestructuringSlot> slots, SourceInfo source)
        implements IrBindingTarget {
    public IrDestructuringPattern {
        slots = List.copyOf(slots);
        Objects.requireNonNull(source, "source");
        Boolean keyed = null;
        boolean hasSkippedSlot = false;
        for (IrDestructuringSlot slot : slots) {
            if (slot.target() == null) {
                hasSkippedSlot = true;
                continue;
            }
            boolean slotKeyed = slot.key() != null;
            if (keyed != null && keyed != slotKeyed) {
                throw new IllegalArgumentException("同层解构不能混用有键和无键条目");
            }
            keyed = slotKeyed;
        }
        if (keyed == null) throw new IllegalArgumentException("解构模式必须至少包含一个目标");
        if (keyed && hasSkippedSlot) throw new IllegalArgumentException("有键解构不能包含跳过槽");
    }
}
