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
        for (String number : List.of("2", "1.25")) {
            NodeExpr expression = parsedExpression("$value + " + number);
            IrBinary binary = assertInstanceOf(IrBinary.class, SyntaxConverter.convertExpression(
                    new SyntaxExpression(expression, new SourceInfo("expression.php", null))));
            NodeExprWithoutVariable original = expression.getEv();
            assertEquals(sourceRange(original.getLeft()), binary.left().source().range());
            assertEquals(sourceRange(original.getRight()), binary.right().source().range());
            assertAllSourceIds(binary, "expression.php");
        }
    }

    // 用手写坐标验证跨行范围：外层一元节点的操作数 Symbol 包含右括号，内层算式不含括号。
    @Test
    void preservesExactRangesAcrossMultilineParenthesizedExpressions() {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php\n-(0x2A +\n  1.5);");
        NodeExpr expression = parsed.getStmts().getValue().getFirst().getStmt().getExpression();
        String sourceId = "multiline-expression.php";
        IrUnary unary = assertInstanceOf(IrUnary.class, SyntaxConverter.convertExpression(
                new SyntaxExpression(expression, new SourceInfo(sourceId, null))));
        assertEquals(UnaryOperator.MINUS, unary.operator());
        assertEquals(new SourceInfo(sourceId, new SourceRange(2, 1, 3, 7)), unary.source());

        IrBinary binary = assertInstanceOf(IrBinary.class, unary.operand());
        assertEquals(BinaryOperator.ADD, binary.operator());
        assertEquals(new SourceInfo(sourceId, new SourceRange(2, 3, 3, 6)), binary.source());

        IrIntegerLiteral integer = assertInstanceOf(IrIntegerLiteral.class, binary.left());
        assertEquals(42L, integer.value());
        assertEquals(new SourceInfo(sourceId, new SourceRange(2, 3, 2, 7)), integer.source());
        IrFloatLiteral floating = assertInstanceOf(IrFloatLiteral.class, binary.right());
        assertEquals(1.5, floating.value());
        assertEquals(new SourceInfo(sourceId, new SourceRange(3, 3, 3, 6)), floating.source());
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
        expressions.add(new IrIntegerLiteral(1, source));
        var echo = new IrEcho(expressions, source);
        var arguments = new ArrayList<>(List.of(new IrArgument(expressions.getFirst(), false, source)));
        var call = new IrCall(new IrNamedCallTarget(new NameReference("f", NameForm.UNQUALIFIED, source), source),
                arguments, source);
        expressions.clear();
        arguments.clear();
        assertEquals(1, echo.expressions().size());
        assertEquals(1, call.arguments().size());
        assertThrows(UnsupportedOperationException.class, echo.expressions()::clear);
        assertThrows(UnsupportedOperationException.class, call.arguments()::clear);

        var branches = new ArrayList<IrIfBranch>();
        branches.add(new IrIfBranch(new IrVariable(new IrFixedName("a", source), source), block, source));
        var conditional = new IrIf(branches, null, source);
        branches.clear();
        assertEquals(1, conditional.branches().size());
        assertThrows(UnsupportedOperationException.class, conditional.branches()::clear);

        var entries = new ArrayList<IrArrayEntry>();
        entries.add(new IrValueArrayEntry(null, new IrIntegerLiteral(2, source), source));
        var array = new IrArrayLiteral(entries, source);
        entries.clear();
        assertEquals(1, array.entries().size());
        assertThrows(UnsupportedOperationException.class, array.entries()::clear);

        var initializers = new ArrayList<IrExpression>(List.of(new IrIntegerLiteral(1, source)));
        var conditions = new ArrayList<IrExpression>(List.of(new IrIntegerLiteral(2, source)));
        var updates = new ArrayList<IrExpression>(List.of(new IrIntegerLiteral(3, source)));
        var loop = new IrFor(initializers, conditions, updates, block, source);
        initializers.clear();
        conditions.clear();
        updates.clear();
        assertEquals(1, assertInstanceOf(IrIntegerLiteral.class, loop.initializers().getFirst()).value());
        assertEquals(2, assertInstanceOf(IrIntegerLiteral.class, loop.conditions().getFirst()).value());
        assertEquals(3, assertInstanceOf(IrIntegerLiteral.class, loop.updates().getFirst()).value());
        assertThrows(UnsupportedOperationException.class, loop.initializers()::clear);
        assertThrows(UnsupportedOperationException.class, loop.conditions()::clear);
        assertThrows(UnsupportedOperationException.class, loop.updates()::clear);
    }

    // 数组默认值和完整过程式函数体共享转换规则，跨节点组合仍保留源码且不修改声明模型。
    @Test
    void convertsProceduralBodiesWithoutMutatingDeclarationsOrLosingSources() throws ReflectiveOperationException {
        NodeProgram parsed = (NodeProgram) Main.parse("""
                <?php
                function collect($values = [1, 2, 3]) {
                    $result = [];
                    for ($i = 0; $i < count($values); $i++) {
                        if ($values[$i] < 0) continue;
                        $result[] = $values[$i];
                    }
                    foreach ($result as $key => $value) {
                        $result[$key] += 1;
                    }
                    while ($i > 0) { --$i; if ($i == 1) break; }
                    do { $i--; } while ($i > 0);
                    return $result;
                }
                """);
        String before = parsed.toTreeString(false);
        PhpFile file = DeclarationExtractor.extract(parsed, "procedural.php");
        FunctionDefinition function = (FunctionDefinition) file.namespaceSections().getFirst().declarations().getFirst();
        var originalStatements = List.copyOf(function.body().statements());
        IrBlock body = SyntaxConverter.convertBody(function.body());
        IrArrayLiteral defaults = assertInstanceOf(IrArrayLiteral.class,
                SyntaxConverter.convertExpression(function.signature().parameters().getFirst().defaultValue()));
        assertEquals(List.of(1L, 2L, 3L), defaults.entries().stream()
                .map(entry -> assertInstanceOf(IrIntegerLiteral.class,
                        assertInstanceOf(IrValueArrayEntry.class, entry).value()).value()).toList());

        assertEquals(6, body.statements().size());
        IrAssignment initialization = assertInstanceOf(IrAssignment.class,
                assertInstanceOf(IrExpressionStatement.class, body.statements().getFirst()).expression());
        assertEquals("result", fixedName(assertInstanceOf(IrVariableTarget.class, initialization.target()).name()));
        assertTrue(assertInstanceOf(IrArrayLiteral.class, initialization.value()).entries().isEmpty());
        IrFor loop = assertInstanceOf(IrFor.class, body.statements().get(1));
        assertEquals(UpdateOperator.POST_INCREMENT, assertInstanceOf(IrUpdate.class, loop.updates().getFirst()).operator());
        assertEquals(2, loop.body().statements().size());
        IrIf guard = assertInstanceOf(IrIf.class, loop.body().statements().get(0));
        assertInstanceOf(IrContinue.class, guard.branches().getFirst().body().statements().getFirst());
        IrAssignment append = assertInstanceOf(IrAssignment.class,
                assertInstanceOf(IrExpressionStatement.class, loop.body().statements().get(1)).expression());
        assertNull(assertInstanceOf(IrIndexTarget.class, append.target()).index());
        assertEquals("i", fixedName(assertInstanceOf(IrVariable.class,
                assertInstanceOf(IrIndex.class, append.value()).index()).name()));
        IrForeach foreach = assertInstanceOf(IrForeach.class, body.statements().get(2));
        assertEquals("key", fixedName(assertInstanceOf(IrVariableTarget.class, foreach.keyTarget()).name()));
        assertEquals("value", fixedName(assertInstanceOf(IrVariableTarget.class, foreach.valueTarget()).name()));
        IrCompoundAssignment compound = assertInstanceOf(IrCompoundAssignment.class,
                assertInstanceOf(IrExpressionStatement.class, foreach.body().statements().getFirst()).expression());
        assertEquals(CompoundAssignmentOperator.ADD, compound.operator());
        assertEquals("key", fixedName(assertInstanceOf(IrVariable.class,
                assertInstanceOf(IrIndexTarget.class, compound.target()).index()).name()));
        IrWhile whileLoop = assertInstanceOf(IrWhile.class, body.statements().get(3));
        assertEquals(UpdateOperator.PRE_DECREMENT, assertInstanceOf(IrUpdate.class,
                assertInstanceOf(IrExpressionStatement.class, whileLoop.body().statements().getFirst()).expression()).operator());
        IrDoWhile doLoop = assertInstanceOf(IrDoWhile.class, body.statements().get(4));
        assertEquals(UpdateOperator.POST_DECREMENT, assertInstanceOf(IrUpdate.class,
                assertInstanceOf(IrExpressionStatement.class, doLoop.body().statements().getFirst()).expression()).operator());
        assertEquals("result", fixedName(assertInstanceOf(IrVariable.class,
                assertInstanceOf(IrReturn.class, body.statements().get(5)).value()).name()));

        assertEquals(function.body().source(), body.source());
        assertAllSourceIds(body, "procedural.php");
        assertAllSourceIds(defaults, "procedural.php");
        assertNoAst(body, Collections.newSetFromMap(new IdentityHashMap<>()));
        assertNoAst(defaults, Collections.newSetFromMap(new IdentityHashMap<>()));
        assertEquals(before, parsed.toTreeString(false));
        for (int i = 0; i < originalStatements.size(); i++) {
            assertSame(originalStatements.get(i), function.body().statements().get(i));
        }
        assertSame(function, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "collect").getFirst());
        assertEquals(body, SyntaxConverter.convertBody(function.body()));
    }

    // 更新、下标目标、数组条目均继承各自的 AST 范围，不把父节点的位置分发给所有子节点。
    @Test
    void preservesOriginalRangesForArrayEntriesAndUpdateTargets() throws ReflectiveOperationException {
        NodeExpr parsed = parsedExpression("$a[1] += [2]");
        NodeExprWithoutVariable original = parsed.getEv();
        IrCompoundAssignment result = assertInstanceOf(IrCompoundAssignment.class,
                SyntaxConverter.convertExpression(new SyntaxExpression(parsed, new SourceInfo("updates.php", null))));
        assertEquals(sourceRange(original), result.source().range());
        IrIndexTarget target = assertInstanceOf(IrIndexTarget.class, result.target());
        assertEquals(sourceRange(original.getTarget().getCv()), target.source().range());
        assertEquals(sourceRange(original.getTarget().getCv().getOffset()), target.index().source().range());
        NodeDereferencableScalar originalArray = original.getValue().getEv().getScalar().getDs();
        IrArrayLiteral array = assertInstanceOf(IrArrayLiteral.class, result.value());
        assertEquals(sourceRange(originalArray), array.source().range());
        assertEquals(sourceRange(originalArray.getItems().getItems().getValue().getFirst().getPair()),
                array.entries().getFirst().source().range());
        assertAllSourceIds(result, "updates.php");
    }

    // 缺失列表、null 槽节点和缺失条目字段是损坏 AST，不能被当成空数组或尾逗号。
    @Test
    void rejectsMalformedArrayListsAndEntriesWithContext() {
        var location = ComplexLocation.of(4, 2, 4, 8);
        var nullSlot = new ArrayList<NodePossibleArrayPair>();
        nullSlot.add(null);
        for (NodeArrayPairList list : List.of(
                new NodeArrayPairList.ArrayPairList(null, location),
                new NodeArrayPairList.ArrayPairList(new NodeListNodePossibleArrayPair(null, location), location),
                new NodeArrayPairList.ArrayPairList(new NodeListNodePossibleArrayPair(List.of(), location), location),
                new NodeArrayPairList.ArrayPairList(new NodeListNodePossibleArrayPair(nullSlot, location), location))) {
            var error = assertArrayFailure(list, location);
            assertTrue(error.fieldPath().contains("items"));
        }
        for (NodeArrayPair pair : List.of(new NodeArrayPair.Value(null, location),
                new NodeArrayPair.KeyValue(null, literal(location), location),
                new NodeArrayPair.KeyValue(literal(location), null, location))) {
            // 可选槽位的生成类名不稳定；手工提供同样的稳定字段契约。
            var slot = new NodePossibleArrayPair() {
                @Override public NodeArrayPair getPair() { return pair; }
                @Override public boolean hasPair() { return true; }
                @Override public ComplexLocation getLocation() { return location; }
            };
            var list = new NodeArrayPairList.ArrayPairList(
                    new NodeListNodePossibleArrayPair(List.of(slot), location), location);
            var error = assertArrayFailure(list, location);
            assertTrue(error.fieldPath().endsWith(pair.getValue() == null ? ".value" : ".key"));
        }
        //noinspection ThrowableNotThrown
        assertArrayFailure(null, location);
    }

    // 更新表达式的缺失目标、值或运算符应保留诊断上下文，不泄漏空指针或接受未知运算符。
    @Test
    void rejectsMalformedCompoundAssignmentsAndUpdatesWithContext() {
        var location = ComplexLocation.of(5, 3, 5, 12);
        NodeVariable target = parsedExpression("$a").getV();
        NodeString plusEqual = new NodeString("+=", location);
        for (NodeExprWithoutVariable node : List.of(
                new NodeExprWithoutVariable.AssignOp(null, plusEqual, literal(location), location),
                new NodeExprWithoutVariable.AssignOp(target, plusEqual, null, location),
                new NodeExprWithoutVariable.AssignOp(target, null, literal(location), location),
                new NodeExprWithoutVariable.AssignOp(target, new NodeString("??=", location), literal(location), location),
                new NodeExprWithoutVariable.PostIncDec(null, new NodeString("++", location), location),
                new NodeExprWithoutVariable.PostIncDec(target, null, location),
                new NodeExprWithoutVariable.PreIncDec(new NodeString("+", location), target, location))) {
            var error = assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertExpression(
                    new SyntaxExpression(new NodeExpr.ExprWithoutVariable(node, location),
                            new SourceInfo("broken-update.php", null))));
            assertEquals("broken-update.php", error.source().sourceId());
            assertEquals(new SourceRange(5, 3, 5, 12), error.source().range());
            assertTrue(error.fieldPath().startsWith("expression.ev."));
            assertFalse(error.reason().isBlank());
        }
    }

    // 新模型不携带 CUP AST；转换本身不会修改旧树、旧列表或旧声明索引。
    @Test
    void producesAstFreeResultsWithoutMutatingInput() throws ReflectiveOperationException {
        NodeProgram parsed = (NodeProgram) Main.parse("""
                <?php function f($a) {
                    $value = $a + 0x2A + 1.25;
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
                function bad() { echo 1; (new class {}); return 2; }
                function good() { return 3; }
                """));
        var declarations = file.namespaceSections().getFirst().declarations();
        var bad = (FunctionDefinition) declarations.getFirst();
        var good = (FunctionDefinition) declarations.get(1);
        String before = file.syntax().toTreeString(false);
        assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(bad.body()));
        IrBlock result = SyntaxConverter.convertBody(good.body());
        assertEquals(1, result.statements().size());
        assertEquals(3, assertInstanceOf(IrIntegerLiteral.class,
                assertInstanceOf(IrReturn.class, result.statements().getFirst()).value()).value());
        assertEquals(before, file.syntax().toTreeString(false));
    }

    private static SyntaxConversionException assertArrayFailure(NodeArrayPairList list, ComplexLocation location) {
        var array = new NodeDereferencableScalar.ShortArray(new NodeString("[", location), list, location);
        var expression = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.DereferencableScalar(array, location), location), location);
        var error = assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertExpression(
                new SyntaxExpression(expression, new SourceInfo("broken-array.php", null))));
        assertEquals("broken-array.php", error.source().sourceId());
        assertEquals(new SourceRange(4, 2, 4, 8), error.source().range());
        assertFalse(error.reason().isBlank());
        return error;
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

    private static String fixedName(IrAccessName name) {
        return assertInstanceOf(IrFixedName.class, name).value();
    }
}
