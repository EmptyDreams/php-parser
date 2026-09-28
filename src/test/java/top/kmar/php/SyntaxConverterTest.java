package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 从真实 PHP 语法树验证基础语句／表达式转换，不在转换阶段求值或绑定名称。 */
class SyntaxConverterTest {

    // 整数的进制、浮点数表示和字符串的前缀、引号、转义都保留原文。
    @Test
    void preservesLiteralKindsAndLexemes() {
        for (String value : List.of("0", "42", "077", "0x2A", "0b101010", "9223372036854775807",
                "0100000000000000000000", "0" + "7".repeat(21), "0x00007FFFFFFFFFFFFFFF")) {
            assertLiteral(expression(value), LiteralKind.INTEGER, value);
        }
        for (String value : List.of("1.0", ".5", "1e3", "1e309", "9223372036854775808",
                "01" + "0".repeat(21),
                "0xFFFFFFFFFFFFFFFF", "0b1111111111111111111111111111111111111111111111111111111111111111")) {
            assertLiteral(expression(value), LiteralKind.FLOAT, value);
        }
        for (String value : List.of("'text'", "\"text\"", "'a\\'b'", "\"a\\n\\t\"", "b'raw'", "B\"raw\"")) {
            assertLiteral(expression(value), LiteralKind.STRING, value);
        }
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
        assertEquals("value", assertInstanceOf(IrVariable.class, expression("$value")).name());
        IrAssignment outer = assertInstanceOf(IrAssignment.class, expression("$a = $b = 3"));
        assertEquals("a", assertInstanceOf(IrVariableTarget.class, outer.target()).name());
        IrAssignment inner = assertInstanceOf(IrAssignment.class, outer.value());
        assertEquals("b", assertInstanceOf(IrVariableTarget.class, inner.target()).name());
        assertLiteral(inner.value(), LiteralKind.INTEGER, "3");
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
            assertEquals("a", assertInstanceOf(IrVariable.class, unary.operand()).name());
        });
        IrUnary minus = assertInstanceOf(IrUnary.class, expression("-42"));
        assertLiteral(minus.operand(), LiteralKind.INTEGER, "42");
        IrUnary nested = assertInstanceOf(IrUnary.class, expression("!-+$a"));
        assertEquals(UnaryOperator.NOT, nested.operator());
        assertEquals(UnaryOperator.MINUS, assertInstanceOf(IrUnary.class, nested.operand()).operator());
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
            assertEquals("a", assertInstanceOf(IrVariable.class, binary.left()).name());
            assertEquals("b", assertInstanceOf(IrVariable.class, binary.right()).name());
        });
    }

    // 去掉括号包装时仍保留 AST 已确定的优先级、左结合与右结合。
    @Test
    void preservesPrecedenceAndAssociativityAfterRemovingParentheses() {
        IrBinary sum = assertInstanceOf(IrBinary.class, expression("$a + $b * $c"));
        assertEquals(BinaryOperator.ADD, sum.operator());
        assertEquals(BinaryOperator.MULTIPLY, assertInstanceOf(IrBinary.class, sum.right()).operator());
        IrBinary product = assertInstanceOf(IrBinary.class, expression("($a + $b) * $c"));
        assertEquals(BinaryOperator.MULTIPLY, product.operator());
        assertEquals(BinaryOperator.ADD, assertInstanceOf(IrBinary.class, product.left()).operator());
        IrBinary subtraction = assertInstanceOf(IrBinary.class, expression("$a - $b - $c"));
        assertEquals(BinaryOperator.SUBTRACT, assertInstanceOf(IrBinary.class, subtraction.left()).operator());
        IrBinary power = assertInstanceOf(IrBinary.class, expression("$a ** $b ** $c"));
        assertEquals(BinaryOperator.POWER, assertInstanceOf(IrBinary.class, power.right()).operator());
        assertInstanceOf(IrVariable.class, expression("((($a)))"));
    }

    // &&/and 与 ||/or 都标记短路，但归一化操作符不得改变各自的赋值优先级。
    @Test
    void modelsShortCircuitOperatorsAndPreservesKeywordPrecedence() {
        for (String token : List.of("&&", "and", "AnD")) {
            IrLogical value = assertInstanceOf(IrLogical.class, expression("$a " + token + " $b"));
            assertEquals(LogicalOperator.AND, value.operator());
        }
        for (String token : List.of("||", "or", "OR")) {
            IrLogical value = assertInstanceOf(IrLogical.class, expression("$a " + token + " $b"));
            assertEquals(LogicalOperator.OR, value.operator());
        }
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression("$a = $b && $c"));
        assertInstanceOf(IrLogical.class, assignment.value());
        IrLogical keyword = assertInstanceOf(IrLogical.class, expression("$a = $b and $c"));
        assertInstanceOf(IrAssignment.class, keyword.left());
    }

    // 空合并使用独立节点保持右结合；短三元省略中间值，避免把条件表达式复制执行。
    @Test
    void modelsCoalescingAndBothConditionalForms() {
        IrCoalesce coalesce = assertInstanceOf(IrCoalesce.class, expression("$a ?? $b ?? $c"));
        assertInstanceOf(IrCoalesce.class, coalesce.right());
        IrConditional full = assertInstanceOf(IrConditional.class, expression("$a ? $b : $c"));
        assertEquals("a", assertInstanceOf(IrVariable.class, full.condition()).name());
        assertEquals("b", assertInstanceOf(IrVariable.class, full.thenExpression()).name());
        assertEquals("c", assertInstanceOf(IrVariable.class, full.elseExpression()).name());
        IrConditional shortForm = assertInstanceOf(IrConditional.class, expression("nextValue() ?: fallback()"));
        assertEquals("nextValue", assertInstanceOf(IrCall.class, shortForm.condition()).name().spelling());
        assertNull(shortForm.thenExpression());
        assertEquals("fallback", assertInstanceOf(IrCall.class, shortForm.elseExpression()).name().spelling());
        IrConditional leftAssociative = assertInstanceOf(IrConditional.class,
                expression("$a ? $b : $c ? $d : $e"));
        assertInstanceOf(IrConditional.class, leftAssociative.condition());
    }

    // 具名调用保留限定形式和实参顺序，空参数列表与嵌套调用均可转换。
    @Test
    void preservesFunctionCallNamesAndArgumentOrder() {
        Map<String, NameForm> names = Map.of(
                "run", NameForm.UNQUALIFIED, "Vendor\\run", NameForm.QUALIFIED,
                "\\Vendor\\run", NameForm.FULLY_QUALIFIED, "namespace\\run", NameForm.NAMESPACE_RELATIVE);
        names.forEach((name, form) -> {
            IrCall call = assertInstanceOf(IrCall.class, expression(name + "($a, 2, nested())"));
            assertEquals(name, call.name().spelling());
            assertEquals(form, call.name().form());
            assertEquals(3, call.arguments().size());
            assertEquals("a", assertInstanceOf(IrVariable.class, call.arguments().getFirst()).name());
            assertLiteral(call.arguments().get(1), LiteralKind.INTEGER, "2");
            assertTrue(assertInstanceOf(IrCall.class, call.arguments().get(2)).arguments().isEmpty());
        });
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
        assertEquals("a", assertInstanceOf(IrVariable.class, echo.expressions().getFirst()).name());
        assertLiteral(echo.expressions().get(1), LiteralKind.STRING, "'next'");
        assertLiteral(echo.expressions().get(2), LiteralKind.INTEGER, "3");
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
                    .map(branch -> assertInstanceOf(IrVariable.class, branch.condition()).name()).toList());
            for (int i = 0; i < 2; i++) {
                IrEcho echo = assertInstanceOf(IrEcho.class, conditional.branches().get(i).body().statements().getFirst());
                assertLiteral(echo.expressions().getFirst(), LiteralKind.INTEGER, Integer.toString(i + 1));
            }
            assertInstanceOf(IrEmpty.class, conditional.branches().get(2).body().statements().getFirst());
            assertNotNull(conditional.elseBlock());
            assertLiteral(assertInstanceOf(IrReturn.class, conditional.elseBlock().statements().getFirst()).value(),
                    LiteralKind.INTEGER, "3");
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
        assertNull(outer.elseBlock());
        IrIf inner = assertInstanceOf(IrIf.class, outer.branches().getFirst().body().statements().getFirst());
        assertNotNull(inner.elseBlock());
    }

    // 阶段一公开的函数体、方法体和默认值／初始化值均可按需转换，不要求转换整个文件。
    @Test
    void convertsSelectedBodiesDefaultsAndInitializersThroughExistingModel() {
        PhpFile file = DeclarationExtractor.extract(Main.parse("""
                <?php
                function good($value = null) { return $value; }
                function unrelated() { while (true) {} }
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
        assertLiteral(SyntaxConverter.convertExpression(constant.value()), LiteralKind.INTEGER, "3");
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
                "[]", "array(1)", "$a[0]", "$a->p", "C::$p", "$$a",
                "$callback()", "$a->run()", "C::run()", "f(...$args)",
                "$a += 1", "$a =& $b", "++$a", "$a--", "(int) $a", "@f()",
                "function () {}", "\"$a\"", "__LINE__", "C::VALUE",
                "new C", "clone $a", "print $a", "isset($a)", "yield 1",
                "<<<EOT\nplain text\nEOT\n", "<<<'NOW'\nno $interpolation\nNOW\n",
                "<<<EMPTY\nEMPTY\n")) {
            SyntaxExpression syntax = assertDoesNotThrow(() -> syntaxExpression(code), code);
            var error = assertThrows(SyntaxConversionException.class,
                    () -> SyntaxConverter.convertExpression(syntax), code);
            assertFalse(error.fieldPath().isBlank(), code);
            assertFalse(error.reason().isBlank(), code);
        }
    }

    // 文法中的 variable 也包括调用，但这些表达式不是本阶段允许的可写赋值目标。
    @Test
    void rejectsNonSimpleAssignmentTargetsEvenWhenGrammarAcceptsThem() {
        for (String code : List.of("f() = 1", "$a[0] = 1", "$a->p = 1", "C::$p = 1", "$$a = 1")) {
            SyntaxExpression syntax = syntaxExpression(code);
            assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertExpression(syntax), code);
        }
    }

    // 循环、嵌套声明和其它非子集语句一律报错，不跳过其可执行内容。
    @Test
    void rejectsUnsupportedStatementsAndNestedDeclarations() {
        for (String code : List.of(
                "while ($a) {}", "do {} while ($a);", "for (;;) {}", "foreach ($a as $b) {}",
                "switch ($a) { default: ; }", "throw $a;", "try {} finally {}",
                "break;", "continue;", "global $a;", "unset($a);",
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

    private static void assertConstant(String spelling, NameForm form) {
        IrConstantReference constant = assertInstanceOf(IrConstantReference.class, expression(spelling));
        assertEquals(spelling, constant.name().spelling());
        assertEquals(form, constant.name().form());
    }
}
