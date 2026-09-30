package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SyntaxExpression;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 从真实 PHP AST 验证数组、索引及读改写表达式，不在 IR 转换阶段执行或降级展开。 */
class ArrayAndUpdateConversionTest {

    // 长短数组写法统一为有序条目；空数组和单个尾逗号产生的空槽不变成数组元素。
    @Test
    void normalizesEmptyArraysAndSingleTrailingCommas() {
        for (String code : List.of("[]", "array()")) {
            assertTrue(array(code).entries().isEmpty(), code);
        }
        for (String code : List.of("[1]", "[1,]", "array(1)", "array(1,)")) {
            IrArrayLiteral array = array(code);
            assertEquals(1, array.entries().size(), code);
            assertNull(array.entries().getFirst().key());
            assertInteger(1, assertInstanceOf(IrValueArrayEntry.class, array.entries().getFirst()).value());
        }
        IrArrayLiteral nested = array("array([1,], array(),)");
        assertEquals(2, nested.entries().size());
        IrArrayLiteral first = assertInstanceOf(IrArrayLiteral.class,
                assertInstanceOf(IrValueArrayEntry.class, nested.entries().getFirst()).value());
        assertEquals(1, first.entries().size());
        assertInteger(1, assertInstanceOf(IrValueArrayEntry.class, first.entries().getFirst()).value());
        assertTrue(assertInstanceOf(IrArrayLiteral.class,
                assertInstanceOf(IrValueArrayEntry.class, nested.entries().get(1)).value()).entries().isEmpty());
    }

    // 保留插入顺序、重复键、显式 null 键与缺省键，不预先分配索引或转换键的类型。
    @Test
    void preservesOrderedEntriesDuplicateKeysAndUnconvertedKeys() {
        for (String code : List.of(
                "[first(), 2 => second(), 2 => third(), null => fourth(), fifth(), 1.5 => sixth(), '2' => seventh()]",
                "array(first(), 2 => second(), 2 => third(), null => fourth(), fifth(), 1.5 => sixth(), '2' => seventh())")) {
            List<IrArrayEntry> entries = array(code).entries();
            assertEquals(7, entries.size());
            List<String> names = List.of("first", "second", "third", "fourth", "fifth", "sixth", "seventh");
            for (int i = 0; i < entries.size(); i++) {
                assertCall(names.get(i), assertInstanceOf(IrValueArrayEntry.class, entries.get(i)).value());
            }
            assertNull(entries.getFirst().key());
            assertInteger(2, entries.get(1).key());
            assertInteger(2, entries.get(2).key());
            assertInstanceOf(IrNullLiteral.class, entries.get(3).key());
            assertNull(entries.get(4).key());
            assertEquals(1.5, assertInstanceOf(IrFloatLiteral.class, entries.get(5).key()).value());
            assertString("2", entries.get(6).key());
        }
    }

    // 键和值仍是独立表达式，嵌套数组不会与外层条目合并，也不提前执行调用。
    @Test
    void preservesKeyAndValueExpressionsAndNestedArrays() {
        IrArrayLiteral outer = array("[key() => [value(),], after()]");
        assertEquals(2, outer.entries().size());
        assertCall("key", outer.entries().getFirst().key());
        IrArrayLiteral inner = assertInstanceOf(IrArrayLiteral.class,
                assertInstanceOf(IrValueArrayEntry.class, outer.entries().getFirst()).value());
        assertEquals(1, inner.entries().size());
        assertNull(inner.entries().getFirst().key());
        assertCall("value", assertInstanceOf(IrValueArrayEntry.class, inner.entries().getFirst()).value());
        assertNull(outer.entries().get(1).key());
        assertCall("after", assertInstanceOf(IrValueArrayEntry.class, outer.entries().get(1)).value());
    }

    // 文法保留的首部、中间或连续空槽不是普通数组元素，不能在转换时悄悄删除。
    @Test
    void rejectsArrayHolesOtherThanAnEmptyArrayOrSingleTrailingSlot() {
        for (String code : List.of("[,]", "[,,]", "[,1]", "[1,,2]", "[1,,]",
                "array(,)", "array(,1)", "array(1,,2)", "array(1,,)", "[[1,,2]]")) {
            assertRejected(code);
        }
    }

    // 普通数组构造中的 list 不是值表达式，不能因支持解构而误当作数组元素。
    @Test
    void rejectsDestructuringInsideOrdinaryArrayValues() {
        for (String code : List.of("[list($a)]", "['k' => list($a)]",
                "array(list($a))", "array('k' => list($a))")) {
            assertRejected(code);
        }
    }

    // 原先拒绝的引用条目现在明确保存写目标，不能伪装成普通数组值。
    @Test
    void convertsReferenceArrayEntriesWithoutReadingTheirTargets() {
        for (String code : List.of("[&$a]", "['k' => &$a]", "array(&$a)", "array('k' => &$a)")) {
            IrArrayLiteral array = array(code);
            assertEquals(1, array.entries().size());
            IrReferenceArrayEntry entry = assertInstanceOf(IrReferenceArrayEntry.class, array.entries().getFirst());
            assertVariableTarget("a", entry.target());
            if (code.contains("=>")) assertString("k", entry.key());
            else assertNull(entry.key());
        }
    }

    // 引用赋值的来源与目标分别保留可写链，不降级成普通读取赋值。
    @Test
    void distinguishesReferenceAssignmentSourcesFromReadExpressions() {
        IrReferenceAssignment indexedTarget = assertInstanceOf(IrReferenceAssignment.class,
                expression("$a[0] =& $b"));
        IrIndexTarget target = assertInstanceOf(IrIndexTarget.class, indexedTarget.target());
        assertVariableTarget("a", target.base());
        assertInteger(0, target.index());
        assertVariableTarget("b", indexedTarget.reference());

        IrReferenceAssignment indexedSource = assertInstanceOf(IrReferenceAssignment.class,
                expression("$a =& $b[0]"));
        assertVariableTarget("a", indexedSource.target());
        IrIndexTarget reference = assertInstanceOf(IrIndexTarget.class, indexedSource.reference());
        assertVariableTarget("b", reference.base());
        assertInteger(0, reference.index());
    }

    // 长短解构赋值都保留独立模式及唯一右值，不与普通数组构造混淆。
    @Test
    void distinguishesDestructuringAssignmentsFromArrayValues() {
        for (String code : List.of("[$a, $b] = $items", "list($a, $b) = $items")) {
            IrDestructuringAssignment assignment = assertInstanceOf(IrDestructuringAssignment.class, expression(code));
            assertVariable("items", assignment.value());
            assertEquals(2, assignment.pattern().slots().size());
            assertVariableTarget("a", assertInstanceOf(IrVariableTarget.class,
                    assignment.pattern().slots().getFirst().target()));
            assertVariableTarget("b", assertInstanceOf(IrVariableTarget.class,
                    assignment.pattern().slots().get(1).target()));
        }
    }

    // 读取索引的基底可为已支持的任意可解引用表达式，不套用写目标的变量根限制。
    @Test
    void convertsIndexReadsFromEverySupportedBaseKind() {
        IrIndex variable = index("$items[0]");
        assertVariable("items", variable.base());
        assertInteger(0, variable.index());

        assertCall("items", index("items()[1]").base());
        assertInteger(1, index("items()[1]").index());
        IrArrayLiteral literal = assertInstanceOf(IrArrayLiteral.class, index("[42][0]").base());
        assertEquals(1, literal.entries().size());
        assertInteger(42, assertInstanceOf(IrValueArrayEntry.class, literal.entries().getFirst()).value());
        assertInstanceOf(IrArrayLiteral.class, index("array(42)[0]").base());

        assertString("text", index("'text'[1]").base());
        IrConstantReference constant = assertInstanceOf(IrConstantReference.class, index("ITEMS[0]").base());
        assertEquals("ITEMS", constant.name().spelling());
        assertEquals(NameForm.UNQUALIFIED, constant.name().form());
        IrConstantReference qualified = assertInstanceOf(IrConstantReference.class, index("\\Demo\\ITEMS[0]").base());
        assertEquals("\\Demo\\ITEMS", qualified.name().spelling());
        assertEquals(NameForm.FULLY_QUALIFIED, qualified.name().form());

        assertVariable("items", index("($items)[0]").base());
        assertInteger(1, index("(1)[0]").base());
        IrBinary computed = assertInstanceOf(IrBinary.class, index("($a + $b)[0]").base());
        assertEquals(BinaryOperator.ADD, computed.operator());
        assertVariable("a", computed.left());
        assertVariable("b", computed.right());
    }

    // 花括号和方括号偏移统一为索引节点，并保持混合多维下标的嵌套顺序。
    @Test
    void normalizesCurlyAndSquareIndicesWithoutFlatteningTheirOrder() {
        IrIndex outer = index("$items{first()}[second()]");
        assertCall("second", outer.index());
        IrIndex inner = assertInstanceOf(IrIndex.class, outer.base());
        assertVariable("items", inner.base());
        assertCall("first", inner.index());

        IrIndex curlyString = index("'text'{1}");
        assertString("text", curlyString.base());
        assertInteger(1, curlyString.index());

        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression("$items{1}[2] = 3"));
        IrIndexTarget outerTarget = assertInstanceOf(IrIndexTarget.class, assignment.target());
        assertInteger(2, outerTarget.index());
        IrIndexTarget innerTarget = assertInstanceOf(IrIndexTarget.class, outerTarget.base());
        assertInteger(1, innerTarget.index());
        assertVariableTarget("items", innerTarget.base());
        assertInteger(3, assignment.value());
    }

    // 普通赋值使用目标链而非读索引；连续赋值保持右结合，右侧读取仍使用 IrIndex。
    @Test
    void distinguishesRecursiveIndexTargetsFromIndexReads() {
        IrAssignment outer = assertInstanceOf(IrAssignment.class, expression("$a[0][1] = $b[2] = $c[3]"));
        IrIndexTarget outerTarget = assertInstanceOf(IrIndexTarget.class, outer.target());
        assertInteger(1, outerTarget.index());
        IrIndexTarget innerTarget = assertInstanceOf(IrIndexTarget.class, outerTarget.base());
        assertInteger(0, innerTarget.index());
        assertVariableTarget("a", innerTarget.base());

        IrAssignment inner = assertInstanceOf(IrAssignment.class, outer.value());
        IrIndexTarget otherTarget = assertInstanceOf(IrIndexTarget.class, inner.target());
        assertInteger(2, otherTarget.index());
        assertVariableTarget("b", otherTarget.base());
        IrIndex read = assertInstanceOf(IrIndex.class, inner.value());
        assertVariable("c", read.base());
        assertInteger(3, read.index());

        IrAssignment parenthesized = assertInstanceOf(IrAssignment.class, expression("($a)[0] = 1"));
        IrIndexTarget parenthesizedTarget = assertInstanceOf(IrIndexTarget.class, parenthesized.target());
        assertInteger(0, parenthesizedTarget.index());
        assertVariableTarget("a", parenthesizedTarget.base());
        assertInteger(1, parenthesized.value());
    }

    // append 以目标链中的 null index 表示，可出现在末层或中间层，不能补成读索引。
    @Test
    void preservesAppendAtEveryWritableTargetDepth() {
        IrAssignment direct = assertInstanceOf(IrAssignment.class, expression("$a[] = 1"));
        IrIndexTarget append = assertInstanceOf(IrIndexTarget.class, direct.target());
        assertNull(append.index());
        assertVariableTarget("a", append.base());
        assertInteger(1, direct.value());

        IrIndexTarget afterAppend = assertInstanceOf(IrIndexTarget.class,
                assertInstanceOf(IrAssignment.class, expression("$a[][0] = 2")).target());
        assertInteger(0, afterAppend.index());
        IrIndexTarget beforeIndex = assertInstanceOf(IrIndexTarget.class, afterAppend.base());
        assertNull(beforeIndex.index());
        assertVariableTarget("a", beforeIndex.base());

        IrIndexTarget lastAppend = assertInstanceOf(IrIndexTarget.class,
                assertInstanceOf(IrAssignment.class, expression("$a[0][] = 3")).target());
        assertNull(lastAppend.index());
        IrIndexTarget firstIndex = assertInstanceOf(IrIndexTarget.class, lastAppend.base());
        assertInteger(0, firstIndex.index());
        assertVariableTarget("a", firstIndex.base());

        IrIndexTarget secondAppend = assertInstanceOf(IrIndexTarget.class,
                assertInstanceOf(IrAssignment.class, expression("$a[][] = 4")).target());
        assertNull(secondAppend.index());
        IrIndexTarget firstAppend = assertInstanceOf(IrIndexTarget.class, secondAppend.base());
        assertNull(firstAppend.index());
        assertVariableTarget("a", firstAppend.base());
    }

    // 没有偏移的索引不能读取，嵌套读取和空合并左侧也不能放宽为 append。
    @Test
    void rejectsAppendInReadContextsIncludingCoalescing() {
        for (String code : List.of("$a[]", "$a[][0]", "$a[0][]", "$a[] ?? 1", "$a[][0] ?? 1",
                "items()[]", "[1][]", "ITEMS[]", "$a[$b[]] = 1", "[$a[]]")) {
            assertRejected(code);
        }
    }

    // 扩展可写访问链后，常量、字面量与运算结果仍不能成为目标根。
    @Test
    void rejectsUnsupportedRootsForEveryWriteOperation() {
        for (String target : List.of("[1][0]", "ITEMS[0]", "'text'[0]", "($a + $b)[0]")) {
            assertRejected(target + " = 1");
            assertRejected(target + " += 1");
            assertRejected(target + "++");
        }
    }

    // 十二种复合赋值均有独立操作符，不混用比较或短路运算，也不展开为普通赋值。
    @Test
    void convertsEveryCompoundAssignmentOperator() {
        Map<String, CompoundAssignmentOperator> operators = Map.ofEntries(
                Map.entry("+=", CompoundAssignmentOperator.ADD), Map.entry("-=", CompoundAssignmentOperator.SUBTRACT),
                Map.entry("*=", CompoundAssignmentOperator.MULTIPLY), Map.entry("/=", CompoundAssignmentOperator.DIVIDE),
                Map.entry("%=", CompoundAssignmentOperator.MODULO), Map.entry("**=", CompoundAssignmentOperator.POWER),
                Map.entry(".=", CompoundAssignmentOperator.CONCAT), Map.entry("<<=", CompoundAssignmentOperator.SHIFT_LEFT),
                Map.entry(">>=", CompoundAssignmentOperator.SHIFT_RIGHT), Map.entry("&=", CompoundAssignmentOperator.BITWISE_AND),
                Map.entry("|=", CompoundAssignmentOperator.BITWISE_OR), Map.entry("^=", CompoundAssignmentOperator.BITWISE_XOR));
        operators.forEach((token, operator) -> {
            IrCompoundAssignment assignment = assertInstanceOf(IrCompoundAssignment.class, expression("$a " + token + " 2"));
            assertEquals(operator, assignment.operator(), token);
            assertVariableTarget("a", assignment.target());
            assertInteger(2, assignment.value());
        });
    }

    // 前后置递增递减分别建模，作为其它表达式的操作数时也不能丢失返回值时机。
    @Test
    void distinguishesEveryPrefixAndPostfixUpdate() {
        Map<String, UpdateOperator> operators = Map.of(
                "++$a", UpdateOperator.PRE_INCREMENT, "$a++", UpdateOperator.POST_INCREMENT,
                "--$a", UpdateOperator.PRE_DECREMENT, "$a--", UpdateOperator.POST_DECREMENT);
        operators.forEach((code, operator) -> {
            IrUpdate update = assertInstanceOf(IrUpdate.class, expression(code));
            assertEquals(operator, update.operator(), code);
            assertVariableTarget("a", update.target());
        });
        IrBinary binary = assertInstanceOf(IrBinary.class, expression("++$a + $b--"));
        assertEquals(BinaryOperator.ADD, binary.operator());
        IrUpdate prefix = assertInstanceOf(IrUpdate.class, binary.left());
        assertEquals(UpdateOperator.PRE_INCREMENT, prefix.operator());
        assertVariableTarget("a", prefix.target());
        IrUpdate postfix = assertInstanceOf(IrUpdate.class, binary.right());
        assertEquals(UpdateOperator.POST_DECREMENT, postfix.operator());
        assertVariableTarget("b", postfix.target());
    }

    // 读改写表达式中的目标链只出现一次，保留下标调用及右值调用，不复制带副作用的索引。
    @Test
    void keepsSideEffectingTargetsSingleInCompoundAssignmentsAndUpdates() {
        IrCompoundAssignment assignment = assertInstanceOf(IrCompoundAssignment.class,
                expression("$a[first()][second()] += rhs()"));
        assertEquals(CompoundAssignmentOperator.ADD, assignment.operator());
        IrIndexTarget outer = assertInstanceOf(IrIndexTarget.class, assignment.target());
        assertCall("second", outer.index());
        IrIndexTarget inner = assertInstanceOf(IrIndexTarget.class, outer.base());
        assertCall("first", inner.index());
        assertVariableTarget("a", inner.base());
        assertCall("rhs", assignment.value());

        IrUpdate update = assertInstanceOf(IrUpdate.class, expression("$a[next()]++"));
        assertEquals(UpdateOperator.POST_INCREMENT, update.operator());
        IrIndexTarget target = assertInstanceOf(IrIndexTarget.class, update.target());
        assertVariableTarget("a", target.base());
        assertCall("next", target.index());
    }

    // PHP 7.2 的读改写上下文允许 append，不能按普通读取路径拒绝空偏移。
    @Test
    void acceptsAppendTargetsInCompoundAssignmentsAndUpdates() {
        IrCompoundAssignment assignment = assertInstanceOf(IrCompoundAssignment.class, expression("$a[] += 1"));
        assertEquals(CompoundAssignmentOperator.ADD, assignment.operator());
        IrIndexTarget target = assertInstanceOf(IrIndexTarget.class, assignment.target());
        assertNull(target.index());
        assertVariableTarget("a", target.base());
        assertInteger(1, assignment.value());

        for (String code : List.of("++$a[]", "$a[]++", "--$a[]", "$a[]--")) {
            IrUpdate update = assertInstanceOf(IrUpdate.class, expression(code));
            IrIndexTarget append = assertInstanceOf(IrIndexTarget.class, update.target());
            assertNull(append.index());
            assertVariableTarget("a", append.base());
        }
        IrUpdate nested = assertInstanceOf(IrUpdate.class, expression("$a[][next()]++"));
        IrIndexTarget outer = assertInstanceOf(IrIndexTarget.class, nested.target());
        assertCall("next", outer.index());
        IrIndexTarget inner = assertInstanceOf(IrIndexTarget.class, outer.base());
        assertNull(inner.index());
        assertVariableTarget("a", inner.base());
    }

    private static IrArrayLiteral array(String code) {
        return assertInstanceOf(IrArrayLiteral.class, expression(code));
    }

    private static IrIndex index(String code) {
        return assertInstanceOf(IrIndex.class, expression(code));
    }

    private static IrExpression expression(String code) {
        return SyntaxConverter.convertExpression(syntaxExpression(code));
    }

    private static SyntaxExpression syntaxExpression(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php " + code + ";");
        NodeExpr expression = parsed.getStmts().getValue().getFirst().getStmt().getExpression();
        return new SyntaxExpression(expression, new SourceInfo("array-update.php", null));
    }

    private static void assertRejected(String code) {
        SyntaxExpression syntax = assertDoesNotThrow(() -> syntaxExpression(code), code);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                () -> SyntaxConverter.convertExpression(syntax), code);
        assertEquals("array-update.php", error.source().sourceId());
        assertFalse(error.fieldPath().isBlank(), code);
        assertFalse(error.reason().isBlank(), code);
    }

    private static void assertString(String value, IrExpression actual) {
        assertArrayEquals(value.getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringLiteral.class, actual).value().toByteArray());
    }

    private static void assertInteger(long expected, IrExpression expression) {
        assertEquals(expected, assertInstanceOf(IrIntegerLiteral.class, expression).value());
    }

    private static void assertVariable(String expected, IrExpression expression) {
        assertEquals(expected, fixedName(assertInstanceOf(IrVariable.class, expression).name()));
    }

    private static void assertVariableTarget(String expected, IrWriteBase target) {
        assertEquals(expected, fixedName(assertInstanceOf(IrVariableTarget.class, target).name()));
    }

    private static void assertCall(String expected, IrExpression expression) {
        IrCall call = assertInstanceOf(IrCall.class, expression);
        assertEquals(expected, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
        assertTrue(call.arguments().isEmpty());
    }

    private static String fixedName(IrAccessName name) {
        return assertInstanceOf(IrFixedName.class, name).value();
    }
}
