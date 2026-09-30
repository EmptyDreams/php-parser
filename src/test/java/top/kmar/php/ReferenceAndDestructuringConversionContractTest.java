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

/** 验证引用、解构和 foreach 的模型不变量、损坏 AST、来源与输入隔离。 */
class ReferenceAndDestructuringConversionContractTest {
    private static final ComplexLocation OUTER = ComplexLocation.of(3, 2, 8, 40);
    private static final ComplexLocation ITEMS = ComplexLocation.of(4, 3, 4, 25);
    private static final ComplexLocation SLOT = ComplexLocation.of(4, 5, 4, 19);
    private static final ComplexLocation LEAF = ComplexLocation.of(4, 7, 4, 12);
    private static final ComplexLocation RIGHT = ComplexLocation.of(7, 2, 7, 15);
    private static final SourceInfo SOURCE = new SourceInfo("references.php", null);

    // 引用赋值的两端都是必需字段，未知变量包装不能根据看似有效的子标签降级。
    @Test
    void rejectsMissingAndUnknownReferenceAssignmentOperands() {
        assertExpressionFailure(new NodeExprWithoutVariable.AssignRef(null, variable("right", RIGHT), OUTER), ".target");
        assertExpressionFailure(new NodeExprWithoutVariable.AssignRef(variable("left", LEAF), null, OUTER), ".ref");
        NodeVariable unknown = new NodeVariable() {
            @Override public NodeCallableVariable getCv() { return variable("value", LEAF).getCv(); }
            @Override public ComplexLocation getLocation() { return RIGHT; }
        };
        assertExpressionFailure(new NodeExprWithoutVariable.AssignRef(variable("left", LEAF), unknown, OUTER), ".ref");
        assertExpressionFailure(new NodeExprWithoutVariable.AssignRef(unknown, variable("right", RIGHT), OUTER), ".target");
    }

    // 引用数组项不能缺少引用目标或显式键，普通调用不能冒充数组项的单个写目标。
    @Test
    void rejectsMalformedReferenceArrayEntries() {
        assertArrayFailure(new NodeArrayPair.RefValue(null, SLOT), ".ref");
        assertArrayFailure(new NodeArrayPair.KeyRefValue(null, variable("value", LEAF), SLOT), ".key");
        assertArrayFailure(new NodeArrayPair.KeyRefValue(integer(1, LEAF), null, SLOT), ".ref");
        assertArrayFailure(new NodeArrayPair.RefValue(parsedExpression("factory()").getV(), SLOT), ".ref");
        assertArrayFailure(new NodeArrayPair.KeyRefValue(integer(1, LEAF), parsedExpression("factory()").getV(), SLOT), ".ref");
    }

    // 长短解构的条目列表和右值均必需；空产生式包装不能用 null 代替。
    @Test
    void rejectsMissingDestructuringFieldsAndListWrappers() {
        for (boolean shortSyntax : List.of(false, true)) {
            assertExpressionFailure(destructuring(shortSyntax, null, integer(1, RIGHT)), ".items");
            assertExpressionFailure(destructuring(shortSyntax, pairs(valuePair("value")), null), ".value");
            assertExpressionFailure(destructuring(shortSyntax,
                    new NodeArrayPairList.ArrayPairList(null, ITEMS), integer(1, RIGHT)), ".items");
            assertExpressionFailure(destructuring(shortSyntax,
                    new NodeArrayPairList.ArrayPairList(new NodeListNodePossibleArrayPair(null, ITEMS), ITEMS),
                    integer(1, RIGHT)), ".items");
            NodeArrayPairList unknown = new NodeArrayPairList() {
                @Override public NodeListNodePossibleArrayPair getItems() { return pairs(valuePair("value")).getItems(); }
                @Override public ComplexLocation getLocation() { return ITEMS; }
            };
            assertExpressionFailure(destructuring(shortSyntax, unknown, integer(1, RIGHT)), ".items");
        }
    }

    // 语法槽位列表必须存在且无 null 元素；合法跳过槽是包装内缺省 pair 而不是 null 元素。
    @Test
    void rejectsMalformedDestructuringSlotLists() {
        for (List<NodePossibleArrayPair> slots : List.of(List.<NodePossibleArrayPair>of(),
                Arrays.asList(slot(valuePair("value"), SLOT), null))) {
            var items = new NodeArrayPairList.ArrayPairList(new NodeListNodePossibleArrayPair(slots, ITEMS), ITEMS);
            assertFailure(() -> convert(destructuring(false, items, integer(1, RIGHT))), ".items", false);
        }
        IrDestructuringAssignment result = assertInstanceOf(IrDestructuringAssignment.class,
                convert(destructuring(false, pairs(null, valuePair("value"), null), integer(1, RIGHT))));
        assertEquals(2, result.pattern().slots().size());
        assertNull(result.pattern().slots().getFirst().target());
        assertInstanceOf(IrVariableTarget.class, result.pattern().slots().get(1).target());
    }

    // 各种模式条目都校验必需字段，未知条目不能当作普通值条目处理。
    @Test
    void rejectsMissingAndUnknownDestructuringEntryFields() {
        assertPatternFailure(new NodeArrayPair.Value(null, SLOT), ".value");
        assertPatternFailure(new NodeArrayPair.KeyValue(null, variableExpression("value", LEAF), SLOT), ".key");
        assertPatternFailure(new NodeArrayPair.KeyValue(integer(1, LEAF), null, SLOT), ".value");
        assertPatternFailure(new NodeArrayPair.ListValue(null, SLOT), ".items");
        assertPatternFailure(new NodeArrayPair.KeyListValue(null, pairs(valuePair("value")), SLOT), ".key");
        assertPatternFailure(new NodeArrayPair.KeyListValue(integer(1, LEAF), null, SLOT), ".items");
        assertPatternFailure(new NodeArrayPair() {
            @Override public NodeExpr getValue() { return variableExpression("value", LEAF); }
            @Override public ComplexLocation getLocation() { return SLOT; }
        }, ".pair");
    }

    // 模式必须能绑定真实目标；同层键模式不能混用，有键模式不能夹带跳过槽。
    @Test
    void rejectsEmptyMixedAndKeyedHolePatterns() {
        for (String code : List.of("list() = $items", "[] = $items", "list(,) = $items", "[,,] = $items",
                "list(0 => $a, $b) = $items", "[0 => $a, $b] = $items", "[$a, 1 => $b] = $items",
                "list(, 0 => $a) = $items", "[0 => $a, ,] = $items")) {
            NodeExpr parsed = parsedExpression(code);
            assertFailure(() -> convertExpression(parsed), ".items", false);
        }
    }

    // PHP 7.2 不允许解构叶引用或混合 list/[] 嵌套，array(...) 也不是解构模式。
    @Test
    void rejectsReferenceLeavesAndInconsistentPatternSyntax() {
        for (String code : List.of("list(&$a) = $items", "[&$a] = $items", "list('k' => &$a) = $items",
                "['k' => &$a] = $items", "list([$a]) = $items", "[list($a)] = $items",
                "list(array($a)) = $items", "[array($a)] = $items")) {
            NodeExpr parsed = parsedExpression(code);
            assertFailure(() -> convertExpression(parsed), ".items", false);
        }
    }

    // 非变量叶、损坏表达式包装和未知表达式不能被当成可写目标。
    @Test
    void rejectsInvalidAndUnknownPatternLeaves() {
        for (String leaf : List.of("1", "null", "factory()", "$a + 1", "new class {}")) {
            assertPatternFailure(new NodeArrayPair.Value(parsedExpression(leaf), SLOT), ".value", false);
        }
        assertPatternFailure(new NodeArrayPair.Value(new NodeExpr.VariableExpr(null, LEAF), SLOT), ".v");
        assertPatternFailure(new NodeArrayPair.Value(new NodeExpr.ExprWithoutVariable(null, LEAF), SLOT), ".ev");
        assertPatternFailure(new NodeArrayPair.Value(new NodeExpr() {
            @Override public NodeVariable getV() { return variable("value", LEAF); }
            @Override public ComplexLocation getLocation() { return LEAF; }
        }, SLOT), ".value");
    }

    // 嵌套模式识别前必须逐层验证括号和标量包装，缺失子树不能降级成普通非法叶。
    @Test
    void rejectsMissingNestedPatternExpressionWrappers() {
        assertPatternFailure(new NodeArrayPair.Value(new NodeExpr.ExprWithoutVariable(
                new NodeExprWithoutVariable.Paren(null, LEAF), LEAF), SLOT), ".expr");
        assertPatternFailure(new NodeArrayPair.Value(new NodeExpr.ExprWithoutVariable(
                new NodeExprWithoutVariable.Scalar(null, LEAF), LEAF), SLOT), ".scalar");
        assertPatternFailure(new NodeArrayPair.Value(new NodeExpr.ExprWithoutVariable(
                new NodeExprWithoutVariable.Scalar(new NodeScalar.DereferencableScalar(null, LEAF), LEAF), LEAF), SLOT), ".ds");
    }

    // 短数组模式的标记与列表必需，即使已经处于短解构上下文也不能忽略损坏字段。
    @Test
    void rejectsMalformedNestedShortPatternMarkersAndItems() {
        for (NodeString marker : new NodeString[]{null, token(null, LEAF), token("", LEAF),
                token("array", LEAF), token("[ ", LEAF), token("]", LEAF)}) {
            NodeDereferencableScalar array = new NodeDereferencableScalar.ShortArray(marker, pairs(valuePair("nested")), ITEMS);
            NodeExpr nested = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                    new NodeScalar.DereferencableScalar(array, ITEMS), ITEMS), ITEMS);
            assertExpressionFailure(destructuring(true, pairs(new NodeArrayPair.Value(nested, SLOT)), integer(1, RIGHT)), ".kw");
        }
        NodeDereferencableScalar array = new NodeDereferencableScalar.ShortArray(token("[", LEAF), null, ITEMS);
        NodeExpr nested = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.DereferencableScalar(array, ITEMS), ITEMS), ITEMS);
        assertExpressionFailure(destructuring(true, pairs(new NodeArrayPair.Value(nested, SLOT)), integer(1, RIGHT)), ".items");
    }

    // foreach 引用标记必须是完整的 &，引用目标和长短模式列表均不能缺失。
    @Test
    void rejectsMalformedForeachBindingMarkersAndFields() {
        for (NodeString marker : new NodeString[]{null, token(null, LEAF), token("", LEAF), token("&&", LEAF),
                token(" &", LEAF), token("& ", LEAF)}) {
            assertStatementFailure(foreach(new NodeForeachVariable.Ref(marker, variable("value", LEAF), SLOT)), ".amp");
        }
        assertStatementFailure(foreach(new NodeForeachVariable.Ref(token("&", LEAF), null, SLOT)), ".v");
        assertStatementFailure(foreach(new NodeForeachVariable.List(null, SLOT)), ".items");
        assertStatementFailure(foreach(new NodeForeachVariable.ShortList(null, SLOT)), ".items");
        assertStatementFailure(foreach(new NodeForeachVariable() {
            @Override public NodeVariable getV() { return variable("value", LEAF); }
            @Override public ComplexLocation getLocation() { return SLOT; }
        }), ".var");
    }

    // 可写迭代源分类不能捕获转换异常后回退读值，损坏位置必须保留原来的字段诊断。
    @Test
    void rejectsMalformedWritableIterablesWithoutReadFallback() {
        NodeForeachVariable binding = new NodeForeachVariable.Ref(token("&", LEAF), variable("value", LEAF), SLOT);
        assertStatementFailure(new NodeStatement.Foreach(null, binding, emptyForeachBody(), OUTER), ".iterable");
        assertStatementFailure(new NodeStatement.Foreach(new NodeExpr.VariableExpr(null, RIGHT), binding,
                emptyForeachBody(), OUTER), ".v");
        NodeVariable broken = new NodeVariable.CallableVariable(new NodeCallableVariable.SimpleVar(null, RIGHT), RIGHT);
        assertStatementFailure(new NodeStatement.Foreach(new NodeExpr.VariableExpr(broken, RIGHT), binding,
                emptyForeachBody(), OUTER), ".sv");
        NodeExpr unknown = new NodeExpr() {
            @Override public NodeVariable getV() { return variable("items", RIGHT); }
            @Override public ComplexLocation getLocation() { return RIGHT; }
        };
        assertStatementFailure(new NodeStatement.Foreach(unknown, binding, emptyForeachBody(), OUTER), ".iterable");
    }

    // 迭代源地址分类逐层拒绝未知变量和访问变体，不能仅凭同名 getter 认为可写。
    @Test
    void rejectsUnknownIterableAccessWrappers() {
        NodeVariable unknownVariable = new NodeVariable() {
            @Override public NodeCallableVariable getCv() { return variable("items", RIGHT).getCv(); }
            @Override public ComplexLocation getLocation() { return RIGHT; }
        };
        assertIterableFailure(new NodeExpr.VariableExpr(unknownVariable, RIGHT), ".v");
        NodeCallableVariable unknownCallable = new NodeCallableVariable() {
            @Override public NodeSimpleVariable getSv() { return variable("items", RIGHT).getCv().getSv(); }
            @Override public ComplexLocation getLocation() { return RIGHT; }
        };
        assertIterableFailure(new NodeExpr.VariableExpr(new NodeVariable.CallableVariable(unknownCallable, RIGHT), RIGHT), ".cv");
        NodeDereferencable unknownBase = new NodeDereferencable() {
            @Override public NodeVariable getV() { return variable("items", RIGHT); }
            @Override public ComplexLocation getLocation() { return RIGHT; }
        };
        NodeCallableVariable index = new NodeCallableVariable.Index(unknownBase, integer(0, LEAF), RIGHT);
        assertIterableFailure(new NodeExpr.VariableExpr(new NodeVariable.CallableVariable(index, RIGHT), RIGHT), ".d");
    }

    // 分类访问链时不能跳过必需的基底或括号操作数，诊断仍指向最内层缺失字段。
    @Test
    void rejectsMissingIterableAccessBasesAndParenthesizedOperands() {
        NodeCallableVariable missingBase = new NodeCallableVariable.Index(null, integer(0, LEAF), RIGHT);
        assertIterableFailure(new NodeExpr.VariableExpr(new NodeVariable.CallableVariable(missingBase, RIGHT), RIGHT), ".d");
        NodeCallableVariable missingInner = new NodeCallableVariable.Index(
                new NodeDereferencable.Paren(null, ITEMS), integer(0, LEAF), RIGHT);
        assertIterableFailure(new NodeExpr.VariableExpr(new NodeVariable.CallableVariable(missingInner, RIGHT), RIGHT), ".e");
        assertIterableFailure(new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Paren(null, ITEMS), RIGHT), ".expr");
        NodeCallableVariable missingVariable = new NodeCallableVariable.Index(
                new NodeDereferencable.Var(null, ITEMS), integer(0, LEAF), RIGHT);
        assertIterableFailure(new NodeExpr.VariableExpr(new NodeVariable.CallableVariable(missingVariable, RIGHT), RIGHT), ".v");
    }

    // foreach 键仍是普通单个写目标，不因值端扩展而接受引用或模式。
    @Test
    void rejectsReferenceAndDestructuringForeachKeys() {
        for (NodeForeachVariable key : List.of(
                new NodeForeachVariable.Ref(token("&", LEAF), variable("key", LEAF), SLOT),
                new NodeForeachVariable.List(pairs(valuePair("key")), SLOT),
                new NodeForeachVariable.ShortList(pairs(valuePair("key")), SLOT))) {
            assertStatementFailure(new NodeStatement.ForeachKV(variableExpression("items", RIGHT), key,
                    new NodeForeachVariable.Var(variable("value", LEAF), SLOT), emptyForeachBody(), OUTER), ".key");
        }
    }

    // 新增表达式的必需模型字段不允许为空，未知位置通过非空 SourceInfo 表示。
    @Test
    void validatesRequiredReferenceAndDestructuringModelFields() {
        IrAssignmentTarget target = irTarget("value");
        IrExpression value = new IrIntegerLiteral(1, SOURCE);
        IrDestructuringPattern pattern = irPattern();
        for (Executable call : List.<Executable>of(
                () -> new IrReferenceAssignment(null, target, SOURCE),
                () -> new IrReferenceAssignment(target, null, SOURCE),
                () -> new IrReferenceAssignment(target, target, null),
                () -> new IrDestructuringAssignment(null, value, SOURCE),
                () -> new IrDestructuringAssignment(pattern, null, SOURCE),
                () -> new IrDestructuringAssignment(pattern, value, null),
                () -> new IrDestructuringSlot(null, target, null),
                () -> new IrDestructuringPattern(null, SOURCE),
                () -> new IrDestructuringPattern(pattern.slots(), null),
                () -> new IrValueArrayEntry(null, null, SOURCE),
                () -> new IrValueArrayEntry(null, value, null),
                () -> new IrReferenceArrayEntry(null, null, SOURCE),
                () -> new IrReferenceArrayEntry(null, target, null),
                () -> new IrExpressionIterable(null, SOURCE),
                () -> new IrExpressionIterable(value, null),
                () -> new IrWritableIterable(null, SOURCE),
                () -> new IrWritableIterable(target, null))) {
            assertThrows(NullPointerException.class, call);
        }
    }

    // 洞、键和目标的组合在构造层也必须合法，但重复键和嵌套层的不同键模式不被去重或合并。
    @Test
    void validatesPatternSlotInvariantsWithoutNormalizingKeys() {
        IrExpression key = new IrIntegerLiteral(1, SOURCE);
        IrAssignmentTarget target = irTarget("value");
        var plain = new IrDestructuringSlot(null, target, SOURCE);
        var keyed = new IrDestructuringSlot(key, target, SOURCE);
        var hole = new IrDestructuringSlot(null, null, SOURCE);
        assertThrows(IllegalArgumentException.class, () -> new IrDestructuringSlot(key, null, SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrDestructuringPattern(List.of(), SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrDestructuringPattern(List.of(hole, hole), SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrDestructuringPattern(List.of(plain, keyed), SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrDestructuringPattern(List.of(keyed, plain), SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrDestructuringPattern(List.of(hole, keyed), SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrDestructuringPattern(List.of(keyed, hole), SOURCE));
        assertThrows(NullPointerException.class, () -> new IrDestructuringPattern(Arrays.asList(plain, null), SOURCE));
        assertEquals(List.of(keyed, keyed), new IrDestructuringPattern(List.of(keyed, keyed), SOURCE).slots());
        IrDestructuringPattern nested = new IrDestructuringPattern(List.of(keyed), SOURCE);
        var parent = new IrDestructuringPattern(List.of(hole, new IrDestructuringSlot(null, nested, SOURCE)), SOURCE);
        assertSame(nested, parent.slots().get(1).target());
    }

    // foreach 的引用值端只能是单目标，可写来源只能配引用循环；临时表达式引用迭代仍合法。
    @Test
    void validatesForeachModelCombinations() {
        IrAssignmentTarget target = irTarget("value");
        IrForeachIterable expression = new IrExpressionIterable(new IrArrayLiteral(List.of(), SOURCE), SOURCE);
        IrForeachIterable writable = new IrWritableIterable(target, SOURCE);
        IrBlock body = new IrBlock(List.of(), SOURCE);
        assertThrows(IllegalArgumentException.class,
                () -> new IrForeach(expression, null, irPattern(), true, body, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> new IrForeach(writable, null, target, false, body, SOURCE));
        for (Executable call : List.<Executable>of(
                () -> new IrForeach(null, null, target, false, body, SOURCE),
                () -> new IrForeach(expression, null, null, false, body, SOURCE),
                () -> new IrForeach(expression, null, target, false, null, SOURCE),
                () -> new IrForeach(expression, null, target, false, body, null))) {
            assertThrows(NullPointerException.class, call);
        }
        assertTrue(new IrForeach(expression, null, target, true, body, SOURCE).byReference());
        assertTrue(new IrForeach(writable, null, target, true, body, SOURCE).byReference());
        assertFalse(new IrForeach(expression, null, irPattern(), false, body, SOURCE).byReference());
    }

    // 模式和混合数组条目都冻结输入列表，旧结果不随调用方增删列表改变。
    @Test
    void snapshotsPatternSlotsAndMixedArrayEntries() {
        var slot = new IrDestructuringSlot(null, irTarget("value"), SOURCE);
        var slots = new ArrayList<>(List.of(slot));
        var pattern = new IrDestructuringPattern(slots, SOURCE);
        slots.clear();
        assertEquals(List.of(slot), pattern.slots());
        assertThrows(UnsupportedOperationException.class, pattern.slots()::clear);
        IrArrayEntry value = new IrValueArrayEntry(null, new IrIntegerLiteral(1, SOURCE), SOURCE);
        IrArrayEntry reference = new IrReferenceArrayEntry(null, irTarget("value"), SOURCE);
        var entries = new ArrayList<>(List.of(value, reference));
        var array = new IrArrayLiteral(entries, SOURCE);
        entries.clear();
        assertEquals(List.of(value, reference), array.entries());
        assertThrows(UnsupportedOperationException.class, array.entries()::clear);
        assertNull(value.key());
        assertNull(reference.key());
    }

    // 引用赋值本体与两端继承各自节点范围，数组引用项与目标也不共用一个伪造范围。
    @Test
    void preservesDistinctReferenceAssignmentAndEntryOrigins() {
        IrReferenceAssignment assignment = assertInstanceOf(IrReferenceAssignment.class,
                convert(new NodeExprWithoutVariable.AssignRef(variable("left", LEAF), variable("right", RIGHT), OUTER)));
        assertSource(OUTER, assignment.source());
        assertSource(LEAF, assignment.target().source());
        assertSource(RIGHT, assignment.reference().source());
        IrArrayLiteral array = assertInstanceOf(IrArrayLiteral.class,
                convert(array(pairs(new NodeArrayPair.RefValue(variable("value", LEAF), SLOT)))));
        IrReferenceArrayEntry entry = assertInstanceOf(IrReferenceArrayEntry.class, array.entries().getFirst());
        assertSource(SLOT, entry.source());
        assertSource(LEAF, entry.target().source());
    }

    // 模式用条目列表的来源，有值槽用 pair 来源，跳过槽则保留可选包装自己的来源。
    @Test
    void preservesDistinctPatternSlotAndNestedOrigins() {
        NodeArrayPair value = new NodeArrayPair.Value(variableExpression("value", RIGHT), LEAF);
        var items = new NodeArrayPairList.ArrayPairList(new NodeListNodePossibleArrayPair(List.of(
                slot(null, SLOT), slot(value, OUTER)), ITEMS), ITEMS);
        IrDestructuringAssignment assignment = assertInstanceOf(IrDestructuringAssignment.class,
                convert(destructuring(false, items, integer(1, RIGHT))));
        assertSource(OUTER, assignment.source());
        assertSource(ITEMS, assignment.pattern().source());
        assertSource(SLOT, assignment.pattern().slots().getFirst().source());
        IrDestructuringSlot valueSlot = assignment.pattern().slots().get(1);
        assertSource(LEAF, valueSlot.source());
        assertSource(RIGHT, valueSlot.target().source());
        assertSource(RIGHT, assignment.value().source());

        var nestedItems = new NodeArrayPairList.ArrayPairList(new NodeListNodePossibleArrayPair(
                List.of(slot(valuePair("nested"), SLOT)), RIGHT), RIGHT);
        IrDestructuringAssignment nestedAssignment = assertInstanceOf(IrDestructuringAssignment.class,
                convert(destructuring(false, pairs(new NodeArrayPair.ListValue(nestedItems, SLOT)), integer(1, LEAF))));
        IrDestructuringSlot nestedSlot = nestedAssignment.pattern().slots().getFirst();
        assertSource(SLOT, nestedSlot.source());
        assertSource(RIGHT, assertInstanceOf(IrDestructuringPattern.class, nestedSlot.target()).source());
    }

    // foreach 来源包装保留完整输入表达式位置，剥去括号后的读值或目标仍保留自己的位置。
    @Test
    void preservesForeachIterableWrapperAndInnerOrigins() {
        NodeExpr iterable = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Paren(
                variableExpression("items", LEAF), ITEMS), RIGHT);
        for (boolean byReference : List.of(false, true)) {
            NodeForeachVariable binding = byReference
                    ? new NodeForeachVariable.Ref(token("&", SLOT), variable("value", SLOT), SLOT)
                    : new NodeForeachVariable.Var(variable("value", SLOT), SLOT);
            IrForeach result = assertInstanceOf(IrForeach.class, convertStatement(new NodeStatement.Foreach(
                    iterable, binding, emptyForeachBody(), OUTER)).statements().getFirst());
            assertSource(OUTER, result.source());
            assertSource(RIGHT, result.iterable().source());
            assertSource(SLOT, result.valueTarget().source());
            if (byReference) {
                assertSource(LEAF, assertInstanceOf(IrWritableIterable.class, result.iterable()).target().source());
            } else {
                assertSource(LEAF, assertInstanceOf(IrExpressionIterable.class, result.iterable()).expression().source());
            }
        }
    }

    // 未知范围不会从外部包装借用，零宽范围则继续保留为已知位置。
    @Test
    void preservesUnknownAndZeroWidthReferenceOrigins() {
        for (ComplexLocation location : List.of(ComplexLocation.NO_LOCATION, ComplexLocation.of(5, 7, 5, 7))) {
            IrReferenceAssignment result = assertInstanceOf(IrReferenceAssignment.class,
                    convert(new NodeExprWithoutVariable.AssignRef(variable("left", location), variable("right", location), location)));
            SourceRange expected = location.isNoLocation() ? null : range(location);
            assertEquals(expected, result.source().range());
            assertEquals(expected, result.target().source().range());
            assertEquals(expected, result.reference().source().range());
            assertEquals("references.php", result.source().sourceId());

            NodeArrayPair pair = new NodeArrayPair.Value(variableExpression("value", location), location);
            var items = new NodeArrayPairList.ArrayPairList(new NodeListNodePossibleArrayPair(
                    List.of(slot(null, location), slot(pair, location)), location), location);
            IrDestructuringAssignment destructuring = assertInstanceOf(IrDestructuringAssignment.class,
                    convert(new NodeExprWithoutVariable.ListAssign(items, integer(1, location), location)));
            assertEquals(expected, destructuring.source().range());
            assertEquals(expected, destructuring.pattern().source().range());
            assertEquals(expected, destructuring.pattern().slots().getFirst().source().range());
            assertNull(destructuring.pattern().slots().getFirst().target());
            assertEquals(expected, destructuring.pattern().slots().get(1).source().range());
            assertEquals(expected, destructuring.pattern().slots().get(1).target().source().range());

            for (boolean byReference : List.of(false, true)) {
                NodeForeachVariable binding = byReference
                        ? new NodeForeachVariable.Ref(token("&", location), variable("value", location), location)
                        : new NodeForeachVariable.Var(variable("value", location), location);
                IrForeach loop = assertInstanceOf(IrForeach.class, convertStatement(new NodeStatement.Foreach(
                        variableExpression("items", location), binding, emptyForeachBody(), OUTER)).statements().getFirst());
                assertEquals(expected, loop.iterable().source().range());
                assertEquals("references.php", loop.iterable().source().sourceId());
                if (byReference) {
                    assertEquals(expected, assertInstanceOf(IrWritableIterable.class, loop.iterable()).target().source().range());
                } else {
                    assertEquals(expected, assertInstanceOf(IrExpressionIterable.class, loop.iterable()).expression().source().range());
                }
            }
        }
    }

    // 新结构转换失败不会修改原文件；成功结果不含 AST，重复转换得到等价结构。
    @Test
    void convertsWithoutMutatingDeclarationsOrLeakingAst() throws ReflectiveOperationException {
        var file = DeclarationExtractor.extract(Main.parse("""
                <?php
                function sample() {
                    $alias =& $items;
                    $values = [1, &$alias, 'key' => &$items[0]];
                    list($first, , list($second)) = $values;
                    foreach ($items as &$item) { $item++; }
                    foreach ($values as [$value]) { echo $value; }
                }
                function unsupported() { list($value) = ($invalid[]); }
                """), "references.php");
        var declarations = file.namespaceSections().getFirst().declarations();
        var function = (FunctionDefinition) declarations.getFirst();
        var unsupported = (FunctionDefinition) declarations.get(1);
        String before = file.syntax().toTreeString(false);
        assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(unsupported.body()));
        IrBlock result = SyntaxConverter.convertBody(function.body());
        assertEquals(before, file.syntax().toTreeString(false));
        assertSame(function, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "sample").getFirst());
        assertEquals(result, SyntaxConverter.convertBody(function.body()));
        assertNoAst(result, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static IrAssignmentTarget irTarget(String name) {
        return new IrVariableTarget(new IrFixedName(name, SOURCE), SOURCE);
    }

    private static IrDestructuringPattern irPattern() {
        return new IrDestructuringPattern(List.of(new IrDestructuringSlot(null, irTarget("value"), SOURCE)), SOURCE);
    }

    private static NodeVariable variable(String name, ComplexLocation location) {
        return new NodeVariable.CallableVariable(new NodeCallableVariable.SimpleVar(
                new NodeSimpleVariable.NamedVar(token(name, location), location), location), location);
    }

    private static NodeExpr variableExpression(String name, ComplexLocation location) {
        return new NodeExpr.VariableExpr(variable(name, location), location);
    }

    private static NodeExpr integer(int number, ComplexLocation location) {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(token(Integer.toString(number), location), location), location), location);
    }

    private static NodeString token(String value, ComplexLocation location) {
        return new NodeString(value, location);
    }

    private static NodeArrayPair valuePair(String name) {
        return new NodeArrayPair.Value(variableExpression(name, LEAF), SLOT);
    }

    private static NodePossibleArrayPair slot(NodeArrayPair pair, ComplexLocation location) {
        // CUP 的匿名变体名称不稳定，只依赖可选 pair 的稳定字段契约。
        return new NodePossibleArrayPair() {
            @Override public NodeArrayPair getPair() { return pair; }
            @Override public boolean hasPair() { return pair != null; }
            @Override public ComplexLocation getLocation() { return location; }
        };
    }

    private static NodeArrayPairList.ArrayPairList pairs(NodeArrayPair... pairs) {
        var slots = new ArrayList<NodePossibleArrayPair>();
        for (NodeArrayPair pair : pairs) slots.add(slot(pair, SLOT));
        return new NodeArrayPairList.ArrayPairList(new NodeListNodePossibleArrayPair(slots, ITEMS), ITEMS);
    }

    private static NodeExprWithoutVariable destructuring(boolean shortSyntax, NodeArrayPairList items, NodeExpr value) {
        return shortSyntax ? new NodeExprWithoutVariable.ShortListAssign(items, value, OUTER)
                : new NodeExprWithoutVariable.ListAssign(items, value, OUTER);
    }

    private static NodeExprWithoutVariable array(NodeArrayPairList items) {
        return new NodeExprWithoutVariable.Scalar(new NodeScalar.DereferencableScalar(
                new NodeDereferencableScalar.ShortArray(token("[", OUTER), items, OUTER), OUTER), OUTER);
    }

    private static NodeForeachStatement emptyForeachBody() {
        return new NodeForeachStatement.Body(new NodeStatement(), OUTER);
    }

    private static NodeStatement foreach(NodeForeachVariable value) {
        return new NodeStatement.Foreach(variableExpression("items", RIGHT), value, emptyForeachBody(), OUTER);
    }

    private static IrExpression convert(NodeExprWithoutVariable expression) {
        return convertExpression(new NodeExpr.ExprWithoutVariable(expression, OUTER));
    }

    private static IrExpression convertExpression(NodeExpr expression) {
        return SyntaxConverter.convertExpression(new SyntaxExpression(expression, SOURCE));
    }

    private static IrBlock convertStatement(NodeStatement statement) {
        return SyntaxConverter.convertBody(new SyntaxBody(List.of(statement), SOURCE));
    }

    private static NodeExpr parsedExpression(String code) {
        NodeProgram program = (NodeProgram) Main.parse("<?php " + code + ";");
        return program.getStmts().getValue().getFirst().getStmt().getExpression();
    }

    private static void assertExpressionFailure(NodeExprWithoutVariable expression, String suffix) {
        assertFailure(() -> convert(expression), suffix, true);
    }

    private static void assertStatementFailure(NodeStatement statement, String suffix) {
        assertFailure(() -> convertStatement(statement), suffix, true);
    }

    private static void assertIterableFailure(NodeExpr iterable, String suffix) {
        var binding = new NodeForeachVariable.Ref(token("&", LEAF), variable("value", LEAF), SLOT);
        assertStatementFailure(new NodeStatement.Foreach(iterable, binding, emptyForeachBody(), OUTER), suffix);
    }

    private static void assertArrayFailure(NodeArrayPair pair, String suffix) {
        assertExpressionFailure(array(pairs(pair)), suffix);
    }

    private static void assertPatternFailure(NodeArrayPair pair, String suffix) {
        assertPatternFailure(pair, suffix, true);
    }

    private static void assertPatternFailure(NodeArrayPair pair, String suffix, boolean endsWith) {
        assertFailure(() -> convert(destructuring(false, pairs(pair), integer(1, RIGHT))), suffix, endsWith);
    }

    private static void assertFailure(Executable conversion, String field, boolean endsWith) {
        var error = assertThrows(SyntaxConversionException.class, conversion);
        assertTrue(endsWith ? error.fieldPath().endsWith(field) : error.fieldPath().contains(field), error.fieldPath());
        assertFalse(error.reason().isBlank());
        assertEquals("references.php", error.source().sourceId());
    }

    private static SourceRange range(ComplexLocation location) {
        return new SourceRange(location.getStartLine(), location.getStartColumn(), location.getEndLine(), location.getEndColumn());
    }

    private static void assertSource(ComplexLocation expected, SourceInfo actual) {
        assertEquals("references.php", actual.sourceId());
        assertEquals(range(expected), actual.range());
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
        assertFalse(value instanceof AstNode, "引用和解构 IR 不应保留 CUP AST");
        if (value instanceof List<?> list) {
            for (Object child : list) assertNoAst(child, visited);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertNoAst(component.getAccessor().invoke(value), visited);
            }
        } else {
            assertTrue(value instanceof String || value instanceof Enum<?>
                    || value instanceof Number || value instanceof Boolean, "未预期的结果字段类型：" + value.getClass());
        }
    }
}
