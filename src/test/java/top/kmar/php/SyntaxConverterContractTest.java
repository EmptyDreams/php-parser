package top.kmar.php;

import java_cup.runtime.AstNode;
import java_cup.runtime.symbol.complex.ComplexLocation;
import org.junit.jupiter.api.Test;
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

/** 验证独立转换入口的来源、失败隔离、只读结果和 AST 边界。 */
class SyntaxConverterContractTest {

    // 顶层包装、函数内包装及裸 statement 都可传入，不能误把包装当成新语句。
    @Test
    void acceptsOrdinaryStatementWrappersAndRawStatements() {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php echo 1;");
        NodeTopStatement top = parsed.getStmts().getValue().getFirst();
        NodeStatement raw = top.getStmt();
        NodeInnerStatement inner = new NodeInnerStatement.Statement(raw, raw.getLocation());
        SourceInfo source = new SourceInfo("wrappers.php", null);
        for (AstNode statement : List.of(top, inner, raw)) {
            IrBlock block = SyntaxConverter.convertBody(new SyntaxBody(List.of(statement), source));
            assertEquals(1, block.statements().size());
            assertInstanceOf(IrEcho.class, block.statements().getFirst());
        }
    }

    // 调用方直接转换包含声明或导入的区段时明确失败，不自动过滤或提升。
    @Test
    void rejectsDeclarationsAndImportsInsideSelectedBodies() {
        for (String code : List.of("function f() {}", "class C {}", "const A = 1;",
                "use Vendor\\Thing;", "use function Vendor\\run;")) {
            PhpFile file = DeclarationExtractor.extract(Main.parse("<?php " + code));
            SyntaxBody body = file.namespaceSections().getFirst().body();
            assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(body), code);
        }
    }

    // 来源标识取自转换入口，子节点范围直接继承原树，不扩展到缺失的关键字或括号。
    @Test
    void propagatesSourceIdsAndOriginalChildRanges() throws ReflectiveOperationException {
        NodeProgram parsed = (NodeProgram) Main.parse("""
                <?php
                function f($value) {
                    if ($value) { echo Vendor\\read($value, 2); } else return null;
                }
                """);
        PhpFile file = DeclarationExtractor.extract(parsed, "memory:source.php");
        FunctionDefinition function = (FunctionDefinition) file.namespaceSections().getFirst().declarations().getFirst();
        IrBlock block = SyntaxConverter.convertBody(function.body());
        assertEquals(function.body().source(), block.source());
        assertAllSourceIds(block, "memory:source.php");
        NodeExpr expression = parsedExpression("$value + 2");
        IrBinary binary = assertInstanceOf(IrBinary.class, SyntaxConverter.convertExpression(
                new SyntaxExpression(expression, new SourceInfo("expression.php", null))));
        NodeExprWithoutVariable original = expression.getEv();
        assertEquals(sourceRange(original.getLeft()), binary.left().source().range());
        assertEquals(sourceRange(original.getRight()), binary.right().source().range());
        assertAllSourceIds(binary, "expression.php");
    }

    // 未知位置不猜测成有效范围；合法零宽位置仍应保留，来源标识可以独立缺省。
    @Test
    void keepsUnknownAndZeroWidthLocationsDistinct() {
        SourceInfo unknown = new SourceInfo(null, null);
        IrBlock empty = SyntaxConverter.convertBody(new SyntaxBody(List.of(), unknown));
        assertEquals(unknown, empty.source());
        NodeExpr noLocation = literal(ComplexLocation.NO_LOCATION);
        assertNull(SyntaxConverter.convertExpression(new SyntaxExpression(noLocation, unknown)).source().range());
        ComplexLocation zeroWidth = ComplexLocation.of(3, 7, 3, 7);
        IrExpression known = SyntaxConverter.convertExpression(new SyntaxExpression(literal(zeroWidth), unknown));
        assertEquals(new SourceRange(3, 7, 3, 7), known.source().range());
        assertNull(known.source().sourceId());
        IrEmpty emptyStatement = assertInstanceOf(IrEmpty.class, SyntaxConverter.convertBody(
                new SyntaxBody(List.of(new NodeStatement()), new SourceInfo("empty.php", null)))
                .statements().getFirst());
        assertNull(emptyStatement.source().range());
        assertEquals("empty.php", emptyStatement.source().sourceId());
    }

    // 损坏的表达式字段应报告来源和字段路径，而不是抛出无上下文的空指针异常。
    @Test
    void reportsMalformedExpressionsWithSourceAndFieldPath() {
        ComplexLocation location = ComplexLocation.of(2, 4, 2, 9);
        var broken = new NodeExprWithoutVariable.Binary(
                literal(location), new NodeString("+", location), null, location);
        var syntax = new SyntaxExpression(new NodeExpr.ExprWithoutVariable(broken, location),
                new SourceInfo("broken.php", null));
        var error = assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertExpression(syntax));
        assertEquals("broken.php", error.source().sourceId());
        assertEquals(new SourceRange(2, 4, 2, 9), error.source().range());
        assertTrue(error.fieldPath().contains("right"));
        assertFalse(error.reason().isBlank());
        assertTrue(error.getMessage().contains("broken.php"));
        assertTrue(error.getMessage().contains("2:4"));
        assertTrue(error.getMessage().contains(error.fieldPath()));
    }

    // 未知根节点、未知变体及已识别语句的缺失字段都不能退化为空语句。
    @Test
    void rejectsUnknownStructuresAndMissingRequiredStatementFields() {
        SourceInfo source = new SourceInfo("invalid.php", null);
        for (AstNode syntax : List.of(new NodeExpr(), new NodeString("x", ComplexLocation.NO_LOCATION),
                new NodeExpr.ExprWithoutVariable(null, ComplexLocation.NO_LOCATION))) {
            var error = assertThrows(SyntaxConversionException.class, () ->
                    SyntaxConverter.convertExpression(new SyntaxExpression(syntax, source)));
            assertEquals("invalid.php", error.source().sourceId());
            assertFalse(error.fieldPath().isBlank());
        }
        for (AstNode syntax : List.of(new NodeTopStatement(), new NodeInnerStatement(), new NodeStatement() {},
                new NodeStatement.ExpressionStatement(null, ComplexLocation.NO_LOCATION),
                new NodeStatement.Block(null, ComplexLocation.NO_LOCATION),
                new NodeTopStatement.Statement(null, ComplexLocation.NO_LOCATION))) {
            assertThrows(SyntaxConversionException.class,
                    () -> SyntaxConverter.convertBody(new SyntaxBody(List.of(syntax), source)));
        }
    }

    // 已有列表节点若被外部破坏，转换必须失败而不是默默跳过其中的 null。
    @Test
    void rejectsMalformedAstLists() {
        var parsed = (NodeProgram) Main.parse("<?php function f() { { echo 1; } }");
        var declaration = parsed.getStmts().getValue().getFirst().getFunction();
        var block = declaration.getStmts().getValue().getFirst().getStmt();
        block.getStmts().getValue().add(null);
        var syntax = new SyntaxBody(List.of(block), new SourceInfo("bad-list.php", null));
        var error = assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(syntax));
        assertEquals("bad-list.php", error.source().sourceId());
        assertTrue(error.fieldPath().contains("[1]"));
    }

    // 冒号式分支的语句列表即使为空也必须存在，缺失列表属于损坏 AST。
    @Test
    void rejectsMissingAlternativeIfStatementLists() {
        var location = ComplexLocation.NO_LOCATION;
        var missingThen = new NodeAltIfStmtWithoutElse.AltIfElem(literal(location), null, location);
        var validThen = new NodeAltIfStmtWithoutElse.AltIfElem(literal(location),
                new NodeListNodeInnerStatement(List.of(), location), location);
        for (NodeAltIfStmt syntax : List.of(
                new NodeAltIfStmt.AltIf(missingThen, location),
                new NodeAltIfStmt.AltIfElse(validThen, null, location))) {
            var body = new SyntaxBody(List.of(new NodeStatement.AltIf(syntax, location)),
                    new SourceInfo("broken-if.php", null));
            var error = assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(body));
            assertTrue(error.fieldPath().contains("stmts"));
        }
    }

    // API 参数必须存在，可空函数体或默认值应由调用者先判断，不能自动变为空转换结果。
    @Test
    void rejectsNullPublicArguments() {
        assertThrows(NullPointerException.class, () -> SyntaxConverter.convertBody(null));
        assertThrows(NullPointerException.class, () -> SyntaxConverter.convertExpression(null));
    }

    // 转换结果所有集合均只读，同时构造器防御性复制外部集合。
    @Test
    void exposesImmutableCollectionsAndSnapshotsConstructorInputs() {
        SourceInfo source = new SourceInfo(null, null);
        var statements = new ArrayList<IrStatement>();
        var block = new IrBlock(statements, source);
        statements.add(new IrEmpty(source));
        assertTrue(block.statements().isEmpty());
        assertThrows(UnsupportedOperationException.class, block.statements()::clear);

        var expressions = new ArrayList<IrExpression>();
        expressions.add(new IrLiteral(LiteralKind.INTEGER, "1", source));
        var echo = new IrEcho(expressions, source);
        var call = new IrCall(new NameReference("f", NameForm.UNQUALIFIED, source), expressions, source);
        expressions.clear();
        assertEquals(1, echo.expressions().size());
        assertEquals(1, call.arguments().size());
        assertThrows(UnsupportedOperationException.class, echo.expressions()::clear);
        assertThrows(UnsupportedOperationException.class, call.arguments()::clear);

        var branches = new ArrayList<IrIfBranch>();
        branches.add(new IrIfBranch(new IrVariable("a", source), block, source));
        var conditional = new IrIf(branches, null, source);
        branches.clear();
        assertEquals(1, conditional.branches().size());
        assertThrows(UnsupportedOperationException.class, conditional.branches()::clear);
    }

    // 新模型不携带 CUP AST；转换本身不会修改旧树、旧列表或旧声明索引。
    @Test
    void producesAstFreeResultsWithoutMutatingInput() throws ReflectiveOperationException {
        NodeProgram parsed = (NodeProgram) Main.parse("""
                <?php function f($a) {
                    $value = $a + 2;
                    if ($value) echo call($value, FLAG); else return null;
                    return $value ?? other() ?: 3;
                }
                """);
        String before = parsed.toTreeString(false);
        var file = DeclarationExtractor.extract(parsed, "immutable.php");
        var function = (FunctionDefinition) file.namespaceSections().getFirst().declarations().getFirst();
        List<AstNode> statements = List.copyOf(function.body().statements());
        IrBlock result = SyntaxConverter.convertBody(function.body());
        assertNoAst(result, Collections.newSetFromMap(new IdentityHashMap<>()));
        assertEquals(before, parsed.toTreeString(false));
        assertEquals(statements, function.body().statements());
        for (int i = 0; i < statements.size(); i++) {
            assertSame(statements.get(i), function.body().statements().get(i));
        }
        assertSame(function, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "f").getFirst());
        assertEquals(result, SyntaxConverter.convertBody(function.body()));
    }

    // 失败不会污染后续调用，也不会把已转换的前半个函数作为成功结果交给调用方。
    @Test
    void keepsFailureStateLocalToEachConversion() {
        var file = DeclarationExtractor.extract(Main.parse("""
                <?php
                function bad() { echo 1; while (true) {} return 2; }
                function good() { return 3; }
                """));
        var declarations = file.namespaceSections().getFirst().declarations();
        var bad = (FunctionDefinition) declarations.getFirst();
        var good = (FunctionDefinition) declarations.get(1);
        String before = file.syntax().toTreeString(false);
        assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(bad.body()));
        IrBlock result = SyntaxConverter.convertBody(good.body());
        assertEquals(1, result.statements().size());
        assertEquals("3", assertInstanceOf(IrLiteral.class,
                assertInstanceOf(IrReturn.class, result.statements().getFirst()).value()).lexeme());
        assertEquals(before, file.syntax().toTreeString(false));
    }

    private static NodeExpr parsedExpression(String code) {
        return ((NodeProgram) Main.parse("<?php " + code + ";"))
                .getStmts().getValue().getFirst().getStmt().getExpression();
    }

    private static NodeExpr literal(ComplexLocation location) {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(new NodeString("1", location), location), location), location);
    }

    private static SourceRange sourceRange(AstNode syntax) {
        var location = assertInstanceOf(ComplexLocation.class, syntax.getLocation());
        return new SourceRange(location.getStartLine(), location.getStartColumn(),
                location.getEndLine(), location.getEndColumn());
    }

    private static void assertAllSourceIds(Object value, String sourceId) throws ReflectiveOperationException {
        if (value == null) return;
        if (value instanceof SourceInfo source) {
            assertEquals(sourceId, source.sourceId());
        } else if (value instanceof List<?> list) {
            for (Object child : list) assertAllSourceIds(child, sourceId);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertAllSourceIds(component.getAccessor().invoke(value), sourceId);
            }
        }
    }

    private static void assertNoAst(Object value, Set<Object> visited) throws ReflectiveOperationException {
        if (value == null || !visited.add(value)) return;
        assertFalse(value instanceof AstNode, "转换结果不应保留 CUP AST");
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
