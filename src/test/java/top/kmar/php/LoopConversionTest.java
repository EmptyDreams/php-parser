package top.kmar.php;

import java_cup.runtime.AstNode;
import java_cup.runtime.symbol.complex.ComplexLocation;
import org.junit.jupiter.api.Test;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SourceRange;
import top.kmar.php.model.SyntaxBody;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证循环结构、表达式顺序和跳转层级的保留，不执行循环或校验控制流是否合法。 */
class LoopConversionTest {

    // 普通块、冒号体和单语句体统一为 IrBlock，while 与 do-while 保留不同节点。
    @Test
    void convertsWhileAndDoWhileBodiesWithoutChangingStatementOrder() {
        for (String code : List.of("while ($ready) { echo 1; return 2; }",
                "while ($ready): echo 1; return 2; endwhile;")) {
            IrWhile loop = assertInstanceOf(IrWhile.class, only(code));
            assertEquals("ready", fixedName(assertInstanceOf(IrVariable.class, loop.condition()).name()));
            assertEchoAndReturn(loop.body());
        }
        IrWhile single = assertInstanceOf(IrWhile.class, only("while ($ready) echo 1;"));
        assertEquals(1, single.body().statements().size());
        assertInteger(assertInstanceOf(IrEcho.class, single.body().statements().getFirst()).expressions().getFirst(), 1);

        IrDoWhile loop = assertInstanceOf(IrDoWhile.class, only("do { echo 1; return 2; } while ($ready);"));
        assertEchoAndReturn(loop.body());
        assertEquals("ready", fixedName(assertInstanceOf(IrVariable.class, loop.condition()).name()));
        IrDoWhile empty = assertInstanceOf(IrDoWhile.class, only("do ; while (1);"));
        assertInstanceOf(IrEmpty.class, empty.body().statements().getFirst());
        assertInteger(empty.condition(), 1);
    }

    // 三组列表各自有序；条件列表不是逻辑与，更新列表也不能与初始化混在一起。
    @Test
    void preservesAllThreeForExpressionListsInBothBodyForms() {
        for (String suffix : List.of("{ echo 1; return 2; }", ": echo 1; return 2; endfor;")) {
            IrFor loop = assertInstanceOf(IrFor.class, only("for (1, 2; 3, 4; 5, 6) " + suffix));
            assertEquals(List.of(1L, 2L), integers(loop.initializers()));
            assertEquals(List.of(3L, 4L), integers(loop.conditions()));
            assertEquals(List.of(5L, 6L), integers(loop.updates()));
            assertEchoAndReturn(loop.body());
        }
        IrFor loop = assertInstanceOf(IrFor.class,
                only("for ($i = 0; check(), $i < 9; $i++, $i += 2) ;"));
        IrAssignment initializer = assertInstanceOf(IrAssignment.class, loop.initializers().getFirst());
        assertEquals("i", fixedName(assertInstanceOf(IrVariableTarget.class, initializer.target()).name()));
        assertInteger(initializer.value(), 0);
        assertEquals(2, loop.conditions().size());
        IrCall check = assertInstanceOf(IrCall.class, loop.conditions().getFirst());
        assertEquals("check", assertInstanceOf(IrNamedCallTarget.class, check.target()).name().spelling());
        assertEquals(BinaryOperator.LESS, assertInstanceOf(IrBinary.class, loop.conditions().get(1)).operator());
        assertEquals(2, loop.updates().size());
        assertInstanceOf(IrUpdate.class, loop.updates().getFirst());
        assertInstanceOf(IrCompoundAssignment.class, loop.updates().get(1));
        assertInstanceOf(IrEmpty.class, loop.body().statements().getFirst());
    }

    // 空循环头仍是三个非 null 空列表，空块与单独分号的空语句保持区别。
    @Test
    void preservesEmptyHeadersAndEmptyBodies() {
        for (String code : List.of("for (;;) {}", "for (;;): endfor;")) {
            IrFor loop = assertInstanceOf(IrFor.class, only(code));
            assertTrue(loop.initializers().isEmpty());
            assertTrue(loop.conditions().isEmpty());
            assertTrue(loop.updates().isEmpty());
            assertTrue(loop.body().statements().isEmpty());
        }
        for (String code : List.of("while (1) {}", "while (1): endwhile;")) {
            assertTrue(assertInstanceOf(IrWhile.class, only(code)).body().statements().isEmpty());
        }
        for (String code : List.of("foreach ($items as $item) {}", "foreach ($items as $item): endforeach;")) {
            assertTrue(assertInstanceOf(IrForeach.class, only(code)).body().statements().isEmpty());
        }
        assertTrue(assertInstanceOf(IrDoWhile.class, only("do {} while (1);")).body().statements().isEmpty());
    }

    // 仅值目标与键值目标使用不同 AST 字段，但转换后统一且不会交换键和值。
    @Test
    void convertsForeachVariableTargetsAndBothBodyForms() {
        IrForeach valueOnly = assertInstanceOf(IrForeach.class, only("foreach (items() as $value) echo 1;"));
        IrCall items = assertInstanceOf(IrCall.class,
                assertInstanceOf(IrExpressionIterable.class, valueOnly.iterable()).expression());
        assertEquals("items", assertInstanceOf(IrNamedCallTarget.class, items.target()).name().spelling());
        assertNull(valueOnly.keyTarget());
        assertFalse(valueOnly.byReference());
        assertEquals("value", fixedName(assertInstanceOf(IrVariableTarget.class, valueOnly.valueTarget()).name()));
        assertEquals(1, valueOnly.body().statements().size());
        for (String suffix : List.of("{ echo 1; return 2; }", ": echo 1; return 2; endforeach;")) {
            IrForeach loop = assertInstanceOf(IrForeach.class, only("foreach ($items as $key => $value) " + suffix));
            assertEquals("items", fixedName(assertInstanceOf(IrVariable.class,
                    assertInstanceOf(IrExpressionIterable.class, loop.iterable()).expression()).name()));
            assertFalse(loop.byReference());
            assertEquals("key", fixedName(assertInstanceOf(IrVariableTarget.class, loop.keyTarget()).name()));
            assertEquals("value", fixedName(assertInstanceOf(IrVariableTarget.class, loop.valueTarget()).name()));
            assertEchoAndReturn(loop.body());
        }
    }

    // foreach 目标复用可写下标链，键和值均可追加，不能误当成无下标的读取。
    @Test
    void preservesForeachIndexAndAppendTargets() {
        IrForeach loop = assertInstanceOf(IrForeach.class,
                only("foreach ($items as $keys[] => $values[][0]) ;"));
        IrIndexTarget key = assertInstanceOf(IrIndexTarget.class, loop.keyTarget());
        assertNull(key.index());
        assertEquals("keys", fixedName(assertInstanceOf(IrVariableTarget.class, key.base()).name()));
        IrIndexTarget value = assertInstanceOf(IrIndexTarget.class, loop.valueTarget());
        assertInteger(value.index(), 0);
        IrIndexTarget append = assertInstanceOf(IrIndexTarget.class, value.base());
        assertNull(append.index());
        assertEquals("values", fixedName(assertInstanceOf(IrVariableTarget.class, append.base()).name()));
        assertInstanceOf(IrEmpty.class, loop.body().statements().getFirst());
    }

    // 片段中的层级表达式只做结构转换，不折叠、不补默认值，也不要求外层一定有循环。
    @Test
    void retainsOptionalJumpLevelsWithoutContextValidation() {
        List<IrStatement> statements = body("break; continue; break 2; continue ($depth); break 1 + 1;").statements();
        assertEquals(5, statements.size());
        assertNull(assertInstanceOf(IrBreak.class, statements.get(0)).levels());
        assertNull(assertInstanceOf(IrContinue.class, statements.get(1)).levels());
        assertInteger(assertInstanceOf(IrBreak.class, statements.get(2)).levels(), 2);
        assertEquals("depth", fixedName(assertInstanceOf(IrVariable.class,
                assertInstanceOf(IrContinue.class, statements.get(3)).levels()).name()));
        IrBinary levels = assertInstanceOf(IrBinary.class, assertInstanceOf(IrBreak.class, statements.get(4)).levels());
        assertEquals(BinaryOperator.ADD, levels.operator());
        assertInteger(levels.left(), 1);
        assertInteger(levels.right(), 1);
    }

    // 跳转留在原分支内，不提升到循环外，也不把 continue 的多层目标改写成 break。
    @Test
    void preservesJumpsInsideNestedLoopsAndBranches() {
        IrWhile outer = assertInstanceOf(IrWhile.class, only("while ($a) {"
                + " foreach ($items as $item) { if ($item) continue 2; break; } }"));
        IrForeach inner = assertInstanceOf(IrForeach.class, outer.body().statements().getFirst());
        assertEquals(2, inner.body().statements().size());
        IrIf branch = assertInstanceOf(IrIf.class, inner.body().statements().getFirst());
        assertEquals("item", fixedName(assertInstanceOf(IrVariable.class, branch.branches().getFirst().condition()).name()));
        IrContinue jump = assertInstanceOf(IrContinue.class, branch.branches().getFirst().body().statements().getFirst());
        assertInteger(jump.levels(), 2);
        assertNull(assertInstanceOf(IrBreak.class, inner.body().statements().get(1)).levels());
    }

    // 引用键、模式键和裸调用目标仍必须明确失败，不能因放宽值目标而丢掉键约束。
    @Test
    void rejectsUnsupportedForeachTargets() {
        for (String binding : List.of("&$key => $value", "list($key) => $value",
                "[$key] => $value", "f()")) {
            SyntaxBody syntax = syntaxBody("foreach ($items as " + binding + ") {}");
            var error = assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(syntax), binding);
            assertTrue(error.fieldPath().contains(".var") || error.fieldPath().contains(".key")
                    || error.fieldPath().contains(".valueVar"), binding);
            assertFalse(error.reason().isBlank());
        }
    }

    // 引用值目标保留引用标记；变量来源以可写迭代包装保存，键仍是普通写目标。
    @Test
    void convertsReferenceForeachValuesWithoutChangingKeyBindings() {
        for (String binding : List.of("&$value", "$key => &$value")) {
            IrForeach loop = assertInstanceOf(IrForeach.class, only("foreach ($items as " + binding + ") {}"));
            assertTrue(loop.byReference());
            IrWritableIterable iterable = assertInstanceOf(IrWritableIterable.class, loop.iterable());
            assertEquals("items", fixedName(assertInstanceOf(IrVariableTarget.class, iterable.target()).name()));
            assertEquals("value", fixedName(assertInstanceOf(IrVariableTarget.class, loop.valueTarget()).name()));
            if (binding.contains("=>")) {
                assertEquals("key", fixedName(assertInstanceOf(IrVariableTarget.class, loop.keyTarget()).name()));
            } else assertNull(loop.keyTarget());
        }
    }

    // foreach 长短解构仅替换值绑定，迭代对象仍按值读取，外层键不参与模式。
    @Test
    void convertsDestructuringForeachValuesWithoutChangingIterationMode() {
        for (String binding : List.of("list($value)", "[$value]", "$key => [$value]")) {
            IrForeach loop = assertInstanceOf(IrForeach.class, only("foreach ($items as " + binding + ") {}"));
            assertFalse(loop.byReference());
            assertEquals("items", fixedName(assertInstanceOf(IrVariable.class,
                    assertInstanceOf(IrExpressionIterable.class, loop.iterable()).expression()).name()));
            IrDestructuringPattern pattern = assertInstanceOf(IrDestructuringPattern.class, loop.valueTarget());
            assertEquals(1, pattern.slots().size());
            assertEquals("value", fixedName(assertInstanceOf(IrVariableTarget.class,
                    pattern.slots().getFirst().target()).name()));
            if (binding.contains("=>")) {
                assertEquals("key", fixedName(assertInstanceOf(IrVariableTarget.class, loop.keyTarget()).name()));
            } else assertNull(loop.keyTarget());
        }
    }

    // 必需条件、循环体、目标或列表缺失时，错误包含准确字段及来源，不能退化为空循环。
    @Test
    void reportsMissingLoopFieldsAndUnknownBodyVariants() {
        var location = ComplexLocation.of(3, 2, 3, 8);
        NodeExpr condition = rawStatement("while (1) {}").getCond();
        var emptyStatements = new NodeListNodeInnerStatement(List.of(), location);
        var emptyExpressions = new NodeListNodeExpr(List.of(), location);
        var whileBody = new NodeWhileStatement.AltBody(emptyStatements, location);
        var forBody = new NodeForStatement.AltBody(emptyStatements, location);
        var foreachBody = new NodeForeachStatement.AltBody(emptyStatements, location);
        assertInvalid(new NodeStatement.While(null, whileBody, location), ".cond");
        assertInvalid(new NodeStatement.While(condition, null, location), ".whileBody");
        assertInvalid(new NodeStatement.While(condition, new NodeWhileStatement.Body(null, location), location), ".body");
        assertInvalid(new NodeStatement.While(condition, new NodeWhileStatement.AltBody(null, location), location), ".stmts");
        assertInvalid(new NodeStatement.While(condition, new NodeWhileStatement(), location), ".whileBody");
        assertInvalid(new NodeStatement.DoWhile(null, condition, location), ".body");
        assertInvalid(new NodeStatement.DoWhile(new NodeStatement(), null, location), ".cond");
        assertInvalid(new NodeStatement.For(null, emptyExpressions, emptyExpressions, forBody, location), ".forInit");
        assertInvalid(new NodeStatement.For(emptyExpressions, null, emptyExpressions, forBody, location), ".forCond");
        assertInvalid(new NodeStatement.For(emptyExpressions, emptyExpressions, null, forBody, location), ".forStep");
        assertInvalid(new NodeStatement.For(emptyExpressions, emptyExpressions, emptyExpressions, null, location), ".forBody");
        assertInvalid(new NodeStatement.For(emptyExpressions, emptyExpressions, emptyExpressions,
                new NodeForStatement.AltBody(null, location), location), ".stmts");
        assertInvalid(new NodeStatement.Foreach(condition, null, foreachBody, location), ".var");
        assertInvalid(new NodeStatement.Foreach(condition, new NodeForeachVariable.Var(null, location), foreachBody, location), ".v");
        NodeForeachVariable target = rawStatement("foreach ($items as $item) {}").getVar();
        assertInvalid(new NodeStatement.Foreach(null, target, foreachBody, location), ".iterable");
        assertInvalid(new NodeStatement.Foreach(condition, target, null, location), ".foreachBody");
        assertInvalid(new NodeStatement.Foreach(condition, target,
                new NodeForeachStatement.AltBody(null, location), location), ".stmts");
        assertInvalid(new NodeStatement.ForeachKV(condition, null, target, foreachBody, location), ".key");
        assertInvalid(new NodeStatement.ForeachKV(condition, target, null, foreachBody, location), ".valueVar");
    }

    // 已有列表中的 null 元素与有效空列表不同，必须定位到对应的列表下标。
    @Test
    void rejectsNullForExpressionsAndLoopStatements() {
        NodeStatement loop = rawStatement("for (1; 2; 3) {}");
        loop.getForCond().getValue().add(null);
        assertInvalid(loop, ".forCond[1]");
        NodeStatement whileLoop = rawStatement("while (1): echo 1; endwhile;");
        whileLoop.getWhileBody().getStmts().getValue().add(null);
        assertInvalid(whileLoop, ".stmts[1]");
    }

    // 循环与跳转来源继承原始节点范围；不根据关键字或循环层级猜测新位置。
    @Test
    void preservesLoopAndJumpSourceRanges() {
        var location = ComplexLocation.of(4, 3, 4, 12);
        NodeExpr condition = rawStatement("while (1) {}").getCond();
        var jump = new NodeStatement.Break(null, location);
        var syntax = new NodeStatement.While(condition, new NodeWhileStatement.Body(jump, location), location);
        IrWhile loop = assertInstanceOf(IrWhile.class, SyntaxConverter.convertBody(
                new SyntaxBody(List.of(syntax), new SourceInfo("loops.php", null))).statements().getFirst());
        assertEquals(new SourceInfo("loops.php", new SourceRange(4, 3, 4, 12)), loop.source());
        assertEquals(loop.source(), loop.body().statements().getFirst().source());
    }

    private static void assertInvalid(AstNode node, String suffix) {
        var error = assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(
                new SyntaxBody(List.of(node), new SourceInfo("broken-loop.php", null))));
        assertEquals("broken-loop.php", error.source().sourceId());
        assertTrue(error.fieldPath().endsWith(suffix), error.fieldPath());
        assertFalse(error.reason().isBlank());
    }

    private static void assertEchoAndReturn(IrBlock block) {
        assertEquals(2, block.statements().size());
        assertInteger(assertInstanceOf(IrEcho.class, block.statements().getFirst()).expressions().getFirst(), 1);
        assertInteger(assertInstanceOf(IrReturn.class, block.statements().get(1)).value(), 2);
    }

    private static List<Long> integers(List<IrExpression> expressions) {
        return expressions.stream().map(value -> assertInstanceOf(IrIntegerLiteral.class, value).value()).toList();
    }

    private static void assertInteger(IrExpression expression, long expected) {
        assertEquals(expected, assertInstanceOf(IrIntegerLiteral.class, expression).value());
    }

    private static IrStatement only(String code) {
        IrBlock block = body(code);
        assertEquals(1, block.statements().size());
        return block.statements().getFirst();
    }

    private static IrBlock body(String code) {
        return SyntaxConverter.convertBody(syntaxBody(code));
    }

    private static SyntaxBody syntaxBody(String code) {
        var program = (NodeProgram) Main.parse("<?php function f() { " + code + " }");
        var statements = program.getStmts().getValue().getFirst().getFunction().getStmts().getValue();
        return new SyntaxBody(List.copyOf(statements), new SourceInfo("loops.php", null));
    }

    private static NodeStatement rawStatement(String code) {
        return assertInstanceOf(NodeInnerStatement.Statement.class, syntaxBody(code).statements().getFirst()).getStmt();
    }

    private static String fixedName(IrAccessName name) {
        return assertInstanceOf(IrFixedName.class, name).value();
    }
}
