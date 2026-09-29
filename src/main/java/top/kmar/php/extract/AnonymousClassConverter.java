package top.kmar.php.extract;

import org.jetbrains.annotations.Nullable;
import top.kmar.php.*;
import top.kmar.php.ir.IrAnonymousClass;
import top.kmar.php.model.NameReference;

import java.util.ArrayList;
import java.util.List;
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
        return new IrAnonymousClass(
                parent(context.required(node.getExtendsFrom(), node, path + ".extendsFrom"), path + ".extendsFrom"),
                interfaces(context.required(node.getImplementsList(), node, path + ".implementsList"),
                        path + ".implementsList"),
                new ClassMemberConverter(context, expressions).convert(
                        context.required(node.getMembers(), node, path + ".members"), path + ".members"),
                context.source(node));
    }

    private @Nullable NameReference parent(NodeExtendsFrom node, String path) {
        if (node.getClass() == NodeExtendsFrom.class) return null;
        if (!(node instanceof NodeExtendsFrom.Extends)) {
            throw context.error(node, path, "无法识别的父类包装");
        }
        return context.name(context.required(node.getN(), node, path + ".n"), path + ".n");
    }

    private List<NameReference> interfaces(NodeImplementsList node, String path) {
        if (node.getClass() == NodeImplementsList.class) return List.of();
        if (!(node instanceof NodeImplementsList.ImplementsList)) {
            throw context.error(node, path, "无法识别的接口列表包装");
        }
        var list = context.required(node.getNames(), node, path + ".names");
        var values = context.elements(list.getValue(), node, path + ".names");
        if (values.isEmpty()) throw context.error(node, path + ".names", "implements 至少需要一个接口");
        var result = new ArrayList<NameReference>(values.size());
        for (int i = 0; i < values.size(); i++) {
            result.add(context.name(values.get(i), path + ".names[" + i + "]"));
        }
        return result;
    }
}
