package top.kmar.php;

import java_cup.runtime.AstNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** CUP 升级后直接验证 AST 字段、列表和结合性，而非仅验证解析成功。 */
class AstStructureTest {

    @Test
    void 空程序和空声明保留空列表并支持遍历() {
        AstNode empty = Main.parse("<?php\n");
        assertEquals("program", empty.getNodeName());
        assertTrue(list(empty, "stmts").isEmpty());
        assertTrue(empty.getLocation().isNoLocation());

        AstNode tree = Main.parse("<?php function f() {} class C {} namespace N {} namespace {}");
        List<AstNode> statements = list(tree, "stmts");
        assertEquals(4, statements.size());
        AstNode function = child(statements.get(0), "function");
        assertTrue(list(function, "params").isEmpty());
        assertTrue(list(function, "stmts").isEmpty());
        assertTrue(list(child(statements.get(1), "clazz"), "members").isEmpty());
        assertTrue(list(statements.get(2), "stmts").isEmpty());
        assertTrue(list(statements.get(3), "stmts").isEmpty());
        assertDoesNotThrow(() -> tree.toTreeString(false));
    }

    @Test
    void 参数和函数的可选字段直接表示有无() {
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
        assertEquals("type_expr", child(params.get(0), "type").getNodeName());
        assertEquals("?", text(child(child(params.get(0), "type"), "op")));
        assertEquals("&", text(child(params.get(0), "byRef")));
        absent(params.get(0), "variadic");
        assertEquals("...", text(child(params.get(1), "variadic")));
        absent(params.get(1), "type");
        absent(params.get(1), "byRef");
        assertEquals("rest", text(child(params.get(1), "var")));
        assertDoesNotThrow(() -> plain.toTreeString(false));
        assertDoesNotThrow(() -> referenced.toTreeString(false));
    }

    @Test
    void 直接列表保留语句参数与表达式顺序() {
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

    @Test
    void 方法和类常量的修饰符直接列表保留空值与顺序() {
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

    @Test
    void 构造参数的缺省与显式空列表可区分() {
        AstNode withoutArgs = onlyNamed(expression("new Foo"), "new_expr");
        absent(withoutArgs, "ctorArgs");
        AstNode emptyArgs = onlyNamed(expression("new Foo()"), "new_expr");
        assertTrue(list(child(emptyArgs, "ctorArgs"), "args").isEmpty());
        AstNode withArgs = onlyNamed(expression("new Foo(1, 2)"), "new_expr");
        assertEquals(List.of("1", "2"), list(child(withArgs, "ctorArgs"), "args").stream()
                .map(argument -> number(child(argument, "arg"))).toList());
    }

    @Test
    void 方括号偏移可选字段不再使用包装节点() {
        AstNode emptyIndex = onlyVariant(expression("$a[]"), "callable_variable", "Index");
        absent(emptyIndex, "offset");
        AstNode index = onlyVariant(expression("$a[1]"), "callable_variable", "Index");
        assertEquals("expr", child(index, "offset").getNodeName());
        assertEquals("1", number(child(index, "offset")));
    }

    @Test
    void 数组空槽和尾逗号不会丢失() {
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

    @Test
    void Php72参数和实参禁止尾逗号而分组导入允许() {
        assertThrows(PhpParseException.class, () -> Main.parse("<?php function f($a,) {}"));
        assertThrows(PhpParseException.class, () -> Main.parse("<?php f(1,);"));
        AstNode group = child(top("use A\\{B, C,};"), "mixedUse");
        assertEquals(2, list(group, "uses").size());
    }

    @Test
    void 普通运算统一为Binary而复合赋值为AssignOp() {
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

    @Test
    void 乘法优先且减法左结合() {
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

    @Test
    void 幂和空合并右结合() {
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

    @Test
    void 三元在Php72左结合且短三元没有中间字段() {
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

    @Test
    void Else绑定最近的If() {
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

    private static AstNode onlyVariant(AstNode tree, String name, String variant) {
        List<AstNode> matches = named(tree, name).stream()
                .filter(node -> node.getClass().getSimpleName().equals(variant)).toList();
        assertEquals(1, matches.size(), variant);
        return matches.getFirst();
    }
}
