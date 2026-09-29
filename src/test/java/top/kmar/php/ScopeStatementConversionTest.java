package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SyntaxBody;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证作用域相关语句的结构保留，不执行绑定、初始化、指令或跳转。 */
class ScopeStatementConversionTest {

    // global 保留声明顺序、重复项和变量大小写，不展开为普通引用赋值。
    @Test
    void preservesGlobalVariableOrderDuplicatesAndCase() {
        IrGlobal statement = global("global $value, $Value, $value;");
        assertEquals(3, statement.variables().size());
        assertVariableTarget(statement.variables().get(0), "value");
        assertVariableTarget(statement.variables().get(1), "Value");
        assertVariableTarget(statement.variables().get(2), "value");
    }

    // 多层变量变量和花括号中的表达式保留各层读取，不预先求出最终变量名。
    @Test
    void preservesNestedAndComputedGlobalNames() {
        IrGlobal statement = global("global $$name, $$$name, ${pickName()}, ${$names[0]};");
        assertVariable(computedName(statement.variables().get(0)), "name");
        IrVariable nested = assertInstanceOf(IrVariable.class, computedName(statement.variables().get(1)));
        assertVariable(assertInstanceOf(IrComputedName.class, nested.name()).expression(), "name");
        assertCall(computedName(statement.variables().get(2)), "pickName");
        IrIndex index = assertInstanceOf(IrIndex.class, computedName(statement.variables().get(3)));
        assertVariable(index.base(), "names");
        assertInteger(index.index(), 0);
    }

    // global 的目标可写，但计算目标名称时仍不能读取缺省下标。
    @Test
    void rejectsAppendReadsInsideGlobalNameExpressions() {
        for (String code : List.of("global ${$names[]};", "global ${pickName($arguments[])};",
                "global ${$objects[]->name()};", "global ${$names[$indexes[]]};")) {
            assertRejectedBody(code);
        }
    }

    // 名称表达式中的独立赋值仍可追加写入，且调用与赋值只保留一个节点。
    @Test
    void preservesIndependentAppendAssignmentsInsideGlobalNames() {
        IrGlobal statement = global("global ${$names[] = nextName()}, ${pickName($arguments[] = nextName())};");
        assertAppendAssignment(computedName(statement.variables().get(0)), "names", "nextName");
        IrCall call = assertCall(computedName(statement.variables().get(1)), "pickName");
        assertEquals(1, call.arguments().size());
        assertAppendAssignment(call.arguments().getFirst().expression(), "arguments", "nextName");
    }

    // 本层不判断特殊变量能否在当前 PHP 作用域声明为 global 或 static。
    @Test
    void keepsSpecialVariableValidationOutsideConversion() {
        IrGlobal global = global("global $this, $GLOBALS;");
        assertVariableTarget(global.variables().get(0), "this");
        assertVariableTarget(global.variables().get(1), "GLOBALS");
        IrStaticVariables statement = statics("static $this, $GLOBALS = null;");
        assertEquals("this", statement.variables().get(0).name());
        assertEquals("GLOBALS", statement.variables().get(1).name());
    }

    // static 的缺省初始化与显式 PHP null 不合并，重复变量仍逐项保留。
    @Test
    void distinguishesAbsentStaticInitializersFromExplicitNull() {
        IrStaticVariables statement = statics("static $value, $value = NuLl, $Value = 2;");
        assertEquals(3, statement.variables().size());
        assertEquals("value", statement.variables().get(0).name());
        assertNull(statement.variables().get(0).initializer());
        assertEquals("value", statement.variables().get(1).name());
        IrLiteral explicitNull = assertInstanceOf(IrLiteral.class, statement.variables().get(1).initializer());
        assertEquals(LiteralKind.NULL, explicitNull.kind());
        assertEquals("NuLl", explicitNull.lexeme());
        assertEquals("Value", statement.variables().get(2).name());
        assertInteger(statement.variables().get(2).initializer(), 2);
    }

    // static 初值复用所有已支持表达式，不做常量折叠或 PHP 常量表达式合法性检查。
    @Test
    void preservesStaticInitializersWithoutEvaluatingOrRestrictingThem() {
        IrStaticVariables statement = statics("""
                static $number = 1 + 2, $text = 'hello', $array = [true, null],
                       $constant = LIMIT, $read = $outer, $call = nextValue(),
                       $object = new Box, $saved = $items[] = nextValue(),
                       $closure = function() { global $shared; };
                """);
        assertEquals(9, statement.variables().size());
        IrBinary sum = assertInstanceOf(IrBinary.class, statement.variables().getFirst().initializer());
        assertInteger(sum.left(), 1);
        assertInteger(sum.right(), 2);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringLiteral.class, statement.variables().get(1).initializer())
                        .value().toByteArray());
        assertEquals(2, assertInstanceOf(IrArrayLiteral.class,
                statement.variables().get(2).initializer()).entries().size());
        assertEquals("LIMIT", assertInstanceOf(IrConstantReference.class,
                statement.variables().get(3).initializer()).name().spelling());
        assertVariable(statement.variables().get(4).initializer(), "outer");
        assertCall(statement.variables().get(5).initializer(), "nextValue");
        assertInstanceOf(IrNew.class, statement.variables().get(6).initializer());
        assertAppendAssignment(statement.variables().get(7).initializer(), "items", "nextValue");
        IrClosure closure = assertInstanceOf(IrClosure.class, statement.variables().get(8).initializer());
        assertVariableTarget(assertInstanceOf(IrGlobal.class,
                closure.body().statements().getFirst()).variables().getFirst(), "shared");
    }

    // static 初始化本身按读取转换，不能因声明会绑定变量而允许读取追加下标。
    @Test
    void rejectsAppendReadsInsideStaticInitializers() {
        for (String code : List.of("static $value = $items[];", "static $value = nextValue($items[]);",
                "static $value = [$items[]];", "static $value = $items[$indexes[]];")) {
            assertRejectedBody(code);
        }
    }

    // 只有直接分号式 declare 没有主体，花括号与冒号的空主体都是真实的空块。
    @Test
    void distinguishesUnscopedDeclarationsFromExplicitEmptyBodies() {
        assertNull(declaration("declare(ticks=1);").body());
        for (String code : List.of("declare(ticks=1) {}", "declare(ticks=1): enddeclare;")) {
            IrBlock declaredBody = declaration(code).body();
            assertNotNull(declaredBody, code);
            assertTrue(declaredBody.statements().isEmpty(), code);
        }
        for (String code : List.of("declare(ticks=1) {;}", "declare(ticks=1): ; enddeclare;")) {
            IrBlock declaredBody = declaration(code).body();
            assertNotNull(declaredBody, code);
            assertEquals(1, declaredBody.statements().size(), code);
            assertInstanceOf(IrEmpty.class, declaredBody.statements().getFirst(), code);
        }
    }

    // 单语句、普通块与冒号体统一为 IrBlock，但内部顺序不改变。
    @Test
    void normalizesEveryScopedDeclareBodyToABlock() {
        for (String code : List.of("declare(ticks=1) echo 2;", "declare(ticks=1) { echo 2; }",
                "declare(ticks=1): echo 2; enddeclare;")) {
            IrBlock declaredBody = declaration(code).body();
            assertNotNull(declaredBody, code);
            assertEquals(1, declaredBody.statements().size(), code);
            assertEcho(declaredBody.statements().getFirst(), 2);
        }
    }

    // 分号式 declare 不收拢后续语句，也不把指令复制到后续节点上。
    @Test
    void keepsStatementsFollowingUnscopedDeclarationsInPlace() {
        IrBlock converted = body("declare(ticks=1); echo 2; declare(ticks=3); echo 4;");
        assertEquals(4, converted.statements().size());
        assertNull(assertInstanceOf(IrDeclare.class, converted.statements().get(0)).body());
        assertEcho(converted.statements().get(1), 2);
        assertNull(assertInstanceOf(IrDeclare.class, converted.statements().get(2)).body());
        assertEcho(converted.statements().get(3), 4);
    }

    // 嵌套 declare 保留所属块，不把内层分号式指令提升或扩散到外层。
    @Test
    void preservesNestedDeclareBoundariesAndFollowingStatements() {
        IrBlock converted = body("""
                declare(ticks=1) {
                    declare(ticks=2);
                    declare(ticks=3): echo 4; enddeclare;
                    echo 5;
                }
                echo 6;
                """);
        assertEquals(2, converted.statements().size());
        IrBlock outer = assertInstanceOf(IrDeclare.class, converted.statements().getFirst()).body();
        assertNotNull(outer);
        assertEquals(3, outer.statements().size());
        assertNull(assertInstanceOf(IrDeclare.class, outer.statements().get(0)).body());
        IrBlock inner = assertInstanceOf(IrDeclare.class, outer.statements().get(1)).body();
        assertNotNull(inner);
        assertEquals(1, inner.statements().size());
        assertEcho(inner.statements().getFirst(), 4);
        assertEcho(outer.statements().get(2), 5);
        assertEcho(converted.statements().get(1), 6);
    }

    // 多指令按源码顺序保存，不合并重复项、不规范化拼写，也不丢弃未知指令。
    @Test
    void preservesDirectiveOrderDuplicatesUnknownNamesAndSpelling() {
        IrDeclare declaration = declaration("declare(TiCkS=1, custom='on', TiCkS=2, ticks=3, CUSTOM=false);");
        assertEquals(List.of("TiCkS", "custom", "TiCkS", "ticks", "CUSTOM"),
                declaration.directives().stream().map(IrDeclareDirective::name).toList());
        assertInteger(declaration.directives().get(0).value(), 1);
        assertArrayEquals("on".getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringLiteral.class, declaration.directives().get(1).value())
                        .value().toByteArray());
        assertInteger(declaration.directives().get(2).value(), 2);
        assertInteger(declaration.directives().get(3).value(), 3);
        assertEquals(LiteralKind.BOOLEAN,
                assertInstanceOf(IrLiteral.class, declaration.directives().get(4).value()).kind());
    }

    // 指令值按普通表达式保存，既不执行调用，也不要求值已经是 PHP 字面量。
    @Test
    void preservesGeneralExpressionsInsideDeclareDirectives() {
        IrDeclare declaration = declaration("""
                declare(first=nextValue(), second=$value, third=1 + 2, fourth=[null],
                        fifth=$items[] = nextValue(), sixth=function() { static $count = 1; });
                """);
        assertEquals(6, declaration.directives().size());
        assertCall(declaration.directives().get(0).value(), "nextValue");
        assertVariable(declaration.directives().get(1).value(), "value");
        assertInstanceOf(IrBinary.class, declaration.directives().get(2).value());
        assertInstanceOf(IrArrayLiteral.class, declaration.directives().get(3).value());
        assertAppendAssignment(declaration.directives().get(4).value(), "items", "nextValue");
        IrClosure closure = assertInstanceOf(IrClosure.class, declaration.directives().get(5).value());
        assertInteger(assertInstanceOf(IrStaticVariables.class, closure.body().statements().getFirst())
                .variables().getFirst().initializer(), 1);
    }

    // 不校验 strict_types 的位置、主体或值，也不应用 encoding 对解析器的副作用。
    @Test
    void keepsPhpDirectiveSemanticValidationOutsideConversion() {
        IrBlock converted = body("""
                echo 1;
                declare(STRICT_TYPES=3) {}
                declare(encoding=$encoding): echo 2; enddeclare;
                declare(ticks=0);
                """);
        assertEquals(4, converted.statements().size());
        assertEcho(converted.statements().get(0), 1);
        IrDeclare strict = assertInstanceOf(IrDeclare.class, converted.statements().get(1));
        assertEquals("STRICT_TYPES", strict.directives().getFirst().name());
        assertInteger(strict.directives().getFirst().value(), 3);
        assertNotNull(strict.body());
        assertTrue(strict.body().statements().isEmpty());
        IrDeclare encoding = assertInstanceOf(IrDeclare.class, converted.statements().get(2));
        assertVariable(encoding.directives().getFirst().value(), "encoding");
        assertNotNull(encoding.body());
        assertEcho(encoding.body().statements().getFirst(), 2);
        assertNull(assertInstanceOf(IrDeclare.class, converted.statements().get(3)).body());
    }

    // 指令表达式和主体中的读取错误继续向外传播，不把追加读取误当可写目标。
    @Test
    void rejectsAppendReadsInsideDeclareValuesAndBodies() {
        for (String code : List.of("declare(ticks=$items[]);", "declare(custom=nextValue($items[]));",
                "declare(ticks=1) echo $items[];", "declare(ticks=1): echo $items[]; enddeclare;")) {
            assertRejectedBody(code);
        }
    }

    // 前向、后向、未定义、重复及大小写不同的标签均原样保留，不提前绑定或去重。
    @Test
    void preservesGotoAndLabelOrderWithoutResolvingTargets() {
        IrBlock converted = body("goto Later; Start: goto Start; Later: goto later; Later: goto Missing;");
        assertEquals(7, converted.statements().size());
        assertEquals("Later", assertInstanceOf(IrGoto.class, converted.statements().get(0)).label());
        assertEquals("Start", assertInstanceOf(IrLabel.class, converted.statements().get(1)).name());
        assertEquals("Start", assertInstanceOf(IrGoto.class, converted.statements().get(2)).label());
        assertEquals("Later", assertInstanceOf(IrLabel.class, converted.statements().get(3)).name());
        assertEquals("later", assertInstanceOf(IrGoto.class, converted.statements().get(4)).label());
        assertEquals("Later", assertInstanceOf(IrLabel.class, converted.statements().get(5)).name());
        assertEquals("Missing", assertInstanceOf(IrGoto.class, converted.statements().get(6)).label());
    }

    // 跳入循环、离开循环等合法性属于后续控制流分析，本层只保留所在语句块。
    @Test
    void keepsCrossControlStructureGotoValidationOutsideConversion() {
        IrBlock converted = body("goto Inside; while ($condition) { Inside: goto Outside; } Outside: ;");
        assertEquals(4, converted.statements().size());
        assertEquals("Inside", assertInstanceOf(IrGoto.class, converted.statements().get(0)).label());
        IrWhile loop = assertInstanceOf(IrWhile.class, converted.statements().get(1));
        assertEquals(2, loop.body().statements().size());
        assertEquals("Inside", assertInstanceOf(IrLabel.class, loop.body().statements().get(0)).name());
        assertEquals("Outside", assertInstanceOf(IrGoto.class, loop.body().statements().get(1)).label());
        assertEquals("Outside", assertInstanceOf(IrLabel.class, converted.statements().get(2)).name());
        assertInstanceOf(IrEmpty.class, converted.statements().get(3));
    }

    // 闭包体拥有自己的完整语句树，global/static/declare/标签不会泄漏到外层列表。
    @Test
    void preservesScopeStatementsInsideNestedClosures() {
        IrBlock converted = body("""
                $closure = function() {
                    global $shared;
                    static $count = 1;
                    declare(ticks=1) { goto Done; Done: ; }
                    return function() { global $$name; static $inner; };
                };
                echo 2;
                """);
        assertEquals(2, converted.statements().size());
        IrAssignment assignment = assertInstanceOf(IrAssignment.class,
                assertInstanceOf(IrExpressionStatement.class, converted.statements().getFirst()).expression());
        IrClosure closure = assertInstanceOf(IrClosure.class, assignment.value());
        assertEquals(4, closure.body().statements().size());
        assertInstanceOf(IrGlobal.class, closure.body().statements().get(0));
        assertInstanceOf(IrStaticVariables.class, closure.body().statements().get(1));
        IrBlock declaration = assertInstanceOf(IrDeclare.class, closure.body().statements().get(2)).body();
        assertNotNull(declaration);
        assertEquals(3, declaration.statements().size());
        assertInstanceOf(IrGoto.class, declaration.statements().get(0));
        assertInstanceOf(IrLabel.class, declaration.statements().get(1));
        assertInstanceOf(IrEmpty.class, declaration.statements().get(2));
        IrClosure inner = assertInstanceOf(IrClosure.class,
                assertInstanceOf(IrReturn.class, closure.body().statements().get(3)).value());
        assertEquals(2, inner.body().statements().size());
        assertVariable(computedName(assertInstanceOf(IrGlobal.class,
                inner.body().statements().get(0)).variables().getFirst()), "name");
        assertNull(assertInstanceOf(IrStaticVariables.class,
                inner.body().statements().get(1)).variables().getFirst().initializer());
        assertEcho(converted.statements().get(1), 2);
    }

    // 现有条件、foreach、异常和 switch 容器都递归接受新增语句，不改变容器形状。
    @Test
    void convertsScopeStatementsInsideExistingStatementContainers() {
        IrBlock converted = body("""
                if ($condition): global $conditional; else: static $fallback; endif;
                foreach ($items as &$item) { global $shared; static $index = 0; }
                try { static $attempt = 1; }
                catch (Exception $error) { declare(custom=1); }
                finally { goto Done; Done: ; }
                switch ($value) { default: declare(ticks=1) global $selected; }
                """);
        assertEquals(4, converted.statements().size());
        IrIf condition = assertInstanceOf(IrIf.class, converted.statements().getFirst());
        assertInstanceOf(IrGlobal.class, condition.branches().getFirst().body().statements().getFirst());
        assertNotNull(condition.elseBlock());
        assertInstanceOf(IrStaticVariables.class, condition.elseBlock().statements().getFirst());
        IrForeach loop = assertInstanceOf(IrForeach.class, converted.statements().get(1));
        assertTrue(loop.byReference());
        assertInstanceOf(IrGlobal.class, loop.body().statements().get(0));
        assertInstanceOf(IrStaticVariables.class, loop.body().statements().get(1));
        IrTry attempt = assertInstanceOf(IrTry.class, converted.statements().get(2));
        assertInstanceOf(IrStaticVariables.class, attempt.body().statements().getFirst());
        assertInstanceOf(IrDeclare.class, attempt.catches().getFirst().body().statements().getFirst());
        assertNotNull(attempt.finallyBlock());
        assertInstanceOf(IrGoto.class, attempt.finallyBlock().statements().get(0));
        assertInstanceOf(IrLabel.class, attempt.finallyBlock().statements().get(1));
        IrSwitch selection = assertInstanceOf(IrSwitch.class, converted.statements().get(3));
        IrDeclare declaration = assertInstanceOf(IrDeclare.class,
                selection.cases().getFirst().body().statements().getFirst());
        assertNotNull(declaration.body());
        assertInstanceOf(IrGlobal.class, declaration.body().statements().getFirst());
    }

    // 新增入口在顶层语句片段同样可用，不擅自要求 global/static 一定处于函数内。
    @Test
    void convertsTopLevelScopeStatementFragmentsWithoutContextValidation() {
        NodeProgram parsed = (NodeProgram) Main.parse("""
                <?php global $shared; static $count = 1; declare(strict_types=1); goto Done; Done:
                """);
        IrBlock converted = SyntaxConverter.convertBody(new SyntaxBody(
                List.copyOf(parsed.getStmts().getValue()), new SourceInfo("scope.php", null)));
        assertEquals(5, converted.statements().size());
        assertInstanceOf(IrGlobal.class, converted.statements().get(0));
        assertInstanceOf(IrStaticVariables.class, converted.statements().get(1));
        assertNull(assertInstanceOf(IrDeclare.class, converted.statements().get(2)).body());
        assertInstanceOf(IrGoto.class, converted.statements().get(3));
        assertInstanceOf(IrLabel.class, converted.statements().get(4));
    }

    // 新语句的值、动态名称和三种主体仍必须递归失败，不能吞掉嵌套命名声明或反引号表达式。
    @Test
    void propagatesUnsupportedSubtreesThroughEveryNewContainer() {
        for (String code : List.of(
                "global ${(`echo sentinel`)};",
                "global ${function() { function nested() {} }};",
                "static $value = `echo sentinel`;",
                "static $value = function() { class Nested {} };",
                "declare(custom=(`echo sentinel`));",
                "declare(custom=function() { function nested() {} });",
                "declare(ticks=1) (`echo sentinel`);",
                "declare(ticks=1) { function nested() {} }",
                "declare(ticks=1): class Nested {} enddeclare;")) {
            assertRejectedBody(code);
        }
    }

    // 新节点及独立的变量项、指令项保留来源，不使用外层函数或空来源替代。
    @Test
    void preservesSourceInformationOnStatementsAndTheirItems() {
        IrBlock converted = body("global $value; static $count = 1; declare(ticks=2) {} goto Done; Done:");
        for (IrStatement statement : converted.statements()) assertKnownSource(statement.source());
        IrGlobal global = assertInstanceOf(IrGlobal.class, converted.statements().getFirst());
        assertKnownSource(global.variables().getFirst().source());
        assertKnownSource(global.variables().getFirst().name().source());
        IrStaticVariable variable = assertInstanceOf(IrStaticVariables.class,
                converted.statements().get(1)).variables().getFirst();
        assertKnownSource(variable.source());
        assertNotNull(variable.initializer());
        assertKnownSource(variable.initializer().source());
        IrDeclare declaration = assertInstanceOf(IrDeclare.class, converted.statements().get(2));
        assertKnownSource(declaration.directives().getFirst().source());
        assertKnownSource(declaration.directives().getFirst().value().source());
        assertNotNull(declaration.body());
        assertKnownSource(declaration.body().source());
    }

    private static IrGlobal global(String code) {
        return assertInstanceOf(IrGlobal.class, only(code));
    }

    private static IrStaticVariables statics(String code) {
        return assertInstanceOf(IrStaticVariables.class, only(code));
    }

    private static IrDeclare declaration(String code) {
        return assertInstanceOf(IrDeclare.class, only(code));
    }

    private static IrStatement only(String code) {
        IrBlock converted = body(code);
        assertEquals(1, converted.statements().size(), code);
        return converted.statements().getFirst();
    }

    private static IrBlock body(String code) {
        return SyntaxConverter.convertBody(syntaxBody(code));
    }

    private static SyntaxBody syntaxBody(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php function testBody() { " + code + " }");
        var statements = parsed.getStmts().getValue().getFirst().getFunction().getStmts().getValue();
        return new SyntaxBody(List.copyOf(statements), new SourceInfo("scope.php", null));
    }

    private static IrExpression computedName(IrVariableTarget target) {
        return assertInstanceOf(IrComputedName.class, target.name()).expression();
    }

    private static void assertVariableTarget(Object target, String name) {
        assertEquals(name, assertInstanceOf(IrFixedName.class,
                assertInstanceOf(IrVariableTarget.class, target).name()).value());
    }

    private static void assertVariable(IrExpression expression, String name) {
        assertEquals(name, assertInstanceOf(IrFixedName.class,
                assertInstanceOf(IrVariable.class, expression).name()).value());
    }

    private static void assertInteger(IrExpression expression, long value) {
        assertEquals(value, assertInstanceOf(IrIntegerLiteral.class, expression).value());
    }

    private static void assertEcho(IrStatement statement, long value) {
        IrEcho echo = assertInstanceOf(IrEcho.class, statement);
        assertEquals(1, echo.expressions().size());
        assertInteger(echo.expressions().getFirst(), value);
    }

    private static IrCall assertCall(IrExpression expression, String name) {
        IrCall call = assertInstanceOf(IrCall.class, expression);
        assertEquals(name, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
        return call;
    }

    private static void assertAppendAssignment(IrExpression expression, String target, String call) {
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression);
        IrIndexTarget index = assertInstanceOf(IrIndexTarget.class, assignment.target());
        assertNull(index.index());
        assertVariableTarget(index.base(), target);
        assertTrue(assertCall(assignment.value(), call).arguments().isEmpty());
    }

    private static void assertKnownSource(SourceInfo source) {
        assertEquals("scope.php", source.sourceId());
        assertNotNull(source.range());
    }

    private static void assertRejectedBody(String code) {
        SyntaxBody syntax = assertDoesNotThrow(() -> syntaxBody(code), code);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                () -> SyntaxConverter.convertBody(syntax), code);
        assertEquals("scope.php", error.source().sourceId(), code);
        assertFalse(error.fieldPath().isBlank(), code);
        assertFalse(error.reason().isBlank(), code);
    }
}
