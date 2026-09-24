package top.kmar.php.model;

import java.util.List;

/** 常量声明的共同信息；类常量和命名空间常量仍由不同模型表达。 */
public interface ConstantDefinition extends Declaration {
    String name();
    List<Modifier> declaredModifiers();
    SyntaxExpression value();
}
