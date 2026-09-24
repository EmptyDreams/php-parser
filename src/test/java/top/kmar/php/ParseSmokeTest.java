package top.kmar.php;

import java_cup.runtime.AstNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冒烟测试：覆盖 PHP 7.2 的主要语法特性，保证整条流水线
 * （jflex 词法 -> CUP 语法分析 -> AST）可用。
 */
class ParseSmokeTest {

    private AstNode parse(String php) {
        return Main.parse(php);
    }

    // 验证源文件必须以 PHP 开始标签开头，且不能重复开启 PHP 模式。
    @Test
    void requiresPhpOpeningTag() {
        assertThrows(PhpLexerException.class, () -> parse("echo 1;"));
        assertThrows(Exception.class, () -> parse("<?php echo 1; <?php echo 2;"));
    }

    // 验证仅包含 PHP 开始标签和空白的程序可以解析。
    @Test
    void parsesEmptyPhpPrograms() {
        assertDoesNotThrow(() -> parse("<?php\n"));
        assertDoesNotThrow(() -> parse("<?php "));
    }

    // 验证 PHP 关闭标签可以充当隐式分号。
    @Test
    void treatsClosingTagAsImplicitSemicolon() {
        // "?>" 等价于 ";"，之后的 HTML 被丢弃，允许再次进入 PHP 模式
        assertDoesNotThrow(() -> parse("<?php if (true) { ?>html<?php } echo 1; ?>"));
        assertDoesNotThrow(() -> parse("<?php $a = 1 ?><?php echo $a;"));
    }

    // 验证混合运算符、括号和不同优先级的表达式可以解析。
    @Test
    void parsesExpressionsWithMixedOperatorPrecedence() {
        assertDoesNotThrow(() -> parse("<?php $r = 1 + 2 * 3 ** 2 << 1 | 4 & 5 ^ 6;"));
        assertDoesNotThrow(() -> parse("<?php $a = $x ?? $y ?? 'default';"));
        assertDoesNotThrow(() -> parse("<?php $b = -$c + !$d . ~$e;"));
        assertDoesNotThrow(() -> parse("<?php $f = ($g + 1) * 2;"));
        assertDoesNotThrow(() -> parse("<?php $h = 1 <=> 2; $i = 1 === 2; $j = 1 != 2;"));
        assertDoesNotThrow(() -> parse("<?php $k = $cond ? 'a' : 'b'; $l = $cond ?: 'b';"));
        assertDoesNotThrow(() -> parse("<?php $m .= 'x' . $n . 'y';"));
        assertDoesNotThrow(() -> parse("<?php print $a and $b or $c xor $d;"));
        assertDoesNotThrow(() -> parse("<?php $o = $arr['k']{0} . $arr[0];"));
    }

    // 验证带括号的嵌套三元表达式可以解析。
    @Test
    void parsesNestedTernaryExpressions() {
        assertDoesNotThrow(() -> parse("<?php $a = $b ? $c : ($d ? $e : $f);"));
    }

    // 验证动态变量、成员访问、解构和引用等变量语法可以解析。
    @Test
    void parsesVariableSyntaxForms() {
        assertDoesNotThrow(() -> parse("<?php $a = $$b;"));
        assertDoesNotThrow(() -> parse("<?php $a = ${$b};"));
        assertDoesNotThrow(() -> parse("<?php $a = $obj->prop->chain->method()->field[0];"));
        assertDoesNotThrow(() -> parse("<?php $a = $obj->{'dyn'}->$dynProp;"));
        assertDoesNotThrow(() -> parse("<?php $a = Foo::$static; $b = $foo::class; $c = Foo::BAR;"));
        assertDoesNotThrow(() -> parse("<?php $a = ($foo)::$prop[1];"));
        assertDoesNotThrow(() -> parse("<?php $a = $foo['k'][1][2];"));
        assertDoesNotThrow(() -> parse("<?php $a = 'str'{'i'};"));
        assertDoesNotThrow(() -> parse("<?php Foo::$bar::$baz = 1;"));
        assertDoesNotThrow(() -> parse("<?php list($a, $b) = [1, 2]; [$c, , $d] = $arr;"));
        assertDoesNotThrow(() -> parse("<?php $e = &$f;"));
    }

    // 验证条件、循环、分支和跳转等控制流语句可以解析。
    @Test
    void parsesControlFlowStatements() {
        assertDoesNotThrow(() -> parse(
                "<?php if ($a) { 1; } elseif ($b) { 2; } else if ($c) { 3; } else { 4; }"));
        assertDoesNotThrow(() -> parse(
                "<?php if ($a): 1; elseif ($b): 2; else: 3; endif;"));
        assertDoesNotThrow(() -> parse(
                "<?php while (true) { break 2; continue; }"));
        assertDoesNotThrow(() -> parse(
                "<?php do { 1; } while ($a);"));
        assertDoesNotThrow(() -> parse(
                "<?php for ($i = 0; $i < 10; $i++, $j--) { echo $i; }"));
        assertDoesNotThrow(() -> parse(
                "<?php for ($i = 0;;) : echo 1; endfor;"));
        assertDoesNotThrow(() -> parse(
                "<?php foreach ($arr as $v) {} foreach ($arr as $k => $v) {} "
                + "foreach ($arr as &$ref) {} foreach ($a as [$x, $y]) {}"));
        assertDoesNotThrow(() -> parse(
                "<?php switch ($a) { case 1: break; case 2; default: break; }"));
        assertDoesNotThrow(() -> parse(
                "<?php switch ($a): case 1: ; default: ; endswitch;"));
        assertDoesNotThrow(() -> parse(
                "<?php goto end; end: echo 1;"));
    }

    // 验证函数、闭包以及参数和返回值类型声明可以解析。
    @Test
    void parsesFunctionsAndTypeDeclarations() {
        assertDoesNotThrow(() -> parse(
                "<?php function f(int $a, string $b = 'x', ?Foo ...$rest): ?int { return $a; }"));
        assertDoesNotThrow(() -> parse(
                "<?php function g(array $a, callable $c): void {}"));
        assertDoesNotThrow(() -> parse(
                "<?php function &h(object $o) { return $o; }"));
        assertDoesNotThrow(() -> parse(
                "<?php $fn = function ($x) use ($a, &$b): int { return $x; };"));
        assertDoesNotThrow(() -> parse(
                "<?php $fn = static function () use ($c) {};"));
        assertDoesNotThrow(() -> parse(
                "<?php function withRef(&$param) {}"));
    }

    // 验证类、接口、Trait 和匿名类声明可以解析。
    @Test
    void parsesClassesInterfacesAndTraits() {
        assertDoesNotThrow(() -> parse("<?php class A {}"));
        assertDoesNotThrow(() -> parse(
                "<?php abstract class A extends B implements C, D {"
                + " const K = 1, J = 2;"
                + " public $x = 1, $y;"
                + " private static $z = null;"
                + " var $w;"
                + " public const VISIBILITY = 2;"
                + " final protected function m(): iterable { }"
                + " abstract public function am();"
                + " public static function sm() { return new self(); }"
                + "}"));
        assertDoesNotThrow(() -> parse(
                "<?php interface I extends I1, I2 { const K = 1; public function f(); }"));
        assertDoesNotThrow(() -> parse(
                "<?php trait T {"
                + " public function f() {}"
                + " abstract function g();"
                + "}"));
        assertDoesNotThrow(() -> parse(
                "<?php class C {"
                + " use T1, T2 { T1::f insteadof T2; T2::g as h; }"
                + " use T3;"
                + " use T4 { f as public; }"
                + " use T5 { f as protected newF; }"
                + "}"));
        assertDoesNotThrow(() -> parse(
                "<?php $anon = new class(1, 2) extends P implements I {"
                + " private $x;"
                + " public function m() { return $this->x; }"
                + " };"));
    }

    // 验证命名空间声明和各类 use 导入语句可以解析。
    @Test
    void parsesNamespacesAndUseDeclarations() {
        assertDoesNotThrow(() -> parse("<?php namespace A\\B\\C;"));
        assertDoesNotThrow(() -> parse("<?php namespace A\\B { function f() {} }"));
        assertDoesNotThrow(() -> parse("<?php namespace { function g() {} }"));
        assertDoesNotThrow(() -> parse(
                "<?php use A\\B, C\\D as E; use function F\\g; use const G\\H;"));
        assertDoesNotThrow(() -> parse(
                "<?php use A\\B\\{C, D as E, function f, const K};"));
        assertDoesNotThrow(() -> parse(
                "<?php use \\A\\B\\{C,};"));
        assertDoesNotThrow(() -> parse(
                "<?php use \\A, \\B\\C;"));
    }

    // 验证数组、解构以及不同格式的数值字面量可以解析。
    @Test
    void parsesDataStructuresAndLiterals() {
        assertDoesNotThrow(() -> parse("<?php $a = []; $b = array();"));
        assertDoesNotThrow(() -> parse(
                "<?php $a = [1, 2, , 4,]; $b = array('k' => 'v', 'w' => &$ref);"));
        assertDoesNotThrow(() -> parse(
                "<?php $a = ['k' => [1, 2], 'nested' => ['x' => 1]];"));
        assertDoesNotThrow(() -> parse(
                "<?php list('k' => $a, 'j' => [$b, $c]) = $d;"));
        assertDoesNotThrow(() -> parse(
                "<?php $h1 = 0x1A; $h2 = 0b1010; $h3 = 0777;"));
        assertDoesNotThrow(() -> parse(
                "<?php $f1 = 1.5; $f2 = .5; $f3 = 1.; $f4 = 1e10; $f5 = 1.5e-3;"));
    }

    // 验证字符串转义、变量插值和反引号表达式可以解析。
    @Test
    void parsesStringsAndInterpolation() {
        assertDoesNotThrow(() -> parse("<?php $a = 'plain'; $b = 'esc \\' quote'; $c = 'multi\\nline';"));
        assertDoesNotThrow(() -> parse("<?php $d = \"no interp\";"));
        assertDoesNotThrow(() -> parse("<?php $e = \"with $var here\";"));
        assertDoesNotThrow(() -> parse("<?php $f = \"{$expr['k']->prop()} end\";"));
        assertDoesNotThrow(() -> parse("<?php $g = \"${name}\"; $h = \"${name[0]}\";"));
        assertDoesNotThrow(() -> parse("<?php $i = \"$arr[key] $arr[0] $arr[-1] $obj->prop\";"));
        assertDoesNotThrow(() -> parse("<?php $j = `ls -la`; $k = `ls $dir`;"));
        assertDoesNotThrow(() -> parse("<?php $m = \"$obj->\"; $n = \"dollar \\$x and brace {y}\";"));
        assertDoesNotThrow(() -> parse("<?php $o = \"nested {$a['x']} {$$b}\";"));
    }

    // 验证 heredoc 和 nowdoc 的内容、插值及结束标签可以解析。
    @Test
    void parsesHeredocAndNowdoc() {
        assertDoesNotThrow(() -> parse("<?php $a = <<<EOT\nplain text\nEOT;\n"));
        assertDoesNotThrow(() -> parse("<?php $b = <<<'NOW'\nno $interp\nNOW;\n"));
        assertDoesNotThrow(() -> parse("<?php $c = <<<EOT\nwith $var and {$e['k']}\nEOT;\n"));
        assertDoesNotThrow(() -> parse("<?php $d = <<<EOT\nEOT;\n"));
        // 标签出现在行中间时是普通文本，只有整行标签才结束
        assertDoesNotThrow(() -> parse("<?php $e = <<<EOT\nxEOT y EOTz\nEOT;\n"));
        // 结束标签行带分号
        assertDoesNotThrow(() -> parse("<?php $f = <<<EOT\ntext\nEOT;\n echo 1;"));
    }

    // 验证生成器中的 yield 和 yield from 表达式可以解析。
    @Test
    void parsesGeneratorsAndYieldExpressions() {
        assertDoesNotThrow(() -> parse("<?php function g() { yield; yield 1; yield $k => $v; }"));
        assertDoesNotThrow(() -> parse("<?php function h() { yield from g(); $x = yield; }"));
        assertDoesNotThrow(() -> parse("<?php function yields() { yield 1 + 2; }"));
    }

    // 验证内建语法、类型转换、异常处理和其他关键字可以解析。
    @Test
    void parsesBuiltinsAndOtherKeywords() {
        assertDoesNotThrow(() -> parse(
                "<?php isset($a, $b['k']); empty($c);"));
        assertDoesNotThrow(() -> parse(
                "<?php $x = include 'a.php'; $y = include_once 'b.php';"
                + " $z = require 'c.php'; $w = require_once 'd.php';"
                + " $v = eval('code');"));
        assertDoesNotThrow(() -> parse(
                "<?php $s = (int)$a . (integer)$b . (double)$c . (float)$d . (real)$e"
                + " . (string)$f . (binary)$g . (array)$h . (object)$i . (bool)$j . (boolean)$k . (unset)$l;"));
        assertDoesNotThrow(() -> parse(
                "<?php @$err(); exit; die; exit(1); die('msg');"));
        assertDoesNotThrow(() -> parse(
                "<?php $a = clone $b; $c = $d instanceof Foo;"));
        assertDoesNotThrow(() -> parse(
                "<?php global $x, $$y; static $z = 1, $w = [1]; unset($x, $y[0]);"));
        assertDoesNotThrow(() -> parse(
                "<?php echo 1, 2, 3; print 4; throw $e;"));
        assertDoesNotThrow(() -> parse(
                "<?php try { f(); } catch (A|B $e) { } catch (C $e2) { } finally { g(); }"));
        assertDoesNotThrow(() -> parse(
                "<?php declare(strict_types=1); declare(ticks=1) { echo 1; } declare(ticks=1): echo 2; enddeclare;"));
        assertDoesNotThrow(() -> parse(
                "<?php $a = __LINE__ + __FILE__ + __DIR__ + __FUNCTION__ + __CLASS__"
                + " + __TRAIT__ + __METHOD__ + __NAMESPACE__;"));
        assertDoesNotThrow(() -> parse(
                "<?php function reserved() { $list = $a->list(); $b->new(); $c::print(); }"));
        assertDoesNotThrow(() -> parse(
                "<?php $obj->method()->prop[0]::$static::CONSTANT;"));
        assertDoesNotThrow(() -> parse(
                "<?php __halt_compiler();\n<<<raw binary data>>>"));
        assertDoesNotThrow(() -> parse(
                "<?php echo 1 // comment with ?> not closed\n + 2;"));
        assertDoesNotThrow(() -> parse(
                "<?php /* block */ echo /** doc */ 1; # hash comment\n echo 2;"));
    }

    // 验证行注释中的 PHP 关闭标签会结束 PHP 模式。
    @Test
    void handlesClosingTagsInLineComments() {
        // 行注释中的 ?> 会结束 PHP 模式（zend 语义），剩余内容被丢弃
        assertDoesNotThrow(() -> parse("<?php echo 1; // comment ?> garbage"));
        assertDoesNotThrow(() -> parse("<?php echo 1; # comment ?> garbage <?php echo 2;"));
    }

    // 验证语法树文本包含程序根节点、表达式节点和运算符。
    @Test
    void includesExpectedNodesInAstTree() {
        AstNode tree = parse("<?php $a = 1 + 2;");
        String text = tree.toTreeString(false);
        assertTrue(text.contains("program"), "根节点应为 program: " + text);
        assertTrue(text.contains("expr"), "树中应包含 expr 节点: " + text);
        assertTrue(text.contains("+"), "树中应包含 + 运算符: " + text);
    }
}
