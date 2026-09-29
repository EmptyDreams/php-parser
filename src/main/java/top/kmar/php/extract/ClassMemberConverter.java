package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.*;
import top.kmar.php.ir.*;
import top.kmar.php.model.Modifier;
import top.kmar.php.model.NameReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 类型成员的结构转换；保留声明顺序，不注册符号、不求值或执行 trait 合并。 */
final class ClassMemberConverter {
    private final ConversionContext context;
    private final ExpressionConverter expressions;
    private final CallableSignatureConverter signatures;

    ClassMemberConverter(ConversionContext context, ExpressionConverter expressions) {
        this.context = Objects.requireNonNull(context, "context");
        this.expressions = Objects.requireNonNull(expressions, "expressions");
        this.signatures = new CallableSignatureConverter(context, expressions);
    }

    List<IrClassMember> convert(NodeListNodeClassStatement list, String path) {
        var values = context.elements(list.getValue(), list, path);
        var result = new ArrayList<IrClassMember>(values.size());
        for (int i = 0; i < values.size(); i++) {
            var member = values.get(i);
            String p = path + "[" + i + "]";
            switch (member) {
                case NodeClassStatement.Method ignored -> result.add(method(member, p));
                case NodeClassStatement.PropertyDecl ignored -> result.addAll(properties(member, p));
                case NodeClassStatement.VarPropertyDecl ignored -> result.addAll(properties(member, p));
                case NodeClassStatement.ConstDecl ignored -> result.addAll(constants(member, p));
                case NodeClassStatement.UseTrait ignored -> result.add(new IrTraitUse(
                        names(context.required(member.getTraits(), member, p + ".traits"), p + ".traits"),
                        adaptations(context.required(member.getAdaptations(), member, p + ".adaptations"),
                                p + ".adaptations"), context.source(member)));
                default -> throw context.error(member, p, "无法识别的类型成员结构");
            }
        }
        return result;
    }

    private IrMethod method(NodeClassStatement node, String path) {
        return new IrMethod(
                context.identifier(context.required(node.getName(), node, path + ".name"), path + ".name"),
                modifiers(context.required(node.getMethodMods(), node, path + ".methodMods"), path + ".methodMods"),
                signatures.parameters(context.required(node.getParams(), node, path + ".params"), path + ".params"),
                signatures.returnType(context.required(node.getReturnType(), node, path + ".returnType"),
                        path + ".returnType"),
                signatures.marker(node.getReturnsRef(), "&", node, path + ".returnsRef"),
                methodBody(context.required(node.getBody(), node, path + ".body"), path + ".body"),
                context.source(node));
    }

    private @Nullable IrBlock methodBody(NodeMethodBody node, String path) {
        // 无字段的分号产生式生成精确基类；未知子类不能降级成省略方法体。
        if (node.getClass() == NodeMethodBody.class) return null;
        if (!(node instanceof NodeMethodBody.Body)) {
            throw context.error(node, path, "无法识别的方法体包装");
        }
        return new StatementConverter(context, expressions).innerBlock(node.getStmts(), node, path + ".stmts");
    }

    private List<IrProperty> properties(NodeClassStatement node, String path) {
        List<Modifier> modifiers;
        if (node instanceof NodeClassStatement.VarPropertyDecl) {
            if (!context.text(node.getKw(), node, path + ".kw").equalsIgnoreCase("var")) {
                throw context.error(node, path + ".kw", "无法识别的 var 属性标记");
            }
            modifiers = List.of(Modifier.VAR);
        } else {
            modifiers = nonEmpty(modifiers(context.required(node.getPropMods(), node, path + ".propMods"),
                    path + ".propMods"), node, path + ".propMods");
        }
        var list = context.required(node.getProps(), node, path + ".props");
        var values = nonEmpty(context.elements(list.getValue(), node, path + ".props"), node, path + ".props");
        var result = new ArrayList<IrProperty>(values.size());
        for (int i = 0; i < values.size(); i++) {
            var property = values.get(i);
            String p = path + ".props[" + i + "]";
            IrExpression initialValue;
            if (property instanceof NodeProperty.PropertyWithDefault) {
                initialValue = expressions.convert(context.required(property.getDefaultValue(), property,
                        p + ".defaultValue"), p + ".defaultValue");
            } else if (property instanceof NodeProperty.Property) {
                initialValue = null;
            } else {
                throw context.error(property, p, "无法识别的属性声明结构");
            }
            result.add(new IrProperty(context.text(property.getVar(), property, p + ".var"),
                    modifiers, initialValue, context.source(property)));
        }
        return result;
    }

    private List<IrClassConstant> constants(NodeClassStatement node, String path) {
        var modifiers = modifiers(context.required(node.getConstMods(), node, path + ".constMods"), path + ".constMods");
        var list = context.required(node.getConsts(), node, path + ".consts");
        var values = nonEmpty(context.elements(list.getValue(), node, path + ".consts"), node, path + ".consts");
        var result = new ArrayList<IrClassConstant>(values.size());
        for (int i = 0; i < values.size(); i++) {
            var constant = values.get(i);
            String p = path + ".consts[" + i + "]";
            if (!(constant instanceof NodeClassConstDecl.ClassConstDecl)) {
                throw context.error(constant, p, "无法识别的类常量声明结构");
            }
            result.add(new IrClassConstant(
                    context.identifier(context.required(constant.getName(), constant, p + ".name"), p + ".name"),
                    modifiers, expressions.convert(context.required(constant.getValue(), constant, p + ".value"),
                    p + ".value"), context.source(constant)));
        }
        return result;
    }

    private List<Modifier> modifiers(NodeListNodeMemberModifier list, String path) {
        var values = context.elements(list.getValue(), list, path);
        var result = new ArrayList<Modifier>(values.size());
        for (int i = 0; i < values.size(); i++) result.add(modifier(values.get(i), path + "[" + i + "]"));
        return result;
    }

    private Modifier modifier(NodeMemberModifier node, String path) {
        Modifier modifier = switch (node) {
            case NodeMemberModifier.Public ignored -> Modifier.PUBLIC;
            case NodeMemberModifier.Protected ignored -> Modifier.PROTECTED;
            case NodeMemberModifier.Private ignored -> Modifier.PRIVATE;
            case NodeMemberModifier.Static ignored -> Modifier.STATIC;
            case NodeMemberModifier.Abstract ignored -> Modifier.ABSTRACT;
            case NodeMemberModifier.Final ignored -> Modifier.FINAL;
            default -> throw context.error(node, path, "无法识别的成员修饰符结构");
        };
        if (!context.text(node.getKw(), node, path + ".kw").equalsIgnoreCase(modifier.name())) {
            throw context.error(node, path + ".kw", "成员修饰符与结构不一致");
        }
        return modifier;
    }

    private List<NameReference> names(NodeListNodeName list, String path) {
        var values = nonEmpty(context.elements(list.getValue(), list, path), list, path);
        var result = new ArrayList<NameReference>(values.size());
        for (int i = 0; i < values.size(); i++) result.add(context.name(values.get(i), path + "[" + i + "]"));
        return result;
    }

    private List<IrTraitAdaptation> adaptations(NodeTraitAdaptations node, String path) {
        // use T; 与 use T {} 都是无字段产生式，统一为空规则列表。
        if (node.getClass() == NodeTraitAdaptations.class) return List.of();
        if (!(node instanceof NodeTraitAdaptations.Block)) {
            throw context.error(node, path, "无法识别的 trait 适配规则包装");
        }
        var list = context.required(node.getAdaptations(), node, path + ".adaptations");
        var values = nonEmpty(context.elements(list.getValue(), node, path + ".adaptations"),
                node, path + ".adaptations");
        var result = new ArrayList<IrTraitAdaptation>(values.size());
        for (int i = 0; i < values.size(); i++) {
            var rule = values.get(i);
            String p = path + ".adaptations[" + i + "]";
            result.add(switch (rule) {
                case NodeTraitAdaptation.Precedence ignored -> precedence(
                        context.required(rule.getPrecedence(), rule, p + ".precedence"), p + ".precedence");
                case NodeTraitAdaptation.Alias ignored -> alias(
                        context.required(rule.getAlias(), rule, p + ".alias"), p + ".alias");
                default -> throw context.error(rule, p, "无法识别的 trait 适配规则");
            });
        }
        return result;
    }

    private IrTraitPrecedence precedence(NodeTraitPrecedence node, String path) {
        if (!(node instanceof NodeTraitPrecedence.TraitPrecedence)) {
            throw context.error(node, path, "无法识别的 trait 优先规则");
        }
        return new IrTraitPrecedence(
                absoluteMethod(context.required(node.getMethod(), node, path + ".method"), path + ".method"),
                names(context.required(node.getInsteadof(), node, path + ".insteadof"), path + ".insteadof"),
                context.source(node));
    }

    private IrTraitAlias alias(NodeTraitAlias node, String path) {
        if (!(node instanceof NodeTraitAlias.AliasAs || node instanceof NodeTraitAlias.AliasAsKeyword
                || node instanceof NodeTraitAlias.AliasModifier || node instanceof NodeTraitAlias.AliasModifierNewName)) {
            throw context.error(node, path, "无法识别的 trait 别名规则");
        }
        var method = traitMethod(context.required(node.getMethod(), node, path + ".method"), path + ".method");
        Modifier modifier = null;
        String newName = null;
        if (node instanceof NodeTraitAlias.AliasAs) {
            newName = context.text(node.getAlias(), node, path + ".alias");
        } else if (node instanceof NodeTraitAlias.AliasAsKeyword) {
            var keyword = context.required(node.getKeyword(), node, path + ".keyword");
            // 保留字分支使用共享的 kw 字段，不依赖 CUP 生成的匿名变体名称。
            newName = context.text(keyword.getKw(), keyword, path + ".keyword.kw");
        } else {
            modifier = modifier(context.required(node.getModifier(), node, path + ".modifier"), path + ".modifier");
            if (node instanceof NodeTraitAlias.AliasModifierNewName) {
                newName = context.identifier(context.required(node.getNewName(), node, path + ".newName"),
                        path + ".newName");
            }
        }
        return new IrTraitAlias(method, modifier, newName, context.source(node));
    }

    private IrTraitMethodReference traitMethod(NodeTraitMethodReference node, String path) {
        return switch (node) {
            case NodeTraitMethodReference.SelfMethod ignored -> new IrTraitMethodReference(null,
                    context.identifier(context.required(node.getMethod(), node, path + ".method"), path + ".method"),
                    context.source(node));
            case NodeTraitMethodReference.ClassMethod ignored -> {
                var absolute = absoluteMethod(context.required(node.getAbsolute(), node, path + ".absolute"),
                        path + ".absolute");
                yield new IrTraitMethodReference(absolute.trait(), absolute.method(), context.source(node));
            }
            default -> throw context.error(node, path, "无法识别的 trait 方法引用");
        };
    }

    private IrTraitMethodReference absoluteMethod(NodeAbsoluteTraitMethodReference node, String path) {
        if (!(node instanceof NodeAbsoluteTraitMethodReference.TraitMethodRef)) {
            throw context.error(node, path, "无法识别的显式 trait 方法引用");
        }
        return new IrTraitMethodReference(
                context.name(context.required(node.getClazz(), node, path + ".clazz"), path + ".clazz"),
                context.identifier(context.required(node.getMethod(), node, path + ".method"), path + ".method"),
                context.source(node));
    }

    private <T> List<T> nonEmpty(List<T> values, AstNode origin, String path) {
        if (values.isEmpty()) throw context.error(origin, path, "必需列表不能为空");
        return values;
    }
}
