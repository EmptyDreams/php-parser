package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 保留分支顺序及贯穿关系的 switch；不合并条件、不补充 break 或绑定跳转目标。 */
public record IrSwitch(IrExpression condition, List<IrSwitchCase> cases, SourceInfo source)
        implements IrStatement {
    public IrSwitch {
        Objects.requireNonNull(condition, "condition");
        cases = List.copyOf(cases);
        Objects.requireNonNull(source, "source");
    }
}
