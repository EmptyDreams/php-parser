package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 简单变量赋值目标，name 不包含开头的 $。 */
public record IrVariableTarget(String name, SourceInfo source) implements IrAssignmentTarget {
    public IrVariableTarget {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
    }
}
