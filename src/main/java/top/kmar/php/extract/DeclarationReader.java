package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import top.kmar.php.*;
import top.kmar.php.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 只读取已经确定处于声明层级的节点，不递归收集函数体中的声明。 */
final class DeclarationReader {

    private final ExtractionContext context;

    DeclarationReader(ExtractionContext context) {
        this.context = context;
    }

    List<TopLevelDeclaration> read(NodeTopStatement statement, NamespaceSectionId sectionId,
                                   String namespaceName) {
        if (statement instanceof NodeTopStatement.FunctionDecl) {
            return List.of(function(context.required(statement.getFunction(), statement, "function"),
                    sectionId, namespaceName));
        }
        if (statement instanceof NodeTopStatement.ClassDecl) {
            return List.of(clazz(context.required(statement.getClazz(), statement, "clazz"),
                    sectionId, namespaceName));
        }
        if (statement instanceof NodeTopStatement.InterfaceDecl) {
            return List.of(iface(context.required(statement.getIface(), statement, "iface"),
                    sectionId, namespaceName));
        }
        if (statement instanceof NodeTopStatement.TraitDecl) {
            return List.of(trait(context.required(statement.getTrait(), statement, "trait"),
                    sectionId, namespaceName));
        }
        if (statement instanceof NodeTopStatement.Const) {
            var list = context.required(statement.getConsts(), statement, "consts");
            List<TopLevelDeclaration> result = new ArrayList<>();
            for (NodeConstDecl constant : nonEmpty(
                    context.elements(list.getValue(), statement, "consts"), statement, "consts")) {
                String name = context.text(constant.getName(), constant, "const.name");
                result.add(new NamespaceConstantDefinition(context.nextId(), sectionId, name,
                        context.qualifiedName(namespaceName, name),
                        context.expression(context.required(constant.getValue(), constant, "const.value")),
                        context.source(constant)));
            }
            return List.copyOf(result);
        }
        throw context.error(statement, "declaration", "不支持的顶层声明节点");
    }

    private FunctionDefinition function(NodeFunctionDeclarationStatement node, NamespaceSectionId sectionId,
                                        String namespaceName) {
        DeclarationId id = context.nextId();
        String name = context.text(node.getName(), node, "function.name");
        FunctionSignature signature = signature(name, node.getParams(), node.getReturnType(),
                node.getReturnsRef(), node, "function");
        var statements = context.required(node.getStmts(), node, "function.stmts");
        return new FunctionDefinition(id, sectionId, context.qualifiedName(namespaceName, name), signature,
                context.body(context.elements(statements.getValue(), node, "function.stmts"), statements),
                context.source(node));
    }

    private ClassLikeDefinition clazz(NodeClassDeclarationStatement node, NamespaceSectionId sectionId,
                                      String namespaceName) {
        DeclarationId id = context.nextId();
        String name = context.text(node.getName(), node, "class.name");
        List<Modifier> modifiers = new ArrayList<>();
        if (node instanceof NodeClassDeclarationStatement.ClassWithModifiers) {
            var list = context.required(node.getMods(), node, "class.mods");
            for (NodeClassModifier modifier : nonEmpty(
                    context.elements(list.getValue(), node, "class.mods"), node, "class.mods")) {
                modifiers.add(modifier(modifier.getKw(), modifier, "class.mods.kw"));
            }
        } else if (!(node instanceof NodeClassDeclarationStatement.Class)) {
            throw context.error(node, "class", "不支持的类声明节点");
        }

        NodeExtendsFrom parent = context.required(node.getExtendsFrom(), node, "class.extendsFrom");
        List<NameReference> parents;
        if (parent instanceof NodeExtendsFrom.Extends) {
            parents = List.of(context.name(context.required(parent.getN(), parent, "class.extendsFrom.n")));
        } else if (parent.getClass() == NodeExtendsFrom.class) {
            parents = List.of();
        } else {
            throw context.error(parent, "class.extendsFrom", "不支持的父类引用节点");
        }

        NodeImplementsList implemented = context.required(node.getImplementsList(), node, "class.implementsList");
        List<NameReference> interfaces;
        if (implemented instanceof NodeImplementsList.ImplementsList) {
            interfaces = names(implemented.getNames(), implemented, "class.implementsList.names");
        } else if (implemented.getClass() == NodeImplementsList.class) {
            interfaces = List.of();
        } else {
            throw context.error(implemented, "class.implementsList", "不支持的接口实现列表节点");
        }
        return new ClassLikeDefinition(id, sectionId, name, context.qualifiedName(namespaceName, name),
                ClassLikeKind.CLASS, modifiers, parents, interfaces,
                members(node.getMembers(), id, node), context.source(node));
    }

    private ClassLikeDefinition iface(NodeInterfaceDeclarationStatement node, NamespaceSectionId sectionId,
                                      String namespaceName) {
        DeclarationId id = context.nextId();
        String name = context.text(node.getName(), node, "interface.name");
        NodeInterfaceExtendsList extended = context.required(node.getExtendsFrom(), node, "interface.extendsFrom");
        List<NameReference> parents;
        if (extended instanceof NodeInterfaceExtendsList.ExtendsList) {
            parents = names(extended.getNames(), extended, "interface.extendsFrom.names");
        } else if (extended.getClass() == NodeInterfaceExtendsList.class) {
            parents = List.of();
        } else {
            throw context.error(extended, "interface.extendsFrom", "不支持的接口继承列表节点");
        }
        return new ClassLikeDefinition(id, sectionId, name, context.qualifiedName(namespaceName, name),
                ClassLikeKind.INTERFACE, List.of(), parents, List.of(),
                members(node.getMembers(), id, node), context.source(node));
    }

    private ClassLikeDefinition trait(NodeTraitDeclarationStatement node, NamespaceSectionId sectionId,
                                      String namespaceName) {
        DeclarationId id = context.nextId();
        String name = context.text(node.getName(), node, "trait.name");
        return new ClassLikeDefinition(id, sectionId, name, context.qualifiedName(namespaceName, name),
                ClassLikeKind.TRAIT, List.of(), List.of(), List.of(),
                members(node.getMembers(), id, node), context.source(node));
    }

    private List<ClassMember> members(NodeListNodeClassStatement list, DeclarationId ownerId, AstNode owner) {
        var required = context.required(list, owner, "members");
        List<ClassMember> members = new ArrayList<>();
        for (NodeClassStatement node : context.elements(required.getValue(), owner, "members")) {
            if (node instanceof NodeClassStatement.Method) {
                members.add(method(node, ownerId));
            } else if (node instanceof NodeClassStatement.PropertyDecl
                    || node instanceof NodeClassStatement.VarPropertyDecl) {
                List<Modifier> modifiers;
                if (node instanceof NodeClassStatement.VarPropertyDecl) {
                    Modifier modifier = modifier(node.getKw(), node, "property.kw");
                    if (modifier != Modifier.VAR) {
                        throw context.error(node, "property.kw", "var 属性声明缺少 var 标记");
                    }
                    modifiers = List.of(modifier);
                } else {
                    modifiers = nonEmpty(modifiers(node.getPropMods(), node, "property.propMods"),
                            node, "property.propMods");
                }
                var properties = context.required(node.getProps(), node, "property.props");
                for (NodeProperty property : nonEmpty(context.elements(properties.getValue(), node,
                        "property.props"), node, "property.props")) {
                    String name = context.text(property.getVar(), property, "property.var");
                    SyntaxExpression initialValue;
                    if (property instanceof NodeProperty.PropertyWithDefault) {
                        initialValue = context.expression(context.required(property.getDefaultValue(),
                                property, "property.defaultValue"));
                    } else if (property instanceof NodeProperty.Property) {
                        initialValue = null;
                    } else {
                        throw context.error(property, "property", "不支持的属性声明节点");
                    }
                    members.add(new PropertyDefinition(context.nextId(), ownerId, name, modifiers,
                            initialValue, context.source(property)));
                }
            } else if (node instanceof NodeClassStatement.ConstDecl) {
                List<Modifier> modifiers = modifiers(node.getConstMods(), node, "constant.constMods");
                var constants = context.required(node.getConsts(), node, "constant.consts");
                for (NodeClassConstDecl constant : nonEmpty(context.elements(constants.getValue(), node,
                        "constant.consts"), node, "constant.consts")) {
                    members.add(new ClassConstantDefinition(context.nextId(), ownerId,
                            context.identifier(context.required(constant.getName(), constant, "constant.name")),
                            modifiers, context.expression(context.required(constant.getValue(), constant,
                            "constant.value")), context.source(constant)));
                }
            } else if (node instanceof NodeClassStatement.UseTrait) {
                List<NameReference> traits = names(node.getTraits(), node, "traitUse.traits");
                var adaptations = context.required(node.getAdaptations(), node, "traitUse.adaptations");
                if (adaptations instanceof NodeTraitAdaptations.Block) {
                    var rules = context.required(adaptations.getAdaptations(), adaptations,
                            "traitUse.adaptations.rules");
                    nonEmpty(context.elements(rules.getValue(), adaptations, "traitUse.adaptations.rules"),
                            adaptations, "traitUse.adaptations.rules");
                } else if (adaptations.getClass() != NodeTraitAdaptations.class) {
                    throw context.error(adaptations, "traitUse.adaptations", "不支持的 trait 适配规则节点");
                }
                members.add(new TraitUseDefinition(context.nextId(), ownerId, traits,
                        adaptations, context.source(node)));
            } else {
                throw context.error(node, "members", "不支持的类型成员节点");
            }
        }
        return List.copyOf(members);
    }

    private MethodDefinition method(NodeClassStatement node, DeclarationId ownerId) {
        DeclarationId id = context.nextId();
        String name = context.identifier(context.required(node.getName(), node, "method.name"));
        FunctionSignature signature = signature(name, node.getParams(), node.getReturnType(),
                node.getReturnsRef(), node, "method");
        NodeMethodBody body = context.required(node.getBody(), node, "method.body");
        SyntaxBody syntax;
        if (body instanceof NodeMethodBody.Body) {
            var statements = context.required(body.getStmts(), body, "method.body.stmts");
            syntax = context.body(context.elements(statements.getValue(), body,
                    "method.body.stmts"), body);
        } else if (body.getClass() == NodeMethodBody.class && body.getStmts() == null) {
            syntax = null;
        } else {
            throw context.error(body, "method.body", "不支持的方法体节点");
        }
        return new MethodDefinition(id, ownerId, modifiers(node.getMethodMods(), node, "method.methodMods"),
                signature, syntax, context.source(node));
    }

    private FunctionSignature signature(String name, NodeListNodeParameter parameters, NodeReturnType returnType,
                                        NodeString returnsReference, AstNode origin, String path) {
        var list = context.required(parameters, origin, path + ".params");
        List<ParameterDefinition> result = new ArrayList<>();
        for (NodeParameter parameter : context.elements(list.getValue(), origin, path + ".params")) {
            String parameterName = context.text(parameter.getVar(), parameter, path + ".params.var");
            SyntaxExpression defaultValue;
            if (parameter instanceof NodeParameter.ParamWithDefault) {
                defaultValue = context.expression(context.required(parameter.getDefaultValue(),
                        parameter, path + ".params.defaultValue"));
            } else if (parameter instanceof NodeParameter.Param) {
                defaultValue = null;
            } else {
                throw context.error(parameter, path + ".params", "不支持的参数节点");
            }
            var declaredType = parameter.getType();
            result.add(new ParameterDefinition(parameterName,
                    declaredType == null ? null : type(declaredType),
                    marker(parameter.getByRef(), "&", parameter, path + ".params.byRef"),
                    marker(parameter.getVariadic(), "...", parameter, path + ".params.variadic"),
                    defaultValue, context.source(parameter)));
        }
        NodeReturnType required = context.required(returnType, origin, path + ".returnType");
        TypeReference resultType;
        if (required instanceof NodeReturnType.ReturnType) {
            resultType = type(context.required(required.getT(), required, path + ".returnType.t"));
        } else if (required.getClass() == NodeReturnType.class) {
            resultType = null;
        } else {
            throw context.error(required, path + ".returnType", "不支持的返回类型节点");
        }
        return new FunctionSignature(name, result, resultType,
                marker(returnsReference, "&", origin, path + ".returnsRef"));
    }

    private TypeReference type(NodeTypeExpr node) {
        boolean nullable;
        if (node instanceof NodeTypeExpr.NullableType) {
            nullable = marker(context.required(node.getOp(), node, "type.op"), "?", node, "type.op");
        } else if (node instanceof NodeTypeExpr.Type) {
            nullable = false;
        } else {
            throw context.error(node, "type", "不支持的类型声明节点");
        }
        NodeType type = context.required(node.getT(), node, "type.t");
        NameReference name;
        if (type instanceof NodeType.NameType) {
            name = context.name(context.required(type.getN(), type, "type.name"));
        } else if (type instanceof NodeType.ArrayType || type instanceof NodeType.CallableType) {
            NodeString keyword = context.required(type.getKw(), type, "type.kw");
            name = new NameReference(context.text(keyword, type, "type.kw"), NameForm.UNQUALIFIED,
                    context.source(keyword));
        } else {
            throw context.error(type, "type.t", "不支持的类型名称节点");
        }
        return new TypeReference(name, nullable, context.source(node));
    }

    private List<NameReference> names(NodeListNodeName list, AstNode origin, String path) {
        var required = context.required(list, origin, path);
        List<NameReference> result = new ArrayList<>();
        for (NodeName name : nonEmpty(context.elements(required.getValue(), origin, path), origin, path)) {
            result.add(context.name(name));
        }
        return List.copyOf(result);
    }

    private List<Modifier> modifiers(NodeListNodeMemberModifier list, AstNode origin, String path) {
        var required = context.required(list, origin, path);
        List<Modifier> result = new ArrayList<>();
        for (NodeMemberModifier modifier : context.elements(required.getValue(), origin, path)) {
            result.add(modifier(modifier.getKw(), modifier, path + ".kw"));
        }
        return List.copyOf(result);
    }

    private Modifier modifier(NodeString keyword, AstNode origin, String path) {
        String value = context.text(keyword, origin, path);
        try {
            return Modifier.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw context.error(origin, path, "不支持的声明修饰符: " + value);
        }
    }

    private boolean marker(NodeString marker, String expected, AstNode origin, String path) {
        if (marker == null) return false;
        if (!context.text(marker, origin, path).equals(expected)) {
            throw context.error(origin, path, "预期语法标记 " + expected);
        }
        return true;
    }

    private <T> List<T> nonEmpty(List<T> values, AstNode origin, String path) {
        if (values.isEmpty()) throw context.error(origin, path, "必需列表不能为空");
        return values;
    }
}
