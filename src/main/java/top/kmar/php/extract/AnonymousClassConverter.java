package top.kmar.php.extract;

import top.kmar.php.*;
import top.kmar.php.ir.IrAnonymousClass;

import java.util.Objects;

/** 仅转换匿名类定义；构造实参由实例化表达式持有，不生成声明 ID 或合成名称。 */
final class AnonymousClassConverter {
    private final ConversionContext context;
    private final ExpressionConverter expressions;

    AnonymousClassConverter(ConversionContext context, ExpressionConverter expressions) {
        this.context = Objects.requireNonNull(context, "context");
        this.expressions = Objects.requireNonNull(expressions, "expressions");
    }

    IrAnonymousClass convert(NodeAnonymousClass node, String path) {
        if (!(node instanceof NodeAnonymousClass.AnonymousClass)) {
            throw context.error(node, path, "无法识别的匿名类定义");
        }
        var inheritance = new TypeInheritanceConverter(context);
        return new IrAnonymousClass(
                inheritance.parent(context.required(node.getExtendsFrom(), node, path + ".extendsFrom"),
                        path + ".extendsFrom"),
                inheritance.interfaces(context.required(node.getImplementsList(), node, path + ".implementsList"),
                        path + ".implementsList"),
                new ClassMemberConverter(context, expressions, false).convert(
                        context.required(node.getMembers(), node, path + ".members"), path + ".members"),
                context.source(node));
    }
}
