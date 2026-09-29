package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.NameReference;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SyntaxBody;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 switch 与异常处理的结构保留，不执行分支、匹配异常或校验控制流是否合法。 */
class ControlFlowConversionTest {

    // 普通／冒号外壳及其前导分号均可转换，case/default 的两种分隔符不产生不同 IR。
    @Test
    void normalizesAllSwitchShellsAndCaseSeparators() {
        for (String separator : List.of(":", ";")) {
            String cases = "case 1" + separator + " echo 2; break; DeFaUlT" + separator + " echo 3;";
            for (String code : switchForms(cases)) {
                IrSwitch statement = assertInstanceOf(IrSwitch.class, only(code), code);
                assertVariable(statement.condition(), "value");
                assertEquals(2, statement.cases().size());
                IrSwitchCase first = statement.cases().getFirst();
                assertInteger(first.condition(), 1);
                assertEquals(2, first.body().statements().size());
                assertEcho(first.body().statements().getFirst(), 2);
                assertNull(assertInstanceOf(IrBreak.class, first.body().statements().get(1)).levels());
                IrSwitchCase fallback = statement.cases().get(1);
                assertNull(fallback.condition());
                assertEquals(1, fallback.body().statements().size());
                assertEcho(fallback.body().statements().getFirst(), 3);
            }
        }
    }

    // 空 switch 保留为空分支列表，前导分号不是分支体内的空语句。
    @Test
    void preservesEmptySwitchesInEveryShell() {
        for (String code : switchForms("")) {
            IrSwitch statement = assertInstanceOf(IrSwitch.class, only(code), code);
            assertVariable(statement.condition(), "value");
            assertTrue(statement.cases().isEmpty(), code);
        }
    }

    // 连续空 case、位于中间的 default、重复条件和 case null 均保持原顺序与区别。
    @Test
    void preservesFallthroughCaseOrderAndDefaultPosition() {
        IrSwitch statement = assertInstanceOf(IrSwitch.class, only("""
                switch ($value) {
                    case 1:
                    case 2: echo 20;
                    default: ;
                    case null: echo 30;
                    case 1: echo 40;
                }
                """));
        List<IrSwitchCase> cases = statement.cases();
        assertEquals(5, cases.size());
        assertInteger(cases.getFirst().condition(), 1);
        assertTrue(cases.getFirst().body().statements().isEmpty());
        assertInteger(cases.get(1).condition(), 2);
        assertEquals(1, cases.get(1).body().statements().size());
        assertEcho(cases.get(1).body().statements().getFirst(), 20);
        assertNull(cases.get(2).condition());
        assertEquals(1, cases.get(2).body().statements().size());
        assertInstanceOf(IrEmpty.class, cases.get(2).body().statements().getFirst());
        IrLiteral nullCase = assertInstanceOf(IrLiteral.class, cases.get(3).condition());
        assertEquals(LiteralKind.NULL, nullCase.kind());
        assertEquals("null", nullCase.lexeme());
        assertEquals(1, cases.get(3).body().statements().size());
        assertEcho(cases.get(3).body().statements().getFirst(), 30);
        assertInteger(cases.get(4).condition(), 1);
        assertEquals(1, cases.get(4).body().statements().size());
        assertEcho(cases.get(4).body().statements().getFirst(), 40);
    }

    // 选择器和条件中的调用、赋值、自增各保留一次，不提前求值或合并重复条件。
    @Test
    void keepsSideEffectsInsideTheirOriginalSwitchExpressions() {
        IrSwitch statement = assertInstanceOf(IrSwitch.class, only("""
                switch (selectValue($saved = nextValue())) {
                    case nextCase($i++): $object->act();
                    case nextCase($i++): break;
                }
                """));
        IrCall selector = assertCall(statement.condition(), "selectValue", 1);
        IrAssignment saved = assertInstanceOf(IrAssignment.class, selector.arguments().getFirst().expression());
        assertEquals("saved", fixedName(assertInstanceOf(IrVariableTarget.class, saved.target()).name()));
        assertCall(saved.value(), "nextValue", 0);
        assertEquals(2, statement.cases().size());
        for (IrSwitchCase branch : statement.cases()) {
            IrCall condition = assertCall(branch.condition(), "nextCase", 1);
            IrUpdate update = assertInstanceOf(IrUpdate.class, condition.arguments().getFirst().expression());
            assertEquals(UpdateOperator.POST_INCREMENT, update.operator());
            assertEquals("i", fixedName(assertInstanceOf(IrVariableTarget.class, update.target()).name()));
            assertEquals(1, branch.body().statements().size());
        }
        IrMethodCall action = assertInstanceOf(IrMethodCall.class, assertInstanceOf(IrExpressionStatement.class,
                statement.cases().getFirst().body().statements().getFirst()).expression());
        assertVariable(action.receiver(), "object");
        assertEquals("act", fixedName(action.method()));
        assertTrue(action.arguments().isEmpty());
        assertInstanceOf(IrBreak.class, statement.cases().get(1).body().statements().getFirst());
    }

    // try-catch、try-finally 和三者组合统一为同一模型，但不补上源码未提供的分支。
    @Test
    void convertsCatchFinallyAndCombinedTryStatements() {
        IrTry withCatch = assertInstanceOf(IrTry.class,
                only("try { echo 1; } catch (Problem $e) { echo 2; }"));
        assertOnlyEcho(withCatch.body(), 1);
        assertEquals(1, withCatch.catches().size());
        assertEquals("e", withCatch.catches().getFirst().variableName());
        assertOnlyEcho(withCatch.catches().getFirst().body(), 2);
        assertNull(withCatch.finallyBlock());

        IrTry withFinally = assertInstanceOf(IrTry.class, only("try { echo 1; } finally { echo 3; }"));
        assertOnlyEcho(withFinally.body(), 1);
        assertTrue(withFinally.catches().isEmpty());
        assertOnlyEcho(withFinally.finallyBlock(), 3);

        IrTry combined = assertInstanceOf(IrTry.class,
                only("try { echo 1; } catch (Problem $error) { echo 2; } finally { echo 3; }"));
        assertOnlyEcho(combined.body(), 1);
        assertEquals(1, combined.catches().size());
        assertOnlyEcho(combined.catches().getFirst().body(), 2);
        assertOnlyEcho(combined.finallyBlock(), 3);
    }

    // 无 finally、显式空 finally 和含空语句的 finally 具有不同结构。
    @Test
    void distinguishesAbsentEmptyAndEmptyStatementFinallyBlocks() {
        IrTry absent = assertInstanceOf(IrTry.class, only("try {} catch (Problem $error) {}"));
        assertTrue(absent.body().statements().isEmpty());
        assertTrue(absent.catches().getFirst().body().statements().isEmpty());
        assertNull(absent.finallyBlock());

        IrTry empty = assertInstanceOf(IrTry.class, only("try {} finally {}"));
        assertNotNull(empty.finallyBlock());
        assertTrue(empty.finallyBlock().statements().isEmpty());
        IrTry emptyStatement = assertInstanceOf(IrTry.class, only("try {} finally { ; }"));
        assertNotNull(emptyStatement.finallyBlock());
        assertEquals(1, emptyStatement.finallyBlock().statements().size());
        assertInstanceOf(IrEmpty.class, emptyStatement.finallyBlock().statements().getFirst());
    }

    // 多 catch 与多异常类型保持顺序、限定形式及大小写，变量名使用不带 $ 的词法值。
    @Test
    void preservesCatchOrderTypeNamesAndVariableSpelling() {
        IrTry statement = assertInstanceOf(IrTry.class, only("""
                try {} catch (Basic|Vendor\\Failure|\\Root\\Error|namespace\\Local $Err) {
                    echo 1;
                } catch (Fallback $fallback) {
                    return $fallback;
                } catch (Missing|Missing $last) {}
                """));
        assertEquals(3, statement.catches().size());
        IrCatch first = statement.catches().getFirst();
        assertEquals("Err", first.variableName());
        assertEquals(List.of("Basic", "Vendor\\Failure", "\\Root\\Error", "namespace\\Local"),
                first.exceptionTypes().stream().map(NameReference::spelling).toList());
        assertEquals(List.of(NameForm.UNQUALIFIED, NameForm.QUALIFIED,
                        NameForm.FULLY_QUALIFIED, NameForm.NAMESPACE_RELATIVE),
                first.exceptionTypes().stream().map(NameReference::form).toList());
        assertOnlyEcho(first.body(), 1);
        IrCatch second = statement.catches().get(1);
        assertEquals("fallback", second.variableName());
        assertEquals(List.of("Fallback"), second.exceptionTypes().stream().map(NameReference::spelling).toList());
        assertEquals(1, second.body().statements().size());
        assertVariable(assertInstanceOf(IrReturn.class, second.body().statements().getFirst()).value(), "fallback");
        IrCatch third = statement.catches().get(2);
        assertEquals("last", third.variableName());
        assertEquals(List.of("Missing", "Missing"), third.exceptionTypes().stream().map(NameReference::spelling).toList());
        assertTrue(third.body().statements().isEmpty());
    }

    // throw 是语句，原有表达式能力可直接用于异常对象构造和变量重抛，不绑定异常类型。
    @Test
    void convertsThrowStatementsWithExistingExpressionModels() {
        List<IrStatement> statements = body("throw $error; throw new \\RuntimeException(message(), 3);").statements();
        assertEquals(2, statements.size());
        assertVariable(assertInstanceOf(IrThrow.class, statements.getFirst()).expression(), "error");
        IrNew exception = assertInstanceOf(IrNew.class, assertInstanceOf(IrThrow.class, statements.get(1)).expression());
        IrNamedClassReference reference = assertInstanceOf(IrNamedClassReference.class, exception.classReference());
        assertEquals("\\RuntimeException", reference.name().spelling());
        assertEquals(NameForm.FULLY_QUALIFIED, reference.name().form());
        assertEquals(2, exception.arguments().size());
        assertCall(exception.arguments().getFirst().expression(), "message", 0);
        assertInteger(exception.arguments().get(1).expression(), 3);
    }

    // 循环、switch 与 try 可以嵌套；跳转、返回及 finally 中的副作用保持各自归属。
    @Test
    void preservesNestedLoopsSwitchesExceptionsAndJumps() {
        IrWhile loop = assertInstanceOf(IrWhile.class, only("""
                while ($ready) {
                    switch ($state) {
                        case 1:
                            try {
                                if ($retry) continue 2;
                                throw new Problem($state);
                            } catch (Problem $error) {
                                return $error;
                            } finally {
                                cleanup();
                            }
                            break;
                        default:
                            break 2;
                    }
                    echo 9;
                }
                """));
        assertVariable(loop.condition(), "ready");
        assertEquals(2, loop.body().statements().size());
        IrSwitch selection = assertInstanceOf(IrSwitch.class, loop.body().statements().getFirst());
        assertVariable(selection.condition(), "state");
        assertEquals(2, selection.cases().size());
        IrBlock firstBody = selection.cases().getFirst().body();
        assertEquals(2, firstBody.statements().size());
        IrTry attempt = assertInstanceOf(IrTry.class, firstBody.statements().getFirst());
        assertEquals(2, attempt.body().statements().size());
        IrIf retry = assertInstanceOf(IrIf.class, attempt.body().statements().getFirst());
        assertVariable(retry.branches().getFirst().condition(), "retry");
        IrContinue next = assertInstanceOf(IrContinue.class, retry.branches().getFirst().body().statements().getFirst());
        assertInteger(next.levels(), 2);
        IrNew failure = assertInstanceOf(IrNew.class,
                assertInstanceOf(IrThrow.class, attempt.body().statements().get(1)).expression());
        assertEquals("Problem", assertInstanceOf(IrNamedClassReference.class, failure.classReference()).name().spelling());
        assertEquals(1, failure.arguments().size());
        assertVariable(failure.arguments().getFirst().expression(), "state");
        assertEquals(1, attempt.catches().size());
        assertVariable(assertInstanceOf(IrReturn.class,
                attempt.catches().getFirst().body().statements().getFirst()).value(), "error");
        assertNotNull(attempt.finallyBlock());
        assertEquals(1, attempt.finallyBlock().statements().size());
        assertCall(assertInstanceOf(IrExpressionStatement.class,
                attempt.finallyBlock().statements().getFirst()).expression(), "cleanup", 0);
        assertNull(assertInstanceOf(IrBreak.class, firstBody.statements().get(1)).levels());
        assertNull(selection.cases().get(1).condition());
        assertInteger(assertInstanceOf(IrBreak.class,
                selection.cases().get(1).body().statements().getFirst()).levels(), 2);
        assertEcho(loop.body().statements().get(1), 9);
    }

    // 内层 catch/finally 不应挂到外层 try；内层 switch 也保留独立的分支列表。
    @Test
    void keepsNestedTryAndSwitchOwnership() {
        IrTry outer = assertInstanceOf(IrTry.class, only("""
                try { work(); } catch (OuterError $outer) {
                    try { throw $outer; } catch (InnerError $inner) { throw $inner; }
                } finally {
                    switch ($finished) {
                        default: switch ($detail) { case 1: return; }
                    }
                }
                """));
        assertEquals(1, outer.body().statements().size());
        assertCall(assertInstanceOf(IrExpressionStatement.class, outer.body().statements().getFirst()).expression(), "work", 0);
        assertEquals(1, outer.catches().size());
        IrCatch outerCatch = outer.catches().getFirst();
        assertEquals("outer", outerCatch.variableName());
        assertEquals(1, outerCatch.body().statements().size());
        IrTry inner = assertInstanceOf(IrTry.class, outerCatch.body().statements().getFirst());
        assertNull(inner.finallyBlock());
        assertEquals(1, inner.body().statements().size());
        assertVariable(assertInstanceOf(IrThrow.class, inner.body().statements().getFirst()).expression(), "outer");
        assertEquals(1, inner.catches().size());
        assertEquals("inner", inner.catches().getFirst().variableName());
        assertVariable(assertInstanceOf(IrThrow.class,
                inner.catches().getFirst().body().statements().getFirst()).expression(), "inner");
        assertNotNull(outer.finallyBlock());
        assertEquals(1, outer.finallyBlock().statements().size());
        IrSwitch first = assertInstanceOf(IrSwitch.class, outer.finallyBlock().statements().getFirst());
        assertVariable(first.condition(), "finished");
        assertEquals(1, first.cases().size());
        assertNull(first.cases().getFirst().condition());
        IrSwitch second = assertInstanceOf(IrSwitch.class, first.cases().getFirst().body().statements().getFirst());
        assertVariable(second.condition(), "detail");
        assertEquals(1, second.cases().size());
        assertInteger(second.cases().getFirst().condition(), 1);
        assertNull(assertInstanceOf(IrReturn.class, second.cases().getFirst().body().statements().getFirst()).value());
    }

    // 文法已接受的裸 try 和重复 default 只保留结构，不引入额外 PHP 语义合法性校验。
    @Test
    void preservesParserAcceptedStructuresWithoutContextValidation() {
        IrTry bare = assertInstanceOf(IrTry.class, only("try { echo 1; }"));
        assertOnlyEcho(bare.body(), 1);
        assertTrue(bare.catches().isEmpty());
        assertNull(bare.finallyBlock());
        IrSwitch repeated = assertInstanceOf(IrSwitch.class,
                only("switch ($value) { default: echo 1; default: echo 2; }"));
        assertEquals(2, repeated.cases().size());
        for (int i = 0; i < 2; i++) {
            assertNull(repeated.cases().get(i).condition());
            assertOnlyEcho(repeated.cases().get(i).body(), i + 1);
        }
    }

    // default 分支中的 global 保留在原分支内，不能被当成文件级声明提升。
    @Test
    void preservesGlobalStatementsInsideDefaultBranches() {
        IrSwitch statement = assertInstanceOf(IrSwitch.class,
                only("switch ($value) { default: global $value; }"));
        assertEquals(1, statement.cases().size());
        IrSwitchCase branch = statement.cases().getFirst();
        assertNull(branch.condition());
        assertEquals(1, branch.body().statements().size());
        IrGlobal global = assertInstanceOf(IrGlobal.class, branch.body().statements().getFirst());
        assertEquals(1, global.variables().size());
        assertEquals("value", fixedName(global.variables().getFirst().name()));
    }

    // 已支持外壳不会吞掉内部未知语法，分支、处理器和 finally 中的命名声明仍必须明确失败。
    @Test
    void rejectsUnsupportedContentsInEveryNewStatementPosition() {
        for (String code : List.of(
                "switch ((`echo sentinel`)) {}",
                "switch ($value) { case (`echo sentinel`): ; }",
                "switch ($value) { case 1: (`echo sentinel`); }",
                "switch ($value) { default: function nestedDefault() {} }",
                "switch ($value) { case 1: function nested() {} }",
                "try { (`echo sentinel`); } finally {}",
                "try {} catch (Problem $error) { (`echo sentinel`); }",
                "try {} finally { (`echo sentinel`); }",
                "try { class Nested {} } finally {}",
                "try {} catch (Problem $error) { function nested() {} }",
                "try {} finally { function nested() {} }",
                "throw (`echo sentinel`);")) {
            SyntaxBody syntax = assertDoesNotThrow(() -> syntaxBody(code), code);
            var error = assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(syntax), code);
            assertEquals("control-flow.php", error.source().sourceId());
            assertFalse(error.fieldPath().isBlank(), code);
            assertFalse(error.reason().isBlank(), code);
        }
    }

    private static List<String> switchForms(String cases) {
        return List.of("switch ($value) { " + cases + " }",
                "switch ($value) { ; " + cases + " }",
                "switch ($value): " + cases + " endswitch;",
                "switch ($value): ; " + cases + " endswitch;");
    }

    private static void assertOnlyEcho(IrBlock block, long expected) {
        assertNotNull(block);
        assertEquals(1, block.statements().size());
        assertEcho(block.statements().getFirst(), expected);
    }

    private static void assertEcho(IrStatement statement, long expected) {
        IrEcho echo = assertInstanceOf(IrEcho.class, statement);
        assertEquals(1, echo.expressions().size());
        assertInteger(echo.expressions().getFirst(), expected);
    }

    private static IrCall assertCall(IrExpression expression, String expectedName, int argumentCount) {
        IrCall call = assertInstanceOf(IrCall.class, expression);
        assertEquals(expectedName, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
        assertEquals(argumentCount, call.arguments().size());
        return call;
    }

    private static void assertVariable(IrExpression expression, String expected) {
        assertEquals(expected, fixedName(assertInstanceOf(IrVariable.class, expression).name()));
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
        return new SyntaxBody(List.copyOf(statements), new SourceInfo("control-flow.php", null));
    }

    private static String fixedName(IrAccessName name) {
        return assertInstanceOf(IrFixedName.class, name).value();
    }
}
