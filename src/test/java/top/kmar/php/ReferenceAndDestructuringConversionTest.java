package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SyntaxBody;
import top.kmar.php.model.SyntaxExpression;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证引用地址、解构模式及 foreach 来源分类，不执行 PHP 或推断实际引用关系。 */
class ReferenceAndDestructuringConversionTest {

    // 引用赋值独立于按值赋值；两侧保存可写位置而不是先读取变量值。
    @Test
    void distinguishesReferenceAssignmentsFromOrdinaryAssignments() {
        IrReferenceAssignment reference = reference("$left =& $right");
        assertVariableTarget(reference.target(), "left");
        assertVariableTarget(reference.reference(), "right");
        IrAssignment ordinary = assertInstanceOf(IrAssignment.class, expression("$left = $right"));
        assertVariableTarget(ordinary.target(), "left");
        assertVariable(ordinary.value(), "right");
        assertEquals("references.php", reference.source().sourceId());
    }

    // 引用两端沿属性和下标链传播写上下文，追加索引仍以 null 明确保留。
    @Test
    void preservesWritableReferenceChainsAndAppendSlots() {
        IrReferenceAssignment assignment = reference("$left[][key()]->$field =& $right[][offset()]");
        IrPropertyTarget property = assertInstanceOf(IrPropertyTarget.class, assignment.target());
        assertVariable(assertInstanceOf(IrComputedName.class, property.property()).expression(), "field");
        IrIndexTarget selected = assertInstanceOf(IrIndexTarget.class, property.receiver());
        assertCall(selected.index(), "key");
        assertAppend(selected.base(), "left");
        IrIndexTarget source = assertInstanceOf(IrIndexTarget.class, assignment.reference());
        assertCall(source.index(), "offset");
        assertAppend(source.base(), "right");

        IrReferenceAssignment staticReference = reference("$type::$$field =& Box::$value");
        IrStaticPropertyTarget target = assertInstanceOf(IrStaticPropertyTarget.class, staticReference.target());
        assertVariable(assertInstanceOf(IrDynamicClassReference.class, target.classReference()).expression(), "type");
        assertVariable(assertInstanceOf(IrComputedName.class, target.property()).expression(), "field");
        IrStaticPropertyTarget value = assertInstanceOf(IrStaticPropertyTarget.class, staticReference.reference());
        assertEquals("value", assertInstanceOf(IrFixedName.class, value.property()).value());
        assertEquals("Box", assertInstanceOf(IrNamedClassReference.class, value.classReference()).name().spelling());
    }

    // 普通、动态、实例和静态调用可作为引用来源，不要求解析其返回声明或执行调用。
    @Test
    void acceptsEveryCallKindAsAReferenceSource() {
        for (String code : List.of("factory()", "$factory()", "($factory)()", "factory()()")) {
            IrExpression source = assertInstanceOf(IrExpressionWriteBase.class,
                    reference("$target =& " + code).reference()).expression();
            assertInstanceOf(IrCall.class, source, code);
        }
        assertInstanceOf(IrMethodCall.class, assertInstanceOf(IrExpressionWriteBase.class,
                reference("$target =& $object->$method()").reference()).expression());
        assertInstanceOf(IrStaticCall.class, assertInstanceOf(IrExpressionWriteBase.class,
                reference("$target =& $type::$method()").reference()).expression());
        IrIndexTarget indexed = assertInstanceOf(IrIndexTarget.class,
                reference("$target =& factory()[next()]").reference());
        assertCall(assertInstanceOf(IrExpressionWriteBase.class, indexed.base()).expression(), "factory");
        assertCall(indexed.index(), "next");
    }

    // 裸调用只能作为引用赋值的来源，不能成为赋值目标、数组引用项或 foreach 绑定目标。
    @Test
    void rejectsBareCallsWhereAnAssignableTargetIsRequired() {
        for (String call : List.of("factory()", "$object->run()", "Box::run()")) {
            assertRejectedExpression(call + " =& $source");
            assertRejectedExpression("[&" + call + "]");
            assertRejectedExpression("['key' => &" + call + "]");
            assertRejectedExpression("[" + call + "] = $source");
            assertRejectedBody("foreach ($items as &" + call + ") {}");
            assertRejectedBody("foreach ($items as " + call + ") {}");
        }
    }

    // 引用外层上下文不能泄漏到索引、名称、类名、调用接收者或实参中的普通读取。
    @Test
    void keepsReferenceOperandSubexpressionsInReadContext() {
        for (String code : List.of("$left =& $right[$keys[]]", "$left[$keys[]] =& $right",
                "$object->{$names[]} =& $right", "$left =& $object->{$names[]}",
                "$classes[]::$field =& $right", "$left =& $classes[]::$field",
                "$left =& factory($args[])", "$left =& factory($args[])[0]",
                "$left =& $objects[]->run()", "$left =& ($first + $second)->field",
                "$left =& (new Box)->field", "$left =& ITEMS[0]", "$left =& [1][0]")) {
            assertRejectedExpression(code);
        }
    }

    // 读取位置中的独立赋值仍有自身写上下文，不能因外层引用而禁止合法追加。
    @Test
    void preservesIndependentAssignmentsInsideReferenceSelectorsAndArguments() {
        IrReferenceAssignment assignment = reference("$left[$keys[] = key()] =& $right[$offsets[] = offset()]");
        IrIndexTarget target = assertInstanceOf(IrIndexTarget.class, assignment.target());
        assertAppendAssignment(target.index(), "keys", "key");
        IrIndexTarget source = assertInstanceOf(IrIndexTarget.class, assignment.reference());
        assertAppendAssignment(source.index(), "offsets", "offset");
        IrCall call = assertInstanceOf(IrCall.class, assertInstanceOf(IrExpressionWriteBase.class,
                reference("$target =& factory($args[] = next())").reference()).expression());
        assertEquals(1, call.arguments().size());
        assertAppendAssignment(call.arguments().getFirst().expression(), "args", "next");
    }

    // 引用赋值仍是表达式，可嵌入原有赋值、数组、闭包和 yield，不展开或复制副作用。
    @Test
    void preservesReferenceAssignmentsInExistingExpressionPositions() {
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression("$saved = ($left =& $right)"));
        assertInstanceOf(IrReferenceAssignment.class, assignment.value());
        IrArrayLiteral array = array("[($left =& $right)]");
        assertInstanceOf(IrReferenceAssignment.class,
                assertInstanceOf(IrValueArrayEntry.class, array.entries().getFirst()).value());
        IrClosure closure = assertInstanceOf(IrClosure.class,
                expression("function() use (&$outer) { $local =& $outer; yield ($local =& factory()); }"));
        assertInstanceOf(IrReferenceAssignment.class, statementExpression(closure.body(), 0));
        IrYield yielded = assertInstanceOf(IrYield.class, statementExpression(closure.body(), 1));
        assertInstanceOf(IrReferenceAssignment.class, yielded.value());
    }

    // 数组值项和引用项使用不同变体，保留键顺序、重复键、缺省键与显式 null 键。
    @Test
    void preservesMixedValueAndReferenceArrayEntries() {
        for (String code : List.of("[first(), &$value, 'key' => &$other, 'key' => next(), null => &$last,]",
                "array(first(), &$value, 'key' => &$other, 'key' => next(), null => &$last,)")) {
            List<IrArrayEntry> entries = array(code).entries();
            assertEquals(5, entries.size());
            IrValueArrayEntry first = assertInstanceOf(IrValueArrayEntry.class, entries.getFirst());
            assertNull(first.key());
            assertCall(first.value(), "first");
            IrReferenceArrayEntry second = assertInstanceOf(IrReferenceArrayEntry.class, entries.get(1));
            assertNull(second.key());
            assertVariableTarget(second.target(), "value");
            IrReferenceArrayEntry third = assertInstanceOf(IrReferenceArrayEntry.class, entries.get(2));
            assertString(third.key(), "key");
            assertVariableTarget(third.target(), "other");
            IrValueArrayEntry fourth = assertInstanceOf(IrValueArrayEntry.class, entries.get(3));
            assertString(fourth.key(), "key");
            assertCall(fourth.value(), "next");
            IrReferenceArrayEntry fifth = assertInstanceOf(IrReferenceArrayEntry.class, entries.get(4));
            assertEquals(LiteralKind.NULL, assertInstanceOf(IrLiteral.class, fifth.key()).kind());
            assertVariableTarget(fifth.target(), "last");
        }
    }

    // 数组引用项支持追加、动态变量、属性、静态属性及调用结果上的访问，不接受空洞。
    @Test
    void convertsReferenceArrayTargetsWithoutTreatingThemAsReads() {
        List<IrArrayEntry> entries = array("[&$items[], &$$name, &$object->$field, &$type::$field, &factory()[0]]")
                .entries();
        assertAppend(assertInstanceOf(IrReferenceArrayEntry.class, entries.getFirst()).target(), "items");
        IrVariableTarget indirect = assertInstanceOf(IrVariableTarget.class,
                assertInstanceOf(IrReferenceArrayEntry.class, entries.get(1)).target());
        assertVariable(assertInstanceOf(IrComputedName.class, indirect.name()).expression(), "name");
        assertInstanceOf(IrPropertyTarget.class, assertInstanceOf(IrReferenceArrayEntry.class, entries.get(2)).target());
        assertInstanceOf(IrStaticPropertyTarget.class,
                assertInstanceOf(IrReferenceArrayEntry.class, entries.get(3)).target());
        IrIndexTarget index = assertInstanceOf(IrIndexTarget.class,
                assertInstanceOf(IrReferenceArrayEntry.class, entries.get(4)).target());
        assertCall(assertInstanceOf(IrExpressionWriteBase.class, index.base()).expression(), "factory");
        for (String code : List.of("[&$items[$keys[]]]", "[$keys[] => &$item]", "[&$items,,]",
                "[,&$items]", "[list($item)]", "[&factory($args[])[0]]")) {
            assertRejectedExpression(code);
        }
    }

    // list 和短方括号归一为同一解构模型，叶子是写目标，右侧仍只保留一次表达式。
    @Test
    void normalizesBothDestructuringAssignmentForms() {
        for (String code : List.of("list($first, $second) = source()", "[$first, $second] = source()")) {
            IrDestructuringAssignment assignment = destructuring(code);
            assertEquals(2, assignment.pattern().slots().size());
            assertSlotVariable(assignment.pattern(), 0, "first");
            assertSlotVariable(assignment.pattern(), 1, "second");
            assertCall(assignment.value(), "source");
        }
        IrAssignment outer = assertInstanceOf(IrAssignment.class,
                expression("$saved = ([$first, $second] = source())"));
        assertInstanceOf(IrDestructuringAssignment.class, outer.value());
    }

    // 每层只移除一个尾部语法空槽，开头、中间及剩余尾部跳槽保留位置。
    @Test
    void preservesSkippedSlotsAndRemovesOnlyOneTrailingSlot() {
        for (String code : List.of("list(, $first, , , $last,) = $source",
                "[, $first, , , $last,] = $source")) {
            IrDestructuringPattern pattern = destructuring(code).pattern();
            assertEquals(5, pattern.slots().size());
            assertSkip(pattern, 0);
            assertSlotVariable(pattern, 1, "first");
            assertSkip(pattern, 2);
            assertSkip(pattern, 3);
            assertSlotVariable(pattern, 4, "last");
        }
        assertEquals(1, destructuring("[$item,] = $source").pattern().slots().size());
        IrDestructuringPattern extraComma = destructuring("[$item,,] = $source").pattern();
        assertEquals(2, extraComma.slots().size());
        assertSlotVariable(extraComma, 0, "item");
        assertSkip(extraComma, 1);
    }

    // 同种语法的嵌套模式分别保存槽位，内层尾逗号不会改变外层索引。
    @Test
    void preservesNestedDestructuringPatternsAndTheirHoles() {
        for (String code : List.of("list($first, list(, $second,), $last) = $source",
                "[$first, [, $second,], $last] = $source")) {
            IrDestructuringPattern outer = destructuring(code).pattern();
            assertEquals(3, outer.slots().size());
            assertSlotVariable(outer, 0, "first");
            assertSlotVariable(outer, 2, "last");
            IrDestructuringPattern inner = assertInstanceOf(IrDestructuringPattern.class, outer.slots().get(1).target());
            assertEquals(2, inner.slots().size());
            assertSkip(inner, 0);
            assertSlotVariable(inner, 1, "second");
        }
        IrDestructuringPattern parenthesized = destructuring("[( [$value] )] = $source").pattern();
        assertSlotVariable(assertInstanceOf(IrDestructuringPattern.class,
                parenthesized.slots().getFirst().target()), 0, "value");
    }

    // 键模式逐层判定，允许外层带键而内层按位置；重复键和重复目标均保留。
    @Test
    void preservesKeyedPatternsWithoutCoercionOrDeduplication() {
        for (String code : List.of("list('group' => list($first, $second), 'again' => $first,) = $source",
                "['group' => [$first, $second], 'again' => $first,] = $source")) {
            IrDestructuringPattern outer = destructuring(code).pattern();
            assertEquals(2, outer.slots().size());
            assertString(outer.slots().getFirst().key(), "group");
            IrDestructuringPattern inner = assertInstanceOf(IrDestructuringPattern.class,
                    outer.slots().getFirst().target());
            assertSlotVariable(inner, 0, "first");
            assertSlotVariable(inner, 1, "second");
            assertString(outer.slots().get(1).key(), "again");
            assertVariableTarget(outer.slots().get(1).target(), "first");
        }
        IrDestructuringPattern repeated = destructuring("['same' => $value, 'same' => $value, null => $last] = $source")
                .pattern();
        assertEquals(3, repeated.slots().size());
        assertString(repeated.slots().getFirst().key(), "same");
        assertString(repeated.slots().get(1).key(), "same");
        assertEquals(LiteralKind.NULL, assertInstanceOf(IrLiteral.class, repeated.slots().get(2).key()).kind());
        IrDestructuringPattern nestedKeyed = assertInstanceOf(IrDestructuringPattern.class,
                destructuring("[['key' => $value]] = $source").pattern().slots().getFirst().target());
        assertString(nestedKeyed.slots().getFirst().key(), "key");
    }

    // 解构叶子复用所有已有写目标，不能降低成数组值或读取表达式。
    @Test
    void supportsWritableAccessChainsAsDestructuringLeaves() {
        IrDestructuringPattern pattern = destructuring(
                "[$items[], $object->$field, $type::$value, factory()[index()], ${name()}] = $source").pattern();
        assertEquals(5, pattern.slots().size());
        assertAppend(pattern.slots().getFirst().target(), "items");
        assertInstanceOf(IrPropertyTarget.class, pattern.slots().get(1).target());
        assertInstanceOf(IrStaticPropertyTarget.class, pattern.slots().get(2).target());
        IrIndexTarget index = assertInstanceOf(IrIndexTarget.class, pattern.slots().get(3).target());
        assertCall(assertInstanceOf(IrExpressionWriteBase.class, index.base()).expression(), "factory");
        assertCall(index.index(), "index");
        IrVariableTarget variable = assertInstanceOf(IrVariableTarget.class, pattern.slots().get(4).target());
        assertCall(assertInstanceOf(IrComputedName.class, variable.name()).expression(), "name");
    }

    // 显式键、目标下标和右值中的调用各保留一次，不展开解构或重复读取右侧。
    @Test
    void preservesDestructuringExpressionsWithoutLoweringTheirEvaluation() {
        IrDestructuringAssignment assignment = destructuring("[key() => $items[index()]] = source()");
        IrDestructuringSlot slot = assignment.pattern().slots().getFirst();
        assertCall(slot.key(), "key");
        assertCall(assertInstanceOf(IrIndexTarget.class, slot.target()).index(), "index");
        assertCall(assignment.value(), "source");
        IrDestructuringAssignment self = destructuring("list($items, $other) = $items");
        assertSlotVariable(self.pattern(), 0, "items");
        assertVariable(self.value(), "items");
        IrDestructuringAssignment nested = destructuring("[$outer] = ([$inner] = source())");
        assertInstanceOf(IrDestructuringAssignment.class, nested.value());
    }

    // 解构的键和选择器仍为读取，内部独立赋值则继续允许自身追加写入。
    @Test
    void keepsDestructuringSelectorReadsSeparateFromItsWriteTargets() {
        for (String code : List.of("[$keys[] => $value] = $source", "[$items[$keys[]]] = $source",
                "[$object->{$fields[]}] = $source", "[$classes[]::$value] = $source",
                "[factory($arguments[])[0]] = $source", "[$value] = $source[]")) {
            assertRejectedExpression(code);
        }
        IrDestructuringPattern pattern = destructuring(
                "[$keys[] = key() => $items[$offsets[] = index()]] = $source").pattern();
        assertAppendAssignment(pattern.slots().getFirst().key(), "keys", "key");
        assertAppendAssignment(assertInstanceOf(IrIndexTarget.class, pattern.slots().getFirst().target()).index(),
                "offsets", "index");
    }

    // 空、全跳过、键模式混合、带键跳槽及长数组都无法作为本轮合法的解构模式。
    @Test
    void rejectsInvalidLocalPatternShapesAtEveryDepth() {
        for (String code : List.of("[] = $source", "list() = $source", "[,] = $source", "list(,,) = $source",
                "[[]] = $source", "list(list()) = $source", "[[,]] = $source",
                "['key' => $first, $second] = $source", "[$first, 'key' => $second] = $source",
                "['key' => $first,,] = $source", "[, 'key' => $first] = $source",
                "[['key' => $first, $second]] = $source", "[array($value)] = $source",
                "list(array($value)) = $source", "[1] = $source", "[$first + $second] = $source")) {
            assertRejectedExpression(code);
        }
    }

    // PHP 7.2 不允许 list 与 [] 跨层混用，也尚未支持解构项上的引用。
    @Test
    void rejectsMixedPatternSyntaxAndReferenceDestructuring() {
        for (String code : List.of("list([$value]) = $source", "[list($value)] = $source",
                "list('key' => [$value]) = $source", "['key' => list($value)] = $source",
                "[[list($value)]] = $source", "list(list([$value])) = $source",
                "[&$value] = $source", "list(&$value) = $source",
                "['key' => &$value] = $source", "list('key' => &$value) = $source",
                "[[&$value]] = $source", "list(list(&$value)) = $source")) {
            assertRejectedExpression(code);
        }
    }

    // 普通和冒号循环体保持等价，引用 foreach 明确记录标志、键目标和可写 iterable。
    @Test
    void convertsReferenceForeachInBothBodyForms() {
        for (String suffix : List.of("{ echo $value; }", ": echo $value; endforeach;")) {
            IrForeach valueOnly = foreach("foreach ($items as &$value) " + suffix);
            assertTrue(valueOnly.byReference());
            assertNull(valueOnly.keyTarget());
            assertVariableTarget(valueOnly.valueTarget(), "value");
            assertVariableTarget(assertInstanceOf(IrWritableIterable.class, valueOnly.iterable()).target(), "items");
            assertEquals(1, valueOnly.body().statements().size());
            assertVariable(assertInstanceOf(IrEcho.class, valueOnly.body().statements().getFirst())
                    .expressions().getFirst(), "value");
            IrForeach keyed = foreach("foreach ($items as $keys[] => &$values[]) " + suffix);
            assertAppend(keyed.keyTarget(), "keys");
            assertAppend(keyed.valueTarget(), "values");
            assertTrue(keyed.byReference());
        }
    }

    // 引用 foreach 对可写变量链使用地址，括号和调用结果上的后续访问不改变这个分类。
    @Test
    void classifiesWritableReferenceForeachIterablesWithoutReadingAppendSlots() {
        for (String iterable : List.of("$items", "(($items))", "$items[]", "($items[])", "($items[])[0]",
                "$object->items", "$object->$field", "Box::$items", "$type::$items",
                "factory()[0]", "factory()[]", "factory()->items", "($classes[0])::$items")) {
            IrForeach loop = foreach("foreach (" + iterable + " as &$value) {}");
            assertTrue(loop.byReference(), iterable);
            assertInstanceOf(IrWritableIterable.class, loop.iterable(), iterable);
        }
        assertAppend(assertInstanceOf(IrWritableIterable.class,
                foreach("foreach ($items[] as &$value) {}").iterable()).target(), "items");
        IrIndexTarget fromCall = assertInstanceOf(IrIndexTarget.class, assertInstanceOf(IrWritableIterable.class,
                foreach("foreach (factory()[] as &$value) {}").iterable()).target());
        assertNull(fromCall.index());
        assertCall(assertInstanceOf(IrExpressionWriteBase.class, fromCall.base()).expression(), "factory");
    }

    // 裸调用及临时值来源仍是表达式；不能因外层引用而错误拒绝 new/常量基底上的读取。
    @Test
    void preservesExpressionSourcesInReferenceForeach() {
        for (String iterable : List.of("factory()", "$factory()", "$object->run()", "Box::run()",
                "[1, 2]", "ITEMS", "Box::ITEMS", "ITEMS[0]", "[[]][0]", "new Box",
                "(new Box)->items", "(clone $object)->items", "($left + $right)->items",
                "($saved = source())", "1", "function() {}")) {
            IrForeach loop = foreach("foreach (" + iterable + " as &$value) {}");
            assertTrue(loop.byReference(), iterable);
            assertInstanceOf(IrExpressionIterable.class, loop.iterable(), iterable);
        }
        IrPropertyAccess property = assertInstanceOf(IrPropertyAccess.class, assertInstanceOf(IrExpressionIterable.class,
                foreach("foreach ((new Box)->items as &$value) {}").iterable()).expression());
        assertInstanceOf(IrNew.class, property.receiver());
        assertCall(assertInstanceOf(IrExpressionIterable.class,
                foreach("foreach (factory() as &$value) {}").iterable()).expression(), "factory");
    }

    // 按值 foreach 始终读取 iterable；同一追加链只在引用形式才允许作为来源。
    @Test
    void keepsOrdinaryForeachIterablesInReadContext() {
        for (String iterable : List.of("$items", "(($items))", "$object->items", "Box::$items", "factory()[0]")) {
            IrForeach loop = foreach("foreach (" + iterable + " as $value) {}");
            assertFalse(loop.byReference());
            assertInstanceOf(IrExpressionIterable.class, loop.iterable(), iterable);
        }
        assertRejectedBody("foreach ($items[] as $value) {}");
        assertRejectedBody("foreach (factory()[] as $value) {}");
        assertInstanceOf(IrWritableIterable.class, foreach("foreach ($items[] as &$value) {}").iterable());
    }

    // 分类不会通过捕获转换异常降级读取，地址内的索引、实参和动态名称错误必须保留。
    @Test
    void rejectsInvalidReadsInsideReferenceForeachSources() {
        for (String iterable : List.of("$items[$keys[]]", "$object->{$fields[]}", "$classes[]::$items",
                "factory($args[])[0]", "$items[]->run()", "factory($args[])",
                "(new Box)->items[]", "[[]][]", "ITEMS[]")) {
            assertRejectedBody("foreach (" + iterable + " as &$value) {}");
        }
        IrForeach loop = foreach("foreach ($items[$keys[] = key()] as &$value) {}");
        IrIndexTarget target = assertInstanceOf(IrIndexTarget.class,
                assertInstanceOf(IrWritableIterable.class, loop.iterable()).target());
        assertAppendAssignment(target.index(), "keys", "key");
        IrCall call = assertInstanceOf(IrCall.class, assertInstanceOf(IrExpressionIterable.class,
                foreach("foreach (factory($args[] = next()) as &$value) {}").iterable()).expression());
        assertAppendAssignment(call.arguments().getFirst().expression(), "args", "next");
    }

    // foreach 解构只用于值绑定，复用递归 pattern；键仍是单独的普通写目标。
    @Test
    void convertsDestructuringForeachValuesWithOptionalKeys() {
        for (String binding : List.of("list($first, list(, $last))", "[$first, [, $last]]")) {
            for (String suffix : List.of("{ echo $first; }", ": echo $first; endforeach;")) {
                for (String key : List.of("", "$keys[] => ")) {
                    IrForeach loop = foreach("foreach ($rows as " + key + binding + ") " + suffix);
                    assertFalse(loop.byReference());
                    assertVariable(assertInstanceOf(IrExpressionIterable.class, loop.iterable()).expression(), "rows");
                    if (key.isEmpty()) assertNull(loop.keyTarget());
                    else assertAppend(loop.keyTarget(), "keys");
                    IrDestructuringPattern outer = assertInstanceOf(IrDestructuringPattern.class, loop.valueTarget());
                    assertSlotVariable(outer, 0, "first");
                    IrDestructuringPattern inner = assertInstanceOf(IrDestructuringPattern.class,
                            outer.slots().get(1).target());
                    assertSkip(inner, 0);
                    assertSlotVariable(inner, 1, "last");
                }
            }
        }
    }

    // 引用键、解构键和非法值 pattern 均在归一化前拒绝，不能丢掉修饰或重新解释。
    @Test
    void rejectsInvalidForeachKeysAndDestructuringValues() {
        for (String binding : List.of("&$key => $value", "&$key => &$value",
                "list($key) => $value", "[$key] => $value", "[]", "list()", "[,]",
                "['key' => $value, $other]", "['key' => $value,,]", "[list($value)]",
                "list([$value])", "[&$value]", "list('key' => &$value)", "[array($value)]")) {
            assertRejectedBody("foreach ($items as " + binding + ") {}");
        }
    }

    // 嵌套 foreach 保持各自引用标志与来源，内层解构不会继承外层的引用方式。
    @Test
    void preservesIndependentBindingsAcrossNestedForeachLoops() {
        IrForeach outer = foreach("foreach ($groups as &$rows) { foreach ($rows as [$left, $right]) {"
                + " $local =& $left; } }");
        assertTrue(outer.byReference());
        assertInstanceOf(IrWritableIterable.class, outer.iterable());
        IrForeach inner = assertInstanceOf(IrForeach.class, outer.body().statements().getFirst());
        assertFalse(inner.byReference());
        assertInstanceOf(IrExpressionIterable.class, inner.iterable());
        assertInstanceOf(IrDestructuringPattern.class, inner.valueTarget());
        assertInstanceOf(IrReferenceAssignment.class, statementExpression(inner.body(), 0));
    }

    // 结构转换不增加完整 PHP 合法性检查：不绑定调用签名、$this、运行时类型或重复目标。
    @Test
    void retainsParserAcceptedStructuresWithoutGlobalSemanticValidation() {
        assertVariableTarget(reference("$this =& $other").target(), "this");
        assertVariableTarget(assertInstanceOf(IrReferenceArrayEntry.class, array("[&$this]").entries().getFirst())
                .target(), "this");
        IrForeach invalidAtRuntime = foreach("foreach (1 as &$this) {}");
        assertVariableTarget(invalidAtRuntime.valueTarget(), "this");
        assertInteger(assertInstanceOf(IrExpressionIterable.class, invalidAtRuntime.iterable()).expression(), 1);
        IrDestructuringAssignment repeated = destructuring("[$this, $this] = 1");
        assertSlotVariable(repeated.pattern(), 0, "this");
        assertSlotVariable(repeated.pattern(), 1, "this");
        assertInteger(repeated.value(), 1);
        assertInstanceOf(IrExpressionWriteBase.class, reference("$value =& strlen()").reference());
        assertInstanceOf(IrStaticPropertyTarget.class, reference("self::$value =& parent::$value").target());
    }

    // 本轮不改变引用返回声明中的 return/yield 读取规则，也不隐式传播引用模式。
    @Test
    void keepsReturnAndYieldReferenceModesOutsideThisExpansion() {
        assertRejectedExpression("function &() { return $items[]; }");
        assertRejectedExpression("function &() { yield $items[]; }");
        IrClosure closure = assertInstanceOf(IrClosure.class,
                expression("function &() { yield ($local =& $outer); yield from []; }"));
        assertTrue(closure.returnsReference());
        assertInstanceOf(IrReferenceAssignment.class,
                assertInstanceOf(IrYield.class, statementExpression(closure.body(), 0)).value());
        assertInstanceOf(IrYieldFrom.class, statementExpression(closure.body(), 1));
    }

    // 引用 foreach 体内的 global 独立保存，不改变外层引用迭代来源和值目标。
    @Test
    void preservesGlobalStatementsInsideReferenceForeachBodies() {
        IrForeach loop = foreach("foreach ($items as &$value) { global $other; }");
        assertTrue(loop.byReference());
        assertVariableTarget(assertInstanceOf(IrWritableIterable.class, loop.iterable()).target(), "items");
        assertVariableTarget(loop.valueTarget(), "value");
        assertEquals(1, loop.body().statements().size());
        IrGlobal global = assertInstanceOf(IrGlobal.class, loop.body().statements().getFirst());
        assertEquals(1, global.variables().size());
        assertVariableTarget(global.variables().getFirst(), "other");
    }

    // 新容器仍递归拒绝未支持的子树，不把匿名类或嵌套命名声明悄悄跳过。
    @Test
    void propagatesUnsupportedSubtreesThroughEveryNewContainer() {
        for (String code : List.of("$left =& $right[(new class {})]", "$left[(new class {})] =& $right",
                "[(new class {}) => &$value]", "[&$items[(new class {})]]",
                "[(new class {}) => $value] = $source", "[$items[(new class {})]] = $source",
                "[$value] = (new class {})")) {
            assertRejectedExpression(code);
        }
        for (String code : List.of("foreach ((new class {}) as &$value) {}",
                "foreach ($items[(new class {})] as &$value) {}",
                "foreach ($items as [$items[(new class {})]]) {}",
                "foreach ($items as &$value) { global ${(new class {})}; }",
                "foreach ($items as [$value]) { function nested() {} }")) {
            assertRejectedBody(code);
        }
    }

    private static IrReferenceAssignment reference(String code) {
        return assertInstanceOf(IrReferenceAssignment.class, expression(code));
    }

    private static IrDestructuringAssignment destructuring(String code) {
        return assertInstanceOf(IrDestructuringAssignment.class, expression(code));
    }

    private static IrArrayLiteral array(String code) {
        return assertInstanceOf(IrArrayLiteral.class, expression(code));
    }

    private static IrForeach foreach(String code) {
        IrBlock body = SyntaxConverter.convertBody(syntaxBody(code));
        assertEquals(1, body.statements().size(), code);
        return assertInstanceOf(IrForeach.class, body.statements().getFirst(), code);
    }

    private static IrExpression expression(String code) {
        return SyntaxConverter.convertExpression(syntaxExpression(code));
    }

    private static SyntaxExpression syntaxExpression(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php " + code + ";");
        return new SyntaxExpression(parsed.getStmts().getValue().getFirst().getStmt().getExpression(),
                new SourceInfo("references.php", null));
    }

    private static SyntaxBody syntaxBody(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php function testBody() { " + code + " }");
        var statements = parsed.getStmts().getValue().getFirst().getFunction().getStmts().getValue();
        return new SyntaxBody(List.copyOf(statements), new SourceInfo("references.php", null));
    }

    private static IrExpression statementExpression(IrBlock body, int index) {
        return assertInstanceOf(IrExpressionStatement.class, body.statements().get(index)).expression();
    }

    private static void assertSlotVariable(IrDestructuringPattern pattern, int index, String name) {
        IrDestructuringSlot slot = pattern.slots().get(index);
        assertNull(slot.key());
        assertVariableTarget(slot.target(), name);
    }

    private static void assertSkip(IrDestructuringPattern pattern, int index) {
        IrDestructuringSlot slot = pattern.slots().get(index);
        assertNull(slot.key());
        assertNull(slot.target());
    }

    private static void assertVariableTarget(Object target, String name) {
        assertEquals(name, assertInstanceOf(IrFixedName.class,
                assertInstanceOf(IrVariableTarget.class, target).name()).value());
    }

    private static void assertAppend(Object target, String name) {
        IrIndexTarget index = assertInstanceOf(IrIndexTarget.class, target);
        assertNull(index.index());
        assertVariableTarget(index.base(), name);
    }

    private static void assertVariable(IrExpression expression, String name) {
        assertEquals(name, assertInstanceOf(IrFixedName.class,
                assertInstanceOf(IrVariable.class, expression).name()).value());
    }

    private static void assertInteger(IrExpression expression, long value) {
        assertEquals(value, assertInstanceOf(IrIntegerLiteral.class, expression).value());
    }

    private static void assertString(IrExpression expression, String value) {
        assertArrayEquals(value.getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringLiteral.class, expression).value().toByteArray());
    }

    private static void assertCall(IrExpression expression, String name) {
        IrCall call = assertInstanceOf(IrCall.class, expression);
        assertEquals(name, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
        assertTrue(call.arguments().isEmpty());
    }

    private static void assertAppendAssignment(IrExpression expression, String target, String call) {
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression);
        assertAppend(assignment.target(), target);
        assertCall(assignment.value(), call);
    }

    private static void assertRejectedExpression(String code) {
        SyntaxExpression syntax = assertDoesNotThrow(() -> syntaxExpression(code), code);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                () -> SyntaxConverter.convertExpression(syntax), code);
        assertFailure(error, code);
    }

    private static void assertRejectedBody(String code) {
        SyntaxBody syntax = assertDoesNotThrow(() -> syntaxBody(code), code);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                () -> SyntaxConverter.convertBody(syntax), code);
        assertFailure(error, code);
    }

    private static void assertFailure(SyntaxConversionException error, String code) {
        assertEquals("references.php", error.source().sourceId(), code);
        assertFalse(error.fieldPath().isBlank(), code);
        assertFalse(error.reason().isBlank(), code);
    }
}
