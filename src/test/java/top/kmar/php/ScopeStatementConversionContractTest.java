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

/** 验证作用域相关语句的结构约束、损坏 AST、来源和不可变模型契约。 */
class ScopeStatementConversionContractTest {
    private static final ComplexLocation OUTER = ComplexLocation.of(3, 2, 9, 30);
    private static final ComplexLocation ITEMS = ComplexLocation.of(4, 3, 4, 25);
    private static final ComplexLocation ENTRY = ComplexLocation.of(5, 4, 5, 20);
    private static final ComplexLocation LEAF = ComplexLocation.of(6, 5, 6, 11);
    private static final ComplexLocation VALUE = ComplexLocation.of(7, 6, 7, 15);
    private static final ComplexLocation BODY = ComplexLocation.of(8, 2, 8, 22);
    private static final SourceInfo SOURCE = new SourceInfo("scope.php", null);

    // global 的语法列表必须存在且非空；缺失元素不是可省略的变量声明。
    @Test
    void rejectsMissingEmptyAndMalformedGlobalLists() {
        assertFailure(new NodeStatement.Global(null, OUTER), ".globalVars");
        assertFailure(new NodeStatement.Global(new NodeListNodeSimpleVariable(null, ITEMS), OUTER), ".globalVars");
        assertFailure(new NodeStatement.Global(new NodeListNodeSimpleVariable(List.of(), ITEMS), OUTER), ".globalVars");
        assertFailure(new NodeStatement.Global(new NodeListNodeSimpleVariable(
                Arrays.asList(named("value", LEAF), null), ITEMS), OUTER), ".globalVars[1]");
    }

    // 固定、嵌套和间接全局变量各自的必需字段要校验，未知 simple_variable 不能降级。
    @Test
    void rejectsMissingAndUnknownGlobalVariableFields() {
        for (NodeString name : new NodeString[]{null, token(null, LEAF), token("", LEAF)}) {
            assertFailure(globals(new NodeSimpleVariable.NamedVar(name, ENTRY)), ".globalVars[0].var");
        }
        assertFailure(globals(new NodeSimpleVariable.NestedVar(null, ENTRY)), ".globalVars[0].nested");
        assertFailure(globals(new NodeSimpleVariable.IndirectVar(null, ENTRY)), ".globalVars[0].e");
        assertFailure(globals(new NodeSimpleVariable() {
            @Override public NodeString getVar() { return token("value", LEAF); }
            @Override public ComplexLocation getLocation() { return ENTRY; }
        }), ".globalVars[0]");
        assertFailure(globals(new NodeSimpleVariable.NestedVar(new NodeSimpleVariable.IndirectVar(null, LEAF), ENTRY)),
                ".nested.e");
    }

    // static 列表与元素不能缺失，合法的省略初始化也需要明确的 StaticVar 包装。
    @Test
    void rejectsMissingEmptyAndMalformedStaticVariableLists() {
        assertFailure(new NodeStatement.Static(null, OUTER), ".staticVars");
        assertFailure(new NodeStatement.Static(new NodeListNodeStaticVar(null, ITEMS), OUTER), ".staticVars");
        assertFailure(new NodeStatement.Static(new NodeListNodeStaticVar(List.of(), ITEMS), OUTER), ".staticVars");
        assertFailure(new NodeStatement.Static(new NodeListNodeStaticVar(
                Arrays.asList(staticVariable("value"), null), ITEMS), OUTER), ".staticVars[1]");
    }

    // 静态变量名必需，带默认值的变体必须有表达式；未知变体不视为省略初始化。
    @Test
    void rejectsMalformedStaticVariableNamesInitializersAndVariants() {
        for (NodeString name : new NodeString[]{null, token(null, LEAF), token("", LEAF)}) {
            assertFailure(statics(new NodeStaticVar.StaticVar(name, ENTRY)), ".staticVars[0].var");
            assertFailure(statics(new NodeStaticVar.StaticVarWithDefault(name, integer(1, VALUE), ENTRY)),
                    ".staticVars[0].var");
        }
        assertFailure(statics(new NodeStaticVar.StaticVarWithDefault(token("value", LEAF), null, ENTRY)),
                ".staticVars[0].defaultValue");
        assertFailure(statics(new NodeStaticVar() {
            @Override public NodeString getVar() { return token("value", LEAF); }
            @Override public NodeExpr getDefaultValue() { return integer(1, VALUE); }
            @Override public ComplexLocation getLocation() { return ENTRY; }
        }), ".staticVars[0]");
    }

    // declare 指令列表不能缺失或为空，列表元素也不能为 null。
    @Test
    void rejectsMissingEmptyAndMalformedDirectiveLists() {
        assertFailure(new NodeStatement.Declare(null, semicolonBody(), OUTER), ".directives");
        assertFailure(new NodeStatement.Declare(new NodeListNodeConstDecl(null, ITEMS), semicolonBody(), OUTER), ".directives");
        assertFailure(new NodeStatement.Declare(new NodeListNodeConstDecl(List.of(), ITEMS), semicolonBody(), OUTER), ".directives");
        assertFailure(new NodeStatement.Declare(new NodeListNodeConstDecl(
                Arrays.asList(directive("ticks"), null), ITEMS), semicolonBody(), OUTER), ".directives[1]");
    }

    // 指令名和指令值都是必需字段，未知 const_decl 不能凭同名 getter 接受。
    @Test
    void rejectsMissingAndUnknownDeclareDirectiveFields() {
        for (NodeString name : new NodeString[]{null, token(null, LEAF), token("", LEAF)}) {
            assertFailure(declare(semicolonBody(), new NodeConstDecl.ConstDecl(name, integer(1, VALUE), ENTRY)),
                    ".directives[0].name");
        }
        assertFailure(declare(semicolonBody(), new NodeConstDecl.ConstDecl(token("ticks", LEAF), null, ENTRY)),
                ".directives[0].value");
        assertFailure(declare(semicolonBody(), new NodeConstDecl() {
            @Override public NodeString getName() { return token("ticks", LEAF); }
            @Override public NodeExpr getValue() { return integer(1, VALUE); }
            @Override public ComplexLocation getLocation() { return ENTRY; }
        }), ".directives[0]");
    }

    // declare 分号形式仍有 Body 包装与精确空语句，不能用缺失节点表示 body 为 null。
    @Test
    void rejectsMissingAndUnknownDeclareBodyWrappers() {
        assertFailure(declare(null, directive("ticks")), ".declareBody");
        assertFailure(declare(new NodeDeclareStatement.Body(null, BODY), directive("ticks")), ".declareBody.body");
        assertFailure(declare(new NodeDeclareStatement.AltBody(null, BODY), directive("ticks")), ".declareBody.stmts");
        assertFailure(declare(new NodeDeclareStatement() {
            @Override public NodeStatement getBody() { return new NodeStatement(); }
            @Override public ComplexLocation getLocation() { return BODY; }
        }, directive("ticks")), ".declareBody");
    }

    // 冒号体允许空列表，但 value 和元素缺失时不能忽略其中的语句。
    @Test
    void rejectsMalformedAlternateDeclareStatementLists() {
        assertFailure(declare(new NodeDeclareStatement.AltBody(new NodeListNodeInnerStatement(null, ITEMS), BODY),
                directive("ticks")), ".declareBody.stmts");
        assertFailure(declare(new NodeDeclareStatement.AltBody(new NodeListNodeInnerStatement(
                Arrays.asList(inner(new NodeStatement()), null), ITEMS), BODY), directive("ticks")), ".declareBody.stmts[1]");
        assertFailure(declare(new NodeDeclareStatement.AltBody(new NodeListNodeInnerStatement(
                List.of(new NodeInnerStatement.Statement(null, LEAF)), ITEMS), BODY), directive("ticks")),
                ".declareBody.stmts[0].stmt");
    }

    // 只有精确 NodeStatement 基类代表分号，未知子类不可以被当成空体或空语句。
    @Test
    void distinguishesSemicolonDeclareFromEmptyBlocksAndUnknownStatements() {
        IrDeclare semicolon = assertInstanceOf(IrDeclare.class, convert(declare(semicolonBody(), directive("ticks"))));
        assertNull(semicolon.body());
        IrDeclare block = assertInstanceOf(IrDeclare.class, convert(declare(new NodeDeclareStatement.Body(
                new NodeStatement.Block(new NodeListNodeInnerStatement(List.of(), ITEMS), BODY), OUTER), directive("ticks"))));
        assertNotNull(block.body());
        assertTrue(block.body().statements().isEmpty());
        IrDeclare alternate = assertInstanceOf(IrDeclare.class, convert(declare(new NodeDeclareStatement.AltBody(
                new NodeListNodeInnerStatement(List.of(), ITEMS), BODY), directive("ticks"))));
        assertNotNull(alternate.body());
        assertTrue(alternate.body().statements().isEmpty());
        NodeStatement unknown = new NodeStatement() {
            @Override public ComplexLocation getLocation() { return LEAF; }
        };
        assertFailure(declare(new NodeDeclareStatement.Body(unknown, BODY), directive("ticks")), ".declareBody.body");
        assertFailure(declare(new NodeDeclareStatement.Body(new NodeStatement.Block(null, BODY), BODY), directive("ticks")),
                ".declareBody.body.stmts");
    }

    // goto 与标签都要求非空名称，来源 token 不允许缺失或值为 null。
    @Test
    void rejectsMissingAndEmptyJumpLabels() {
        for (NodeString label : new NodeString[]{null, token(null, LEAF), token("", LEAF)}) {
            assertFailure(new NodeStatement.Goto(label, OUTER), ".label");
            assertFailure(new NodeStatement.Label(label, OUTER), ".label");
        }
    }

    // 初始化和已知指令的值按普通 IR 转换，不限制编译期常量；重复指令不合并。
    @Test
    void preservesNonconstantInitializersAndRepeatedKnownDirectives() {
        var initializer = variableExpression("seed", VALUE);
        IrStaticVariables variables = assertInstanceOf(IrStaticVariables.class, convert(statics(
                new NodeStaticVar.StaticVarWithDefault(token("Value", LEAF), initializer, ENTRY), staticVariable("Value"))));
        assertInstanceOf(IrVariable.class, variables.variables().getFirst().initializer());
        assertNull(variables.variables().get(1).initializer());
        assertEquals("Value", variables.variables().getFirst().name());
        assertEquals("Value", variables.variables().get(1).name());
        IrDeclare declaration = assertInstanceOf(IrDeclare.class, convert(declare(semicolonBody(),
                new NodeConstDecl.ConstDecl(token("TiCkS", LEAF), initializer, ENTRY),
                new NodeConstDecl.ConstDecl(token("ticks", LEAF), initializer, ENTRY))));
        assertEquals(List.of(DeclareDirectiveKind.TICKS, DeclareDirectiveKind.TICKS),
                declaration.directives().stream().map(IrDeclareDirective::kind).toList());
        assertInstanceOf(IrVariable.class, declaration.directives().getFirst().value());
    }

    // 新语句外壳不吞掉未支持表达式，诊断包含全局名、初始化、指令值或语句体的完整路径。
    @Test
    void propagatesUnsupportedNestedExpressionsWithFieldPaths() {
        NodeExpr unsupported = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable(), VALUE);
        assertFailure(globals(new NodeSimpleVariable.IndirectVar(unsupported, ENTRY)), ".globalVars[0].e", false);
        assertFailure(statics(new NodeStaticVar.StaticVarWithDefault(token("value", LEAF), unsupported, ENTRY)),
                ".staticVars[0].defaultValue", false);
        assertFailure(declare(semicolonBody(), new NodeConstDecl.ConstDecl(token("ticks", LEAF), unsupported, ENTRY)),
                ".directives[0].value", false);
        NodeStatement expression = new NodeStatement.ExpressionStatement(unsupported, LEAF);
        assertFailure(declare(new NodeDeclareStatement.Body(expression, BODY), directive("ticks")),
                ".declareBody.body.expression", false);
        assertFailure(declare(new NodeDeclareStatement.AltBody(new NodeListNodeInnerStatement(
                List.of(inner(expression)), ITEMS), BODY), directive("ticks")), ".declareBody.stmts[0].stmt.expression", false);
    }

    // 新模型所有必需字段均非空；可选静态初始化和 declare 分号体仍可为 null。
    @Test
    void validatesRequiredScopeModelFields() {
        IrVariableTarget target = irTarget("value");
        IrStaticVariable variable = new IrStaticVariable("value", null, SOURCE);
        IrDeclareDirective directive = new IrDeclareDirective(DeclareDirectiveKind.TICKS, new IrIntegerLiteral(1, SOURCE), SOURCE);
        for (Executable constructor : List.<Executable>of(
                () -> new IrGlobal(null, SOURCE), () -> new IrGlobal(List.of(target), null),
                () -> new IrStaticVariables(null, SOURCE), () -> new IrStaticVariables(List.of(variable), null),
                () -> new IrStaticVariable(null, null, SOURCE), () -> new IrStaticVariable("value", null, null),
                () -> new IrDeclare(null, null, SOURCE), () -> new IrDeclare(List.of(directive), null, null),
                () -> new IrDeclareDirective(null, directive.value(), SOURCE),
                () -> new IrDeclareDirective(DeclareDirectiveKind.TICKS, null, SOURCE),
                () -> new IrDeclareDirective(DeclareDirectiveKind.TICKS, directive.value(), null),
                () -> new IrGoto(null, SOURCE), () -> new IrGoto("target", null),
                () -> new IrLabel(null, SOURCE), () -> new IrLabel("target", null))) {
            assertThrows(NullPointerException.class, constructor);
        }
        assertNull(variable.initializer());
        assertNull(new IrDeclare(List.of(directive), null, SOURCE).body());
    }

    // 声明列表至少有一项且无 null 元素；变量及跳转名称保持原样，指令只保存枚举种类。
    @Test
    void validatesNonemptyListsAndNamesWithoutNormalization() {
        IrVariableTarget target = irTarget("value");
        IrStaticVariable variable = new IrStaticVariable("value", null, SOURCE);
        IrDeclareDirective directive = new IrDeclareDirective(DeclareDirectiveKind.TICKS, new IrIntegerLiteral(1, SOURCE), SOURCE);
        for (Executable constructor : List.<Executable>of(
                () -> new IrGlobal(List.of(), SOURCE), () -> new IrStaticVariables(List.of(), SOURCE),
                () -> new IrDeclare(List.of(), null, SOURCE), () -> new IrStaticVariable("", null, SOURCE),
                () -> new IrGoto("", SOURCE), () -> new IrLabel("", SOURCE))) {
            assertThrows(IllegalArgumentException.class, constructor);
        }
        for (Executable constructor : List.<Executable>of(
                () -> new IrGlobal(Arrays.asList(target, null), SOURCE),
                () -> new IrStaticVariables(Arrays.asList(variable, null), SOURCE),
                () -> new IrDeclare(Arrays.asList(directive, null), null, SOURCE))) {
            assertThrows(NullPointerException.class, constructor);
        }
        assertEquals("Value", new IrStaticVariable("Value", null, SOURCE).name());
        assertEquals(DeclareDirectiveKind.STRICT_TYPES,
                new IrDeclareDirective(DeclareDirectiveKind.STRICT_TYPES, directive.value(), SOURCE).kind());
        assertEquals("Target", new IrGoto("Target", SOURCE).label());
        assertEquals("Target", new IrLabel("Target", SOURCE).name());
    }

    // global、static 和 declare 都保存有序只读快照，不去重重复变量或指令。
    @Test
    void snapshotsAllScopeListsAndPreservesDuplicates() {
        IrVariableTarget target = irTarget("value");
        var globalItems = new ArrayList<>(List.of(target, target));
        var global = new IrGlobal(globalItems, SOURCE);
        globalItems.clear();
        assertEquals(List.of(target, target), global.variables());
        assertThrows(UnsupportedOperationException.class, global.variables()::clear);

        var staticItem = new IrStaticVariable("value", null, SOURCE);
        var staticItems = new ArrayList<>(List.of(staticItem, staticItem));
        var statics = new IrStaticVariables(staticItems, SOURCE);
        staticItems.clear();
        assertEquals(List.of(staticItem, staticItem), statics.variables());
        assertThrows(UnsupportedOperationException.class, statics.variables()::clear);

        var directive = new IrDeclareDirective(DeclareDirectiveKind.TICKS, new IrIntegerLiteral(1, SOURCE), SOURCE);
        var directiveItems = new ArrayList<>(List.of(directive, directive));
        var declaration = new IrDeclare(directiveItems, null, SOURCE);
        directiveItems.clear();
        assertEquals(List.of(directive, directive), declaration.directives());
        assertThrows(UnsupportedOperationException.class, declaration.directives()::clear);
    }

    // global、static 及其初始化表达式分别继承语句、条目和叶节点自己的范围。
    @Test
    void preservesDistinctGlobalAndStaticOrigins() {
        NodeSimpleVariable named = new NodeSimpleVariable.NamedVar(token("value", LEAF), ENTRY);
        NodeSimpleVariable dynamic = new NodeSimpleVariable.IndirectVar(variableExpression("name", VALUE), BODY);
        IrGlobal global = assertInstanceOf(IrGlobal.class, convert(globals(named, dynamic)));
        assertSource(OUTER, global.source());
        assertSource(ENTRY, global.variables().getFirst().source());
        assertSame(global.variables().getFirst().name().source(), global.variables().getFirst().source());
        assertSource(BODY, global.variables().get(1).source());
        IrComputedName computed = assertInstanceOf(IrComputedName.class, global.variables().get(1).name());
        assertSame(computed.source(), global.variables().get(1).source());
        assertSource(VALUE, computed.expression().source());

        IrStaticVariables variables = assertInstanceOf(IrStaticVariables.class, convert(statics(
                new NodeStaticVar.StaticVarWithDefault(token("value", LEAF), integer(1, VALUE), ENTRY))));
        assertSource(OUTER, variables.source());
        assertSource(ENTRY, variables.variables().getFirst().source());
        assertSource(VALUE, variables.variables().getFirst().initializer().source());
    }

    // declare 指令、指令值和三种语句体保留各自范围，goto/标签范围不替换为名称 token。
    @Test
    void preservesDistinctDeclareBodyAndJumpOrigins() {
        NodeConstDecl directive = new NodeConstDecl.ConstDecl(token("ticks", LEAF), integer(1, VALUE), ENTRY);
        NodeStatement jump = new NodeStatement.Goto(token("Target", LEAF), BODY);
        IrDeclare single = assertInstanceOf(IrDeclare.class, convert(declare(new NodeDeclareStatement.Body(jump, ITEMS), directive)));
        assertSource(OUTER, single.source());
        assertSource(ENTRY, single.directives().getFirst().source());
        assertSource(VALUE, single.directives().getFirst().value().source());
        assertSource(BODY, single.body().source());
        assertSource(BODY, single.body().statements().getFirst().source());

        NodeStatement block = new NodeStatement.Block(new NodeListNodeInnerStatement(List.of(inner(jump)), ITEMS), BODY);
        IrDeclare braced = assertInstanceOf(IrDeclare.class, convert(declare(new NodeDeclareStatement.Body(block, OUTER), directive)));
        assertSource(BODY, braced.body().source());
        IrDeclare alternate = assertInstanceOf(IrDeclare.class, convert(declare(new NodeDeclareStatement.AltBody(
                new NodeListNodeInnerStatement(List.of(inner(jump)), ITEMS), BODY), directive)));
        assertSource(BODY, alternate.body().source());
        assertSource(BODY, alternate.body().statements().getFirst().source());
        assertSource(ENTRY, convert(new NodeStatement.Label(token("Target", LEAF), ENTRY)).source());
    }

    // 未知范围不能借用外层范围，零宽范围也不能被当成未知；sourceId 始终沿调用传播。
    @Test
    void preservesUnknownAndZeroWidthScopeOrigins() {
        for (ComplexLocation location : List.of(ComplexLocation.NO_LOCATION, ComplexLocation.of(5, 7, 5, 7))) {
            SourceRange expected = location.isNoLocation() ? null : range(location);
            NodeSimpleVariable globalVariable = named("value", location);
            IrGlobal global = assertInstanceOf(IrGlobal.class, convert(new NodeStatement.Global(
                    new NodeListNodeSimpleVariable(List.of(globalVariable), OUTER), location)));
            assertEquals(expected, global.source().range());
            assertEquals(expected, global.variables().getFirst().source().range());

            IrStaticVariables variables = assertInstanceOf(IrStaticVariables.class, convert(new NodeStatement.Static(
                    new NodeListNodeStaticVar(List.of(new NodeStaticVar.StaticVarWithDefault(
                            token("value", location), integer(1, location), location)), OUTER), location)));
            assertAllSources(variables, expected);
            IrDeclare declaration = assertInstanceOf(IrDeclare.class, convert(new NodeStatement.Declare(
                    new NodeListNodeConstDecl(List.of(new NodeConstDecl.ConstDecl(token("ticks", location),
                            integer(1, location), location)), OUTER),
                    new NodeDeclareStatement.AltBody(new NodeListNodeInnerStatement(List.of(), OUTER), location), location)));
            assertAllSources(declaration, expected);
            assertAllSources(convert(new NodeStatement.Goto(token("Target", location), location)), expected);
            assertAllSources(convert(new NodeStatement.Label(token("Target", location), location)), expected);
        }
    }

    // 转换失败不污染后续入口，声明索引和原 AST 保持不变，重复转换产生等价且无 AST 的结果。
    @Test
    void convertsWithoutMutatingDeclarationsOrLeakingAst() throws ReflectiveOperationException {
        var file = DeclarationExtractor.extract(Main.parse("""
                <?php
                function scope($name) {
                    global $value, $$name, ${"global_" . $name};
                    static $counter = 0, $text = "scope", $counter = null;
                    declare(TiCkS = $value) { goto Finish; Finish: ; }
                    declare(ticks = 1);
                }
                function unsupported() { static $value = ($invalid[]); }
                """), "scope.php");
        var declarations = file.namespaceSections().getFirst().declarations();
        var function = (FunctionDefinition) declarations.getFirst();
        var unsupported = (FunctionDefinition) declarations.get(1);
        String before = file.syntax().toTreeString(false);
        assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(unsupported.body()));
        IrBlock result = SyntaxConverter.convertBody(function.body());
        assertEquals(before, file.syntax().toTreeString(false));
        assertSame(function, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "scope").getFirst());
        assertEquals(result, SyntaxConverter.convertBody(function.body()));
        assertNoAst(result, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static NodeSimpleVariable named(String name, ComplexLocation location) {
        return new NodeSimpleVariable.NamedVar(token(name, location), location);
    }

    private static NodeString token(String value, ComplexLocation location) {
        return new NodeString(value, location);
    }

    private static NodeExpr integer(int value, ComplexLocation location) {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(token(Integer.toString(value), location), location), location), location);
    }

    private static NodeExpr variableExpression(String name, ComplexLocation location) {
        return new NodeExpr.VariableExpr(new NodeVariable.CallableVariable(
                new NodeCallableVariable.SimpleVar(named(name, location), location), location), location);
    }

    private static NodeStatement globals(NodeSimpleVariable... variables) {
        return new NodeStatement.Global(new NodeListNodeSimpleVariable(List.of(variables), ITEMS), OUTER);
    }

    private static NodeStatement statics(NodeStaticVar... variables) {
        return new NodeStatement.Static(new NodeListNodeStaticVar(List.of(variables), ITEMS), OUTER);
    }

    private static NodeStaticVar staticVariable(String name) {
        return new NodeStaticVar.StaticVar(token(name, LEAF), ENTRY);
    }

    private static NodeConstDecl directive(String name) {
        return new NodeConstDecl.ConstDecl(token(name, LEAF), integer(1, VALUE), ENTRY);
    }

    private static NodeStatement declare(NodeDeclareStatement body, NodeConstDecl... directives) {
        return new NodeStatement.Declare(new NodeListNodeConstDecl(List.of(directives), ITEMS), body, OUTER);
    }

    private static NodeDeclareStatement semicolonBody() {
        return new NodeDeclareStatement.Body(new NodeStatement(), BODY);
    }

    private static NodeInnerStatement inner(NodeStatement statement) {
        return new NodeInnerStatement.Statement(statement, ENTRY);
    }

    private static IrVariableTarget irTarget(String name) {
        return new IrVariableTarget(new IrFixedName(name, SOURCE));
    }

    private static IrStatement convert(NodeStatement statement) {
        IrBlock block = SyntaxConverter.convertBody(new SyntaxBody(List.of(statement), SOURCE));
        assertEquals(1, block.statements().size());
        return block.statements().getFirst();
    }

    private static void assertFailure(NodeStatement statement, String field) {
        assertFailure(statement, field, true);
    }

    private static void assertFailure(NodeStatement statement, String field, boolean endsWith) {
        var error = assertThrows(SyntaxConversionException.class, () -> convert(statement));
        assertTrue(error.fieldPath().startsWith("body.statements[0]"), error.fieldPath());
        assertTrue(endsWith ? error.fieldPath().endsWith(field) : error.fieldPath().contains(field), error.fieldPath());
        assertEquals("scope.php", error.source().sourceId());
        assertFalse(error.reason().isBlank());
    }

    private static SourceRange range(ComplexLocation location) {
        return new SourceRange(location.getStartLine(), location.getStartColumn(), location.getEndLine(), location.getEndColumn());
    }

    private static void assertSource(ComplexLocation expected, SourceInfo actual) {
        assertEquals("scope.php", actual.sourceId());
        assertEquals(range(expected), actual.range());
    }

    private static void assertAllSources(Object value, SourceRange expected) {
        try {
            if (value == null) return;
            if (value instanceof SourceInfo(String sourceId, SourceRange range)) {
                assertEquals("scope.php", sourceId);
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
        assertFalse(value instanceof AstNode, "作用域语句 IR 不应保留 CUP AST");
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
