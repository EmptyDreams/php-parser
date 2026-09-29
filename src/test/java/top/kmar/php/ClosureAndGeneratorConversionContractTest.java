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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证闭包与生成器的损坏 AST、来源、不可变性及声明隔离契约。 */
class ClosureAndGeneratorConversionContractTest {
    private static final ComplexLocation LOCATION = ComplexLocation.of(4, 3, 4, 29);
    private static final ComplexLocation INNER = ComplexLocation.of(5, 4, 5, 18);
    private static final ComplexLocation LEAF = ComplexLocation.of(6, 5, 6, 8);
    private static final SourceInfo SOURCE = new SourceInfo("closures.php", null);

    // 普通和静态闭包的必需包装均不能以 null 代替合法空产生式。
    @Test
    void rejectsMissingClosureWrappers() {
        for (boolean isStatic : List.of(false, true)) {
            assertFailure(closure(isStatic, null, emptyUses(), emptyReturnType(), emptyBody()), ".params");
            assertFailure(closure(isStatic, emptyParameters(), null, emptyReturnType(), emptyBody()), ".uses");
            assertFailure(closure(isStatic, emptyParameters(), emptyUses(), null, emptyBody()), ".returnType");
            assertFailure(closure(isStatic, emptyParameters(), emptyUses(), emptyReturnType(), null), ".stmts");
        }
    }

    // 参数和语句列表允许为空，但包装内的值和元素不允许缺失。
    @Test
    void rejectsMalformedParameterAndBodyLists() {
        for (boolean isStatic : List.of(false, true)) {
            assertFailure(closure(isStatic, new NodeListNodeParameter(null, LOCATION), emptyUses(),
                    emptyReturnType(), emptyBody()), ".params");
            assertFailure(closure(isStatic, new NodeListNodeParameter(withNull(parameter()), LOCATION), emptyUses(),
                    emptyReturnType(), emptyBody()), ".params[1]");
            assertFailure(closure(isStatic, emptyParameters(), emptyUses(), emptyReturnType(),
                    new NodeListNodeInnerStatement(null, LOCATION)), ".stmts");
            assertFailure(closure(isStatic, emptyParameters(), emptyUses(), emptyReturnType(),
                    new NodeListNodeInnerStatement(withNull(statement(integer(1, LOCATION), LOCATION)), LOCATION)), ".stmts[1]");
            assertFailure(closure(isStatic, emptyParameters(), emptyUses(), emptyReturnType(),
                    new NodeListNodeInnerStatement(List.of(new NodeInnerStatement.Statement(null, LOCATION)), LOCATION)),
                    ".stmts[0].stmt");
        }
    }

    // 参数名和显式默认值是必需字段，未知参数即使提供有效名称也不能降级。
    @Test
    void rejectsMissingAndUnknownParameterFields() {
        for (NodeString name : new NodeString[]{null, token(null), token("")}) {
            assertParameterFailure(new NodeParameter.Param(null, null, null, name, LOCATION), ".var");
        }
        assertParameterFailure(new NodeParameter.ParamWithDefault(null, null, null, token("value"), null, LOCATION),
                ".defaultValue");
        assertParameterFailure(new NodeParameter() {
            @Override public NodeString getVar() { return token("value"); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        }, ".params[0]");
    }

    // 引用、可变参数和静态关键字必须匹配语法，不能仅以 token 存在判断布尔值。
    @Test
    void rejectsMalformedClosureAndParameterMarkers() {
        for (NodeString marker : List.of(token(null), token(""), token("&&"), token(" &"), token("& "))) {
            assertParameterFailure(new NodeParameter.Param(null, marker, null, token("value"), LOCATION), ".byRef");
            assertFailure(new NodeExprWithoutVariable.Closure(marker, emptyParameters(), emptyUses(),
                    emptyReturnType(), emptyBody(), LOCATION), ".returnsRef");
            assertFailure(new NodeExprWithoutVariable.StaticClosure(token("static"), marker, emptyParameters(),
                    emptyUses(), emptyReturnType(), emptyBody(), LOCATION), ".returnsRef");
        }
        for (NodeString marker : List.of(token(null), token(""), token(".."), token("...."), token(" ..."))) {
            assertParameterFailure(new NodeParameter.Param(null, null, marker, token("value"), LOCATION), ".variadic");
        }
        for (NodeString marker : new NodeString[]{null, token(null), token(""), token("static "), token(" static"), token("function")}) {
            assertFailure(new NodeExprWithoutVariable.StaticClosure(marker, null, emptyParameters(), emptyUses(),
                    emptyReturnType(), emptyBody(), LOCATION), ".kw");
        }
        IrClosure valid = assertInstanceOf(IrClosure.class, convert(new NodeExprWithoutVariable.StaticClosure(
                token("StAtIc"), token("&"), parameters(new NodeParameter.Param(null, token("&"), token("..."),
                token("Value"), LOCATION)), emptyUses(), emptyReturnType(), emptyBody(), LOCATION)));
        assertTrue(valid.isStatic());
        assertTrue(valid.returnsReference());
        assertTrue(valid.parameters().getFirst().byReference());
        assertTrue(valid.parameters().getFirst().variadic());
        assertEquals("Value", valid.parameters().getFirst().name());
    }

    // 只有精确的空返回类型包装表示缺省，已声明类型与未知子类不能当成无返回类型。
    @Test
    void rejectsMissingAndUnknownReturnTypeWrappers() {
        assertReturnTypeFailure(new NodeReturnType.ReturnType(null, LOCATION), ".returnType.t");
        assertReturnTypeFailure(new NodeReturnType() {
            @Override public ComplexLocation getLocation() { return LOCATION; }
        }, ".returnType");
        assertReturnTypeFailure(new NodeReturnType() {
            @Override public NodeTypeExpr getT() { return arrayType(); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        }, ".returnType");
        IrClosure closure = assertInstanceOf(IrClosure.class, convert(closure(false, emptyParameters(), emptyUses(),
                emptyReturnType(), emptyBody())));
        assertNull(closure.returnType());
        assertTrue(closure.captures().isEmpty());
    }

    // 参数和返回类型共享严格的类型转换，不猜测未知包装或缺失的类型名称。
    @Test
    void rejectsMalformedTypeWrappersAndNames() {
        NodeTypeExpr unknownExpression = new NodeTypeExpr() {
            @Override public NodeType getT() { return new NodeType.ArrayType(token("array"), LOCATION); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeType unknownType = new NodeType() {
            @Override public NodeString getKw() { return token("array"); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        for (NodeTypeExpr type : List.of(unknownExpression,
                new NodeTypeExpr.Type(null, LOCATION),
                new NodeTypeExpr.Type(unknownType, LOCATION),
                new NodeTypeExpr.Type(new NodeType.NameType(null, LOCATION), LOCATION),
                new NodeTypeExpr.Type(new NodeType.ArrayType(null, LOCATION), LOCATION),
                new NodeTypeExpr.Type(new NodeType.CallableType(token(null), LOCATION), LOCATION),
                new NodeTypeExpr.Type(new NodeType.NameType(new NodeName.Unqualified(
                        new NodeNamespaceName.Part(token(""), LOCATION), LOCATION), LOCATION), LOCATION))) {
            assertTypeFailure(type);
        }
        for (NodeString marker : new NodeString[]{null, token(null), token(""), token("??"), token(" ?")}) {
            assertTypeFailure(new NodeTypeExpr.NullableType(marker, new NodeType.ArrayType(token("array"), LOCATION), LOCATION));
        }
        for (NodeType type : List.of(new NodeType.ArrayType(token("callable"), LOCATION),
                new NodeType.CallableType(token("array"), LOCATION))) {
            assertTypeFailure(new NodeTypeExpr.Type(type, LOCATION));
        }
    }

    // use 声明必须非空，未知空包装和未知捕获变体不能静默丢失。
    @Test
    void rejectsMalformedCaptureWrappersAndLists() {
        for (NodeLexicalVars uses : List.of(
                new NodeLexicalVars.ClosureUses(null, LOCATION),
                new NodeLexicalVars.ClosureUses(new NodeListNodeLexicalVar(null, LOCATION), LOCATION),
                new NodeLexicalVars.ClosureUses(new NodeListNodeLexicalVar(List.of(), LOCATION), LOCATION),
                new NodeLexicalVars.ClosureUses(new NodeListNodeLexicalVar(withNull(capture()), LOCATION), LOCATION),
                new NodeLexicalVars() {
                    @Override public ComplexLocation getLocation() { return LOCATION; }
                },
                new NodeLexicalVars() {
                    @Override public NodeListNodeLexicalVar getVars() { return new NodeListNodeLexicalVar(List.of(capture()), LOCATION); }
                    @Override public ComplexLocation getLocation() { return LOCATION; }
                })) {
            assertFailure(closure(false, emptyParameters(), uses, emptyReturnType(), emptyBody()), ".uses");
        }
        NodeLexicalVar unknown = new NodeLexicalVar() {
            @Override public NodeString getVar() { return token("name"); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        assertCaptureFailure(unknown, ".uses.vars[0]");
        for (NodeString name : new NodeString[]{null, token(null), token("")}) {
            assertCaptureFailure(new NodeLexicalVar.ByVal(name, LOCATION), ".var");
            assertCaptureFailure(new NodeLexicalVar.ByRef(name, LOCATION), ".var");
        }
    }

    // 各 yield 变体只允许自身明确省略的操作数，缺失字段必须报诊断。
    @Test
    void rejectsMissingYieldOperandsAndMarkers() {
        NodeExpr value = integer(1, LOCATION);
        for (NodeExprWithoutVariable expression : List.of(
                new NodeExprWithoutVariable.Yield(null, LOCATION),
                new NodeExprWithoutVariable.YieldValue(null, value, LOCATION),
                new NodeExprWithoutVariable.YieldKV(null, value, value, LOCATION),
                new NodeExprWithoutVariable.YieldFrom(null, value, LOCATION))) {
            assertFailure(expression, ".op");
        }
        assertFailure(new NodeExprWithoutVariable.YieldValue(token("yield"), null, LOCATION), ".value");
        assertFailure(new NodeExprWithoutVariable.YieldKV(token("yield"), null, value, LOCATION), ".key");
        assertFailure(new NodeExprWithoutVariable.YieldKV(token("yield"), value, null, LOCATION), ".value");
        assertFailure(new NodeExprWithoutVariable.YieldFrom(token("yield from"), null, LOCATION), ".expr");
    }

    // 标记校验遵循词法的大小写及内部空白规则，不接受首尾空白或额外单词。
    @Test
    void validatesYieldSpellingsAgainstLexerRules() {
        NodeExpr value = integer(1, LOCATION);
        for (String spelling : List.of("yield", "YIELD", "YiElD")) {
            assertInstanceOf(IrYield.class, convert(new NodeExprWithoutVariable.Yield(token(spelling), LOCATION)));
            assertInstanceOf(IrYield.class, convert(new NodeExprWithoutVariable.YieldValue(token(spelling), value, LOCATION)));
            assertInstanceOf(IrYield.class, convert(new NodeExprWithoutVariable.YieldKV(token(spelling), value, value, LOCATION)));
        }
        for (NodeString marker : List.of(token(null), token(""), token("yield "), token(" yield"), token("yield from"))) {
            assertFailure(new NodeExprWithoutVariable.Yield(marker, LOCATION), ".op");
            assertFailure(new NodeExprWithoutVariable.YieldValue(marker, value, LOCATION), ".op");
            assertFailure(new NodeExprWithoutVariable.YieldKV(marker, value, value, LOCATION), ".op");
        }
        for (String spelling : List.of("yield from", "YIELD FROM", "YiElD\tFrOm", "yield\rfrom", "yield\nfrom", "yield \t\r\n from")) {
            assertInstanceOf(IrYieldFrom.class, convert(new NodeExprWithoutVariable.YieldFrom(token(spelling), value, LOCATION)));
        }
        for (NodeString marker : List.of(token(null), token(""), token("yield"), token("yieldfrom"), token("yield from "),
                token(" yield from"), token("yield\ffrom"), token("yield\u000bfrom"), token("yield\u00a0from"),
                token("yield from from"), token("yield froM_more"))) {
            assertFailure(new NodeExprWithoutVariable.YieldFrom(marker, value, LOCATION), ".op");
        }
    }

    // 未支持的子表达式不能被闭包默认值、闭包体或 yield 包装吞掉。
    @Test
    void propagatesUnsupportedSubtreesWithFullFieldPaths() {
        NodeExpr unknown = new NodeExpr() {
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        assertParameterFailure(new NodeParameter.ParamWithDefault(null, null, null, token("value"), unknown, LOCATION),
                ".params[0].defaultValue");
        assertFailure(closure(false, emptyParameters(), emptyUses(), emptyReturnType(),
                new NodeListNodeInnerStatement(List.of(statement(unknown, LOCATION)), LOCATION)), ".stmts[0].stmt.expression");
        assertFailure(new NodeExprWithoutVariable.YieldValue(token("yield"), unknown, LOCATION), ".value");
        assertFailure(new NodeExprWithoutVariable.YieldKV(token("yield"), unknown, integer(1, LOCATION), LOCATION), ".key");
        assertFailure(new NodeExprWithoutVariable.YieldKV(token("yield"), integer(1, LOCATION), unknown, LOCATION), ".value");
        assertFailure(new NodeExprWithoutVariable.YieldFrom(token("yield from"), unknown, LOCATION), ".expr");
    }

    // 闭包、签名、类型、捕获、默认值和函数体分别保留自己的来源，不借用外层范围。
    @Test
    void preservesDistinctClosureSignatureAndBodyOrigins() {
        NodeString typeToken = new NodeString("array", LEAF);
        NodeTypeExpr type = new NodeTypeExpr.NullableType(token("?"), new NodeType.ArrayType(typeToken, LEAF), INNER);
        NodeParameter parameter = new NodeParameter.ParamWithDefault(type, null, null, token("value"), integer(7, LEAF), LOCATION);
        NodeLexicalVar capture = new NodeLexicalVar.ByRef(token("saved"), INNER);
        NodeListNodeInnerStatement body = new NodeListNodeInnerStatement(List.of(statement(integer(8, LEAF), INNER)), LEAF);
        NodeExprWithoutVariable syntax = closure(false, parameters(parameter), captures(capture),
                new NodeReturnType.ReturnType(type, LOCATION), body);
        IrClosure result = assertInstanceOf(IrClosure.class, convert(syntax));
        assertSource(syntax, result.source());
        assertSource(parameter, result.parameters().getFirst().source());
        assertSource(type, result.parameters().getFirst().declaredType().source());
        assertSource(typeToken, result.parameters().getFirst().declaredType().name().source());
        assertEquals(range(LEAF), result.parameters().getFirst().defaultValue().source().range());
        assertSource(type, result.returnType().source());
        assertSource(capture, result.captures().getFirst().source());
        assertSource(body, result.body().source());
        assertEquals(range(INNER), result.body().statements().getFirst().source().range());
        assertEquals(range(LEAF), assertInstanceOf(IrExpressionStatement.class,
                result.body().statements().getFirst()).expression().source().range());
    }

    // yield 的位置独立于关键字及操作数，裸 yield 不凭空构造 null 字面量。
    @Test
    void preservesDistinctYieldAndOperandOrigins() {
        NodeString op = new NodeString("yield", LEAF);
        IrYield bare = assertInstanceOf(IrYield.class, convert(new NodeExprWithoutVariable.Yield(op, LOCATION)));
        assertNull(bare.key());
        assertNull(bare.value());
        assertEquals(range(LOCATION), bare.source().range());
        IrYield yielded = assertInstanceOf(IrYield.class, convert(new NodeExprWithoutVariable.YieldKV(
                op, integer(1, INNER), integer(2, LEAF), LOCATION)));
        assertEquals(range(LOCATION), yielded.source().range());
        assertEquals(range(INNER), yielded.key().source().range());
        assertEquals(range(LEAF), yielded.value().source().range());
        IrYieldFrom delegated = assertInstanceOf(IrYieldFrom.class, convert(new NodeExprWithoutVariable.YieldFrom(
                new NodeString("yield from", LEAF), integer(3, INNER), LOCATION)));
        assertEquals(range(LOCATION), delegated.source().range());
        assertEquals(range(INNER), delegated.expression().source().range());
    }

    // 未知与零宽位置原样保留，调用入口提供的备用范围不应填充任何内部节点。
    @Test
    void preservesUnknownAndZeroWidthLocations() throws ReflectiveOperationException {
        for (ComplexLocation location : List.of(ComplexLocation.NO_LOCATION, ComplexLocation.of(8, 2, 8, 2))) {
            NodeTypeExpr type = new NodeTypeExpr.Type(new NodeType.ArrayType(new NodeString("array", location), location), location);
            NodeParameter parameter = new NodeParameter.ParamWithDefault(type, null, null, new NodeString("value", location),
                    integer(1, location), location);
            NodeLexicalVar capture = new NodeLexicalVar.ByVal(new NodeString("saved", location), location);
            NodeExprWithoutVariable yield = new NodeExprWithoutVariable.YieldKV(new NodeString("yield", location),
                    integer(2, location), integer(3, location), location);
            NodeExprWithoutVariable yieldFrom = new NodeExprWithoutVariable.YieldFrom(new NodeString("yield from", location),
                    integer(4, location), location);
            NodeExprWithoutVariable syntax = new NodeExprWithoutVariable.Closure(null,
                    new NodeListNodeParameter(List.of(parameter), location),
                    new NodeLexicalVars.ClosureUses(new NodeListNodeLexicalVar(List.of(capture), location), location),
                    new NodeReturnType.ReturnType(type, location),
                    new NodeListNodeInnerStatement(List.of(statement(wrap(yield), location), statement(wrap(yieldFrom), location)), location),
                    location);
            IrExpression result = SyntaxConverter.convertExpression(new SyntaxExpression(wrap(syntax),
                    new SourceInfo("closures.php", new SourceRange(99, 1, 99, 9))));
            assertAllSources(result, location.isNoLocation() ? null : range(location));
        }
    }

    // 新记录检查必需字段，yield 的可空键值仅允许实际语法中的三种组合。
    @Test
    void validatesRequiredModelFieldsAndYieldInvariants() {
        IrExpression value = new IrIntegerLiteral(1, SOURCE);
        IrBlock body = new IrBlock(List.of(), SOURCE);
        IrParameter parameter = new IrParameter("value", null, false, false, null, SOURCE);
        IrClosureCapture capture = new IrClosureCapture("saved", false, SOURCE);
        for (Executable operation : List.<Executable>of(
                () -> new IrParameter(null, null, false, false, null, SOURCE),
                () -> new IrParameter("value", null, false, false, null, null),
                () -> new IrClosureCapture(null, false, SOURCE),
                () -> new IrClosureCapture("saved", false, null),
                () -> new IrClosure(null, null, false, false, List.of(), body, SOURCE),
                () -> new IrClosure(withNull(parameter), null, false, false, List.of(), body, SOURCE),
                () -> new IrClosure(List.of(), null, false, false, null, body, SOURCE),
                () -> new IrClosure(List.of(), null, false, false, withNull(capture), body, SOURCE),
                () -> new IrClosure(List.of(), null, false, false, List.of(), null, SOURCE),
                () -> new IrClosure(List.of(), null, false, false, List.of(), body, null),
                () -> new IrYield(null, null, null),
                () -> new IrYieldFrom(null, SOURCE),
                () -> new IrYieldFrom(value, null))) {
            assertThrows(NullPointerException.class, operation);
        }
        assertThrows(IllegalArgumentException.class, () -> new IrParameter("", null, false, false, null, SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrClosureCapture("", false, SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrYield(value, null, SOURCE));
        assertNull(new IrYield(null, null, SOURCE).value());
        assertSame(value, new IrYield(null, value, SOURCE).value());
        assertSame(value, new IrYield(value, value, SOURCE).key());
    }

    // 参数与捕获集合创建只读快照，保持重复项、大小写与输入顺序，不做绑定检查。
    @Test
    void snapshotsAndFreezesClosureCollectionsWithoutNormalizingNames() {
        IrParameter parameter = new IrParameter("Value", null, false, false, null, SOURCE);
        IrClosureCapture capture = new IrClosureCapture("Value", true, SOURCE);
        var parameters = new ArrayList<>(List.of(parameter, parameter));
        var captures = new ArrayList<>(List.of(capture, capture));
        IrClosure result = new IrClosure(parameters, null, false, false, captures, new IrBlock(List.of(), SOURCE), SOURCE);
        parameters.clear();
        captures.clear();
        assertEquals(List.of(parameter, parameter), result.parameters());
        assertEquals(List.of(capture, capture), result.captures());
        assertThrows(UnsupportedOperationException.class, result.parameters()::clear);
        assertThrows(UnsupportedOperationException.class, result.captures()::clear);
    }

    // 闭包转换递归清除 AST，但不得修改第一阶段声明、索引、原始 AST 或提升嵌套闭包。
    @Test
    void convertsClosuresWithoutMutatingDeclarationsOrLeakingAst() throws ReflectiveOperationException {
        NodeProgram parsed = (NodeProgram) Main.parse("""
                <?php
                namespace Demo;
                function factory($outer) {
                    return static function &(array $items = [1, 2]) use (&$outer): iterable {
                        $nested = function ($value = 0x2A) { yield $value; };
                        yield $outer => $nested($items);
                        yield from $items;
                    };
                }
                """);
        String before = parsed.toTreeString(false);
        PhpFile file = DeclarationExtractor.extract(parsed, "closures.php");
        NamespaceSection namespace = file.namespaceSections().getFirst();
        assertEquals(1, namespace.declarations().size());
        FunctionDefinition function = assertInstanceOf(FunctionDefinition.class, namespace.declarations().getFirst());
        SyntaxBody originalBody = function.body();
        IrBlock result = SyntaxConverter.convertBody(originalBody);
        IrClosure closure = assertInstanceOf(IrClosure.class,
                assertInstanceOf(IrReturn.class, result.statements().getFirst()).value());
        assertEquals(3, closure.body().statements().size());
        assertSame(originalBody, function.body());
        assertSame(function, file.declarationIndex().findById(function.id()).orElseThrow());
        assertEquals(before, parsed.toTreeString(false));
        assertNoAst(result, Collections.newSetFromMap(new IdentityHashMap<>()));
        assertAllSourceIds(result);
    }

    private static NodeExprWithoutVariable closure(boolean isStatic, NodeListNodeParameter parameters,
                                                    NodeLexicalVars uses, NodeReturnType returnType,
                                                    NodeListNodeInnerStatement body) {
        return isStatic
                ? new NodeExprWithoutVariable.StaticClosure(token("static"), null, parameters, uses, returnType, body, LOCATION)
                : new NodeExprWithoutVariable.Closure(null, parameters, uses, returnType, body, LOCATION);
    }

    private static void assertParameterFailure(NodeParameter parameter, String path) {
        assertFailure(closure(false, parameters(parameter), emptyUses(), emptyReturnType(), emptyBody()), path);
    }

    private static void assertReturnTypeFailure(NodeReturnType type, String path) {
        assertFailure(closure(false, emptyParameters(), emptyUses(), type, emptyBody()), path);
    }

    private static void assertTypeFailure(NodeTypeExpr type) {
        assertParameterFailure(new NodeParameter.Param(type, null, null, token("value"), LOCATION), ".params[0].type");
        assertReturnTypeFailure(new NodeReturnType.ReturnType(type, LOCATION), ".returnType.t");
    }

    private static void assertCaptureFailure(NodeLexicalVar capture, String path) {
        assertFailure(closure(false, emptyParameters(), captures(capture), emptyReturnType(), emptyBody()), path);
    }

    private static void assertFailure(NodeExprWithoutVariable syntax, String path) {
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class, () -> convert(syntax));
        assertEquals("closures.php", error.source().sourceId());
        assertEquals(range(LOCATION), error.source().range());
        assertTrue(error.fieldPath().contains(path), error.fieldPath());
        assertFalse(error.reason().isBlank());
        assertTrue(error.getMessage().contains("closures.php"));
        assertTrue(error.getMessage().contains(error.fieldPath()));
    }

    private static IrExpression convert(NodeExprWithoutVariable expression) {
        return SyntaxConverter.convertExpression(new SyntaxExpression(wrap(expression), SOURCE));
    }

    private static NodeExpr wrap(NodeExprWithoutVariable expression) {
        return new NodeExpr.ExprWithoutVariable(expression, LOCATION);
    }

    private static NodeExpr integer(int value, ComplexLocation location) {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(new NodeString(Integer.toString(value), location), location), location), location);
    }

    private static NodeInnerStatement statement(NodeExpr expression, ComplexLocation location) {
        return new NodeInnerStatement.Statement(new NodeStatement.ExpressionStatement(expression, location), location);
    }

    private static NodeParameter parameter() {
        return new NodeParameter.Param(null, null, null, token("value"), LOCATION);
    }

    private static NodeLexicalVar capture() {
        return new NodeLexicalVar.ByVal(token("saved"), LOCATION);
    }

    private static NodeTypeExpr arrayType() {
        return new NodeTypeExpr.Type(new NodeType.ArrayType(token("array"), LOCATION), LOCATION);
    }

    private static NodeListNodeParameter parameters(NodeParameter parameter) {
        return new NodeListNodeParameter(List.of(parameter), LOCATION);
    }

    private static NodeListNodeParameter emptyParameters() {
        return new NodeListNodeParameter(List.of(), LOCATION);
    }

    private static NodeLexicalVars captures(NodeLexicalVar capture) {
        return new NodeLexicalVars.ClosureUses(new NodeListNodeLexicalVar(List.of(capture), LOCATION), LOCATION);
    }

    private static NodeLexicalVars emptyUses() {
        return new NodeLexicalVars();
    }

    private static NodeReturnType emptyReturnType() {
        return new NodeReturnType();
    }

    private static NodeListNodeInnerStatement emptyBody() {
        return new NodeListNodeInnerStatement(List.of(), LOCATION);
    }

    private static NodeString token(String text) {
        return new NodeString(text, LOCATION);
    }

    private static <T> List<T> withNull(T first) {
        var list = new ArrayList<T>();
        list.add(first);
        list.add(null);
        return list;
    }

    private static void assertSource(AstNode expected, SourceInfo actual) {
        assertEquals("closures.php", actual.sourceId());
        ComplexLocation location = assertInstanceOf(ComplexLocation.class, expected.getLocation());
        assertEquals(location.isNoLocation() ? null : range(location), actual.range());
    }

    private static SourceRange range(ComplexLocation location) {
        return new SourceRange(location.getStartLine(), location.getStartColumn(), location.getEndLine(), location.getEndColumn());
    }

    private static void assertAllSources(Object value, SourceRange expected) throws ReflectiveOperationException {
        if (value == null) return;
        if (value instanceof SourceInfo(String sourceId, SourceRange range)) {
            assertEquals("closures.php", sourceId);
            assertEquals(expected, range);
        } else if (value instanceof List<?> list) {
            for (Object child : list) assertAllSources(child, expected);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertAllSources(component.getAccessor().invoke(value), expected);
            }
        }
    }

    private static void assertAllSourceIds(Object value) throws ReflectiveOperationException {
        if (value == null) return;
        if (value instanceof SourceInfo source) {
            assertEquals("closures.php", source.sourceId());
        } else if (value instanceof List<?> list) {
            for (Object child : list) assertAllSourceIds(child);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertAllSourceIds(component.getAccessor().invoke(value));
            }
        }
    }

    private static void assertNoAst(Object value, Set<Object> visited) throws ReflectiveOperationException {
        if (value == null || !visited.add(value)) return;
        assertFalse(value instanceof AstNode, "闭包与生成器 IR 不应保留 CUP AST");
        if (value instanceof List<?> list) {
            for (Object child : list) assertNoAst(child, visited);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertNoAst(component.getAccessor().invoke(value), visited);
            }
        } else {
            assertTrue(value instanceof String || value instanceof Enum<?> || value instanceof Number || value instanceof Boolean,
                    "未预期的结果字段类型：" + value.getClass());
        }
    }
}