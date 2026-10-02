package top.kmar.php;

import java_cup.runtime.AstNode;
import java_cup.runtime.symbol.complex.ComplexLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.*;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证匿名类及成员的损坏 AST、来源、不可变性和 AST 隔离契约。 */
class AnonymousClassConversionContractTest {
    private static final ComplexLocation OUTER = ComplexLocation.of(2, 2, 12, 20);
    private static final ComplexLocation DEFINITION = ComplexLocation.of(3, 3, 11, 19);
    private static final ComplexLocation GROUP = ComplexLocation.of(4, 4, 10, 18);
    private static final ComplexLocation ITEM = ComplexLocation.of(5, 5, 9, 17);
    private static final ComplexLocation LEAF = ComplexLocation.of(6, 6, 6, 12);
    private static final ComplexLocation VALUE = ComplexLocation.of(7, 7, 7, 15);
    private static final ComplexLocation BODY = ComplexLocation.of(8, 4, 8, 18);
    private static final SourceInfo SOURCE = new SourceInfo("anonymous.php", null);

    // new 包装和匿名定义都必须属于已支持变体，不能凭同名 getter 接受未知结构。
    @Test
    void rejectsMissingAndUnknownAnonymousWrappers() {
        assertFailure(new NodeNewExpr.NewAnonymous(null, OUTER), ".anonClass");
        assertFailure(new NodeNewExpr() {
            @Override public NodeAnonymousClass getAnonClass() { return definition(); }
        }, ".newExpr");
        assertFailure(new NodeAnonymousClass(), ".anonClass");
        assertFailure(new NodeAnonymousClass() {
            @Override public NodeExtendsFrom getExtendsFrom() { return new NodeExtendsFrom(); }
            @Override public NodeImplementsList getImplementsList() { return new NodeImplementsList(); }
            @Override public NodeListNodeClassStatement getMembers() { return members(); }
        }, ".anonClass");
    }

    // 可省略构造实参和继承声明内容，但继承、接口、成员列表的语法包装不能缺失。
    @Test
    void rejectsMissingDefinitionFieldsAndMalformedMemberLists() {
        assertFailure(new NodeAnonymousClass.AnonymousClass(null, null, new NodeImplementsList(), members(), DEFINITION),
                ".extendsFrom");
        assertFailure(new NodeAnonymousClass.AnonymousClass(null, new NodeExtendsFrom(), null, members(), DEFINITION),
                ".implementsList");
        assertFailure(new NodeAnonymousClass.AnonymousClass(null, new NodeExtendsFrom(), new NodeImplementsList(), null, DEFINITION),
                ".members");
        assertFailure(definition(new NodeListNodeClassStatement(null, GROUP)), ".members");
        assertFailure(definition(new NodeListNodeClassStatement(Arrays.asList(method(), null), GROUP)), ".members[1]");
        assertFailure(definition(members(new NodeClassStatement())), ".members[0]");
    }

    // 精确空包装表示无父类或接口；未知子类和缺失名称不能视为省略。
    @Test
    void rejectsMalformedParentAndInterfaceStructures() {
        assertFailure(definition(new NodeExtendsFrom.Extends(null, GROUP), new NodeImplementsList()), ".extendsFrom.n");
        assertFailure(definition(new NodeExtendsFrom() {}, new NodeImplementsList()), ".extendsFrom");
        assertFailure(definition(new NodeExtendsFrom(), new NodeImplementsList() {}), ".implementsList");
        assertFailure(definition(new NodeExtendsFrom(), new NodeImplementsList.ImplementsList(null, GROUP)),
                ".implementsList.names");
        assertFailure(definition(new NodeExtendsFrom(), new NodeImplementsList.ImplementsList(
                new NodeListNodeName(null, ITEM), GROUP)), ".implementsList.names");
        assertFailure(definition(new NodeExtendsFrom(), new NodeImplementsList.ImplementsList(names(), GROUP)),
                ".implementsList.names");
        assertFailure(definition(new NodeExtendsFrom(), new NodeImplementsList.ImplementsList(
                new NodeListNodeName(Arrays.asList(name("A", LEAF), null), ITEM), GROUP)), ".implementsList.names[1]");
        assertFailure(definition(new NodeExtendsFrom.Extends(new NodeName.Unqualified(null, LEAF), GROUP),
                new NodeImplementsList()), ".extendsFrom.n.n");
    }

    // 构造实参复用普通调用规则，错误的列表、元素和展开标记都保留其字段路径。
    @Test
    void rejectsMalformedConstructorArguments() {
        assertFailure(definition(new NodeArgumentList()), ".ctorArgs");
        assertFailure(definition(new NodeArgumentList.Args(null, GROUP)), ".ctorArgs.args");
        assertFailure(definition(new NodeArgumentList.Args(new NodeListNodeArgument(null, GROUP), GROUP)), ".ctorArgs.args");
        assertFailure(definition(new NodeArgumentList.Args(new NodeListNodeArgument(
                Arrays.asList(new NodeArgument.Arg(integer(1, VALUE), ITEM), null), GROUP), GROUP)), ".ctorArgs.args[1]");
        assertFailure(definition(arguments(new NodeArgument.Arg(null, ITEM))), ".ctorArgs.args[0].arg");
        assertFailure(definition(arguments(new NodeArgument())), ".ctorArgs.args[0]");
        for (NodeString marker : new NodeString[]{null, token(null, LEAF), token("", LEAF), token("..", LEAF)}) {
            assertFailure(definition(arguments(new NodeArgument.UnpackArg(marker, integer(1, VALUE), ITEM))),
                    ".ctorArgs.args[0].op");
        }
    }

    // 方法必需字段不因可以省略返回类型、引用标志或分号方法体而变成可缺失。
    @Test
    void rejectsMissingMethodFieldsAndInvalidReferenceMarkers() {
        assertMemberFailure(new NodeClassStatement.Method(null, null, identifier("run", LEAF), parameters(),
                new NodeReturnType(), new NodeMethodBody(), ITEM), ".methodMods");
        assertMemberFailure(new NodeClassStatement.Method(modifiers(), null, null, parameters(),
                new NodeReturnType(), new NodeMethodBody(), ITEM), ".name");
        assertMemberFailure(new NodeClassStatement.Method(modifiers(), null, identifier("run", LEAF), null,
                new NodeReturnType(), new NodeMethodBody(), ITEM), ".params");
        assertMemberFailure(new NodeClassStatement.Method(modifiers(), null, identifier("run", LEAF), parameters(),
                null, new NodeMethodBody(), ITEM), ".returnType");
        assertMemberFailure(new NodeClassStatement.Method(modifiers(), null, identifier("run", LEAF), parameters(),
                new NodeReturnType(), null, ITEM), ".body");
        for (NodeString marker : new NodeString[]{token(null, LEAF), token("", LEAF), token("&&", LEAF)}) {
            assertMemberFailure(new NodeClassStatement.Method(modifiers(), marker, identifier("run", LEAF), parameters(),
                    new NodeReturnType(), new NodeMethodBody(), ITEM), ".returnsRef");
        }
        for (NodeString name : new NodeString[]{null, token(null, LEAF), token("", LEAF)}) {
            assertMemberFailure(new NodeClassStatement.Method(modifiers(), null, new NodeIdentifier.Identifier(name, LEAF),
                    parameters(), new NodeReturnType(), new NodeMethodBody(), ITEM), ".name.name");
        }
    }

    // 方法参数继续执行共享签名校验，不吞掉缺失默认值或未知参数、返回类型变体。
    @Test
    void rejectsMalformedMethodSignatures() {
        assertMemberFailure(method(new NodeListNodeParameter(null, GROUP)), ".params");
        assertMemberFailure(method(new NodeListNodeParameter(Arrays.asList(parameter(), null), GROUP)), ".params[1]");
        assertMemberFailure(method(parameters(new NodeParameter())), ".params[0]");
        assertMemberFailure(method(parameters(new NodeParameter.Param(null, null, null, null, LEAF))), ".params[0].var");
        assertMemberFailure(method(parameters(new NodeParameter.ParamWithDefault(null, null, null,
                token("value", LEAF), null, ITEM))), ".params[0].defaultValue");
        assertMemberFailure(method(parameters(new NodeParameter.Param(null, token("&&", LEAF), null,
                token("value", LEAF), ITEM))), ".params[0].byRef");
        assertMemberFailure(method(parameters(new NodeParameter.Param(null, null, token("..", LEAF),
                token("value", LEAF), ITEM))), ".params[0].variadic");
        assertMemberFailure(new NodeClassStatement.Method(modifiers(), null, identifier("run", LEAF), parameters(),
                new NodeReturnType() {}, new NodeMethodBody(), ITEM), ".returnType");
        assertMemberFailure(new NodeClassStatement.Method(modifiers(), null, identifier("run", LEAF), parameters(),
                new NodeReturnType.ReturnType(null, LEAF), new NodeMethodBody(), ITEM), ".returnType.t");
    }

    // 分号方法仅接受精确基类；真实空块和损坏语句列表必须分别处理。
    @Test
    void distinguishesSemicolonMethodsFromEmptyAndMalformedBodies() {
        IrMethod semicolon = assertInstanceOf(IrMethod.class, convert(definition(members(method()))).definition().members().getFirst());
        assertNull(semicolon.body());
        IrMethod braced = assertInstanceOf(IrMethod.class, convert(definition(members(method(emptyBody())))).definition().members().getFirst());
        assertNotNull(braced.body());
        assertTrue(braced.body().statements().isEmpty());
        assertMemberFailure(method(new NodeMethodBody() {}), ".body");
        assertMemberFailure(method(new NodeMethodBody.Body(null, BODY)), ".body.stmts");
        assertMemberFailure(method(new NodeMethodBody.Body(new NodeListNodeInnerStatement(null, GROUP), BODY)), ".body.stmts");
        assertMemberFailure(method(new NodeMethodBody.Body(new NodeListNodeInnerStatement(
                Arrays.asList(new NodeInnerStatement.Statement(new NodeStatement(), LEAF), null), GROUP), BODY)), ".body.stmts[1]");
        assertMemberFailure(method(new NodeMethodBody.Body(new NodeListNodeInnerStatement(
                List.of(new NodeInnerStatement.Statement(null, LEAF)), GROUP), BODY)), ".body.stmts[0].stmt");
    }

    // 修饰符按具体变体校验拼写，不能把错误的 PUBLIC 节点读成其他修饰符。
    @Test
    void rejectsMissingMalformedAndUnknownModifiers() {
        assertMemberFailure(propertyGroup(new NodeListNodeMemberModifier(null, GROUP), property()), ".propMods");
        assertMemberFailure(propertyGroup(modifiers(), property()), ".propMods");
        assertMemberFailure(propertyGroup(new NodeListNodeMemberModifier(Arrays.asList(publicModifier(), null), GROUP), property()),
                ".propMods[1]");
        for (NodeString keyword : new NodeString[]{null, token(null, LEAF), token("", LEAF), token("private", LEAF)}) {
            assertMemberFailure(propertyGroup(modifiers(new NodeMemberModifier.Public(keyword, ITEM)), property()), ".propMods[0].kw");
        }
        assertMemberFailure(propertyGroup(modifiers(new NodeMemberModifier() {
            @Override public NodeString getKw() { return token("public", LEAF); }
        }), property()), ".propMods[0]");
        assertMemberFailure(new NodeClassStatement.ConstDecl(new NodeListNodeMemberModifier(null, GROUP), constants(constant()), ITEM),
                ".constMods");
        assertMemberFailure(new NodeClassStatement.Method(new NodeListNodeMemberModifier(null, GROUP), null, identifier("run", LEAF),
                parameters(), new NodeReturnType(), new NodeMethodBody(), ITEM), ".methodMods");
    }

    // 属性声明组至少有一项，var 必须保留正确标记；初始化存在的变体必须有表达式。
    @Test
    void rejectsMalformedPropertyGroupsAndItems() {
        assertMemberFailure(new NodeClassStatement.PropertyDecl(null, properties(property()), GROUP), ".propMods");
        assertMemberFailure(new NodeClassStatement.PropertyDecl(modifiers(publicModifier()), null, GROUP), ".props");
        assertMemberFailure(new NodeClassStatement.PropertyDecl(modifiers(publicModifier()), new NodeListNodeProperty(null, ITEM), GROUP), ".props");
        assertMemberFailure(propertyGroup(modifiers(publicModifier())), ".props");
        assertMemberFailure(new NodeClassStatement.PropertyDecl(modifiers(publicModifier()),
                new NodeListNodeProperty(Arrays.asList(property(), null), ITEM), GROUP), ".props[1]");
        for (NodeString keyword : new NodeString[]{null, token(null, LEAF), token("", LEAF), token("public", LEAF)}) {
            assertMemberFailure(new NodeClassStatement.VarPropertyDecl(keyword, properties(property()), GROUP), ".kw");
        }
        for (NodeString name : new NodeString[]{null, token(null, LEAF), token("", LEAF)}) {
            assertPropertyFailure(new NodeProperty.Property(name, ITEM), ".props[0].var");
        }
        assertPropertyFailure(new NodeProperty.PropertyWithDefault(token("value", LEAF), null, ITEM), ".props[0].defaultValue");
        assertPropertyFailure(new NodeProperty() {
            @Override public NodeString getVar() { return token("value", LEAF); }
        }, ".props[0]");
    }

    // 类常量有序展开，名称和值均必需，空声明组或未知条目不能悄悄忽略。
    @Test
    void rejectsMalformedClassConstantGroupsAndItems() {
        assertMemberFailure(new NodeClassStatement.ConstDecl(null, constants(constant()), GROUP), ".constMods");
        assertMemberFailure(new NodeClassStatement.ConstDecl(modifiers(), null, GROUP), ".consts");
        assertMemberFailure(new NodeClassStatement.ConstDecl(modifiers(), new NodeListNodeClassConstDecl(null, ITEM), GROUP), ".consts");
        assertMemberFailure(new NodeClassStatement.ConstDecl(modifiers(), constants(), GROUP), ".consts");
        assertMemberFailure(new NodeClassStatement.ConstDecl(modifiers(), new NodeListNodeClassConstDecl(
                Arrays.asList(constant(), null), ITEM), GROUP), ".consts[1]");
        assertConstantFailure(new NodeClassConstDecl.ClassConstDecl(null, integer(1, VALUE), ITEM), ".consts[0].name");
        assertConstantFailure(new NodeClassConstDecl.ClassConstDecl(identifier("VALUE", LEAF), null, ITEM), ".consts[0].value");
        assertConstantFailure(new NodeClassConstDecl(), ".consts[0]");
    }

    // trait use 至少有一个名称，空适配包装可用，但未知包装不能自动降级为空规则。
    @Test
    void rejectsMalformedTraitListsAndAdaptationWrappers() {
        assertMemberFailure(new NodeClassStatement.UseTrait(null, new NodeTraitAdaptations(), GROUP), ".traits");
        assertMemberFailure(new NodeClassStatement.UseTrait(new NodeListNodeName(null, ITEM), new NodeTraitAdaptations(), GROUP), ".traits");
        assertMemberFailure(new NodeClassStatement.UseTrait(names(), new NodeTraitAdaptations(), GROUP), ".traits");
        assertMemberFailure(new NodeClassStatement.UseTrait(new NodeListNodeName(Arrays.asList(name("A", LEAF), null), ITEM),
                new NodeTraitAdaptations(), GROUP), ".traits[1]");
        assertMemberFailure(traitUse(null), ".adaptations");
        assertMemberFailure(traitUse(new NodeTraitAdaptations() {}), ".adaptations");
        assertMemberFailure(traitUse(new NodeTraitAdaptations.Block(null, ITEM)), ".adaptations.adaptations");
        assertMemberFailure(traitUse(new NodeTraitAdaptations.Block(new NodeListNodeTraitAdaptation(null, LEAF), ITEM)),
                ".adaptations.adaptations");
        assertMemberFailure(traitUse(adaptations()), ".adaptations.adaptations");
        assertMemberFailure(traitUse(new NodeTraitAdaptations.Block(new NodeListNodeTraitAdaptation(
                Arrays.asList(aliasRule(alias()), null), LEAF), ITEM)), ".adaptations.adaptations[1]");
        assertRuleFailure(new NodeTraitAdaptation(), ".adaptations[0]");
        assertRuleFailure(new NodeTraitAdaptation.Alias(null, ITEM), ".alias");
        assertRuleFailure(new NodeTraitAdaptation.Precedence(null, ITEM), ".precedence");
    }

    // 优先规则必须明确方法所属 trait，并提供至少一个排除项；损坏结构不进入模型构造器。
    @Test
    void rejectsMalformedTraitPrecedenceRules() {
        assertPrecedenceFailure(new NodeTraitPrecedence(), ".precedence");
        assertPrecedenceFailure(new NodeTraitPrecedence.TraitPrecedence(null, names(name("B", LEAF)), ITEM), ".method");
        assertPrecedenceFailure(new NodeTraitPrecedence.TraitPrecedence(absoluteMethod(), null, ITEM), ".insteadof");
        assertPrecedenceFailure(new NodeTraitPrecedence.TraitPrecedence(absoluteMethod(), new NodeListNodeName(null, LEAF), ITEM), ".insteadof");
        assertPrecedenceFailure(new NodeTraitPrecedence.TraitPrecedence(absoluteMethod(), names(), ITEM), ".insteadof");
        assertPrecedenceFailure(new NodeTraitPrecedence.TraitPrecedence(absoluteMethod(),
                new NodeListNodeName(Arrays.asList(name("B", LEAF), null), LEAF), ITEM), ".insteadof[1]");
        assertPrecedenceFailure(new NodeTraitPrecedence.TraitPrecedence(new NodeAbsoluteTraitMethodReference(), names(name("B", LEAF)), ITEM), ".method");
        assertPrecedenceFailure(new NodeTraitPrecedence.TraitPrecedence(new NodeAbsoluteTraitMethodReference.TraitMethodRef(
                null, identifier("run", LEAF), VALUE), names(name("B", LEAF)), ITEM), ".method.clazz");
        assertPrecedenceFailure(new NodeTraitPrecedence.TraitPrecedence(new NodeAbsoluteTraitMethodReference.TraitMethodRef(
                name("A", LEAF), null, VALUE), names(name("B", LEAF)), ITEM), ".method.method");
    }

    // 四种别名变体各自的必需字段必须存在，关键字别名也不能缺少实际 token。
    @Test
    void rejectsMalformedTraitAliasVariants() {
        assertAliasFailure(new NodeTraitAlias(), ".alias");
        assertAliasFailure(new NodeTraitAlias.AliasAs(null, token("renamed", LEAF), ITEM), ".method");
        for (NodeString name : new NodeString[]{null, token(null, LEAF), token("", LEAF)}) {
            assertAliasFailure(new NodeTraitAlias.AliasAs(selfMethod(), name, ITEM), ".alias");
        }
        assertAliasFailure(new NodeTraitAlias.AliasAsKeyword(selfMethod(), null, ITEM), ".keyword");
        assertAliasFailure(new NodeTraitAlias.AliasAsKeyword(selfMethod(), new NodeReservedNonModifiers(), ITEM), ".keyword.kw");
        assertAliasFailure(new NodeTraitAlias.AliasModifier(selfMethod(), null, ITEM), ".modifier");
        assertAliasFailure(new NodeTraitAlias.AliasModifierNewName(selfMethod(), null, identifier("renamed", LEAF), ITEM), ".modifier");
        assertAliasFailure(new NodeTraitAlias.AliasModifierNewName(selfMethod(), publicModifier(), null, ITEM), ".newName");
        assertAliasFailure(new NodeTraitAlias.AliasModifierNewName(selfMethod(), publicModifier(), identifier("", LEAF), ITEM), ".newName.name");
        assertAliasFailure(new NodeTraitAlias.AliasModifier(selfMethod(), new NodeMemberModifier() {}, ITEM), ".modifier");
        assertAliasFailure(new NodeTraitAlias.AliasModifier(selfMethod(), new NodeMemberModifier.Private(token("public", LEAF), LEAF), ITEM), ".modifier.kw");
    }

    // 别名的方法引用可以省略 trait，但不能省略方法名或未知包装中的绝对引用。
    @Test
    void rejectsMalformedTraitMethodReferences() {
        assertAliasFailure(new NodeTraitAlias.AliasAs(new NodeTraitMethodReference(), token("renamed", LEAF), ITEM), ".method");
        assertAliasFailure(new NodeTraitAlias.AliasAs(new NodeTraitMethodReference.SelfMethod(null, VALUE), token("renamed", LEAF), ITEM), ".method.method");
        assertAliasFailure(new NodeTraitAlias.AliasAs(new NodeTraitMethodReference.ClassMethod(null, VALUE), token("renamed", LEAF), ITEM), ".method.absolute");
        assertAliasFailure(new NodeTraitAlias.AliasAs(new NodeTraitMethodReference.ClassMethod(
                new NodeAbsoluteTraitMethodReference.TraitMethodRef(name("A", LEAF), identifier("", LEAF), VALUE), GROUP),
                token("renamed", LEAF), ITEM), ".method.absolute.method.name");
    }

    // 构造实参、定义、成员组和组内条目保留各自来源，不使用外层范围替代子节点。
    @Test
    void preservesDistinctInstantiationDefinitionAndMemberOrigins() {
        NodeProperty property = new NodeProperty.PropertyWithDefault(token("value", LEAF), integer(1, VALUE), ITEM);
        NodeParameter parameter = new NodeParameter.ParamWithDefault(null, null, null, token("value", LEAF), integer(2, VALUE), ITEM);
        NodeClassStatement method = new NodeClassStatement.Method(modifiers(), null, identifier("run", LEAF),
                parameters(parameter), new NodeReturnType(), emptyBody(), GROUP);
        NodeAnonymousClass definition = new NodeAnonymousClass.AnonymousClass(
                arguments(new NodeArgument.Arg(integer(3, VALUE), ITEM)),
                new NodeExtendsFrom.Extends(name("Base", LEAF), GROUP),
                new NodeImplementsList.ImplementsList(names(name("Contract", VALUE)), GROUP),
                members(propertyGroup(modifiers(publicModifier()), property),
                        new NodeClassStatement.ConstDecl(modifiers(), constants(constant()), GROUP), method), DEFINITION);
        IrNewAnonymous result = convert(definition);
        assertSource(OUTER, result.source());
        assertSource(DEFINITION, result.definition().source());
        assertSource(ITEM, result.arguments().getFirst().source());
        assertSource(VALUE, result.arguments().getFirst().expression().source());
        assertSource(LEAF, result.definition().parentType().source());
        assertSource(VALUE, result.definition().interfaces().getFirst().source());
        IrProperty convertedProperty = assertInstanceOf(IrProperty.class, result.definition().members().getFirst());
        assertSource(ITEM, convertedProperty.source());
        assertSource(VALUE, convertedProperty.initialValue().source());
        IrClassConstant convertedConstant = assertInstanceOf(IrClassConstant.class, result.definition().members().get(1));
        assertSource(ITEM, convertedConstant.source());
        assertSource(VALUE, convertedConstant.value().source());
        IrMethod convertedMethod = assertInstanceOf(IrMethod.class, result.definition().members().get(2));
        assertSource(GROUP, convertedMethod.source());
        assertSource(ITEM, convertedMethod.parameters().getFirst().source());
        assertSource(VALUE, convertedMethod.parameters().getFirst().defaultValue().source());
        assertSource(BODY, convertedMethod.body().source());
    }

    // 规则来源取实际 alias/precedence；方法引用保留其独立包装，而不是借用 trait 名 token。
    @Test
    void preservesTraitRuleAndMethodReferenceOrigins() {
        NodeTraitAlias alias = new NodeTraitAlias.AliasAs(new NodeTraitMethodReference.ClassMethod(
                absoluteMethod(), BODY), token("renamed", LEAF), ITEM);
        NodeTraitPrecedence precedence = new NodeTraitPrecedence.TraitPrecedence(absoluteMethod(), names(name("B", BODY)), ITEM);
        IrTraitUse use = assertInstanceOf(IrTraitUse.class, convert(definition(members(traitUse(adaptations(
                aliasRule(alias), new NodeTraitAdaptation.Precedence(precedence, GROUP)))))).definition().members().getFirst());
        assertSource(GROUP, use.source());
        assertSource(LEAF, use.traits().getFirst().source());
        IrTraitAlias convertedAlias = assertInstanceOf(IrTraitAlias.class, use.adaptations().getFirst());
        assertSource(ITEM, convertedAlias.source());
        assertSource(BODY, convertedAlias.method().source());
        assertSource(LEAF, convertedAlias.method().trait().source());
        IrTraitPrecedence convertedPrecedence = assertInstanceOf(IrTraitPrecedence.class, use.adaptations().get(1));
        assertSource(ITEM, convertedPrecedence.source());
        assertSource(VALUE, convertedPrecedence.method().source());
        assertSource(LEAF, convertedPrecedence.method().trait().source());
        assertSource(BODY, convertedPrecedence.insteadOf().getFirst().source());
    }

    // 未知范围仍为 null，零宽范围原样保留；已知成员组范围不能污染未知的条目来源。
    @Test
    void preservesUnknownAndZeroWidthOrigins() {
        for (ComplexLocation location : List.of(ComplexLocation.NO_LOCATION, ComplexLocation.of(9, 4, 9, 4))) {
            NodeTraitMethodReference reference = new NodeTraitMethodReference.SelfMethod(identifier("run", location), location);
            NodeTraitAlias alias = new NodeTraitAlias.AliasAs(reference, token("renamed", location), location);
            NodeClassStatement use = new NodeClassStatement.UseTrait(names(name("A", location)),
                    adaptations(new NodeTraitAdaptation.Alias(alias, GROUP)), location);
            NodeClassStatement method = new NodeClassStatement.Method(modifiers(), null, identifier("run", location),
                    parameters(new NodeParameter.ParamWithDefault(null, null, null, token("value", location),
                            integer(1, location), location)), new NodeReturnType(),
                    new NodeMethodBody.Body(new NodeListNodeInnerStatement(List.of(), GROUP), location), location);
            NodeAnonymousClass definition = new NodeAnonymousClass.AnonymousClass(arguments(
                    new NodeArgument.Arg(integer(1, location), location)), new NodeExtendsFrom.Extends(name("Base", location), GROUP),
                    new NodeImplementsList.ImplementsList(names(name("Contract", location)), GROUP),
                    members(propertyGroup(modifiers(publicModifier()), new NodeProperty.PropertyWithDefault(
                                    token("value", location), integer(1, location), location)),
                            new NodeClassStatement.ConstDecl(modifiers(), constants(new NodeClassConstDecl.ClassConstDecl(
                                    identifier("VALUE", location), integer(1, location), location)), GROUP), method, use), location);
            IrNewAnonymous result = assertInstanceOf(IrNewAnonymous.class, convert(new NodeNewExpr.NewAnonymous(definition, location)));
            assertAllSources(result, location.isNoLocation() ? null : range(location));
        }
    }

    // 新模型的必需字段均非 null；父类、方法体、类型和属性初值的缺省含义保持独立。
    @Test
    void validatesAllRequiredModelFields() {
        IrAnonymousClass definition = new IrAnonymousClass(null, List.of(), List.of(), SOURCE);
        IrExpression value = new IrIntegerLiteral(1, SOURCE);
        IrNameReference trait = irName("Trait");
        IrTraitMethodReference reference = new IrTraitMethodReference(trait, "run", SOURCE);
        for (Executable constructor : List.<Executable>of(
                () -> new IrNewAnonymous(null, List.of(), SOURCE), () -> new IrNewAnonymous(definition, null, SOURCE),
                () -> new IrNewAnonymous(definition, List.of(), null),
                () -> new IrAnonymousClass(null, null, List.of(), SOURCE), () -> new IrAnonymousClass(null, List.of(), null, SOURCE),
                () -> new IrAnonymousClass(null, List.of(), List.of(), null),
                () -> new IrMethod(null, List.of(), List.of(), null, false, null, SOURCE),
                () -> new IrMethod("run", null, List.of(), null, false, null, SOURCE),
                () -> new IrMethod("run", List.of(), null, null, false, null, SOURCE),
                () -> new IrMethod("run", List.of(), List.of(), null, false, null, null),
                () -> new IrProperty(null, List.of(), null, SOURCE), () -> new IrProperty("value", null, null, SOURCE),
                () -> new IrProperty("value", List.of(), null, null),
                () -> new IrClassConstant(null, List.of(), value, SOURCE), () -> new IrClassConstant("VALUE", null, value, SOURCE),
                () -> new IrClassConstant("VALUE", List.of(), null, SOURCE), () -> new IrClassConstant("VALUE", List.of(), value, null),
                () -> new IrTraitUse(null, List.of(), SOURCE), () -> new IrTraitUse(List.of(trait), null, SOURCE),
                () -> new IrTraitUse(List.of(trait), List.of(), null),
                () -> new IrTraitMethodReference(trait, null, SOURCE), () -> new IrTraitMethodReference(trait, "run", null),
                () -> new IrTraitPrecedence(null, List.of(trait), SOURCE), () -> new IrTraitPrecedence(reference, null, SOURCE),
                () -> new IrTraitPrecedence(reference, List.of(trait), null),
                () -> new IrTraitAlias(null, null, "renamed", SOURCE), () -> new IrTraitAlias(reference, null, "renamed", null))) {
            assertThrows(NullPointerException.class, constructor);
        }
        assertNull(definition.parentType());
        IrMethod method = new IrMethod("run", List.of(), List.of(), null, false, null, SOURCE);
        assertNull(method.returnType());
        assertNull(method.body());
        assertNull(new IrProperty("value", List.of(), null, SOURCE).initialValue());
        assertNull(new IrTraitMethodReference(null, "run", SOURCE).trait());
    }

    // 名称和语法必需集合非空，优先规则必须限定 trait；不校验修饰符组合或名称重复。
    @Test
    void validatesStructuralModelInvariantsWithoutSemanticNormalization() {
        IrExpression value = new IrIntegerLiteral(1, SOURCE);
        IrNameReference trait = irName("Trait");
        IrTraitMethodReference reference = new IrTraitMethodReference(trait, "run", SOURCE);
        for (Executable constructor : List.<Executable>of(
                () -> new IrMethod("", List.of(), List.of(), null, false, null, SOURCE),
                () -> new IrProperty("", List.of(), null, SOURCE),
                () -> new IrClassConstant("", List.of(), value, SOURCE),
                () -> new IrTraitUse(List.of(), List.of(), SOURCE),
                () -> new IrTraitMethodReference(trait, "", SOURCE),
                () -> new IrTraitPrecedence(reference, List.of(), SOURCE),
                () -> new IrTraitPrecedence(new IrTraitMethodReference(null, "run", SOURCE), List.of(trait), SOURCE),
                () -> new IrTraitAlias(reference, null, null, SOURCE),
                () -> new IrTraitAlias(reference, Modifier.PUBLIC, "", SOURCE))) {
            assertThrows(IllegalArgumentException.class, constructor);
        }
        var flags = List.of(Modifier.PUBLIC, Modifier.PUBLIC, Modifier.PRIVATE, Modifier.VAR);
        assertEquals(flags, new IrMethod("Run", flags, List.of(), null, true, null, SOURCE).declaredModifiers());
        assertEquals(flags, new IrProperty("Value", flags, null, SOURCE).declaredModifiers());
        assertEquals(flags, new IrClassConstant("Value", flags, value, SOURCE).declaredModifiers());
        assertNull(new IrTraitAlias(reference, Modifier.STATIC, null, SOURCE).newName());
        assertNull(new IrTraitAlias(reference, null, "Renamed", SOURCE).modifier());
    }

    // 每一种列表都拒绝 null 元素，避免将损坏记录留到下游遍历时才发现。
    @Test
    void rejectsNullElementsInAllModelLists() {
        IrNameReference trait = irName("Trait");
        IrExpression value = new IrIntegerLiteral(1, SOURCE);
        IrAnonymousClass definition = new IrAnonymousClass(null, List.of(), List.of(), SOURCE);
        IrTraitMethodReference reference = new IrTraitMethodReference(trait, "run", SOURCE);
        for (Executable constructor : List.<Executable>of(
                () -> new IrNewAnonymous(definition, Arrays.asList(new IrArgument(value, false, SOURCE), null), SOURCE),
                () -> new IrAnonymousClass(null, Arrays.asList(trait, null), List.of(), SOURCE),
                () -> new IrAnonymousClass(null, List.of(), Arrays.asList(new IrProperty("value", List.of(), null, SOURCE), null), SOURCE),
                () -> new IrMethod("run", Arrays.asList(Modifier.PUBLIC, null), List.of(), null, false, null, SOURCE),
                () -> new IrMethod("run", List.of(), Arrays.asList(new IrParameter("value", null, false, false, null, SOURCE), null),
                        null, false, null, SOURCE),
                () -> new IrProperty("value", Arrays.asList(Modifier.PUBLIC, null), null, SOURCE),
                () -> new IrClassConstant("VALUE", Arrays.asList(Modifier.PUBLIC, null), value, SOURCE),
                () -> new IrTraitUse(Arrays.asList(trait, null), List.of(), SOURCE),
                () -> new IrTraitUse(List.of(trait), Arrays.asList(new IrTraitAlias(reference, null, "renamed", SOURCE), null), SOURCE),
                () -> new IrTraitPrecedence(reference, Arrays.asList(trait, null), SOURCE))) {
            assertThrows(NullPointerException.class, constructor);
        }
    }

    // 类、成员、实参和 trait 规则均保存有序只读快照，源列表修改不会改变已生成结果。
    @Test
    void snapshotsEveryModelListAndPreservesDuplicates() {
        IrNameReference trait = irName("Trait");
        var flags = new ArrayList<>(List.of(Modifier.PUBLIC, Modifier.PUBLIC));
        var parameter = new IrParameter("value", null, false, false, null, SOURCE);
        var parameters = new ArrayList<>(List.of(parameter, parameter));
        var method = new IrMethod("run", flags, parameters, null, false, null, SOURCE);
        var property = new IrProperty("value", flags, null, SOURCE);
        var constant = new IrClassConstant("VALUE", flags, new IrIntegerLiteral(1, SOURCE), SOURCE);
        var reference = new IrTraitMethodReference(trait, "run", SOURCE);
        var names = new ArrayList<>(List.of(trait, trait));
        var precedence = new IrTraitPrecedence(reference, names, SOURCE);
        var rules = new ArrayList<IrTraitAdaptation>(List.of(precedence, precedence));
        var use = new IrTraitUse(names, rules, SOURCE);
        var members = new ArrayList<IrClassMember>(List.of(method, property, constant, use, method));
        var definition = new IrAnonymousClass(null, names, members, SOURCE);
        var argument = new IrArgument(new IrIntegerLiteral(1, SOURCE), false, SOURCE);
        var arguments = new ArrayList<>(List.of(argument, argument));
        var result = new IrNewAnonymous(definition, arguments, SOURCE);
        flags.clear(); parameters.clear(); names.clear(); rules.clear(); members.clear(); arguments.clear();
        assertEquals(List.of(Modifier.PUBLIC, Modifier.PUBLIC), method.declaredModifiers());
        assertEquals(method.declaredModifiers(), property.declaredModifiers());
        assertEquals(method.declaredModifiers(), constant.declaredModifiers());
        assertEquals(List.of(parameter, parameter), method.parameters());
        assertEquals(List.of(trait, trait), definition.interfaces());
        assertEquals(definition.interfaces(), use.traits());
        assertEquals(definition.interfaces(), precedence.insteadOf());
        assertEquals(List.of(precedence, precedence), use.adaptations());
        assertEquals(List.of(method, property, constant, use, method), definition.members());
        assertEquals(List.of(argument, argument), result.arguments());
        for (List<?> list : List.of(method.declaredModifiers(), property.declaredModifiers(), constant.declaredModifiers(),
                method.parameters(), definition.interfaces(), definition.members(), use.traits(), use.adaptations(),
                precedence.insteadOf(), result.arguments())) {
            assertThrows(UnsupportedOperationException.class, list::clear);
        }
    }

    // 新支持的成员外壳不能掩盖仍不支持的内部表达式，转换错误定位到具体字段。
    @Test
    void propagatesUnsupportedExpressionsFromEveryNewBoundary() {
        NodeExpr unsupported = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable(), VALUE);
        assertContainsFailure(definition(arguments(new NodeArgument.Arg(unsupported, ITEM))), ".ctorArgs.args[0].arg");
        assertContainsFailure(definition(members(propertyGroup(modifiers(publicModifier()),
                new NodeProperty.PropertyWithDefault(token("value", LEAF), unsupported, ITEM)))), ".props[0].defaultValue");
        assertContainsFailure(definition(members(new NodeClassStatement.ConstDecl(modifiers(), constants(
                new NodeClassConstDecl.ClassConstDecl(identifier("VALUE", LEAF), unsupported, ITEM)), GROUP))), ".consts[0].value");
        assertContainsFailure(definition(members(method(parameters(new NodeParameter.ParamWithDefault(null, null, null,
                token("value", LEAF), unsupported, ITEM))))), ".params[0].defaultValue");
        assertContainsFailure(definition(members(method(new NodeMethodBody.Body(new NodeListNodeInnerStatement(List.of(
                new NodeInnerStatement.Statement(new NodeStatement.ExpressionStatement(unsupported, LEAF), ITEM)), GROUP), BODY)))),
                ".body.stmts[0].stmt.expression");
    }

    // 转换前后声明索引与 AST 不变，失败不污染下次调用，嵌套匿名类成员也不泄漏 AST。
    @Test
    void convertsRepeatedlyWithoutMutatingDeclarationsOrLeakingAst() throws ReflectiveOperationException {
        var file = DeclarationExtractor.extract(Main.parse("""
                <?php
                function build($argument) {
                    return new class($argument) extends Base implements First, Second {
                        public $value = "ready", $other;
                        const VALUE = 1, TEXT = "text";
                        use TraitA, TraitB { TraitA::run insteadof TraitB; run as protected renamed; }
                        public function &run(?Thing &$value = null): ?Thing { return $value; }
                        protected function pending();
                        public function nested() { return new class { public $text = "nested"; }; }
                    };
                }
                function unsupported() { return new class { public $value = ($invalid[]); }; }
                """), "anonymous.php");
        var declarations = file.namespaceSections().getFirst().declarations();
        var function = assertInstanceOf(FunctionDefinition.class, declarations.getFirst());
        var unsupported = assertInstanceOf(FunctionDefinition.class, declarations.get(1));
        String before = file.syntax().toTreeString(false);
        assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(unsupported.body()));
        IrBlock result = SyntaxConverter.convertBody(function.body());
        assertEquals(before, file.syntax().toTreeString(false));
        assertSame(function, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "build").getFirst());
        assertEquals(2, declarations.size());
        assertEquals(result, SyntaxConverter.convertBody(function.body()));
        assertNoAst(result, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static NodeString token(String value, ComplexLocation location) { return new NodeString(value, location); }

    private static NodeIdentifier identifier(String name, ComplexLocation location) {
        return new NodeIdentifier.Identifier(token(name, location), location);
    }

    private static NodeName name(String value, ComplexLocation location) {
        return new NodeName.Unqualified(new NodeNamespaceName.Part(token(value, location), location), location);
    }

    private static NodeExpr integer(int value, ComplexLocation location) {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(token(Integer.toString(value), location), location), location), location);
    }

    private static NodeAnonymousClass definition() { return definition(members()); }

    private static NodeAnonymousClass definition(NodeListNodeClassStatement members) {
        return new NodeAnonymousClass.AnonymousClass(null, new NodeExtendsFrom(), new NodeImplementsList(), members, DEFINITION);
    }

    private static NodeAnonymousClass definition(NodeArgumentList arguments) {
        return new NodeAnonymousClass.AnonymousClass(arguments, new NodeExtendsFrom(), new NodeImplementsList(), members(), DEFINITION);
    }

    private static NodeAnonymousClass definition(NodeExtendsFrom parent, NodeImplementsList interfaces) {
        return new NodeAnonymousClass.AnonymousClass(null, parent, interfaces, members(), DEFINITION);
    }

    private static NodeListNodeClassStatement members(NodeClassStatement... items) {
        return new NodeListNodeClassStatement(List.of(items), GROUP);
    }

    private static NodeArgumentList arguments(NodeArgument... items) {
        return new NodeArgumentList.Args(new NodeListNodeArgument(List.of(items), GROUP), GROUP);
    }

    private static NodeListNodeName names(NodeName... items) { return new NodeListNodeName(List.of(items), GROUP); }

    private static NodeListNodeMemberModifier modifiers(NodeMemberModifier... items) {
        return new NodeListNodeMemberModifier(List.of(items), GROUP);
    }

    private static NodeMemberModifier publicModifier() { return new NodeMemberModifier.Public(token("public", LEAF), LEAF); }

    private static NodeListNodeParameter parameters(NodeParameter... items) {
        return new NodeListNodeParameter(List.of(items), GROUP);
    }

    private static NodeParameter parameter() { return new NodeParameter.Param(null, null, null, token("value", LEAF), ITEM); }

    private static NodeClassStatement method() { return method(new NodeMethodBody()); }

    private static NodeClassStatement method(NodeMethodBody body) {
        return new NodeClassStatement.Method(modifiers(), null, identifier("run", LEAF), parameters(), new NodeReturnType(), body, ITEM);
    }

    private static NodeClassStatement method(NodeListNodeParameter parameters) {
        return new NodeClassStatement.Method(modifiers(), null, identifier("run", LEAF), parameters, new NodeReturnType(), new NodeMethodBody(), ITEM);
    }

    private static NodeMethodBody emptyBody() {
        return new NodeMethodBody.Body(new NodeListNodeInnerStatement(List.of(), GROUP), BODY);
    }

    private static NodeProperty property() { return new NodeProperty.Property(token("value", LEAF), ITEM); }

    private static NodeListNodeProperty properties(NodeProperty... items) { return new NodeListNodeProperty(List.of(items), GROUP); }

    private static NodeClassStatement propertyGroup(NodeListNodeMemberModifier modifiers, NodeProperty... items) {
        return new NodeClassStatement.PropertyDecl(modifiers, properties(items), GROUP);
    }

    private static NodeClassConstDecl constant() {
        return new NodeClassConstDecl.ClassConstDecl(identifier("VALUE", LEAF), integer(1, VALUE), ITEM);
    }

    private static NodeListNodeClassConstDecl constants(NodeClassConstDecl... items) {
        return new NodeListNodeClassConstDecl(List.of(items), GROUP);
    }

    private static NodeClassStatement traitUse(NodeTraitAdaptations adaptations) {
        return new NodeClassStatement.UseTrait(names(name("A", LEAF)), adaptations, GROUP);
    }

    private static NodeTraitAdaptations adaptations(NodeTraitAdaptation... items) {
        return new NodeTraitAdaptations.Block(new NodeListNodeTraitAdaptation(List.of(items), GROUP), GROUP);
    }

    private static NodeTraitMethodReference selfMethod() {
        return new NodeTraitMethodReference.SelfMethod(identifier("run", LEAF), VALUE);
    }

    private static NodeAbsoluteTraitMethodReference absoluteMethod() {
        return new NodeAbsoluteTraitMethodReference.TraitMethodRef(name("A", LEAF), identifier("run", LEAF), VALUE);
    }

    private static NodeTraitAlias alias() { return new NodeTraitAlias.AliasAs(selfMethod(), token("renamed", LEAF), ITEM); }

    private static NodeTraitAdaptation aliasRule(NodeTraitAlias alias) { return new NodeTraitAdaptation.Alias(alias, GROUP); }

    private static IrNewAnonymous convert(NodeAnonymousClass definition) {
        return assertInstanceOf(IrNewAnonymous.class, convert(new NodeNewExpr.NewAnonymous(definition, OUTER)));
    }

    private static IrExpression convert(NodeNewExpr expression) {
        return SyntaxConverter.convertExpression(new SyntaxExpression(new NodeExpr.ExprWithoutVariable(
                new NodeExprWithoutVariable.New(expression, OUTER), OUTER), SOURCE));
    }

    private static void assertFailure(NodeAnonymousClass definition, String suffix) {
        assertFailure(new NodeNewExpr.NewAnonymous(definition, OUTER), suffix);
    }

    private static void assertFailure(NodeNewExpr expression, String suffix) {
        var error = assertThrows(SyntaxConversionException.class, () -> convert(expression));
        assertTrue(error.fieldPath().startsWith("expression"), error.fieldPath());
        assertTrue(error.fieldPath().endsWith(suffix), error.fieldPath());
        assertEquals("anonymous.php", error.source().sourceId());
        assertFalse(error.reason().isBlank());
    }

    private static void assertMemberFailure(NodeClassStatement member, String suffix) {
        assertFailure(definition(members(member)), suffix);
    }

    private static void assertPropertyFailure(NodeProperty property, String suffix) {
        assertMemberFailure(propertyGroup(modifiers(publicModifier()), property), suffix);
    }

    private static void assertConstantFailure(NodeClassConstDecl constant, String suffix) {
        assertMemberFailure(new NodeClassStatement.ConstDecl(modifiers(), constants(constant), GROUP), suffix);
    }

    private static void assertRuleFailure(NodeTraitAdaptation rule, String suffix) {
        assertMemberFailure(traitUse(adaptations(rule)), suffix);
    }

    private static void assertAliasFailure(NodeTraitAlias alias, String suffix) { assertRuleFailure(aliasRule(alias), suffix); }

    private static void assertPrecedenceFailure(NodeTraitPrecedence precedence, String suffix) {
        assertRuleFailure(new NodeTraitAdaptation.Precedence(precedence, GROUP), suffix);
    }

    private static IrNameReference irName(String name) { return new IrNameReference(name, NameForm.UNQUALIFIED, SOURCE); }

    private static void assertContainsFailure(NodeAnonymousClass definition, String field) {
        var error = assertThrows(SyntaxConversionException.class, () -> convert(definition));
        assertTrue(error.fieldPath().contains(field), error.fieldPath());
        assertEquals("anonymous.php", error.source().sourceId());
        assertFalse(error.reason().isBlank());
    }

    private static SourceRange range(ComplexLocation location) {
        return new SourceRange(location.getStartLine(), location.getStartColumn(), location.getEndLine(), location.getEndColumn());
    }

    private static void assertSource(ComplexLocation expected, SourceInfo actual) {
        assertEquals("anonymous.php", actual.sourceId());
        assertEquals(range(expected), actual.range());
    }

    private static void assertAllSources(Object value, SourceRange expected) {
        try {
            if (value == null) return;
            if (value instanceof SourceInfo(String sourceId, SourceRange range)) {
                assertEquals("anonymous.php", sourceId);
                assertEquals(expected, range);
            } else if (value instanceof List<?> list) {
                for (Object child : list) assertAllSources(child, expected);
            } else if (value.getClass().isRecord()) {
                for (RecordComponent component : value.getClass().getRecordComponents()) {
                    assertAllSources(component.getAccessor().invoke(value), expected);
                }
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertNoAst(Object value, Set<Object> visited) throws ReflectiveOperationException {
        if (value == null || !visited.add(value)) return;
        if (value instanceof ByteString bytes) {
            byte[] snapshot = bytes.toByteArray();
            assertEquals(snapshot.length, bytes.size());
            if (snapshot.length != 0) {
                byte first = snapshot[0];
                snapshot[0] ^= 1;
                assertEquals(first, bytes.byteAt(0));
            }
            return;
        }
        assertFalse(value instanceof AstNode, "匿名类 IR 不应保留 CUP AST");
        if (value instanceof List<?> list) {
            for (Object child : list) assertNoAst(child, visited);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertNoAst(component.getAccessor().invoke(value), visited);
            }
        } else {
            assertTrue(value instanceof String || value instanceof Enum<?> || value instanceof Number
                    || value instanceof Boolean, "未预期的结果字段类型：" + value.getClass());
        }
    }
}
