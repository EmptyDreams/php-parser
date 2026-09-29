package top.kmar.php.ir;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 单个 declare 指令；保留名称拼写和表达式，不限定指令白名单或执行值校验。 */
public record IrDeclareDirective(String name, IrExpression value, SourceInfo source) {
    public IrDeclareDirective {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(source, "source");
    }
}
