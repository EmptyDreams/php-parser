package top.kmar.php.extract;

import top.kmar.php.*;
import top.kmar.php.ir.*;

import java.util.Objects;

/** 具名声明留在原语句位置；直接转换签名及内容，不经过第一阶段索引或含 AST 的定义。 */
final class NamedDeclarationConverter {
    private final ConversionContext context;
    private final ExpressionConverter expressions;

    NamedDeclarationConverter(ConversionContext context, ExpressionConverter expressions) {
        this.context = Objects.requireNonNull(context, "context");
        this.expressions = Objects.requireNonNull(expressions, "expressions");
    }

    IrFunctionDeclaration convert(NodeFunctionDeclarationStatement node, String path) {
        if (!(node instanceof NodeFunctionDeclarationStatement.FuncDecl)) {
            throw context.error(node, path, "无法识别的具名函数声明");
        }
        var signatures = new CallableSignatureConverter(context, expressions);
        return new IrFunctionDeclaration(
                context.text(node.getName(), node, path + ".name"),
                signatures.parameters(context.required(node.getParams(), node, path + ".params"), path + ".params"),
                signatures.returnType(context.required(node.getReturnType(), node, path + ".returnType"),
                        path + ".returnType"),
                signatures.marker(node.getReturnsRef(), "&", node, path + ".returnsRef"),
                // 函数没有独立的主体包装，块范围取语句列表，不借用整个声明的范围。
                new StatementConverter(context, expressions).sequenceBlock(node.getStmts(), node, path + ".stmts"),
                context.source(node));
    }

    IrClassDeclaration convert(NodeClassDeclarationStatement node, String path) {
        ModifierConverter.ClassFlags flags;
        if (node instanceof NodeClassDeclarationStatement.ClassWithModifiers) {
            flags = new ModifierConverter(context).classModifiers(
                    context.required(node.getMods(), node, path + ".mods"), path + ".mods");
        } else if (node instanceof NodeClassDeclarationStatement.Class) {
            flags = new ModifierConverter.ClassFlags(false, false);
        } else {
            throw context.error(node, path, "无法识别的具名类声明");
        }
        var inheritance = new TypeInheritanceConverter(context);
        return new IrClassDeclaration(
                context.text(node.getName(), node, path + ".name"), flags.isAbstract(), flags.isFinal(),
                inheritance.parent(context.required(node.getExtendsFrom(), node, path + ".extendsFrom"),
                        path + ".extendsFrom"),
                inheritance.interfaces(context.required(node.getImplementsList(), node, path + ".implementsList"),
                        path + ".implementsList"),
                new ClassMemberConverter(context, expressions, false).convert(
                        context.required(node.getMembers(), node, path + ".members"), path + ".members"),
                context.source(node));
    }

    IrInterfaceDeclaration convert(NodeInterfaceDeclarationStatement node, String path) {
        if (!(node instanceof NodeInterfaceDeclarationStatement.InterfaceDecl)) {
            throw context.error(node, path, "无法识别的接口声明");
        }
        return new IrInterfaceDeclaration(
                context.text(node.getName(), node, path + ".name"),
                new TypeInheritanceConverter(context).parentTypes(
                        context.required(node.getExtendsFrom(), node, path + ".extendsFrom"), path + ".extendsFrom"),
                new ClassMemberConverter(context, expressions, true).convert(
                        context.required(node.getMembers(), node, path + ".members"), path + ".members"),
                context.source(node));
    }

    IrTraitDeclaration convert(NodeTraitDeclarationStatement node, String path) {
        if (!(node instanceof NodeTraitDeclarationStatement.TraitDecl)) {
            throw context.error(node, path, "无法识别的 trait 声明");
        }
        return new IrTraitDeclaration(
                context.text(node.getName(), node, path + ".name"),
                new ClassMemberConverter(context, expressions, false).convert(
                        context.required(node.getMembers(), node, path + ".members"), path + ".members"),
                context.source(node));
    }
}
