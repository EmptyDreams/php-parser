package top.kmar.php;

import java_cup.runtime.AstNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CUP 升级后直接验证 AST 字段、列表和结合性，而非仅验证解析成功。 */
class AstStructureTest {

    // 验证空程序和空声明保留空列表，并支持语法树遍历。
    @Test
    void emptyProgramsAndDeclarationsKeepTraversableEmptyLists() {
        AstNode empty = Main.parse("<?php\n");
        assertEquals("program", empty.getNodeName());
        assertTrue(list(empty, "stmts").isEmpty());
        assertTrue(empty.getLocation().isNoLocation());

        AstNode tree = Main.parse("<?php function f() {} class C {} namespace N {} namespace {}");
        List<AstNode> statements = list(tree, "stmts");
        assertEquals(4, statements.size());
        AstNode function = child(statements.getFirst(), "function");
        assertTrue(list(function, "params").isEmpty());
        assertTrue(list(function, "stmts").isEmpty());
        assertTrue(list(child(statements.get(1), "clazz"), "members").isEmpty());
        assertTrue(list(statements.get(2), "stmts").isEmpty());
        assertTrue(list(statements.get(3), "stmts").isEmpty());
        assertDoesNotThrow(() -> tree.toTreeString(false));
    }

    // 验证参数和函数的可选字段直接反映有无对应语法。
    @Test
    void optionalParameterAndFunctionFieldsReflectPresence() {
        AstNode plain = child(top("function plain($a) {}"), "function");
        absent(plain, "returnsRef");
        AstNode param = list(plain, "params").getFirst();
        absent(param, "type");
        absent(param, "byRef");
        absent(param, "variadic");
        absent(param, "defaultValue");
        assertEquals("a", text(child(param, "var")));

        AstNode referenced = child(top("function &ref(?Foo &$a, ...$rest) { return $a; }"), "function");
        assertEquals("&", text(child(referenced, "returnsRef")));
        List<AstNode> params = list(referenced, "params");
        assertEquals(2, params.size());
        assertEquals("type_expr", child(params.getFirst(), "type").getNodeName());
        assertEquals("?", text(child(child(params.getFirst(), "type"), "op")));
        assertEquals("&", text(child(params.get(0), "byRef")));
        absent(params.get(0), "variadic");
        assertEquals("...", text(child(params.get(1), "variadic")));
        absent(params.get(1), "type");
        absent(params.get(1), "byRef");
        assertEquals("rest", text(child(params.get(1), "var")));
        assertDoesNotThrow(() -> plain.toTreeString(false));
        assertDoesNotThrow(() -> referenced.toTreeString(false));
    }

    // 验证直接列表保留语句、参数和表达式的源码顺序。
    @Test
    void directListsPreserveStatementParameterAndExpressionOrder() {
        AstNode tree = Main.parse("<?php function f($a, $b, $c) { echo 1, 2, 3; return 4; }");
        AstNode function = child(list(tree, "stmts").getFirst(), "function");
        assertEquals(List.of("a", "b", "c"), list(function, "params").stream()
                .map(param -> text(child(param, "var"))).toList());
        List<AstNode> body = list(function, "stmts");
        assertEquals(2, body.size());
        AstNode echo = child(body.get(0), "stmt");
        assertEquals(List.of("1", "2", "3"), list(echo, "exprs").stream()
                .map(AstStructureTest::number).toList());
        assertEquals("4", number(child(child(body.get(1), "stmt"), "value")));
    }

    // 验证方法和类常量的修饰符列表保留空值及声明顺序。
    @Test
    void methodAndConstantModifierListsPreserveEmptyValuesAndOrder() {
        AstNode clazz = child(top("class C { function f() {} public static function g() {}"
                + " const X = 1; protected const Y = 2; }"), "clazz");
        List<AstNode> members = list(clazz, "members");
        assertEquals(4, members.size());
        assertTrue(list(members.get(0), "methodMods").isEmpty());
        assertEquals(List.of("public", "static"), list(members.get(1), "methodMods").stream()
                .map(modifier -> text(child(modifier, "kw"))).toList());
        assertTrue(list(members.get(2), "constMods").isEmpty());
        assertEquals(List.of("protected"), list(members.get(3), "constMods").stream()
                .map(modifier -> text(child(modifier, "kw"))).toList());
    }

    // 验证缺省构造参数与显式空参数列表可以区分。
    @Test
    void constructorArgumentsDistinguishOmittedAndExplicitEmptyLists() {
        AstNode withoutArgs = onlyNamed(expression("new Foo"), "new_expr");
        absent(withoutArgs, "ctorArgs");
        AstNode emptyArgs = onlyNamed(expression("new Foo()"), "new_expr");
        assertTrue(list(child(emptyArgs, "ctorArgs"), "args").isEmpty());
        AstNode withArgs = onlyNamed(expression("new Foo(1, 2)"), "new_expr");
        assertEquals(List.of("1", "2"), list(child(withArgs, "ctorArgs"), "args").stream()
                .map(argument -> number(child(argument, "arg"))).toList());
    }

    // 验证方括号偏移直接使用可选表达式，不增加包装节点。
    @Test
    void optionalArrayOffsetsUseExpressionsWithoutWrapperNodes() {
        AstNode emptyIndex = onlyVariant(expression("$a[]"));
        absent(emptyIndex, "offset");
        AstNode index = onlyVariant(expression("$a[1]"));
        assertEquals("expr", child(index, "offset").getNodeName());
        assertEquals("1", number(child(index, "offset")));
    }

    // 验证数组空槽以及尾逗号对应的空槽均被保留。
    @Test
    void arrayHolesAndTrailingCommaSlotsArePreserved() {
        AstNode array = onlyNamed(expression("[1, , 3,]"), "array_pair_list");
        List<AstNode> slots = list(array, "items");
        assertEquals(4, slots.size());
        assertEquals("1", number(child(child(slots.get(0), "pair"), "value")));
        absent(slots.get(1), "pair");
        assertEquals("3", number(child(child(slots.get(2), "pair"), "value")));
        absent(slots.get(3), "pair");
        assertDoesNotThrow(() -> array.toTreeString(false));

        AstNode emptyArray = onlyNamed(expression("[]"), "array_pair_list");
        List<AstNode> emptySlots = list(emptyArray, "items");
        assertEquals(1, emptySlots.size());
        absent(emptySlots.getFirst(), "pair");
    }

    // 验证 PHP 7.2 的参数和实参禁止尾逗号，分组导入允许尾逗号。
    @Test
    void php72RejectsTrailingParameterAndArgumentCommasButAllowsGroupUseCommas() {
        assertThrows(PhpParseException.class, () -> Main.parse("<?php function f($a,) {}"));
        assertThrows(PhpParseException.class, () -> Main.parse("<?php f(1,);"));
        AstNode group = child(top("use A\\{B, C,};"), "mixedUse");
        assertEquals(2, list(group, "uses").size());
    }

    // 验证普通运算统一生成 Binary 节点，复合赋值生成 AssignOp 节点。
    @Test
    void ordinaryOperationsUseBinaryNodesAndCompoundAssignmentsUseAssignOpNodes() {
        for (String operator : List.of("+", "-", "*", "/", "%", ".", "&", "|", "^", "<<", ">>", "**")) {
            for (String leftOperand : List.of("$a", "1", "(1)")) {
                AstNode binary = value(expression(leftOperand + " " + operator + " $b"));
                assertEquals("Binary", binary.getClass().getSimpleName(), leftOperand + " " + operator);
                assertEquals(operator, text(child(binary, "op")));
                AstNode left = child(binary, "left");
                assertNotNull(child(binary, "right"));
                absent(binary, "target");
                if (leftOperand.equals("1")) {
                    assertEquals("1", number(left));
                } else if (leftOperand.equals("(1)")) {
                    AstNode parentheses = value(left);
                    assertEquals("Paren", parentheses.getClass().getSimpleName());
                    assertEquals("1", number(child(parentheses, "expr")));
                }
            }

            AstNode assignment = value(expression("$a " + operator + "= $b"));
            assertEquals("AssignOp", assignment.getClass().getSimpleName(), operator);
            assertEquals(operator + "=", text(child(assignment, "op")));
            assertNotNull(child(assignment, "target"));
            assertNotNull(child(assignment, "value"));
            absent(assignment, "left");
        }
    }

    // 验证乘法优先于加法，连续减法按左结合解析。
    @Test
    void multiplicationTakesPrecedenceAndSubtractionAssociatesLeft() {
        AstNode sum = value(expression("1 + 2 * 3"));
        assertEquals("+", text(child(sum, "op")));
        assertEquals("1", number(child(sum, "left")));
        AstNode product = value(child(sum, "right"));
        assertEquals("*", text(child(product, "op")));
        assertEquals("2", number(child(product, "left")));
        assertEquals("3", number(child(product, "right")));

        AstNode subtraction = value(expression("8 - 3 - 1"));
        assertEquals("1", number(child(subtraction, "right")));
        AstNode left = value(child(subtraction, "left"));
        assertEquals("-", text(child(left, "op")));
        assertEquals("8", number(child(left, "left")));
        assertEquals("3", number(child(left, "right")));
    }

    // 验证幂运算和空合并运算均按右结合解析。
    @Test
    void exponentiationAndNullCoalescingAssociateRight() {
        for (String operator : List.of("**", "??")) {
            AstNode outer = value(expression("1 " + operator + " 2 " + operator + " 3"));
            assertEquals(operator, text(child(outer, "op")));
            assertEquals("1", number(child(outer, "left")));
            AstNode right = value(child(outer, "right"));
            assertEquals(operator, text(child(right, "op")));
            assertEquals("2", number(child(right, "left")));
            assertEquals("3", number(child(right, "right")));
        }
    }

    // PHP 7.2 文法的一元正负号使用 T_INC 优先级，低于幂；括号可以改变分组。
    @Test
    void exponentiationBindsBeforeUnarySignsUnlessParenthesized() {
        for (String sign : List.of("+", "-")) {
            AstNode unary = value(expression(sign + "2 ** 3"));
            assertEquals(sign, text(child(unary, "op")));
            AstNode power = binary(child(unary, "expr"), "**");
            assertEquals("2", number(child(power, "left")));
            assertEquals("3", number(child(power, "right")));

            AstNode groupedPower = binary(expression("(" + sign + "2) ** 3"), "**");
            AstNode parentheses = assertInstanceOf(NodeExprWithoutVariable.Paren.class,
                    value(child(groupedPower, "left")));
            AstNode groupedUnary = value(child(parentheses, "expr"));
            assertEquals(sign, text(child(groupedUnary, "op")));
            assertEquals("2", number(child(groupedUnary, "expr")));
            assertEquals("3", number(child(groupedPower, "right")));
        }
    }

    // 赋值左侧必须是变量，因此连续赋值右嵌套；and/or 比赋值低，&&/|| 比赋值高。
    @Test
    void assignmentsNestRightBetweenKeywordAndSymbolicLogicalPrecedence() {
        AstNode keywordOr = binary(expression("$a = $b = 1 and 2 or 3"), "or");
        assertEquals("3", number(child(keywordOr, "right")));
        AstNode keywordAnd = binary(child(keywordOr, "left"), "and");
        assertEquals("2", number(child(keywordAnd, "right")));
        AstNode outerAssignment = assignment(child(keywordAnd, "left"), "a");
        AstNode innerAssignment = assignment(child(outerAssignment, "value"), "b");
        assertEquals("1", number(child(innerAssignment, "value")));

        AstNode symbolicAssignment = assignment(expression("$a = 1 && 2 || 3"), "a");
        AstNode symbolicOr = binary(child(symbolicAssignment, "value"), "||");
        assertEquals("3", number(child(symbolicOr, "right")));
        AstNode symbolicAnd = binary(child(symbolicOr, "left"), "&&");
        assertEquals("1", number(child(symbolicAnd, "left")));
        assertEquals("2", number(child(symbolicAnd, "right")));
    }

    // PHP 7.2 的点号与加减同级左结合，不能套用 PHP 8 降低拼接优先级后的规则。
    @Test
    void php72ConcatenationSharesLeftAssociativityWithAdditionAndSubtraction() {
        for (String operator : List.of("+", "-")) {
            AstNode arithmetic = binary(expression("1 . 2 " + operator + " 3"), operator);
            AstNode leftConcat = binary(child(arithmetic, "left"), ".");
            assertEquals("1", number(child(leftConcat, "left")));
            assertEquals("2", number(child(leftConcat, "right")));
            assertEquals("3", number(child(arithmetic, "right")));

            AstNode concat = binary(expression("1 " + operator + " 2 . 3"), ".");
            AstNode leftArithmetic = binary(child(concat, "left"), operator);
            assertEquals("1", number(child(leftArithmetic, "left")));
            assertEquals("2", number(child(leftArithmetic, "right")));
            assertEquals("3", number(child(concat, "right")));
        }
    }

    // 空合并优先于三元运算；显式括号允许三元运算嵌套在 else 分支，而非默认左结合。
    @Test
    void coalescingBindsBeforeTernaryAndParenthesesPreserveNestedElseBranches() {
        AstNode conditional = assertInstanceOf(NodeExprWithoutVariable.Conditional.class,
                value(expression("1 ?? 2 ? 3 : 4")));
        AstNode coalesce = binary(child(conditional, "cond"), "??");
        assertEquals("1", number(child(coalesce, "left")));
        assertEquals("2", number(child(coalesce, "right")));
        assertEquals("3", number(child(conditional, "thenExpr")));
        assertEquals("4", number(child(conditional, "elseExpr")));

        AstNode outer = assertInstanceOf(NodeExprWithoutVariable.Conditional.class,
                value(expression("1 ? 2 : (3 ? 4 : 5)")));
        assertEquals("1", number(child(outer, "cond")));
        assertEquals("2", number(child(outer, "thenExpr")));
        AstNode parentheses = assertInstanceOf(NodeExprWithoutVariable.Paren.class,
                value(child(outer, "elseExpr")));
        AstNode inner = assertInstanceOf(NodeExprWithoutVariable.Conditional.class,
                value(child(parentheses, "expr")));
        assertEquals("3", number(child(inner, "cond")));
        assertEquals("4", number(child(inner, "thenExpr")));
        assertEquals("5", number(child(inner, "elseExpr")));
    }

    // 验证 PHP 7.2 三元运算左结合，短三元运算没有中间表达式字段。
    @Test
    void php72TernariesAssociateLeftAndShortTernariesOmitTheMiddleField() {
        AstNode outer = value(expression("1 ? 2 : 3 ? 4 : 5"));
        assertEquals("Conditional", outer.getClass().getSimpleName());
        assertEquals("4", number(child(outer, "thenExpr")));
        assertEquals("5", number(child(outer, "elseExpr")));
        AstNode inner = value(child(outer, "cond"));
        assertEquals("Conditional", inner.getClass().getSimpleName());
        assertEquals("1", number(child(inner, "cond")));
        assertEquals("2", number(child(inner, "thenExpr")));
        assertEquals("3", number(child(inner, "elseExpr")));

        AstNode shortForm = value(expression("1 ?: 2"));
        absent(shortForm, "thenExpr");
        assertEquals("1", number(child(shortForm, "cond")));
        assertEquals("2", number(child(shortForm, "elseExpr")));
    }

    // 验证 else 分支绑定最近且尚未匹配 else 的 if。
    @Test
    void elseBindsToTheNearestUnmatchedIf() {
        AstNode outer = child(child(top("if ($a) if ($b) echo 1; else echo 2;"), "stmt"), "ifStmt");
        absent(outer, "elseStmt");
        AstNode outerElement = child(outer, "stmt");
        AstNode inner = child(child(outerElement, "body"), "ifStmt");
        AstNode elseBody = child(inner, "elseStmt");
        assertEquals("2", number(list(elseBody, "exprs").getFirst()));
        AstNode thenBody = child(child(inner, "stmt"), "body");
        assertEquals("1", number(list(thenBody, "exprs").getFirst()));
    }

    private static AstNode top(String source) {
        List<AstNode> statements = list(Main.parse("<?php " + source), "stmts");
        assertEquals(1, statements.size());
        return statements.getFirst();
    }

    private static AstNode expression(String source) {
        return child(child(top(source + ";"), "stmt"), "expression");
    }

    private static AstNode value(AstNode expression) {
        return child(expression, "ev");
    }

    private static AstNode binary(AstNode expression, String operator) {
        AstNode binary = assertInstanceOf(NodeExprWithoutVariable.Binary.class, value(expression));
        assertEquals(operator, text(child(binary, "op")));
        return binary;
    }

    private static AstNode assignment(AstNode expression, String variableName) {
        AstNode assignment = assertInstanceOf(NodeExprWithoutVariable.Assign.class, value(expression));
        AstNode target = child(assignment, "target");
        assertEquals(variableName, text(child(child(child(target, "cv"), "sv"), "var")));
        return assignment;
    }

    private static String number(AstNode expression) {
        return text(child(child(value(expression), "scalar"), "num"));
    }

    private static String text(AstNode node) {
        return assertInstanceOf(NodeString.class, node).getValue();
    }

    private static AstNode child(AstNode node, String label) {
        assertTrue(node.hasLabel(label), () -> node.getNodeName() + " 缺少字段 " + label);
        AstNode result = node.getByLabel(label);
        assertNotNull(result, label);
        return result;
    }

    private static void absent(AstNode node, String label) {
        assertFalse(node.hasLabel(label), label);
        assertNull(node.getByLabel(label), label);
    }

    private static List<AstNode> list(AstNode node, String label) {
        AstNode field = child(node, label);
        assertTrue(field.getNodeName().startsWith("List<"), () -> label + " 应直接返回列表");
        List<AstNode> result = new ArrayList<>();
        field.forEach(entry -> result.add(entry.getValue()));
        return result;
    }

    private static List<AstNode> named(AstNode tree, String name) {
        List<AstNode> result = new ArrayList<>();
        if (tree.getNodeName().equals(name)) result.add(tree);
        for (var entry : tree) result.addAll(named(entry.getValue(), name));
        return result;
    }

    private static AstNode onlyNamed(AstNode tree, String name) {
        List<AstNode> matches = named(tree, name);
        assertEquals(1, matches.size(), name);
        return matches.getFirst();
    }

    private static AstNode onlyVariant(AstNode tree) {
        List<AstNode> matches = named(tree, "callable_variable").stream()
                .filter(node -> node.getClass().getSimpleName().equals("Index")).toList();
        assertEquals(1, matches.size(), "Index");
        return matches.getFirst();
    }
}
