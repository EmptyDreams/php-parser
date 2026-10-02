package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.*;
import top.kmar.php.ir.IrNameReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 具名类型和匿名类共享的继承声明读取；不绑定名称或检查继承关系。 */
final class TypeInheritanceConverter {
    private final ConversionContext context;

    TypeInheritanceConverter(ConversionContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Nullable IrNameReference parent(NodeExtendsFrom node, String path) {
        if (node.getClass() == NodeExtendsFrom.class) return null;
        if (!(node instanceof NodeExtendsFrom.Extends)) {
            throw context.error(node, path, "无法识别的父类包装");
        }
        return context.name(context.required(node.getN(), node, path + ".n"), path + ".n");
    }

    List<IrNameReference> interfaces(NodeImplementsList node, String path) {
        if (node.getClass() == NodeImplementsList.class) return List.of();
        if (!(node instanceof NodeImplementsList.ImplementsList)) {
            throw context.error(node, path, "无法识别的接口列表包装");
        }
        return names(context.required(node.getNames(), node, path + ".names"), node,
                path + ".names", "implements 至少需要一个接口");
    }

    List<IrNameReference> parentTypes(NodeInterfaceExtendsList node, String path) {
        if (node.getClass() == NodeInterfaceExtendsList.class) return List.of();
        if (!(node instanceof NodeInterfaceExtendsList.ExtendsList)) {
            throw context.error(node, path, "无法识别的接口继承列表包装");
        }
        return names(context.required(node.getNames(), node, path + ".names"), node,
                path + ".names", "接口 extends 至少需要一个父接口");
    }

    private List<IrNameReference> names(NodeListNodeName list, AstNode origin, String path, String emptyReason) {
        var values = context.elements(list.getValue(), origin, path);
        if (values.isEmpty()) throw context.error(origin, path, emptyReason);
        var result = new ArrayList<IrNameReference>(values.size());
        for (int i = 0; i < values.size(); i++) {
            result.add(context.name(values.get(i), path + "[" + i + "]"));
        }
        return result;
    }
}
