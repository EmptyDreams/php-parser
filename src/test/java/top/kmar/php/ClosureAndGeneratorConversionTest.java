package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SyntaxBody;
import top.kmar.php.model.SyntaxExpression;
import top.kmar.php.model.TypeReference;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证闭包与生成器只转换语法结构，不绑定捕获变量或执行生成器。 */
class ClosureAndGeneratorConversionTest {

    // 普通与静态闭包采用同一模型，缺省签名和空函数体不产生虚构字段。
    @Test
    void convertsEmptyOrdinaryAndStaticClosures() {
        for (String prefix : List.of("function", "static function", "StAtIc FuNcTiOn")) {
            IrClosure closure = closure(prefix + "() {}");
            assertEquals(!prefix.equals("function"), closure.isStatic());
            assertFalse(closure.returnsReference());
            assertNull(closure.returnType());
            assertTrue(closure.parameters().isEmpty());
            assertTrue(closure.captures().isEmpty());
            assertTrue(closure.body().statements().isEmpty());
        }
        IrClosure withEmptyStatement = closure("function() { ; }");
        assertEquals(1, withEmptyStatement.body().statements().size());
        assertInstanceOf(IrEmpty.class, withEmptyStatement.body().statements().getFirst());
    }

    // 返回引用、引用参数及可变参数分别保存标志，不改写函数体内的变量读取。
    @Test
    void preservesIndependentReferenceAndVariadicFlags() {
        IrClosure closure = closure("static function &($First, &$Second, &...$Rest) { return $Second; }");
        assertTrue(closure.isStatic());
        assertTrue(closure.returnsReference());
        assertEquals(List.of("First", "Second", "Rest"), closure.parameters().stream().map(IrParameter::name).toList());
        assertEquals(List.of(false, true, true), closure.parameters().stream().map(IrParameter::byReference).toList());
        assertEquals(List.of(false, false, true), closure.parameters().stream().map(IrParameter::variadic).toList());
        for (IrParameter parameter : closure.parameters()) {
            assertNull(parameter.declaredType());
            assertNull(parameter.defaultValue());
        }
        assertVariable(assertInstanceOf(IrReturn.class, closure.body().statements().getFirst()).value(), "Second");
        IrClosure ordinaryReference = closure("function &(...$values) { return $values; }");
        assertFalse(ordinaryReference.isStatic());
        assertTrue(ordinaryReference.returnsReference());
        assertFalse(ordinaryReference.parameters().getFirst().byReference());
        assertTrue(ordinaryReference.parameters().getFirst().variadic());
    }

    // 类型声明保留可空标记、名称形式和大小写，包括 array/callable 两种关键字类型。
    @Test
    void preservesParameterAndReturnTypeForms() {
        IrClosure closure = closure("function (?Thing $a, Vendor\\Thing $b, \\Root\\Thing $c, "
                + "namespace\\Thing $d, ArRaY $e, ?CaLlAbLe $f): ?\\Result\\Value {}");
        List<IrParameter> parameters = closure.parameters();
        assertEquals(6, parameters.size());
        assertType(parameters.get(0).declaredType(), "Thing", NameForm.UNQUALIFIED, true);
        assertType(parameters.get(1).declaredType(), "Vendor\\Thing", NameForm.QUALIFIED, false);
        assertType(parameters.get(2).declaredType(), "\\Root\\Thing", NameForm.FULLY_QUALIFIED, false);
        assertType(parameters.get(3).declaredType(), "namespace\\Thing", NameForm.NAMESPACE_RELATIVE, false);
        assertType(parameters.get(4).declaredType(), "ArRaY", NameForm.UNQUALIFIED, false);
        assertType(parameters.get(5).declaredType(), "CaLlAbLe", NameForm.UNQUALIFIED, true);
        assertType(closure.returnType(), "\\Result\\Value", NameForm.FULLY_QUALIFIED, true);
        assertType(closure("function(): array {}").returnType(), "array", NameForm.UNQUALIFIED, false);
        assertType(closure("function(): callable {}").returnType(), "callable", NameForm.UNQUALIFIED, false);
        assertType(closure("function(): namespace\\Result {}").returnType(),
                "namespace\\Result", NameForm.NAMESPACE_RELATIVE, false);
    }

    // 默认值递归进入现有表达式模型，未声明默认值与显式 null 必须区分。
    @Test
    void convertsDefaultsUsingExistingExpressionAndNumericModels() {
        IrClosure closure = closure("function ($missing, $nil = null, $integer = 0x2A, $float = 1.25, "
                + "$overflow = 1e999, $items = [2 => 'two'], $sum = 1 + 2, $negative = -4) {}");
        List<IrParameter> parameters = closure.parameters();
        assertEquals(8, parameters.size());
        assertNull(parameters.get(0).defaultValue());
        assertNullLiteral(parameters.get(1).defaultValue());
        assertInteger(parameters.get(2).defaultValue(), 42);
        assertEquals(1.25, assertInstanceOf(IrFloatLiteral.class, parameters.get(3).defaultValue()).value());
        assertEquals(Double.POSITIVE_INFINITY,
                assertInstanceOf(IrFloatLiteral.class, parameters.get(4).defaultValue()).value());
        IrArrayLiteral items = assertInstanceOf(IrArrayLiteral.class, parameters.get(5).defaultValue());
        assertEquals(1, items.entries().size());
        assertInteger(items.entries().getFirst().key(), 2);
        assertString(assertInstanceOf(IrValueArrayEntry.class, items.entries().getFirst()).value(), "two");
        IrBinary sum = assertInstanceOf(IrBinary.class, parameters.get(6).defaultValue());
        assertEquals(BinaryOperator.ADD, sum.operator());
        assertInteger(sum.left(), 1);
        assertInteger(sum.right(), 2);
        IrUnary negative = assertInstanceOf(IrUnary.class, parameters.get(7).defaultValue());
        assertInteger(negative.operand(), 4);
    }

    // 捕获条目保留源码顺序、重复项和各自的引用方式，不去重或绑定到外部声明。
    @Test
    void preservesCaptureOrderNamesAndReferenceModes() {
        IrClosure closure = closure("function() use ($Outer, &$shared, $Outer, &$Outer) { return $shared; }");
        assertEquals(List.of("Outer", "shared", "Outer", "Outer"),
                closure.captures().stream().map(IrClosureCapture::name).toList());
        assertEquals(List.of(false, true, false, true),
                closure.captures().stream().map(IrClosureCapture::byReference).toList());
        assertVariable(assertInstanceOf(IrReturn.class, closure.body().statements().getFirst()).value(), "shared");
    }

    // 闭包出现在赋值、数组、调用实参和返回值中时仍是独立表达式节点。
    @Test
    void convertsClosuresInExistingExpressionPositions() {
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression("$callback = function() {}"));
        assertFixed(assertInstanceOf(IrVariableTarget.class, assignment.target()).name(), "callback");
        assertInstanceOf(IrClosure.class, assignment.value());
        IrArrayLiteral array = assertInstanceOf(IrArrayLiteral.class, expression("[function() {}, static function() {}]"));
        assertEquals(2, array.entries().size());
        assertFalse(assertInstanceOf(IrClosure.class,
                assertInstanceOf(IrValueArrayEntry.class, array.entries().getFirst()).value()).isStatic());
        assertTrue(assertInstanceOf(IrClosure.class,
                assertInstanceOf(IrValueArrayEntry.class, array.entries().get(1)).value()).isStatic());
        IrCall consumer = assertCall(expression("consume(function($x) { return $x; })"), "consume", 1);
        assertEquals("x", assertInstanceOf(IrClosure.class, consumer.arguments().getFirst().expression())
                .parameters().getFirst().name());
        IrReturn returned = assertInstanceOf(IrReturn.class, body("return static function() {};").statements().getFirst());
        assertTrue(assertInstanceOf(IrClosure.class, returned.value()).isStatic());
    }

    // 立即调用和连续调用使用现有表达式调用目标，不把闭包降级成虚构具名函数。
    @Test
    void preservesImmediatelyInvokedAndChainedClosures() {
        IrCall immediate = assertInstanceOf(IrCall.class, expression("(function($x) { return $x; })(next())"));
        IrClosure target = assertInstanceOf(IrClosure.class,
                assertInstanceOf(IrExpressionCallTarget.class, immediate.target()).expression());
        assertEquals("x", target.parameters().getFirst().name());
        assertEquals(1, immediate.arguments().size());
        assertFalse(immediate.arguments().getFirst().unpack());
        assertCall(immediate.arguments().getFirst().expression(), "next", 0);
        IrCall outer = assertInstanceOf(IrCall.class,
                expression("(static function() { return function() {}; })()()"));
        IrCall inner = assertInstanceOf(IrCall.class,
                assertInstanceOf(IrExpressionCallTarget.class, outer.target()).expression());
        IrClosure factory = assertInstanceOf(IrClosure.class,
                assertInstanceOf(IrExpressionCallTarget.class, inner.target()).expression());
        assertTrue(factory.isStatic());
        assertInstanceOf(IrClosure.class, assertInstanceOf(IrReturn.class,
                factory.body().statements().getFirst()).value());
        assertTrue(inner.arguments().isEmpty());
        assertTrue(outer.arguments().isEmpty());
    }

    // 嵌套闭包各自拥有参数、捕获和函数体，内层 yield 不提升到外层。
    @Test
    void retainsNestedClosureBoundaries() {
        IrClosure outer = closure("function($outer) use ($root) { "
                + "return static function($inner) use (&$outer) { yield $inner; return $outer; }; }");
        assertEquals(List.of("outer"), outer.parameters().stream().map(IrParameter::name).toList());
        assertEquals(List.of("root"), outer.captures().stream().map(IrClosureCapture::name).toList());
        assertEquals(1, outer.body().statements().size());
        IrClosure inner = assertInstanceOf(IrClosure.class,
                assertInstanceOf(IrReturn.class, outer.body().statements().getFirst()).value());
        assertTrue(inner.isStatic());
        assertEquals(List.of("inner"), inner.parameters().stream().map(IrParameter::name).toList());
        assertEquals(List.of("outer"), inner.captures().stream().map(IrClosureCapture::name).toList());
        assertTrue(inner.captures().getFirst().byReference());
        assertEquals(2, inner.body().statements().size());
        assertVariable(assertInstanceOf(IrYield.class, statementExpression(inner.body(), 0)).value(), "inner");
        assertVariable(assertInstanceOf(IrReturn.class, inner.body().statements().get(1)).value(), "outer");
    }

    // 只处理结构，不校验参数和捕获冲突、默认值常量性或生成器返回类型。
    @Test
    void preservesParserAcceptedSignaturesWithoutSemanticValidation() {
        IrClosure closure = closure("static function &($same, $same = next(), ...$rest = 1) "
                + "use ($same, &$same, $this): int { yield 1; }");
        assertEquals(List.of("same", "same", "rest"), closure.parameters().stream().map(IrParameter::name).toList());
        assertCall(closure.parameters().get(1).defaultValue(), "next", 0);
        assertTrue(closure.parameters().get(2).variadic());
        assertInteger(closure.parameters().get(2).defaultValue(), 1);
        assertEquals(List.of("same", "same", "this"), closure.captures().stream().map(IrClosureCapture::name).toList());
        assertType(closure.returnType(), "int", NameForm.UNQUALIFIED, false);
        assertInteger(assertInstanceOf(IrYield.class, statementExpression(closure.body(), 0)).value(), 1);
        IrClosure referenceDelegation = closure("function &() { yield from []; }");
        assertTrue(referenceDelegation.returnsReference());
        assertInstanceOf(IrYieldFrom.class, statementExpression(referenceDelegation.body(), 0));
    }

    // 默认值也可递归包含闭包和 yield，不因 PHP 常量表达式限制而丢弃已解析的结构。
    @Test
    void recursivelyConvertsClosuresAndYieldInDefaults() {
        IrClosure outer = closure("function($callback = function() { return 1; }, $result = (yield 2)) {}");
        IrClosure callback = assertInstanceOf(IrClosure.class, outer.parameters().getFirst().defaultValue());
        assertInteger(assertInstanceOf(IrReturn.class, callback.body().statements().getFirst()).value(), 1);
        IrYield yielded = assertInstanceOf(IrYield.class, outer.parameters().get(1).defaultValue());
        assertNull(yielded.key());
        assertInteger(yielded.value(), 2);
        assertTrue(outer.body().statements().isEmpty());
    }

    // 裸 yield、值 yield、键值 yield 和 yield from 使用明确不同的字段或模型。
    @Test
    void distinguishesEveryYieldFormAndExplicitNull() {
        IrYield bare = assertInstanceOf(IrYield.class, expression("yield"));
        assertNull(bare.key());
        assertNull(bare.value());
        IrYield value = assertInstanceOf(IrYield.class, expression("yield $value"));
        assertNull(value.key());
        assertVariable(value.value(), "value");
        IrYield pair = assertInstanceOf(IrYield.class, expression("yield $key => $value"));
        assertVariable(pair.key(), "key");
        assertVariable(pair.value(), "value");
        IrYieldFrom delegation = assertInstanceOf(IrYieldFrom.class, expression("yield from $items"));
        assertVariable(delegation.expression(), "items");
        IrYield explicitNull = assertInstanceOf(IrYield.class, expression("yield null"));
        assertNull(explicitNull.key());
        assertNullLiteral(explicitNull.value());
        IrYield nullPair = assertInstanceOf(IrYield.class, expression("yield null => null"));
        assertNullLiteral(nullPair.key());
        assertNullLiteral(nullPair.value());
    }

    // yield 保持表达式身份，赋值接收值、实参和二元分组的层级不被改写。
    @Test
    void preservesYieldExpressionPlacementAndGrouping() {
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression("$received = yield 1"));
        assertFixed(assertInstanceOf(IrVariableTarget.class, assignment.target()).name(), "received");
        assertInteger(assertInstanceOf(IrYield.class, assignment.value()).value(), 1);
        IrAssignment delegated = assertInstanceOf(IrAssignment.class, expression("$result = yield from $items"));
        assertVariable(assertInstanceOf(IrYieldFrom.class, delegated.value()).expression(), "items");
        IrBinary sum = assertInstanceOf(IrBinary.class, expression("(yield 1) + (yield 2)"));
        assertEquals(BinaryOperator.ADD, sum.operator());
        assertInteger(assertInstanceOf(IrYield.class, sum.left()).value(), 1);
        assertInteger(assertInstanceOf(IrYield.class, sum.right()).value(), 2);
        IrCall call = assertCall(expression("consume((yield), (yield 2), (yield from $items))"), "consume", 3);
        assertNull(assertInstanceOf(IrYield.class, call.arguments().get(0).expression()).value());
        assertInteger(assertInstanceOf(IrYield.class, call.arguments().get(1).expression()).value(), 2);
        assertVariable(assertInstanceOf(IrYieldFrom.class, call.arguments().get(2).expression()).expression(), "items");
    }

    // 生成器嵌在循环和异常处理内时，yield、return 与 finally 的原归属保持不变。
    @Test
    void convertsYieldInsideLoopsAndExceptionHandlers() {
        IrClosure closure = closure("function($items) { foreach ($items as $key => $value) { "
                + "try { yield $key => $value; } catch (Problem $error) { yield $error; } "
                + "finally { yield from cleanup(); } } return 7; }");
        assertEquals(2, closure.body().statements().size());
        IrForeach loop = assertInstanceOf(IrForeach.class, closure.body().statements().getFirst());
        assertVariable(assertInstanceOf(IrExpressionIterable.class, loop.iterable()).expression(), "items");
        assertFalse(loop.byReference());
        assertEquals(1, loop.body().statements().size());
        IrTry attempt = assertInstanceOf(IrTry.class, loop.body().statements().getFirst());
        IrYield pair = assertInstanceOf(IrYield.class, statementExpression(attempt.body(), 0));
        assertVariable(pair.key(), "key");
        assertVariable(pair.value(), "value");
        assertEquals(1, attempt.catches().size());
        assertVariable(assertInstanceOf(IrYield.class,
                statementExpression(attempt.catches().getFirst().body(), 0)).value(), "error");
        assertNotNull(attempt.finallyBlock());
        assertCall(assertInstanceOf(IrYieldFrom.class, statementExpression(attempt.finallyBlock(), 0)).expression(),
                "cleanup", 0);
        assertInteger(assertInstanceOf(IrReturn.class, closure.body().statements().get(1)).value(), 7);
    }

    // 关键字大小写及 yield/from 之间所有词法允许空白都归一为相同的生成器模型。
    @Test
    void acceptsYieldKeywordCaseAndDelegationWhitespace() {
        for (String keyword : List.of("yield", "YIELD", "YiElD")) {
            assertInteger(assertInstanceOf(IrYield.class, expression(keyword + " 1")).value(), 1);
        }
        for (String marker : List.of("yield from", "YIELD FROM", "YiElD FrOm", "yield  from",
                "yield\tfrom", "yield\rfrom", "yield\nfrom", "yield \t\r\nfrom")) {
            IrYieldFrom yielded = assertInstanceOf(IrYieldFrom.class, expression(marker + " $items"), marker);
            assertVariable(yielded.expression(), "items");
        }
    }

    // yield 的键和值中的副作用都只出现一次，不为生成器转换提前求值或复制节点。
    @Test
    void preservesSingleOccurrencesOfYieldSideEffects() {
        IrYield yielded = assertInstanceOf(IrYield.class,
                expression("yield nextKey($index++) => ($saved = nextValue())"));
        IrCall key = assertCall(yielded.key(), "nextKey", 1);
        IrUpdate update = assertInstanceOf(IrUpdate.class, key.arguments().getFirst().expression());
        assertEquals(UpdateOperator.POST_INCREMENT, update.operator());
        assertFixed(assertInstanceOf(IrVariableTarget.class, update.target()).name(), "index");
        IrAssignment value = assertInstanceOf(IrAssignment.class, yielded.value());
        assertFixed(assertInstanceOf(IrVariableTarget.class, value.target()).name(), "saved");
        assertCall(value.value(), "nextValue", 0);
        IrYieldFrom delegation = assertInstanceOf(IrYieldFrom.class, expression("yield from fetch($offset++)"));
        IrCall fetch = assertCall(delegation.expression(), "fetch", 1);
        assertInstanceOf(IrUpdate.class, fetch.arguments().getFirst().expression());
    }

    // yield 与 yield from 可递归组合，也可传递闭包值，不推断可迭代性或作用域合法性。
    @Test
    void preservesNestedYieldAndArbitraryDelegationExpressions() {
        IrYield outer = assertInstanceOf(IrYield.class, expression("yield (yield 1) => (yield from $items)"));
        assertInteger(assertInstanceOf(IrYield.class, outer.key()).value(), 1);
        assertVariable(assertInstanceOf(IrYieldFrom.class, outer.value()).expression(), "items");
        IrYield yieldedClosure = assertInstanceOf(IrYield.class, expression("yield function() {}"));
        assertInstanceOf(IrClosure.class, yieldedClosure.value());
        assertInteger(assertInstanceOf(IrYieldFrom.class, expression("yield from 1")).expression(), 1);
        IrReturn returned = assertInstanceOf(IrReturn.class, body("return (yield from $items);").statements().getFirst());
        assertInstanceOf(IrYieldFrom.class, returned.value());
    }

    // 操作数始终沿用读取规则；内部独立赋值可以追加，引用捕获也不放宽裸追加读取。
    @Test
    void preservesReadContextAndIndependentAssignmentBoundaries() {
        for (String code : List.of("yield $items[]", "yield $items[] => 1", "yield 1 => $items[]",
                "yield from $items[]", "function($value = $items[]) {}", "function &() { return $items[]; }",
                "function &() { yield $items[]; }", "function &() { yield from $items[]; }")) {
            assertRejected(code);
        }
        IrYield value = assertInstanceOf(IrYield.class, expression("yield ($items[] = next())"));
        assertAppendAssignment(value.value(), "items", "next");
        IrYieldFrom delegation = assertInstanceOf(IrYieldFrom.class, expression("yield from ($items[] = next())"));
        assertAppendAssignment(delegation.expression(), "items", "next");
        IrClosure closure = closure("function() use (&$items) { $items[] = next(); }");
        assertAppendAssignment(statementExpression(closure.body(), 0), "items", "next");
        IrClosure referenceClosure = closure("function() use (&$items) { $copy =& $items; }");
        IrReferenceAssignment reference = assertInstanceOf(IrReferenceAssignment.class,
                statementExpression(referenceClosure.body(), 0));
        assertFixed(assertInstanceOf(IrVariableTarget.class, reference.target()).name(), "copy");
        assertFixed(assertInstanceOf(IrVariableTarget.class, reference.reference()).name(), "items");
        assertTrue(referenceClosure.captures().getFirst().byReference());
    }

    // global 和局部 static 留在所属闭包体内，嵌套闭包的全局绑定不能被提升或变成捕获项。
    @Test
    void preservesLocalScopeStatementsAcrossNestedClosures() {
        IrClosure outer = closure("function() { global $value; static $counter = 1; "
                + "return function() { global $value; }; }");
        assertEquals(3, outer.body().statements().size());
        IrGlobal global = assertInstanceOf(IrGlobal.class, outer.body().statements().getFirst());
        assertEquals(1, global.variables().size());
        assertFixed(global.variables().getFirst().name(), "value");
        IrStaticVariables locals = assertInstanceOf(IrStaticVariables.class, outer.body().statements().get(1));
        assertEquals(1, locals.variables().size());
        assertEquals("counter", locals.variables().getFirst().name());
        assertInteger(locals.variables().getFirst().initializer(), 1);
        IrClosure inner = assertInstanceOf(IrClosure.class,
                assertInstanceOf(IrReturn.class, outer.body().statements().get(2)).value());
        assertEquals(1, inner.body().statements().size());
        IrGlobal nestedGlobal = assertInstanceOf(IrGlobal.class, inner.body().statements().getFirst());
        assertEquals(1, nestedGlobal.variables().size());
        assertFixed(nestedGlobal.variables().getFirst().name(), "value");
        assertTrue(outer.captures().isEmpty());
        assertTrue(inner.captures().isEmpty());
    }

    // 新增外壳不吞掉非法读取；嵌套具名声明内部的空下标读取也必须递归失败。
    @Test
    void rejectsUnsupportedContentsInEveryNewExpressionPosition() {
        for (String code : List.of("function($value = ($invalid[])) {}", "function() { ($invalid[]); }",
                "function() { return function() { ($invalid[]); }; }",
                "function() { global ${($invalid[])}; }",
                "function() { static $value = ($invalid[]); }",
                "function() { function nested() { ($invalid[]); } }",
                "function() { class Nested { function run() { ($invalid[]); } } }",
                "function() { return ($invalid[]); }",
                "yield ($invalid[])", "yield ($invalid[]) => 1", "yield 1 => ($invalid[])",
                "yield from ($invalid[])")) {
            assertRejected(code);
        }
    }

    private static IrClosure closure(String code) {
        return assertInstanceOf(IrClosure.class, expression(code));
    }

    private static IrExpression expression(String code) {
        return SyntaxConverter.convertExpression(syntaxExpression(code));
    }

    private static SyntaxExpression syntaxExpression(String code) {
        NodeProgram program = (NodeProgram) Main.parse("<?php " + code + ";");
        NodeExpr node = program.getStmts().getValue().getFirst().getStmt().getExpression();
        return new SyntaxExpression(node, new SourceInfo("closure-generator.php", null));
    }

    private static IrBlock body(String code) {
        NodeProgram program = (NodeProgram) Main.parse("<?php function outer() { " + code + " }");
        var statements = program.getStmts().getValue().getFirst().getFunction().getStmts().getValue();
        return SyntaxConverter.convertBody(new SyntaxBody(List.copyOf(statements),
                new SourceInfo("closure-generator.php", null)));
    }

    private static IrExpression statementExpression(IrBlock body, int index) {
        return assertInstanceOf(IrExpressionStatement.class, body.statements().get(index)).expression();
    }

    private static void assertType(TypeReference type, String spelling, NameForm form, boolean nullable) {
        assertNotNull(type);
        assertEquals(spelling, type.name().spelling());
        assertEquals(form, type.name().form());
        assertEquals(nullable, type.nullable());
    }

    private static void assertFixed(IrAccessName name, String expected) {
        assertEquals(expected, assertInstanceOf(IrFixedName.class, name).value());
    }

    private static void assertVariable(IrExpression expression, String expected) {
        assertFixed(assertInstanceOf(IrVariable.class, expression).name(), expected);
    }

    private static void assertInteger(IrExpression expression, long expected) {
        assertEquals(expected, assertInstanceOf(IrIntegerLiteral.class, expression).value());
    }

    private static void assertNullLiteral(IrExpression expression) {
        assertInstanceOf(IrNullLiteral.class, expression);
    }

    private static void assertString(IrExpression expression, String value) {
        assertArrayEquals(value.getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringLiteral.class, expression).value().toByteArray());
    }

    private static IrCall assertCall(IrExpression expression, String name, int argumentCount) {
        IrCall call = assertInstanceOf(IrCall.class, expression);
        assertEquals(name, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
        assertEquals(argumentCount, call.arguments().size());
        return call;
    }

    private static void assertAppendAssignment(IrExpression expression, String variable, String callName) {
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression);
        IrIndexTarget target = assertInstanceOf(IrIndexTarget.class, assignment.target());
        assertNull(target.index());
        assertFixed(assertInstanceOf(IrVariableTarget.class, target.base()).name(), variable);
        assertCall(assignment.value(), callName, 0);
    }

    private static void assertRejected(String code) {
        SyntaxExpression syntax = assertDoesNotThrow(() -> syntaxExpression(code), code);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                () -> SyntaxConverter.convertExpression(syntax), code);
        assertEquals("closure-generator.php", error.source().sourceId());
        assertFalse(error.fieldPath().isBlank(), code);
        assertFalse(error.reason().isBlank(), code);
    }
}
