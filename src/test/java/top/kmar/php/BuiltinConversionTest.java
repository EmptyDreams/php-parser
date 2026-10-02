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
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证内置语言结构的独立模型、分组及目标边界，不执行 PHP 或访问包含的文件。 */
class BuiltinConversionTest {

    // isset 使用有序的独立节点表达短路检查，重复项和带副作用的索引不被合并或展开。
    @Test
    void preservesOrderedIssetOperandsWithoutDesugaring() {
        IrIsset single = assertInstanceOf(IrIsset.class, expression("IsSeT($missing)"));
        assertEquals(1, single.expressions().size());
        assertVariable(single.expressions().getFirst(), "missing");

        IrIsset multiple = assertInstanceOf(IrIsset.class,
                expression("isset($missing, $items[next()], $missing, $object->value, Box::$value)"));
        assertEquals(5, multiple.expressions().size());
        assertVariable(multiple.expressions().getFirst(), "missing");
        IrIndex indexed = assertInstanceOf(IrIndex.class, multiple.expressions().get(1));
        assertVariable(indexed.base(), "items");
        assertCall(indexed.index(), "next", 0);
        assertVariable(multiple.expressions().get(2), "missing");
        IrPropertyAccess property = assertInstanceOf(IrPropertyAccess.class, multiple.expressions().get(3));
        assertVariable(property.receiver(), "object");
        assertEquals("value", fixedName(property.property()));
        assertEquals("value", fixedName(assertInstanceOf(IrStaticPropertyAccess.class, multiple.expressions().get(4)).property()));
    }

    // 文法接受的 isset 表达式直接保留，不在结构转换层补上 PHP 的上下文合法性检查。
    @Test
    void retainsParserAcceptedIssetExpressionsWithoutContextValidation() {
        IrIsset check = assertInstanceOf(IrIsset.class, expression("isset(1, $saved = next(), $a + $b)"));
        assertEquals(3, check.expressions().size());
        assertInteger(check.expressions().getFirst(), 1);
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, check.expressions().get(1));
        assertVariableTarget(assignment.target(), "saved");
        assertCall(assignment.value(), "next", 0);
        IrBinary addition = assertInstanceOf(IrBinary.class, check.expressions().get(2));
        assertEquals(BinaryOperator.ADD, addition.operator());
        assertVariable(addition.left(), "a");
        assertVariable(addition.right(), "b");
    }

    // empty 保留访问路径或任意已支持表达式，不提前读取缺失项、取反或执行函数。
    @Test
    void keepsEmptyChecksAroundTheirOriginalOperands() {
        IrEmptyCheck check = assertInstanceOf(IrEmptyCheck.class, expression("EmPtY($missing['key']->value)"));
        IrPropertyAccess property = assertInstanceOf(IrPropertyAccess.class, check.expression());
        assertEquals("value", fixedName(property.property()));
        IrIndex index = assertInstanceOf(IrIndex.class, property.receiver());
        assertVariable(index.base(), "missing");
        assertString(index.index(), "key");
        assertCall(assertInstanceOf(IrEmptyCheck.class, expression("empty(next())")).expression(), "next", 0);
        IrBinary sum = assertInstanceOf(IrBinary.class,
                assertInstanceOf(IrEmptyCheck.class, expression("empty(1 + 2)")).expression());
        assertEquals(BinaryOperator.ADD, sum.operator());
        assertInteger(sum.left(), 1);
        assertInteger(sum.right(), 2);
    }

    // isset/empty 在逻辑、空合并和相互嵌套时仍保留独立语义边界。
    @Test
    void preservesCheckGroupingInsideExistingOperators() {
        IrLogical logical = assertInstanceOf(IrLogical.class,
                expression("isset($a, $items[next()]) && !empty($object->value)"));
        assertEquals(LogicalOperator.AND, logical.operator());
        assertEquals(2, assertInstanceOf(IrIsset.class, logical.left()).expressions().size());
        IrUnary negation = assertInstanceOf(IrUnary.class, logical.right());
        assertEquals(UnaryOperator.NOT, negation.operator());
        assertInstanceOf(IrEmptyCheck.class, negation.operand());
        IrCoalesce coalesce = assertInstanceOf(IrCoalesce.class, expression("empty(isset($a)) ?? fallback()"));
        IrEmptyCheck nested = assertInstanceOf(IrEmptyCheck.class, coalesce.left());
        assertVariable(assertInstanceOf(IrIsset.class, nested.expression()).expressions().getFirst(), "a");
        assertCall(coalesce.right(), "fallback", 0);
    }

    // 所有 PHP 7.2 强转别名均归一化，关键字大小写及括号内空格/tab 不改变种类。
    @Test
    void normalizesEveryCastAliasAndLexerAcceptedSpacing() {
        Map<String, CastKind> aliases = Map.ofEntries(
                Map.entry("int", CastKind.INTEGER), Map.entry("integer", CastKind.INTEGER),
                Map.entry("real", CastKind.FLOAT), Map.entry("double", CastKind.FLOAT),
                Map.entry("float", CastKind.FLOAT), Map.entry("string", CastKind.STRING),
                Map.entry("binary", CastKind.STRING), Map.entry("array", CastKind.ARRAY),
                Map.entry("object", CastKind.OBJECT), Map.entry("bool", CastKind.BOOLEAN),
                Map.entry("boolean", CastKind.BOOLEAN), Map.entry("unset", CastKind.UNSET));
        aliases.forEach((alias, kind) -> {
            for (String token : List.of("(" + alias + ")", "( " + alias.toUpperCase(Locale.ROOT) + " )",
                    "(\t " + Character.toUpperCase(alias.charAt(0)) + alias.substring(1) + " \t)")) {
                IrCast cast = assertInstanceOf(IrCast.class, expression(token + " $value"), token);
                assertEquals(kind, cast.kind(), token);
                assertVariable(cast.expression(), "value");
            }
        });
    }

    // 强转不计算常量或丢弃操作数，连续强转、算术优先级及幂运算按语法树分组。
    @Test
    void preservesCastNestingAndArithmeticPrecedence() {
        IrCast outer = assertInstanceOf(IrCast.class, expression("(string) (int) '12.5'"));
        assertEquals(CastKind.STRING, outer.kind());
        IrCast inner = assertInstanceOf(IrCast.class, outer.expression());
        assertEquals(CastKind.INTEGER, inner.kind());
        assertString(inner.expression(), "12.5");
        IrCast unset = assertInstanceOf(IrCast.class, expression("(unset) next()"));
        assertEquals(CastKind.UNSET, unset.kind());
        assertCall(unset.expression(), "next", 0);

        IrBinary addition = assertInstanceOf(IrBinary.class, expression("(int) $a + $b"));
        assertEquals(BinaryOperator.ADD, addition.operator());
        assertVariable(assertInstanceOf(IrCast.class, addition.left()).expression(), "a");
        assertVariable(addition.right(), "b");
        IrCast castPower = assertInstanceOf(IrCast.class, expression("(int) 2 ** 3"));
        IrBinary power = assertInstanceOf(IrBinary.class, castPower.expression());
        assertEquals(BinaryOperator.POWER, power.operator());
        assertInteger(power.left(), 2);
        assertInteger(power.right(), 3);
    }

    // @ 保留包裹范围和重复嵌套；不能丢弃标记或扩大到整个外层算术表达式。
    @Test
    void preservesErrorSuppressionBoundaries() {
        IrErrorSuppress nested = assertInstanceOf(IrErrorSuppress.class, expression("@@next()"));
        assertCall(assertInstanceOf(IrErrorSuppress.class, nested.expression()).expression(), "next", 0);
        IrBinary sum = assertInstanceOf(IrBinary.class, expression("@first() + second()"));
        assertEquals(BinaryOperator.ADD, sum.operator());
        assertCall(assertInstanceOf(IrErrorSuppress.class, sum.left()).expression(), "first", 0);
        assertCall(sum.right(), "second", 0);
        IrErrorSuppress suppression = assertInstanceOf(IrErrorSuppress.class, expression("@($saved = next())"));
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, suppression.expression());
        assertVariableTarget(assignment.target(), "saved");
        assertCall(assignment.value(), "next", 0);
    }

    // print 保持为表达式，可嵌入赋值；与 and 和加法的优先级不转换成 echo 语句。
    @Test
    void retainsPrintAsAnExpressionWithItsOriginalGrouping() {
        IrLogical logical = assertInstanceOf(IrLogical.class, expression("PrInT first() + 1 and second()"));
        assertEquals(LogicalOperator.AND, logical.operator());
        IrPrint output = assertInstanceOf(IrPrint.class, logical.left());
        IrBinary sum = assertInstanceOf(IrBinary.class, output.expression());
        assertEquals(BinaryOperator.ADD, sum.operator());
        assertCall(sum.left(), "first", 0);
        assertInteger(sum.right(), 1);
        assertCall(logical.right(), "second", 0);
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression("$status = (print next())"));
        assertVariableTarget(assignment.target(), "status");
        assertCall(assertInstanceOf(IrPrint.class, assignment.value()).expression(), "next", 0);
        assertInstanceOf(IrPrint.class, assertInstanceOf(IrExpressionStatement.class, only("print 1;")).expression());
    }

    // 四种包含方式各有明确枚举，不读取文件；关键字大小写不改变种类且实参副作用只保留一次。
    @Test
    void distinguishesIncludeKindsWithoutLoadingFiles() {
        Map<String, IncludeKind> forms = Map.of("include", IncludeKind.INCLUDE,
                "include_once", IncludeKind.INCLUDE_ONCE, "require", IncludeKind.REQUIRE,
                "require_once", IncludeKind.REQUIRE_ONCE);
        forms.forEach((keyword, kind) -> {
            for (String token : List.of(keyword, keyword.toUpperCase(Locale.ROOT))) {
                IrInclude include = assertInstanceOf(IrInclude.class,
                        expression(token + " ($path = locate(first()))"));
                assertEquals(kind, include.kind());
                IrAssignment saved = assertInstanceOf(IrAssignment.class, include.expression());
                assertVariableTarget(saved.target(), "path");
                IrCall locate = assertCall(saved.value(), "locate", 1);
                assertCall(locate.arguments().getFirst().expression(), "first", 0);
            }
            IrInclude missing = assertInstanceOf(IrInclude.class,
                    expression(keyword + " '/file-that-does-not-exist.php'"));
            assertEquals(kind, missing.kind());
            assertString(missing.expression(), "/file-that-does-not-exist.php");
        });
        IrInclude joined = assertInstanceOf(IrInclude.class, expression("include 'dir/' . pathName()"));
        IrBinary concat = assertInstanceOf(IrBinary.class, joined.expression());
        assertEquals(BinaryOperator.CONCAT, concat.operator());
        assertString(concat.left(), "dir/");
        assertCall(concat.right(), "pathName", 0);
    }

    // eval 只保存实参，即使内容不是 PHP 也不能在本阶段重新解析或执行。
    @Test
    void keepsEvalArgumentsOpaqueAndPreservesNesting() {
        assertString(assertInstanceOf(IrEval.class, expression("EvAl('not valid PHP {{{')")).expression(),
                "not valid PHP {{{");
        IrErrorSuppress suppressed = assertInstanceOf(IrErrorSuppress.class, expression("@eval(makeCode($saved = next()))"));
        IrEval eval = assertInstanceOf(IrEval.class, suppressed.expression());
        IrCall call = assertCall(eval.expression(), "makeCode", 1);
        IrAssignment saved = assertInstanceOf(IrAssignment.class, call.arguments().getFirst().expression());
        assertVariableTarget(saved.target(), "saved");
        assertCall(saved.value(), "next", 0);
    }

    // exit 与 die 同型，缺省参数和空括号同为 null，显式 PHP null 则保留字面量节点。
    @Test
    void normalizesExitAliasesButDistinguishesExplicitNull() {
        for (String keyword : List.of("exit", "die", "ExIt", "DiE")) {
            assertNull(assertInstanceOf(IrExit.class, expression(keyword)).expression());
            assertNull(assertInstanceOf(IrExit.class, expression(keyword + "()")).expression());
            assertInstanceOf(IrNullLiteral.class,
                    assertInstanceOf(IrExit.class, expression(keyword + "(null)")).expression());
            assertInteger(assertInstanceOf(IrExit.class, expression(keyword + "(3)")).expression(), 3);
            assertString(assertInstanceOf(IrExit.class, expression(keyword + "('message')")).expression(),
                    "message");
        }
    }

    // exit 的参数可含副作用或其它内置结构，后续语句也保留而不做不可达代码裁剪。
    @Test
    void preservesExitOperandsAndFollowingStatements() {
        IrBlock block = body("exit($code = status(next())); echo 2;");
        assertEquals(2, block.statements().size());
        IrExit exit = assertInstanceOf(IrExit.class,
                assertInstanceOf(IrExpressionStatement.class, block.statements().getFirst()).expression());
        IrAssignment saved = assertInstanceOf(IrAssignment.class, exit.expression());
        assertVariableTarget(saved.target(), "code");
        assertCall(assertCall(saved.value(), "status", 1).arguments().getFirst().expression(), "next", 0);
        assertInteger(assertInstanceOf(IrEcho.class, block.statements().get(1)).expressions().getFirst(), 2);
        IrExit printed = assertInstanceOf(IrExit.class, expression("die(print next())"));
        assertCall(assertInstanceOf(IrPrint.class, printed.expression()).expression(), "next", 0);
    }

    // unset 是语句，多个目标保持顺序和重复项，不转换成读取表达式或函数实参。
    @Test
    void preservesOrderedUnsetTargets() {
        IrUnset unset = assertInstanceOf(IrUnset.class,
                only("UnSeT($a, $items[next()], $object->Value, Box::$Items, $a);"));
        assertEquals(5, unset.targets().size());
        assertVariableTarget(unset.targets().getFirst(), "a");
        IrIndexTarget index = assertInstanceOf(IrIndexTarget.class, unset.targets().get(1));
        assertVariableTarget(index.base(), "items");
        assertCall(index.index(), "next", 0);
        IrPropertyTarget property = assertInstanceOf(IrPropertyTarget.class, unset.targets().get(2));
        assertEquals("Value", fixedName(property.property()));
        assertVariableTarget(property.receiver(), "object");
        IrStaticPropertyTarget staticProperty = assertInstanceOf(IrStaticPropertyTarget.class, unset.targets().get(3));
        assertEquals("Items", fixedName(staticProperty.property()));
        assertEquals("Box", assertInstanceOf(IrNamedClassReference.class, staticProperty.classReference()).name().value());
        assertVariableTarget(unset.targets().get(4), "a");
    }

    // 删除目标沿属性、方括号、花括号和调用结果访问链递归，接收者和索引各出现一次。
    @Test
    void convertsMixedUnsetTargetsAndCallBasedAccesses() {
        IrUnset unset = assertInstanceOf(IrUnset.class, only("""
                unset(($items[0])->Value{next()}, factory(first())->items[next()],
                    $object->make()[0], Box::make()->Value, Box::$items[1]->value);
                """));
        assertEquals(5, unset.targets().size());
        IrIndexTarget curly = assertInstanceOf(IrIndexTarget.class, unset.targets().getFirst());
        assertCall(curly.index(), "next", 0);
        IrPropertyTarget property = assertInstanceOf(IrPropertyTarget.class, curly.base());
        assertEquals("Value", fixedName(property.property()));
        IrIndexTarget inner = assertInstanceOf(IrIndexTarget.class, property.receiver());
        assertInteger(inner.index(), 0);
        assertVariableTarget(inner.base(), "items");

        IrIndexTarget called = assertInstanceOf(IrIndexTarget.class, unset.targets().get(1));
        assertCall(called.index(), "next", 0);
        IrPropertyTarget items = assertInstanceOf(IrPropertyTarget.class, called.base());
        assertEquals("items", fixedName(items.property()));
        IrCall factory = assertCall(assertInstanceOf(IrExpressionWriteBase.class, items.receiver()).expression(), "factory", 1);
        assertCall(factory.arguments().getFirst().expression(), "first", 0);

        IrIndexTarget method = assertInstanceOf(IrIndexTarget.class, unset.targets().get(2));
        assertInteger(method.index(), 0);
        IrMethodCall make = assertInstanceOf(IrMethodCall.class,
                assertInstanceOf(IrExpressionWriteBase.class, method.base()).expression());
        assertEquals("make", fixedName(make.method()));
        assertVariable(make.receiver(), "object");
        assertTrue(make.arguments().isEmpty());

        IrPropertyTarget staticCall = assertInstanceOf(IrPropertyTarget.class, unset.targets().get(3));
        assertEquals("Value", fixedName(staticCall.property()));
        IrStaticCall staticMake = assertInstanceOf(IrStaticCall.class,
                assertInstanceOf(IrExpressionWriteBase.class, staticCall.receiver()).expression());
        assertEquals("make", fixedName(staticMake.method()));
        assertTrue(staticMake.arguments().isEmpty());
        IrPropertyTarget staticPath = assertInstanceOf(IrPropertyTarget.class, unset.targets().get(4));
        assertEquals("value", fixedName(staticPath.property()));
        IrIndexTarget staticIndex = assertInstanceOf(IrIndexTarget.class, staticPath.receiver());
        assertInteger(staticIndex.index(), 1);
        assertEquals("items", fixedName(assertInstanceOf(IrStaticPropertyTarget.class, staticIndex.base()).property()));
    }

    // 禁止追加的删除模式只沿目标基底传递，索引与实参中的独立赋值仍可使用追加目标。
    @Test
    void keepsIndependentAppendAssignmentsInsideUnsetIndexesAndArguments() {
        IrUnset unset = assertInstanceOf(IrUnset.class,
                only("unset($a[$b[] = 1], factory($items[] = 2)[0], $object->make($more[] = 3)->value);"));
        assertEquals(3, unset.targets().size());
        IrIndexTarget indexed = assertInstanceOf(IrIndexTarget.class, unset.targets().getFirst());
        assertVariableTarget(indexed.base(), "a");
        assertAppendAssignment(indexed.index(), "b", 1);
        IrIndexTarget called = assertInstanceOf(IrIndexTarget.class, unset.targets().get(1));
        assertInteger(called.index(), 0);
        IrCall factory = assertCall(assertInstanceOf(IrExpressionWriteBase.class, called.base()).expression(), "factory", 1);
        assertAppendAssignment(factory.arguments().getFirst().expression(), "items", 2);
        IrPropertyTarget property = assertInstanceOf(IrPropertyTarget.class, unset.targets().get(2));
        IrMethodCall method = assertInstanceOf(IrMethodCall.class,
                assertInstanceOf(IrExpressionWriteBase.class, property.receiver()).expression());
        assertEquals("make", fixedName(method.method()));
        assertEquals(1, method.arguments().size());
        assertAppendAssignment(method.arguments().getFirst().expression(), "more", 3);
        assertAppendAssignment(expression("$after[] = 4"), "after", 4);
    }

    // 追加在删除链任意层均失败；独立调用及读取边界中的追加也不能成为删除目标。
    @Test
    void rejectsAppendAndUnsupportedUnsetTargets() {
        for (String target : List.of("$a[]", "$a[]->p", "($a[])[0]", "$a[][0]->p",
                "$a->items[]", "Box::$items[]->p", "factory()[]", "factory()[]->p",
                "$a[]->make()->p", "factory($a[])[0]", "$a[$b[]]", "$a->make($b[])->p",
                "factory()", "$a->make()", "Box::make()")) {
            assertRejectedBody("unset(" + target + ");");
        }
    }

    // 内建结构的操作数属于读取上下文，不能吞掉空下标读取错误。
    @Test
    void rejectsUnsupportedOperandsInsideEveryBuiltinExpression() {
        for (String code : List.of("isset($a[])", "empty($a[])", "(int) $a[]", "@$a[]", "print $a[]",
                "include $a[]", "include_once $a[]", "require $a[]", "require_once $a[]",
                "eval($a[])", "exit($a[])", "die($a[])")) {
            SyntaxExpression syntax = assertDoesNotThrow(() -> syntaxExpression(code), code);
            SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                    () -> SyntaxConverter.convertExpression(syntax), code);
            assertEquals("builtins.php", error.source().sourceId());
            assertFalse(error.fieldPath().isBlank(), code);
            assertFalse(error.reason().isBlank(), code);
        }
    }

    private static void assertAppendAssignment(IrExpression expression, String variable, long value) {
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression);
        IrIndexTarget target = assertInstanceOf(IrIndexTarget.class, assignment.target());
        assertVariableTarget(target.base(), variable);
        assertNull(target.index());
        assertInteger(assignment.value(), value);
    }

    private static void assertRejectedBody(String code) {
        SyntaxBody syntax = assertDoesNotThrow(() -> syntaxBody(code), code);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                () -> SyntaxConverter.convertBody(syntax), code);
        assertEquals("builtins.php", error.source().sourceId());
        assertFalse(error.fieldPath().isBlank(), code);
        assertFalse(error.reason().isBlank(), code);
    }

    private static IrCall assertCall(IrExpression expression, String name, int argumentCount) {
        IrCall call = assertInstanceOf(IrCall.class, expression);
        assertEquals(name, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().value());
        assertEquals(argumentCount, call.arguments().size());
        return call;
    }

    private static void assertVariable(IrExpression expression, String name) {
        assertEquals(name, fixedName(assertInstanceOf(IrVariable.class, expression).name()));
    }

    private static void assertVariableTarget(IrWriteBase base, String name) {
        assertEquals(name, fixedName(assertInstanceOf(IrVariableTarget.class, base).name()));
    }

    private static void assertInteger(IrExpression expression, long value) {
        assertEquals(value, assertInstanceOf(IrIntegerLiteral.class, expression).value());
    }

    private static void assertString(IrExpression expression, String value) {
        assertArrayEquals(value.getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringLiteral.class, expression).value().toByteArray());
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
        NodeProgram parsed = (NodeProgram) Main.parse("<?php function f() { " + code + " }");
        var statements = parsed.getStmts().getValue().getFirst().getFunction().getStmts().getValue();
        return new SyntaxBody(List.copyOf(statements), new SourceInfo("builtins.php", null));
    }

    private static IrExpression expression(String code) {
        return SyntaxConverter.convertExpression(syntaxExpression(code));
    }

    private static SyntaxExpression syntaxExpression(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php " + code + ";");
        NodeExpr syntax = parsed.getStmts().getValue().getFirst().getStmt().getExpression();
        return new SyntaxExpression(syntax, new SourceInfo("builtins.php", null));
    }

    private static String fixedName(IrAccessName name) {
        return assertInstanceOf(IrFixedName.class, name).value();
    }
}
