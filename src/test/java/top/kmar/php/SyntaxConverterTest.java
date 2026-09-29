package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 从真实 PHP 语法树验证基础语句／表达式转换，解码数值但不执行表达式或绑定名称。 */
class SyntaxConverterTest {

    // 不同进制的整数与十进制浮点数统一解码，数值节点不再依赖原文表示。
    @Test
    void decodesNumericLiteralsIntoTypedValues() {
        //noinspection OctalInteger
        Map<String, Long> integers = Map.ofEntries(
                Map.entry("0", 0L), Map.entry("42", 42L), Map.entry("077", 63L),
                Map.entry("0x2A", 42L), Map.entry("0b101010", 42L),
                Map.entry("9223372036854775807", Long.MAX_VALUE),
                Map.entry("0100000000000000000000", 0100000000000000000000L),
                Map.entry("0" + "7".repeat(21), Long.MAX_VALUE),
                Map.entry("0x00007FFFFFFFFFFFFFFF", Long.MAX_VALUE));
        integers.forEach((lexeme, value) -> assertInteger(expression(lexeme), value));
        Map<String, Double> floats = Map.of(
                "1.0", 1.0, ".5", 0.5, "1e3", 1000.0, "1e309", Double.POSITIVE_INFINITY,
                "9223372036854775808", 0x1.0p63, "0x10000000000000000", 0x1.0p64);
        floats.forEach((lexeme, value) ->
                assertEquals(value.doubleValue(), assertInstanceOf(IrFloatLiteral.class, expression(lexeme)).value(), lexeme));
    }

    // 普通字符串统一解码为字节值，前缀和引号不再作为 IR 值保存。
    @Test
    void decodesStringLiteralValues() {
        Map.of("'text'", "text", "\"text\"", "text", "'a\\'b'", "a'b", "\"a\\n\\t\"", "a\n\t",
                "b'raw'", "raw", "B\"raw\"", "raw")
                .forEach((code, value) -> assertString(expression(code), value));
    }

    // 布尔值和 null 不区分大小写；只有非限定名或全局单名称可以转为字面量。
    @Test
    void classifiesReservedConstantNamesWithoutResolvingQualifiedNames() {
        for (String value : List.of("true", "FALSE", "TrUe", "\\TRUE", "\\false")) {
            assertLiteral(expression(value), LiteralKind.BOOLEAN, value);
        }
        for (String value : List.of("null", "NuLl", "\\NULL")) {
            assertLiteral(expression(value), LiteralKind.NULL, value);
        }
        assertConstant("SOME_VALUE", NameForm.UNQUALIFIED);
        assertConstant("Some\\Value", NameForm.QUALIFIED);
        assertConstant("\\Some\\Value", NameForm.FULLY_QUALIFIED);
        assertConstant("namespace\\Value", NameForm.NAMESPACE_RELATIVE);
        assertConstant("Some\\true", NameForm.QUALIFIED);
        assertConstant("\\Some\\null", NameForm.FULLY_QUALIFIED);
        assertConstant("namespace\\false", NameForm.NAMESPACE_RELATIVE);
    }

    // 普通变量读取与赋值目标是不同模型；连续赋值保持右结合及原变量名。
    @Test
    void distinguishesVariableReadsFromAssignmentTargets() {
        assertEquals("value", fixedName(assertInstanceOf(IrVariable.class, expression("$value")).name()));
        IrAssignment outer = assertInstanceOf(IrAssignment.class, expression("$a = $b = 3"));
        assertEquals("a", fixedName(assertInstanceOf(IrVariableTarget.class, outer.target()).name()));
        IrAssignment inner = assertInstanceOf(IrAssignment.class, outer.value());
        assertEquals("b", fixedName(assertInstanceOf(IrVariableTarget.class, inner.target()).name()));
        assertInteger(inner.value(), 3);
    }

    // 前缀运算符独立建模，不把负数折叠成数值字面量，也不丢弃运算次序。
    @Test
    void convertsAllSupportedUnaryOperators() {
        Map<String, UnaryOperator> operators = Map.of(
                "+", UnaryOperator.PLUS, "-", UnaryOperator.MINUS,
                "!", UnaryOperator.NOT, "~", UnaryOperator.BITWISE_NOT);
        operators.forEach((token, operator) -> {
            IrUnary unary = assertInstanceOf(IrUnary.class, expression(token + "$a"));
            assertEquals(operator, unary.operator());
            assertEquals("a", fixedName(assertInstanceOf(IrVariable.class, unary.operand()).name()));
        });
        IrUnary minus = assertInstanceOf(IrUnary.class, expression("-42"));
        assertInteger(minus.operand(), 42);
        IrUnary nested = assertInstanceOf(IrUnary.class, expression("!-+$a"));
        assertEquals(UnaryOperator.NOT, nested.operator());
        IrUnary nestedMinus = assertInstanceOf(IrUnary.class, nested.operand());
        assertEquals(UnaryOperator.MINUS, nestedMinus.operator());
        IrUnary nestedPlus = assertInstanceOf(IrUnary.class, nestedMinus.operand());
        assertEquals(UnaryOperator.PLUS, nestedPlus.operator());
        assertVariable(nestedPlus.operand(), "a");
    }

    // 二元运算符统一为枚举，比较别名及关键字大小写不产生不同语义操作。
    @Test
    void normalizesAllEagerBinaryOperators() {
        Map<String, BinaryOperator> operators = Map.ofEntries(
                Map.entry("+", BinaryOperator.ADD), Map.entry("-", BinaryOperator.SUBTRACT),
                Map.entry("*", BinaryOperator.MULTIPLY), Map.entry("/", BinaryOperator.DIVIDE),
                Map.entry("%", BinaryOperator.MODULO), Map.entry("**", BinaryOperator.POWER),
                Map.entry(".", BinaryOperator.CONCAT), Map.entry("<<", BinaryOperator.SHIFT_LEFT),
                Map.entry(">>", BinaryOperator.SHIFT_RIGHT), Map.entry("&", BinaryOperator.BITWISE_AND),
                Map.entry("|", BinaryOperator.BITWISE_OR), Map.entry("^", BinaryOperator.BITWISE_XOR),
                Map.entry("==", BinaryOperator.EQUAL), Map.entry("!=", BinaryOperator.NOT_EQUAL),
                Map.entry("<>", BinaryOperator.NOT_EQUAL), Map.entry("===", BinaryOperator.IDENTICAL),
                Map.entry("!==", BinaryOperator.NOT_IDENTICAL), Map.entry("<=>", BinaryOperator.SPACESHIP),
                Map.entry("<", BinaryOperator.LESS), Map.entry("<=", BinaryOperator.LESS_EQUAL),
                Map.entry(">", BinaryOperator.GREATER), Map.entry(">=", BinaryOperator.GREATER_EQUAL),
                Map.entry("xor", BinaryOperator.LOGICAL_XOR), Map.entry("XoR", BinaryOperator.LOGICAL_XOR));
        operators.forEach((token, operator) -> {
            IrBinary binary = assertInstanceOf(IrBinary.class, expression("$a " + token + " $b"), token);
            assertEquals(operator, binary.operator(), token);
            assertEquals("a", fixedName(assertInstanceOf(IrVariable.class, binary.left()).name()));
            assertEquals("b", fixedName(assertInstanceOf(IrVariable.class, binary.right()).name()));
        });
    }

    // 去掉括号包装时仍保留 AST 已确定的优先级、左结合与右结合。
    @Test
    void preservesPrecedenceAndAssociativityAfterRemovingParentheses() {
        IrBinary sum = assertInstanceOf(IrBinary.class, expression("$a + $b * $c"));
        assertEquals(BinaryOperator.ADD, sum.operator());
        assertVariable(sum.left(), "a");
        IrBinary rightProduct = assertInstanceOf(IrBinary.class, sum.right());
        assertEquals(BinaryOperator.MULTIPLY, rightProduct.operator());
        assertVariable(rightProduct.left(), "b");
        assertVariable(rightProduct.right(), "c");
        IrBinary product = assertInstanceOf(IrBinary.class, expression("($a + $b) * $c"));
        assertEquals(BinaryOperator.MULTIPLY, product.operator());
        IrBinary leftSum = assertInstanceOf(IrBinary.class, product.left());
        assertEquals(BinaryOperator.ADD, leftSum.operator());
        assertVariable(leftSum.left(), "a");
        assertVariable(leftSum.right(), "b");
        assertVariable(product.right(), "c");
        IrBinary subtraction = assertInstanceOf(IrBinary.class, expression("$a - $b - $c"));
        assertEquals(BinaryOperator.SUBTRACT, subtraction.operator());
        IrBinary leftSubtraction = assertInstanceOf(IrBinary.class, subtraction.left());
        assertEquals(BinaryOperator.SUBTRACT, leftSubtraction.operator());
        assertVariable(leftSubtraction.left(), "a");
        assertVariable(leftSubtraction.right(), "b");
        assertVariable(subtraction.right(), "c");
        IrBinary power = assertInstanceOf(IrBinary.class, expression("$a ** $b ** $c"));
        assertEquals(BinaryOperator.POWER, power.operator());
        assertVariable(power.left(), "a");
        IrBinary rightPower = assertInstanceOf(IrBinary.class, power.right());
        assertEquals(BinaryOperator.POWER, rightPower.operator());
        assertVariable(rightPower.left(), "b");
        assertVariable(rightPower.right(), "c");
        assertVariable(expression("((($a)))"), "a");
    }

    // &&/and 与 ||/or 都标记短路，但归一化操作符不得改变各自的赋值优先级。
    @Test
    void modelsShortCircuitOperatorsAndPreservesKeywordPrecedence() {
        for (String token : List.of("&&", "and", "AnD")) {
            IrLogical value = assertInstanceOf(IrLogical.class, expression("$a " + token + " $b"));
            assertEquals(LogicalOperator.AND, value.operator());
            assertVariable(value.left(), "a");
            assertVariable(value.right(), "b");
        }
        for (String token : List.of("||", "or", "OR")) {
            IrLogical value = assertInstanceOf(IrLogical.class, expression("$a " + token + " $b"));
            assertEquals(LogicalOperator.OR, value.operator());
            assertVariable(value.left(), "a");
            assertVariable(value.right(), "b");
        }
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression("$a = $b && $c"));
        assertInstanceOf(IrLogical.class, assignment.value());
        IrLogical keyword = assertInstanceOf(IrLogical.class, expression("$a = $b and $c"));
        assertInstanceOf(IrAssignment.class, keyword.left());
    }

    // 混用赋值、符号逻辑与关键字逻辑时，调用必须留在各自短路分支，不能提前提取或交换。
    @Test
    void preservesAssignmentAndNestedShortCircuitCallStructure() {
        IrLogical outer = assertInstanceOf(IrLogical.class,
                expression("$saved = first() || second() && third() or fallback()"));
        assertEquals(LogicalOperator.OR, outer.operator());
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, outer.left());
        assertEquals("saved", fixedName(assertInstanceOf(IrVariableTarget.class, assignment.target()).name()));
        IrLogical disjunction = assertInstanceOf(IrLogical.class, assignment.value());
        assertEquals(LogicalOperator.OR, disjunction.operator());
        assertEmptyCall(disjunction.left(), "first");
        IrLogical conjunction = assertInstanceOf(IrLogical.class, disjunction.right());
        assertEquals(LogicalOperator.AND, conjunction.operator());
        assertEmptyCall(conjunction.left(), "second");
        assertEmptyCall(conjunction.right(), "third");
        assertEmptyCall(outer.right(), "fallback");
    }

    // 空合并使用独立节点保持右结合；短三元省略中间值，避免把条件表达式复制执行。
    @Test
    void modelsCoalescingAndBothConditionalForms() {
        IrCoalesce coalesce = assertInstanceOf(IrCoalesce.class, expression("$a ?? $b ?? $c"));
        assertVariable(coalesce.left(), "a");
        IrCoalesce right = assertInstanceOf(IrCoalesce.class, coalesce.right());
        assertVariable(right.left(), "b");
        assertVariable(right.right(), "c");
        IrConditional full = assertInstanceOf(IrConditional.class, expression("$a ? $b : $c"));
        assertEquals("a", fixedName(assertInstanceOf(IrVariable.class, full.condition()).name()));
        assertEquals("b", fixedName(assertInstanceOf(IrVariable.class, full.thenExpression()).name()));
        assertEquals("c", fixedName(assertInstanceOf(IrVariable.class, full.elseExpression()).name()));
        IrConditional shortForm = assertInstanceOf(IrConditional.class, expression("nextValue() ?: fallback()"));
        assertEmptyCall(shortForm.condition(), "nextValue");
        assertNull(shortForm.thenExpression());
        assertEmptyCall(shortForm.elseExpression(), "fallback");
        IrConditional leftAssociative = assertInstanceOf(IrConditional.class,
                expression("$a ? $b : $c ? $d : $e"));
        IrConditional left = assertInstanceOf(IrConditional.class, leftAssociative.condition());
        assertVariable(left.condition(), "a");
        assertVariable(left.thenExpression(), "b");
        assertVariable(left.elseExpression(), "c");
        assertVariable(leftAssociative.thenExpression(), "d");
        assertVariable(leftAssociative.elseExpression(), "e");
    }

    // 具名调用保留限定形式和实参顺序，空参数列表与嵌套调用均可转换。
    @Test
    void preservesFunctionCallNamesAndArgumentOrder() {
        Map<String, NameForm> names = Map.of(
                "run", NameForm.UNQUALIFIED, "Vendor\\run", NameForm.QUALIFIED,
                "\\Vendor\\run", NameForm.FULLY_QUALIFIED, "namespace\\run", NameForm.NAMESPACE_RELATIVE);
        names.forEach((name, form) -> {
            IrCall call = assertInstanceOf(IrCall.class, expression(name + "($a, 2, nested())"));
            assertEquals(name, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
            assertEquals(form, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().form());
            assertEquals(3, call.arguments().size());
            assertEquals("a", fixedName(assertInstanceOf(IrVariable.class, call.arguments().getFirst().expression()).name()));
            assertInteger(call.arguments().get(1).expression(), 2);
            assertEmptyCall(call.arguments().get(2).expression(), "nested");
        });
    }

    // 含赋值和嵌套调用的实参保持源码顺序及归属，后续变量读取不能被替换成赋值表达式。
    @Test
    void preservesAssignmentsWithinOrderedCallArguments() {
        IrCall call = assertInstanceOf(IrCall.class,
                expression("dispatch($a = first(), second($b = third()), $a)"));
        assertEquals("dispatch", assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
        assertEquals(3, call.arguments().size());
        IrAssignment first = assertInstanceOf(IrAssignment.class, call.arguments().getFirst().expression());
        assertEquals("a", fixedName(assertInstanceOf(IrVariableTarget.class, first.target()).name()));
        assertEmptyCall(first.value(), "first");
        IrCall second = assertInstanceOf(IrCall.class, call.arguments().get(1).expression());
        assertEquals("second", assertInstanceOf(IrNamedCallTarget.class, second.target()).name().spelling());
        assertEquals(1, second.arguments().size());
        IrAssignment nested = assertInstanceOf(IrAssignment.class, second.arguments().getFirst().expression());
        assertEquals("b", fixedName(assertInstanceOf(IrVariableTarget.class, nested.target()).name()));
        assertEmptyCall(nested.value(), "third");
        assertVariable(call.arguments().get(2).expression(), "a");
    }

    // return 无值与返回 null 分开表示；echo 参数、表达式语句、块及空语句保持顺序。
    @Test
    void convertsBasicStatementsWithoutCollapsingOptionalValues() {
        IrBlock block = body("$a = 1; echo $a, 'next', 3; return; return null; {} ;");
        assertEquals(6, block.statements().size());
        assertInstanceOf(IrAssignment.class,
                assertInstanceOf(IrExpressionStatement.class, block.statements().getFirst()).expression());
        IrEcho echo = assertInstanceOf(IrEcho.class, block.statements().get(1));
        assertEquals(3, echo.expressions().size());
        assertEquals("a", fixedName(assertInstanceOf(IrVariable.class, echo.expressions().getFirst()).name()));
        assertString(echo.expressions().get(1), "next");
        assertInteger(echo.expressions().get(2), 3);
        assertNull(assertInstanceOf(IrReturn.class, block.statements().get(2)).value());
        assertLiteral(assertInstanceOf(IrReturn.class, block.statements().get(3)).value(), LiteralKind.NULL, "null");
        assertTrue(assertInstanceOf(IrBlock.class, block.statements().get(4)).statements().isEmpty());
        assertInstanceOf(IrEmpty.class, block.statements().get(5));
    }

    // 标准与冒号 if 统一成有序条件分支，分支内容归一化为块且不改变顺序。
    @Test
    void normalizesStandardAndAlternativeIfStatements() {
        for (String code : List.of(
                "if ($a) echo 1; elseif ($b) { echo 2; } elseif ($c) ; else { return 3; }",
                "if ($a): echo 1; elseif ($b): echo 2; elseif ($c): ; else: return 3; endif;")) {
            IrIf conditional = assertInstanceOf(IrIf.class, body(code).statements().getFirst());
            assertEquals(List.of("a", "b", "c"), conditional.branches().stream()
                    .map(branch -> fixedName(assertInstanceOf(IrVariable.class, branch.condition()).name())).toList());
            for (int i = 0; i < 2; i++) {
                IrEcho echo = assertInstanceOf(IrEcho.class, conditional.branches().get(i).body().statements().getFirst());
                assertInteger(echo.expressions().getFirst(), i + 1);
            }
            assertInstanceOf(IrEmpty.class, conditional.branches().get(2).body().statements().getFirst());
            assertNotNull(conditional.elseBlock());
            assertInteger(assertInstanceOf(IrReturn.class, conditional.elseBlock().statements().getFirst()).value(), 3);
        }
    }

    // 缺少 else 与显式空 else 不相同，空语句分支也不能丢失。
    @Test
    void distinguishesAbsentEmptyAndEmptyStatementBranches() {
        for (String code : List.of("if ($a) {}", "if ($a): endif;")) {
            IrIf conditional = assertInstanceOf(IrIf.class, body(code).statements().getFirst());
            assertNull(conditional.elseBlock());
            assertTrue(conditional.branches().getFirst().body().statements().isEmpty());
        }
        for (String code : List.of("if ($a) ; else {}", "if ($a): ; else: endif;")) {
            IrIf conditional = assertInstanceOf(IrIf.class, body(code).statements().getFirst());
            assertNotNull(conditional.elseBlock());
            assertTrue(conditional.elseBlock().statements().isEmpty());
            assertInstanceOf(IrEmpty.class, conditional.branches().getFirst().body().statements().getFirst());
        }
    }

    // 悬空 else 仍归属最近的 if，不在转换时重新解释语法结构。
    @Test
    void preservesDanglingElseOwnership() {
        IrIf outer = assertInstanceOf(IrIf.class,
                body("if ($a) if ($b) echo 1; else echo 2;").statements().getFirst());
        assertEquals(1, outer.branches().size());
        assertVariable(outer.branches().getFirst().condition(), "a");
        assertNull(outer.elseBlock());
        assertEquals(1, outer.branches().getFirst().body().statements().size());
        IrIf inner = assertInstanceOf(IrIf.class, outer.branches().getFirst().body().statements().getFirst());
        assertEquals(1, inner.branches().size());
        assertVariable(inner.branches().getFirst().condition(), "b");
        assertEchoValues(inner.branches().getFirst().body(), 1);
        assertEchoValues(inner.elseBlock(), 2);
    }

    // 普通与冒号式分支中的多条语句、内层 if 和分支后的语句都必须保留原有层次与顺序。
    @Test
    void preservesNestedBranchesAndFollowingStatementOrder() {
        for (String code : List.of("""
                if ($a) {
                    if ($b) echo 1; else echo 2;
                    echo 3;
                } elseif ($c) {
                    echo 4; echo 5;
                } else {
                    echo 6;
                }
                echo 7;
                """, """
                if ($a):
                    if ($b): echo 1; else: echo 2; endif;
                    echo 3;
                elseif ($c):
                    echo 4; echo 5;
                else:
                    echo 6;
                endif;
                echo 7;
                """)) {
            IrBlock block = body(code);
            assertEquals(2, block.statements().size());
            IrIf outer = assertInstanceOf(IrIf.class, block.statements().getFirst());
            assertEquals(2, outer.branches().size());
            IrIfBranch first = outer.branches().getFirst();
            assertVariable(first.condition(), "a");
            assertEquals(2, first.body().statements().size());
            IrIf inner = assertInstanceOf(IrIf.class, first.body().statements().getFirst());
            assertEquals(1, inner.branches().size());
            assertVariable(inner.branches().getFirst().condition(), "b");
            assertEchoValues(inner.branches().getFirst().body(), 1);
            assertEchoValues(inner.elseBlock(), 2);
            assertEchoValue(first.body().statements().get(1), 3);
            assertVariable(outer.branches().get(1).condition(), "c");
            assertEchoValues(outer.branches().get(1).body(), 4, 5);
            assertEchoValues(outer.elseBlock(), 6);
            assertEchoValue(block.statements().get(1), 7);
        }
    }

    // else if 是 else 块内的嵌套 if，不可与同一 if 的 elseif 分支链混淆。
    @Test
    void keepsElseIfStatementNestedInsideElseBlock() {
        IrBlock block = body("if ($a) echo 1; elseif ($b) echo 2; else if ($c) echo 3; else echo 4;");
        assertEquals(1, block.statements().size());
        IrIf outer = assertInstanceOf(IrIf.class, block.statements().getFirst());
        assertEquals(2, outer.branches().size());
        assertVariable(outer.branches().getFirst().condition(), "a");
        assertEchoValues(outer.branches().getFirst().body(), 1);
        assertVariable(outer.branches().get(1).condition(), "b");
        assertEchoValues(outer.branches().get(1).body(), 2);
        assertNotNull(outer.elseBlock());
        assertEquals(1, outer.elseBlock().statements().size());
        IrIf nested = assertInstanceOf(IrIf.class, outer.elseBlock().statements().getFirst());
        assertEquals(1, nested.branches().size());
        assertVariable(nested.branches().getFirst().condition(), "c");
        assertEchoValues(nested.branches().getFirst().body(), 3);
        assertEchoValues(nested.elseBlock(), 4);
    }

    // 阶段一公开的函数体、方法体和默认值／初始化值均可按需转换，不要求转换整个文件。
    @Test
    void convertsSelectedBodiesDefaultsAndInitializersThroughExistingModel() {
        PhpFile file = DeclarationExtractor.extract(Main.parse("""
                <?php
                function good($value = null) { return $value; }
                function unrelated() { (new class {}); }
                class C {
                    public $value = 1 + 2;
                    const NEXT = 3;
                    function run() { return 4; }
                }
                """));
        List<TopLevelDeclaration> declarations = file.namespaceSections().getFirst().declarations();
        FunctionDefinition good = assertInstanceOf(FunctionDefinition.class, declarations.getFirst());
        assertInstanceOf(IrReturn.class, SyntaxConverter.convertBody(good.body()).statements().getFirst());
        assertLiteral(SyntaxConverter.convertExpression(good.signature().parameters().getFirst().defaultValue()),
                LiteralKind.NULL, "null");
        ClassLikeDefinition type = assertInstanceOf(ClassLikeDefinition.class, declarations.get(2));
        PropertyDefinition property = assertInstanceOf(PropertyDefinition.class, type.members().getFirst());
        assertInstanceOf(IrBinary.class, SyntaxConverter.convertExpression(property.initialValue()));
        ClassConstantDefinition constant = assertInstanceOf(ClassConstantDefinition.class, type.members().get(1));
        assertInteger(SyntaxConverter.convertExpression(constant.value()), 3);
        MethodDefinition method = assertInstanceOf(MethodDefinition.class, type.members().get(2));
        assertEquals(1, SyntaxConverter.convertBody(method.body()).statements().size());
        FunctionDefinition unrelated = assertInstanceOf(FunctionDefinition.class, declarations.get(1));
        assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(unrelated.body()));
        assertEquals(3, declarations.size());
    }

    // 当前子集外的表达式明确失败，不退回原始 AST 或部分转换结果。
    @Test
    void rejectsUnsupportedExpressions() {
        for (String code : List.of(
                "$a =& $b",
                "function () { (new class {}); }", "(new class {})", "yield (new class {})")) {
            SyntaxExpression syntax = assertDoesNotThrow(() -> syntaxExpression(code), code);
            var error = assertThrows(SyntaxConversionException.class,
                    () -> SyntaxConverter.convertExpression(syntax), code);
            assertFalse(error.fieldPath().isBlank(), code);
            assertFalse(error.reason().isBlank(), code);
        }
    }

    // 文法中的 variable 也包括调用，但这些表达式不是本阶段允许的可写赋值目标。
    @Test
    void rejectsUnsupportedAssignmentRootsEvenWhenGrammarAcceptsThem() {
        for (String code : List.of("f() = 1", "$callback() = 1")) {
            SyntaxExpression syntax = syntaxExpression(code);
            assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertExpression(syntax), code);
        }
    }

    // 嵌套声明和其它非子集语句一律报错，不跳过其可执行内容。
    @Test
    void rejectsUnsupportedStatementsAndNestedDeclarations() {
        for (String code : List.of(
                "global $a;", "(new class {});",
                "function nested() {}", "class Nested {}", "goto end; end: ;")) {
            SyntaxBody syntax = syntaxBody(code);
            assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(syntax), code);
        }
    }

    private static IrBlock body(String code) {
        return SyntaxConverter.convertBody(syntaxBody(code));
    }

    private static SyntaxBody syntaxBody(String code) {
        PhpFile file = DeclarationExtractor.extract(Main.parse("<?php function testBody() { " + code + " }"));
        return ((FunctionDefinition) file.namespaceSections().getFirst().declarations().getFirst()).body();
    }

    private static IrExpression expression(String code) {
        return SyntaxConverter.convertExpression(syntaxExpression(code));
    }

    private static SyntaxExpression syntaxExpression(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php " + code + ";");
        NodeExpr syntax = parsed.getStmts().getValue().getFirst().getStmt().getExpression();
        return new SyntaxExpression(syntax, new SourceInfo(null, null));
    }

    private static void assertLiteral(IrExpression actual, LiteralKind kind, String lexeme) {
        IrLiteral literal = assertInstanceOf(IrLiteral.class, actual);
        assertEquals(kind, literal.kind());
        assertEquals(lexeme, literal.lexeme());
    }

    private static void assertString(IrExpression actual, String value) {
        assertArrayEquals(value.getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringLiteral.class, actual).value().toByteArray());
    }

    private static void assertInteger(IrExpression actual, long value) {
        assertEquals(value, assertInstanceOf(IrIntegerLiteral.class, actual).value());
    }

    private static void assertVariable(IrExpression actual, String name) {
        assertEquals(name, fixedName(assertInstanceOf(IrVariable.class, actual).name()));
    }

    private static void assertEmptyCall(IrExpression actual, String name) {
        IrCall call = assertInstanceOf(IrCall.class, actual);
        assertEquals(name, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
        assertEquals(NameForm.UNQUALIFIED, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().form());
        assertTrue(call.arguments().isEmpty());
    }

    private static void assertEchoValue(IrStatement actual, long value) {
        IrEcho echo = assertInstanceOf(IrEcho.class, actual);
        assertEquals(1, echo.expressions().size());
        assertInteger(echo.expressions().getFirst(), value);
    }

    private static void assertEchoValues(IrBlock block, long... values) {
        assertNotNull(block);
        assertEquals(values.length, block.statements().size());
        for (int i = 0; i < values.length; i++) {
            assertEchoValue(block.statements().get(i), values[i]);
        }
    }

    private static void assertConstant(String spelling, NameForm form) {
        IrConstantReference constant = assertInstanceOf(IrConstantReference.class, expression(spelling));
        assertEquals(spelling, constant.name().spelling());
        assertEquals(form, constant.name().form());
    }

    private static String fixedName(IrAccessName name) {
        return assertInstanceOf(IrFixedName.class, name).value();
    }
}
