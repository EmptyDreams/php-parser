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

/** 验证具名声明的入口包装、损坏 AST 诊断、来源及第一阶段模型隔离契约。 */
class NamedDeclarationConversionContractTest {
    private static final ComplexLocation OUTER = ComplexLocation.of(2, 2, 12, 20);
    private static final ComplexLocation DECLARATION = ComplexLocation.of(3, 3, 11, 19);
    private static final ComplexLocation GROUP = ComplexLocation.of(4, 4, 10, 18);
    private static final ComplexLocation ITEM = ComplexLocation.of(5, 5, 9, 17);
    private static final ComplexLocation LEAF = ComplexLocation.of(6, 6, 6, 12);
    private static final ComplexLocation VALUE = ComplexLocation.of(7, 7, 7, 15);
    private static final ComplexLocation BODY = ComplexLocation.of(8, 4, 8, 18);
    private static final SourceInfo SOURCE = new SourceInfo("declarations.php", null);

    // 顶层包装、内部包装及实际声明节点是等价入口，包装范围不覆盖声明来源。
    @Test
    void acceptsEquivalentTopInnerAndRawDeclarationRoots() {
        for (String code : List.of("function &run(?Thing &$value = null): ?Thing { return $value; }",
                "abstract class C extends Base implements First, Second { public $value = 1; }",
                "interface I extends First, Second { function run(); }",
                "trait T { use A; function run() { return 1; } }")) {
            AstNode declaration = declaration(parsedTop(code));
            IrStatement raw = convert(declaration);
            assertEquals(raw, convert(top(declaration, OUTER)));
            assertEquals(raw, convert(inner(declaration, GROUP)));
            assertSource(declaration, raw.source());
        }
    }

    // 八种已知包装的声明字段均为必需，不能将缺失声明视为空语句或未知来源。
    @Test
    void rejectsMissingDeclarationWrapperFields() {
        for (FailureCase failure : List.of(
                new FailureCase(new NodeTopStatement.FunctionDecl(null, OUTER), ".function"),
                new FailureCase(new NodeInnerStatement.FunctionDecl(null, OUTER), ".function"),
                new FailureCase(new NodeTopStatement.ClassDecl(null, OUTER), ".clazz"),
                new FailureCase(new NodeInnerStatement.ClassDecl(null, OUTER), ".clazz"),
                new FailureCase(new NodeTopStatement.InterfaceDecl(null, OUTER), ".iface"),
                new FailureCase(new NodeInnerStatement.InterfaceDecl(null, OUTER), ".iface"),
                new FailureCase(new NodeTopStatement.TraitDecl(null, OUTER), ".trait"),
                new FailureCase(new NodeInnerStatement.TraitDecl(null, OUTER), ".trait"))) {
            SyntaxConversionException error = assertThrows(SyntaxConversionException.class, () -> convert(failure.node()));
            assertDiagnostic(error, failure.suffix());
            assertEquals(range(OUTER), error.source().range());
        }
    }

    // 未知包装和实际声明变体即使带有正确 getter，也不能通过猜测字段被接受。
    @Test
    void rejectsUnknownDeclarationAndWrapperVariants() {
        NodeTopStatement unknownTop = new NodeTopStatement() {
            @Override public NodeFunctionDeclarationStatement getFunction() { return function(); }
        };
        NodeInnerStatement unknownInner = new NodeInnerStatement() {
            @Override public NodeClassDeclarationStatement getClazz() { return clazz(members()); }
        };
        assertFailure(unknownTop, "body.statements[0]");
        assertFailure(unknownInner, "body.statements[0]");
        var unknownFunction = new NodeFunctionDeclarationStatement() {
            @Override public NodeString getName() { return token("run", LEAF); }
            @Override public NodeListNodeParameter getParams() { return parameters(); }
            @Override public NodeReturnType getReturnType() { return new NodeReturnType(); }
            @Override public NodeListNodeInnerStatement getStmts() { return statements(); }
        };
        var unknownClass = new NodeClassDeclarationStatement() {
            @Override public NodeString getName() { return token("C", LEAF); }
            @Override public NodeExtendsFrom getExtendsFrom() { return new NodeExtendsFrom(); }
            @Override public NodeImplementsList getImplementsList() { return new NodeImplementsList(); }
            @Override public NodeListNodeClassStatement getMembers() { return members(); }
        };
        var unknownInterface = new NodeInterfaceDeclarationStatement() {
            @Override public NodeString getName() { return token("I", LEAF); }
            @Override public NodeInterfaceExtendsList getExtendsFrom() { return new NodeInterfaceExtendsList(); }
            @Override public NodeListNodeClassStatement getMembers() { return members(); }
        };
        var unknownTrait = new NodeTraitDeclarationStatement() {
            @Override public NodeString getName() { return token("T", LEAF); }
            @Override public NodeListNodeClassStatement getMembers() { return members(); }
        };
        for (AstNode node : List.of(new NodeFunctionDeclarationStatement(), new NodeClassDeclarationStatement(),
                new NodeInterfaceDeclarationStatement(), new NodeTraitDeclarationStatement(),
                unknownFunction, unknownClass, unknownInterface, unknownTrait)) {
            assertFailure(node, "body.statements[0]");
            assertFailure(top(node, OUTER), wrapperSuffix(node));
            assertFailure(inner(node, OUTER), wrapperSuffix(node));
        }
    }

    // 名称不可缺失或为空，省略返回类型和继承内容不代表语法包装可以缺失。
    @Test
    void rejectsMissingNamesAndRequiredDeclarationFields() {
        for (NodeString name : new NodeString[]{null, token(null, LEAF), token("", LEAF)}) {
            assertFailure(new NodeFunctionDeclarationStatement.FuncDecl(null, name, parameters(), new NodeReturnType(),
                    statements(), DECLARATION), ".name");
            assertFailure(new NodeClassDeclarationStatement.Class(name, new NodeExtendsFrom(), new NodeImplementsList(),
                    members(), DECLARATION), ".name");
            assertFailure(new NodeInterfaceDeclarationStatement.InterfaceDecl(name, new NodeInterfaceExtendsList(),
                    members(), DECLARATION), ".name");
            assertFailure(new NodeTraitDeclarationStatement.TraitDecl(name, members(), DECLARATION), ".name");
        }
        for (FailureCase failure : List.of(
                new FailureCase(new NodeFunctionDeclarationStatement.FuncDecl(null, token("run", LEAF), null,
                        new NodeReturnType(), statements(), DECLARATION), ".params"),
                new FailureCase(new NodeFunctionDeclarationStatement.FuncDecl(null, token("run", LEAF), parameters(),
                        null, statements(), DECLARATION), ".returnType"),
                new FailureCase(new NodeFunctionDeclarationStatement.FuncDecl(null, token("run", LEAF), parameters(),
                        new NodeReturnType(), null, DECLARATION), ".stmts"),
                new FailureCase(clazz(null, new NodeImplementsList(), members()), ".extendsFrom"),
                new FailureCase(clazz(new NodeExtendsFrom(), null, members()), ".implementsList"),
                new FailureCase(clazz(null), ".members"),
                new FailureCase(new NodeInterfaceDeclarationStatement.InterfaceDecl(token("I", LEAF), null,
                        members(), DECLARATION), ".extendsFrom"),
                new FailureCase(iface(new NodeInterfaceExtendsList(), null), ".members"),
                new FailureCase(trait(null), ".members"))) {
            assertFailure(failure.node(), failure.suffix());
        }
    }

    // 函数委派给共享签名规则，必需默认值、引用标记与类型包装不能被跳过。
    @Test
    void rejectsMalformedFunctionSignaturesAndBodies() {
        for (NodeString marker : new NodeString[]{token(null, LEAF), token("", LEAF), token("&&", LEAF)}) {
            assertFailure(new NodeFunctionDeclarationStatement.FuncDecl(marker, token("run", LEAF), parameters(),
                    new NodeReturnType(), statements(), DECLARATION), ".returnsRef");
        }
        assertFailure(function(new NodeListNodeParameter(null, GROUP), statements()), ".params");
        assertFailure(function(new NodeListNodeParameter(Arrays.asList(parameter(), null), GROUP), statements()), ".params[1]");
        assertFailure(function(parameters(new NodeParameter()), statements()), ".params[0]");
        assertFailure(function(parameters(new NodeParameter.Param(null, null, null, null, ITEM)), statements()), ".params[0].var");
        assertFailure(function(parameters(new NodeParameter.ParamWithDefault(null, null, null,
                token("value", LEAF), null, ITEM)), statements()), ".params[0].defaultValue");
        assertFailure(function(parameters(new NodeParameter.Param(null, token("&&", LEAF), null,
                token("value", LEAF), ITEM)), statements()), ".params[0].byRef");
        assertFailure(function(parameters(new NodeParameter.Param(null, null, token("..", LEAF),
                token("value", LEAF), ITEM)), statements()), ".params[0].variadic");
        for (NodeReturnType result : List.of(new NodeReturnType() {}, new NodeReturnType.ReturnType(null, ITEM))) {
            assertFailure(new NodeFunctionDeclarationStatement.FuncDecl(null, token("run", LEAF), parameters(),
                    result, statements(), DECLARATION), result instanceof NodeReturnType.ReturnType ? ".returnType.t" : ".returnType");
        }
        assertFailure(function(parameters(), new NodeListNodeInnerStatement(null, BODY)), ".stmts");
        assertFailure(function(parameters(), new NodeListNodeInnerStatement(Arrays.asList(
                new NodeInnerStatement.Statement(new NodeStatement.Return(null, ITEM), ITEM), null), BODY)), ".stmts[1]");
        IrFunctionDeclaration empty = assertInstanceOf(IrFunctionDeclaration.class, convert(function()));
        assertNotNull(empty.body());
        assertTrue(empty.body().statements().isEmpty());
    }

    // 带修饰符 class 的列表至少含一项，每个变体的标记必须与 abstract/final 对应。
    @Test
    void rejectsMalformedClassModifiers() {
        assertFailure(modifiedClass(null), ".mods");
        assertFailure(modifiedClass(new NodeListNodeClassModifier(null, GROUP)), ".mods");
        assertFailure(modifiedClass(new NodeListNodeClassModifier(List.of(), GROUP)), ".mods");
        assertFailure(modifiedClass(new NodeListNodeClassModifier(Arrays.asList(
                new NodeClassModifier.Abstract(token("abstract", LEAF), ITEM), null), GROUP)), ".mods[1]");
        NodeClassModifier unknown = new NodeClassModifier() {
            @Override public NodeString getKw() { return token("abstract", LEAF); }
        };
        assertFailure(modifiedClass(new NodeListNodeClassModifier(List.of(unknown), GROUP)), ".mods[0]");
        for (NodeString marker : new NodeString[]{null, token(null, LEAF), token("", LEAF), token("public", LEAF)}) {
            assertFailure(modifiedClass(new NodeListNodeClassModifier(List.of(
                    new NodeClassModifier.Abstract(marker, ITEM)), GROUP)), ".mods[0].kw");
            assertFailure(modifiedClass(new NodeListNodeClassModifier(List.of(
                    new NodeClassModifier.Final(marker, ITEM)), GROUP)), ".mods[0].kw");
        }
        assertFailure(modifiedClass(new NodeListNodeClassModifier(List.of(
                new NodeClassModifier.Abstract(token("final", LEAF), ITEM)), GROUP)), ".mods[0].kw");
        assertFailure(modifiedClass(new NodeListNodeClassModifier(List.of(
                new NodeClassModifier.Final(token("abstract", LEAF), ITEM)), GROUP)), ".mods[0].kw");
    }

    // 继承的精确空包装表示省略，未知子类和显式空名称列表则必须拒绝。
    @Test
    void rejectsMalformedClassAndInterfaceInheritance() {
        assertFailure(clazz(new NodeExtendsFrom() {}, new NodeImplementsList(), members()), ".extendsFrom");
        assertFailure(clazz(new NodeExtendsFrom.Extends(null, GROUP), new NodeImplementsList(), members()), ".extendsFrom.n");
        assertFailure(clazz(new NodeExtendsFrom.Extends(new NodeName.Unqualified(null, LEAF), GROUP),
                new NodeImplementsList(), members()), ".extendsFrom.n.n");
        assertFailure(clazz(new NodeExtendsFrom(), new NodeImplementsList() {}, members()), ".implementsList");
        assertFailure(iface(new NodeInterfaceExtendsList() {}, members()), ".extendsFrom");
        assertFailure(clazz(new NodeExtendsFrom(), new NodeImplementsList.ImplementsList(null, GROUP), members()), ".implementsList.names");
        assertFailure(iface(new NodeInterfaceExtendsList.ExtendsList(null, GROUP), members()), ".extendsFrom.names");
        for (NodeListNodeName names : List.of(new NodeListNodeName(null, ITEM), new NodeListNodeName(List.of(), ITEM))) {
            assertFailure(clazz(new NodeExtendsFrom(), new NodeImplementsList.ImplementsList(names, GROUP), members()), ".implementsList.names");
            assertFailure(iface(new NodeInterfaceExtendsList.ExtendsList(names, GROUP), members()), ".extendsFrom.names");
        }
        NodeListNodeName nullEntry = new NodeListNodeName(Arrays.asList(name("First", LEAF), null), ITEM);
        assertFailure(clazz(new NodeExtendsFrom(), new NodeImplementsList.ImplementsList(nullEntry, GROUP), members()), ".implementsList.names[1]");
        assertFailure(iface(new NodeInterfaceExtendsList.ExtendsList(nullEntry, GROUP), members()), ".extendsFrom.names[1]");
        NodeListNodeName brokenName = new NodeListNodeName(List.of(new NodeName.Unqualified(null, LEAF)), ITEM);
        assertFailure(iface(new NodeInterfaceExtendsList.ExtendsList(brokenName, GROUP), members()), ".extendsFrom.names[0].n");
        assertNull(assertInstanceOf(IrClassDeclaration.class, convert(clazz(members()))).parentType());
        assertTrue(assertInstanceOf(IrInterfaceDeclaration.class, convert(iface(new NodeInterfaceExtendsList(), members()))).parentTypes().isEmpty());
    }

    // 三种类型声明都复用成员转换，成员列表或方法字段损坏时不能返回部分类型定义。
    @Test
    void rejectsMalformedMembersInEveryNamedType() {
        for (NodeListNodeClassStatement list : List.of(new NodeListNodeClassStatement(null, GROUP),
                new NodeListNodeClassStatement(Arrays.asList(method(), null), GROUP),
                members(new NodeClassStatement()), members(new NodeClassStatement.Method(null, null,
                        new NodeIdentifier.Identifier(token("run", LEAF), LEAF), parameters(),
                        new NodeReturnType(), new NodeMethodBody(), ITEM)))) {
            String suffix = list.getValue() == null ? ".members"
                    : list.getValue().size() == 2 ? ".members[1]"
                    : list.getValue().getFirst() instanceof NodeClassStatement.Method ? ".members[0].methodMods" : ".members[0]";
            assertFailure(clazz(list), suffix);
            assertFailure(iface(new NodeInterfaceExtendsList(), list), suffix);
            assertFailure(trait(list), suffix);
        }
    }

    // 声明、参数、函数语句列表和成员有独立来源，不能由外层包装或声明范围统一覆盖。
    @Test
    void preservesDistinctDeclarationSignatureBodyAndMemberOrigins() {
        NodeTypeExpr type = new NodeTypeExpr.Type(new NodeType.NameType(name("Thing", LEAF), LEAF), ITEM);
        NodeParameter parameter = new NodeParameter.ParamWithDefault(type, null, null,
                token("value", LEAF), integer(VALUE), ITEM);
        NodeListNodeInnerStatement statements = statements(new NodeInnerStatement.Statement(
                new NodeStatement.Return(integer(VALUE), ITEM), GROUP));
        var function = new NodeFunctionDeclarationStatement.FuncDecl(token("&", LEAF), token("run", LEAF),
                parameters(parameter), new NodeReturnType.ReturnType(type, GROUP), statements, DECLARATION);
        IrFunctionDeclaration converted = assertInstanceOf(IrFunctionDeclaration.class, convert(top(function, OUTER)));
        assertSource(DECLARATION, converted.source());
        assertSource(ITEM, converted.parameters().getFirst().source());
        assertSource(ITEM, converted.parameters().getFirst().declaredType().source());
        assertSource(LEAF, converted.parameters().getFirst().declaredType().name().source());
        assertSource(VALUE, converted.parameters().getFirst().defaultValue().source());
        assertSource(ITEM, converted.returnType().source());
        assertSource(BODY, converted.body().source());
        assertSource(ITEM, converted.body().statements().getFirst().source());
        assertSource(VALUE, assertInstanceOf(IrReturn.class, converted.body().statements().getFirst()).value().source());

        NodeClassStatement property = new NodeClassStatement.VarPropertyDecl(token("var", LEAF),
                new NodeListNodeProperty(List.of(new NodeProperty.PropertyWithDefault(token("value", LEAF),
                        integer(VALUE), ITEM)), GROUP), GROUP);
        NodeClassStatement method = new NodeClassStatement.Method(new NodeListNodeMemberModifier(List.of(), GROUP), null,
                new NodeIdentifier.Identifier(token("run", LEAF), LEAF), parameters(), new NodeReturnType(),
                new NodeMethodBody.Body(statements, BODY), GROUP);
        NodeListNodeClassStatement members = members(property, method);
        NodeListNodeName parents = new NodeListNodeName(List.of(name("Parent", LEAF)), ITEM);
        for (AstNode declaration : List.of(clazz(new NodeExtendsFrom.Extends(name("Base", LEAF), GROUP),
                new NodeImplementsList.ImplementsList(parents, GROUP), members),
                iface(new NodeInterfaceExtendsList.ExtendsList(parents, GROUP), members), trait(members))) {
            IrStatement result = convert(inner(declaration, OUTER));
            assertSource(DECLARATION, result.source());
            List<IrClassMember> convertedMembers = switch (result) {
                case IrClassDeclaration value -> {
                    assertSource(LEAF, value.parentType().source());
                    assertSource(LEAF, value.interfaces().getFirst().source());
                    yield value.members();
                }
                case IrInterfaceDeclaration value -> {
                    assertSource(LEAF, value.parentTypes().getFirst().source());
                    yield value.members();
                }
                case IrTraitDeclaration value -> value.members();
                default -> throw new AssertionError(result);
            };
            IrProperty convertedProperty = assertInstanceOf(IrProperty.class, convertedMembers.getFirst());
            assertSource(ITEM, convertedProperty.source());
            assertSource(VALUE, convertedProperty.initialValue().source());
            IrMethod convertedMethod = assertInstanceOf(IrMethod.class, convertedMembers.get(1));
            assertSource(GROUP, convertedMethod.source());
            assertSource(BODY, convertedMethod.body().source());
        }
    }

    // 未知位置与零宽位置原样保存，既不回退到包装范围，也不使用 SyntaxBody 的备用范围。
    @Test
    void preservesUnknownAndZeroWidthDeclarationSources() throws ReflectiveOperationException {
        SourceInfo fallback = new SourceInfo("declarations.php", new SourceRange(99, 1, 99, 9));
        for (ComplexLocation location : List.of(ComplexLocation.NO_LOCATION, ComplexLocation.of(8, 2, 8, 2))) {
            NodeListNodeInnerStatement statements = new NodeListNodeInnerStatement(List.of(
                    new NodeInnerStatement.Statement(new NodeStatement.Return(integer(location), location), location)), location);
            var function = new NodeFunctionDeclarationStatement.FuncDecl(null, token("run", location),
                    new NodeListNodeParameter(List.of(new NodeParameter.ParamWithDefault(null, null, null,
                            token("value", location), integer(location), location)), location),
                    new NodeReturnType(), statements, location);
            var members = new NodeListNodeClassStatement(List.of(new NodeClassStatement.VarPropertyDecl(token("var", location),
                    new NodeListNodeProperty(List.of(new NodeProperty.PropertyWithDefault(token("value", location),
                            integer(location), location)), location), location)), location);
            NodeListNodeName names = new NodeListNodeName(List.of(name("Parent", location)), location);
            var clazz = new NodeClassDeclarationStatement.Class(token("C", location),
                    new NodeExtendsFrom.Extends(name("Base", location), location),
                    new NodeImplementsList.ImplementsList(names, location), members, location);
            var iface = new NodeInterfaceDeclarationStatement.InterfaceDecl(token("I", location),
                    new NodeInterfaceExtendsList.ExtendsList(names, location), members, location);
            var trait = new NodeTraitDeclarationStatement.TraitDecl(token("T", location), members, location);
            IrBlock result = SyntaxConverter.convertBody(new SyntaxBody(List.of(top(function, OUTER), inner(clazz, OUTER),
                    top(iface, OUTER), inner(trait, OUTER)), fallback));
            assertEquals(fallback, result.source());
            for (IrStatement declaration : result.statements()) {
                assertAllSources(declaration, location.isNoLocation() ? null : range(location));
            }
        }
    }

    // 必需名称、函数体及列表均不允许 null；名称非空，空参数或成员列表本身合法。
    @Test
    void validatesRequiredDeclarationModelFields() {
        IrBlock body = new IrBlock(List.of(), SOURCE);
        for (Executable constructor : List.<Executable>of(
                () -> new IrFunctionDeclaration(null, List.of(), null, false, body, SOURCE),
                () -> new IrFunctionDeclaration("run", null, null, false, body, SOURCE),
                () -> new IrFunctionDeclaration("run", List.of(), null, false, null, SOURCE),
                () -> new IrFunctionDeclaration("run", List.of(), null, false, body, null),
                () -> new IrClassDeclaration(null, List.of(), null, List.of(), List.of(), SOURCE),
                () -> new IrClassDeclaration("C", null, null, List.of(), List.of(), SOURCE),
                () -> new IrClassDeclaration("C", List.of(), null, null, List.of(), SOURCE),
                () -> new IrClassDeclaration("C", List.of(), null, List.of(), null, SOURCE),
                () -> new IrClassDeclaration("C", List.of(), null, List.of(), List.of(), null),
                () -> new IrInterfaceDeclaration(null, List.of(), List.of(), SOURCE),
                () -> new IrInterfaceDeclaration("I", null, List.of(), SOURCE),
                () -> new IrInterfaceDeclaration("I", List.of(), null, SOURCE),
                () -> new IrInterfaceDeclaration("I", List.of(), List.of(), null),
                () -> new IrTraitDeclaration(null, List.of(), SOURCE),
                () -> new IrTraitDeclaration("T", null, SOURCE),
                () -> new IrTraitDeclaration("T", List.of(), null))) {
            assertThrows(NullPointerException.class, constructor);
        }
        for (Executable constructor : List.<Executable>of(
                () -> new IrFunctionDeclaration("", List.of(), null, false, body, SOURCE),
                () -> new IrClassDeclaration("", List.of(), null, List.of(), List.of(), SOURCE),
                () -> new IrInterfaceDeclaration("", List.of(), List.of(), SOURCE),
                () -> new IrTraitDeclaration("", List.of(), SOURCE))) {
            assertThrows(IllegalArgumentException.class, constructor);
        }
        assertNull(new IrFunctionDeclaration("run", List.of(), null, false, body, SOURCE).returnType());
        assertNull(new IrClassDeclaration("C", List.of(), null, List.of(), List.of(), SOURCE).parentType());
    }

    // 每种新列表都拒绝 null 元素，不把损坏记录留到下游遍历阶段才发现。
    @Test
    void rejectsNullElementsInEveryDeclarationModelList() {
        IrBlock body = new IrBlock(List.of(), SOURCE);
        var parameter = new IrParameter("value", null, false, false, null, SOURCE);
        var name = new NameReference("Parent", NameForm.UNQUALIFIED, SOURCE);
        var member = new IrProperty("value", List.of(), null, SOURCE);
        for (Executable constructor : List.<Executable>of(
                () -> new IrFunctionDeclaration("run", Arrays.asList(parameter, null), null, false, body, SOURCE),
                () -> new IrClassDeclaration("C", Arrays.asList(Modifier.ABSTRACT, null), null, List.of(), List.of(), SOURCE),
                () -> new IrClassDeclaration("C", List.of(), null, Arrays.asList(name, null), List.of(), SOURCE),
                () -> new IrClassDeclaration("C", List.of(), null, List.of(), Arrays.asList(member, null), SOURCE),
                () -> new IrInterfaceDeclaration("I", Arrays.asList(name, null), List.of(), SOURCE),
                () -> new IrInterfaceDeclaration("I", List.of(), Arrays.asList(member, null), SOURCE),
                () -> new IrTraitDeclaration("T", Arrays.asList(member, null), SOURCE))) {
            assertThrows(NullPointerException.class, constructor);
        }
    }

    // 所有声明列表保存有序只读快照，重复参数、修饰符、父类型和成员不被合并。
    @Test
    void snapshotsDeclarationCollectionsWithoutRemovingDuplicates() {
        IrBlock body = new IrBlock(List.of(), SOURCE);
        var parameter = new IrParameter("value", null, false, false, null, SOURCE);
        var parameters = new ArrayList<>(List.of(parameter, parameter));
        var flags = new ArrayList<>(List.of(Modifier.ABSTRACT, Modifier.ABSTRACT, Modifier.FINAL));
        var name = new NameReference("Parent", NameForm.UNQUALIFIED, SOURCE);
        var names = new ArrayList<>(List.of(name, name));
        IrClassMember member = new IrProperty("value", List.of(), null, SOURCE);
        var members = new ArrayList<>(List.of(member, member));
        var function = new IrFunctionDeclaration("Run", parameters, null, true, body, SOURCE);
        var clazz = new IrClassDeclaration("C", flags, name, names, members, SOURCE);
        var iface = new IrInterfaceDeclaration("I", names, members, SOURCE);
        var trait = new IrTraitDeclaration("T", members, SOURCE);
        parameters.clear(); flags.clear(); names.clear(); members.clear();
        assertEquals(List.of(parameter, parameter), function.parameters());
        assertEquals(List.of(Modifier.ABSTRACT, Modifier.ABSTRACT, Modifier.FINAL), clazz.declaredModifiers());
        assertEquals(List.of(name, name), clazz.interfaces());
        assertEquals(clazz.interfaces(), iface.parentTypes());
        assertEquals(List.of(member, member), clazz.members());
        assertEquals(clazz.members(), iface.members());
        assertEquals(clazz.members(), trait.members());
        for (List<?> list : List.of(function.parameters(), clazz.declaredModifiers(), clazz.interfaces(), clazz.members(),
                iface.parentTypes(), iface.members(), trait.members())) {
            assertThrows(UnsupportedOperationException.class, list::clear);
        }
        IrClassDeclaration converted = assertInstanceOf(IrClassDeclaration.class, convert(parsedTop(
                "abstract class C implements I, I { public $value, $value; }")));
        assertEquals(2, converted.interfaces().size());
        assertEquals(2, converted.members().size());
        assertThrows(UnsupportedOperationException.class, converted.interfaces()::clear);
        assertThrows(UnsupportedOperationException.class, converted.members()::clear);
    }

    // 声明的默认值、方法与嵌套主体都递归转换，任一不支持子树使整个结果失败。
    @Test
    void propagatesUnsupportedSubtreesThroughDeclarationBoundaries() {
        for (UnsupportedCase test : List.of(
                new UnsupportedCase("function run($value = ($invalid[])) {}", ".params[0].defaultValue"),
                new UnsupportedCase("function run() { ($invalid[]); }", ".stmts[0].stmt.expression"),
                new UnsupportedCase("function run() { function nested() { ($invalid[]); } }", ".function.stmts[0].stmt.expression"),
                new UnsupportedCase("class C { public $value = ($invalid[]); }", ".props[0].defaultValue"),
                new UnsupportedCase("class C { const VALUE = ($invalid[]); }", ".consts[0].value"),
                new UnsupportedCase("class C { function run($value = ($invalid[])) {} }", ".params[0].defaultValue"),
                new UnsupportedCase("class C { function run() { ($invalid[]); } }", ".body.stmts[0].stmt.expression"),
                new UnsupportedCase("interface I { const VALUE = ($invalid[]); }", ".consts[0].value"),
                new UnsupportedCase("interface I { function run($value = ($invalid[])); }", ".params[0].defaultValue"),
                new UnsupportedCase("trait T { public $value = ($invalid[]); }", ".props[0].defaultValue"),
                new UnsupportedCase("trait T { function run() { ($invalid[]); } }", ".body.stmts[0].stmt.expression"))) {
            NodeTopStatement node = assertDoesNotThrow(() -> parsedTop(test.code()), test.code());
            SyntaxConversionException error = assertThrows(SyntaxConversionException.class, () -> convert(node), test.code());
            assertEquals("declarations.php", error.source().sourceId());
            assertTrue(error.fieldPath().contains(test.pathPart()), error.fieldPath());
            assertFalse(error.reason().isBlank());
        }
    }

    // 选定语法体的声明转换不提升第一阶段索引，不修改 AST；失败不会污染后续成功调用。
    @Test
    void keepsExtractionIndexesAndAstUnchangedAcrossNamedConversions() throws ReflectiveOperationException {
        var file = DeclarationExtractor.extract(Main.parse("""
                <?php
                namespace Demo;
                class Owner {
                    public function run() {
                        function inMethod() { return "method"; }
                        class InMethod { public $value = "ready"; }
                    }
                }
                function outer() {
                    if ($enabled) { function conditional() {} class Conditional {} }
                    function nested() { return function() { function deeper() {} }; }
                    trait LocalTrait { function work() {} }
                    interface LocalInterface { function work(); }
                    return new class { function build() { class DeepClass {} } };
                }
                function broken() { function invalid() { return ($invalid[]); } }
                """), "declarations.php");
        var section = file.namespaceSections().getFirst();
        var declarationsBefore = List.copyOf(section.declarations());
        var owner = assertInstanceOf(ClassLikeDefinition.class, declarationsBefore.getFirst());
        var method = assertInstanceOf(MethodDefinition.class, owner.members().getFirst());
        var outer = assertInstanceOf(FunctionDefinition.class, declarationsBefore.get(1));
        var broken = assertInstanceOf(FunctionDefinition.class, declarationsBefore.get(2));
        var membersBefore = List.copyOf(owner.members());
        String astBefore = file.syntax().toTreeString(false);
        assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(broken.body()));
        for (SyntaxBody selected : List.of(method.body(), outer.body())) {
            var statementsBefore = List.copyOf(selected.statements());
            IrBlock result = SyntaxConverter.convertBody(selected);
            assertEquals(selected.source(), result.source());
            assertEquals(result, SyntaxConverter.convertBody(selected));
            assertNoAst(result, Collections.newSetFromMap(new IdentityHashMap<>()));
            assertEquals(statementsBefore, selected.statements());
            for (int i = 0; i < statementsBefore.size(); i++) assertSame(statementsBefore.get(i), selected.statements().get(i));
        }
        assertEquals(astBefore, file.syntax().toTreeString(false));
        assertEquals(declarationsBefore, section.declarations());
        assertEquals(membersBefore, owner.members());
        for (TopLevelDeclaration declaration : declarationsBefore) {
            assertSame(declaration, file.declarationIndex().findById(declaration.id()).orElseThrow());
        }
        for (ClassMember member : membersBefore) assertSame(member, file.declarationIndex().findById(member.id()).orElseThrow());
        assertSame(owner, file.declarationIndex().findTopLevel(TopLevelKind.TYPE, "Demo\\Owner").getFirst());
        assertSame(method, file.declarationIndex().findMembers(owner.id(), MemberKind.METHOD, "run").getFirst());
        assertSame(outer, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "Demo\\outer").getFirst());
        for (String name : List.of("inMethod", "conditional", "nested", "deeper", "invalid", "work", "build")) {
            assertTrue(file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "Demo\\" + name).isEmpty(), name);
        }
        for (String name : List.of("InMethod", "Conditional", "LocalTrait", "LocalInterface", "DeepClass")) {
            assertTrue(file.declarationIndex().findTopLevel(TopLevelKind.TYPE, "Demo\\" + name).isEmpty(), name);
        }
        assertTrue(file.declarationIndex().findMembers(owner.id(), MemberKind.METHOD, "inMethod").isEmpty());
    }

    private record FailureCase(AstNode node, String suffix) {}
    private record UnsupportedCase(String code, String pathPart) {}

    private static IrStatement convert(AstNode node) {
        return SyntaxConverter.convertBody(new SyntaxBody(List.of(node), SOURCE)).statements().getFirst();
    }

    private static void assertFailure(AstNode node, String suffix) {
        assertDiagnostic(assertThrows(SyntaxConversionException.class, () -> convert(node)), suffix);
    }

    private static void assertDiagnostic(SyntaxConversionException error, String suffix) {
        assertEquals("declarations.php", error.source().sourceId());
        assertTrue(error.fieldPath().endsWith(suffix), error.fieldPath());
        assertFalse(error.reason().isBlank());
        assertTrue(error.getMessage().contains("declarations.php"));
        assertTrue(error.getMessage().contains(error.fieldPath()));
    }

    private static NodeTopStatement parsedTop(String code) {
        return ((NodeProgram) Main.parse("<?php " + code)).getStmts().getValue().getFirst();
    }

    private static AstNode declaration(NodeTopStatement node) {
        return switch (node) {
            case NodeTopStatement.FunctionDecl value -> value.getFunction();
            case NodeTopStatement.ClassDecl value -> value.getClazz();
            case NodeTopStatement.InterfaceDecl value -> value.getIface();
            case NodeTopStatement.TraitDecl value -> value.getTrait();
            default -> throw new AssertionError(node);
        };
    }

    private static NodeTopStatement top(AstNode node, ComplexLocation location) {
        return switch (node) {
            case NodeFunctionDeclarationStatement value -> new NodeTopStatement.FunctionDecl(value, location);
            case NodeClassDeclarationStatement value -> new NodeTopStatement.ClassDecl(value, location);
            case NodeInterfaceDeclarationStatement value -> new NodeTopStatement.InterfaceDecl(value, location);
            case NodeTraitDeclarationStatement value -> new NodeTopStatement.TraitDecl(value, location);
            default -> throw new AssertionError(node);
        };
    }

    private static NodeInnerStatement inner(AstNode node, ComplexLocation location) {
        return switch (node) {
            case NodeFunctionDeclarationStatement value -> new NodeInnerStatement.FunctionDecl(value, location);
            case NodeClassDeclarationStatement value -> new NodeInnerStatement.ClassDecl(value, location);
            case NodeInterfaceDeclarationStatement value -> new NodeInnerStatement.InterfaceDecl(value, location);
            case NodeTraitDeclarationStatement value -> new NodeInnerStatement.TraitDecl(value, location);
            default -> throw new AssertionError(node);
        };
    }

    private static String wrapperSuffix(AstNode node) {
        return switch (node) {
            case NodeFunctionDeclarationStatement ignored -> ".function";
            case NodeClassDeclarationStatement ignored -> ".clazz";
            case NodeInterfaceDeclarationStatement ignored -> ".iface";
            case NodeTraitDeclarationStatement ignored -> ".trait";
            default -> throw new AssertionError(node);
        };
    }

    private static NodeFunctionDeclarationStatement function() { return function(parameters(), statements()); }

    private static NodeFunctionDeclarationStatement function(NodeListNodeParameter parameters, NodeListNodeInnerStatement body) {
        return new NodeFunctionDeclarationStatement.FuncDecl(null, token("run", LEAF), parameters,
                new NodeReturnType(), body, DECLARATION);
    }

    private static NodeClassDeclarationStatement clazz(NodeListNodeClassStatement members) {
        return clazz(new NodeExtendsFrom(), new NodeImplementsList(), members);
    }

    private static NodeClassDeclarationStatement clazz(NodeExtendsFrom parent, NodeImplementsList interfaces,
                                                       NodeListNodeClassStatement members) {
        return new NodeClassDeclarationStatement.Class(token("C", LEAF), parent, interfaces, members, DECLARATION);
    }

    private static NodeClassDeclarationStatement modifiedClass(NodeListNodeClassModifier modifiers) {
        return new NodeClassDeclarationStatement.ClassWithModifiers(modifiers, token("C", LEAF), new NodeExtendsFrom(),
                new NodeImplementsList(), members(), DECLARATION);
    }

    private static NodeInterfaceDeclarationStatement iface(NodeInterfaceExtendsList parents, NodeListNodeClassStatement members) {
        return new NodeInterfaceDeclarationStatement.InterfaceDecl(token("I", LEAF), parents, members, DECLARATION);
    }

    private static NodeTraitDeclarationStatement trait(NodeListNodeClassStatement members) {
        return new NodeTraitDeclarationStatement.TraitDecl(token("T", LEAF), members, DECLARATION);
    }

    private static NodeListNodeClassStatement members(NodeClassStatement... members) {
        return new NodeListNodeClassStatement(List.of(members), GROUP);
    }

    private static NodeListNodeInnerStatement statements(NodeInnerStatement... statements) {
        return new NodeListNodeInnerStatement(List.of(statements), BODY);
    }

    private static NodeListNodeParameter parameters(NodeParameter... parameters) {
        return new NodeListNodeParameter(List.of(parameters), GROUP);
    }

    private static NodeParameter parameter() {
        return new NodeParameter.Param(null, null, null, token("value", LEAF), ITEM);
    }

    private static NodeClassStatement method() {
        return new NodeClassStatement.Method(new NodeListNodeMemberModifier(List.of(), GROUP), null,
                new NodeIdentifier.Identifier(token("run", LEAF), LEAF), parameters(), new NodeReturnType(),
                new NodeMethodBody(), ITEM);
    }

    private static NodeString token(String value, ComplexLocation location) { return new NodeString(value, location); }

    private static NodeName name(String value, ComplexLocation location) {
        return new NodeName.Unqualified(new NodeNamespaceName.Part(token(value, location), location), location);
    }

    private static NodeExpr integer(ComplexLocation location) {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(token("1", location), location), location), location);
    }

    private static void assertSource(AstNode expected, SourceInfo actual) {
        assertSource(assertInstanceOf(ComplexLocation.class, expected.getLocation()), actual);
    }

    private static void assertSource(ComplexLocation expected, SourceInfo actual) {
        assertEquals("declarations.php", actual.sourceId());
        assertEquals(expected.isNoLocation() ? null : range(expected), actual.range());
    }

    private static SourceRange range(ComplexLocation location) {
        return new SourceRange(location.getStartLine(), location.getStartColumn(), location.getEndLine(), location.getEndColumn());
    }

    private static void assertAllSources(Object value, SourceRange expected) throws ReflectiveOperationException {
        if (value == null) return;
        if (value instanceof SourceInfo(String sourceId, SourceRange range)) {
            assertEquals("declarations.php", sourceId);
            assertEquals(expected, range);
        } else if (value instanceof List<?> list) {
            for (Object child : list) assertAllSources(child, expected);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertAllSources(component.getAccessor().invoke(value), expected);
            }
        }
    }

    private static void assertNoAst(Object value, Set<Object> visited) throws ReflectiveOperationException {
        if (value == null || !visited.add(value)) return;
        if (value instanceof ByteString bytes) {
            byte[] snapshot = bytes.toByteArray();
            assertEquals(snapshot.length, bytes.size());
            return;
        }
        assertFalse(value instanceof AstNode, "具名声明 IR 不应保留 CUP AST");
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
