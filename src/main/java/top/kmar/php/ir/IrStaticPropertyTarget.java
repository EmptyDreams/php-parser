package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 静态属性写入目标；类引用和属性名称的表达式不继承外层写入上下文。 */
public record IrStaticPropertyTarget(IrClassReference classReference, IrAccessName property, SourceInfo source)
        implements IrAssignmentTarget {
    public IrStaticPropertyTarget {
        Objects.requireNonNull(classReference, "classReference");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(source, "source");
    }
}
