package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 固定名称的静态属性写入目标，属性名保留拼写且不包含 $。 */
public record IrStaticPropertyTarget(IrClassReference classReference, String property, SourceInfo source)
        implements IrAssignmentTarget {
    public IrStaticPropertyTarget {
        Objects.requireNonNull(classReference, "classReference");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(source, "source");
    }
}
