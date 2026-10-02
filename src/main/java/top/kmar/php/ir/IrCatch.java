package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.List;
import java.util.Objects;

/** 一个 catch 的有序异常类型、变量名及语句块；变量名不含 $，类型引用不进行名称绑定。 */
public record IrCatch(List<IrNameReference> exceptionTypes, String variableName, IrBlock body, SourceInfo source) {
    public IrCatch {
        exceptionTypes = List.copyOf(exceptionTypes);
        if (exceptionTypes.isEmpty()) throw new IllegalArgumentException("catch 至少需要一个异常类型");
        Objects.requireNonNull(variableName, "variableName");
        if (variableName.isEmpty()) throw new IllegalArgumentException("catch 变量名不能为空");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(source, "source");
    }
}
