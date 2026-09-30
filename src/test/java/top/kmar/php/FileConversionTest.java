package top.kmar.php;

import java_cup.runtime.AstNode;
import org.junit.jupiter.api.Test;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** 验证完整文件的语法分段、原位声明和递归转换，不绑定导入或执行 PHP。 */
class FileConversionTest {

    // 空文件仍有一个空全局区段，未提供 sourceId 的入口不虚构来源标识。
    @Test
    void preservesAnEmptyGlobalSectionForEmptyFiles() {
        IrFile file = SyntaxConverter.convertFile(Main.parse("<?php "));
        assertNull(file.source().sourceId());
        assertEquals(1, file.namespaceSections().size());
        IrNamespaceSection section = file.namespaceSections().getFirst();
        assertEquals("", section.namespaceName());
        assertTrue(section.body().statements().isEmpty());
        assertNull(section.source().sourceId());
    }

    // 重复分号式命名空间保留为独立区段，空区段和末尾声明也不被合并或丢弃。
    @Test
    void keepsRepeatedSemicolonNamespacesAndEmptySectionsSeparate() {
        IrFile file = file("""
                namespace Same;
                use First\\Item as Shared;
                function first() {}
                namespace Same;
                use Second\\Item as Shared;
                function second() {}
                namespace EmptySection;
                namespace EmptySection;
                """);
        assertEquals(List.of("Same", "Same", "EmptySection", "EmptySection"), names(file));
        List<IrStatement> first = statements(file, 0);
        List<IrStatement> second = statements(file, 1);
        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertImport(assertInstanceOf(IrUse.class, first.getFirst()).imports().getFirst(),
                ImportKind.CLASS, "First\\Item", "Shared", "Shared");
        assertImport(assertInstanceOf(IrUse.class, second.getFirst()).imports().getFirst(),
                ImportKind.CLASS, "Second\\Item", "Shared", "Shared");
        assertEquals("first", assertInstanceOf(IrFunctionDeclaration.class, first.get(1)).name());
        assertEquals("second", assertInstanceOf(IrFunctionDeclaration.class, second.get(1)).name());
        assertTrue(statements(file, 2).isEmpty());
        assertTrue(statements(file, 3).isEmpty());
    }

    // 花括号区段、显式全局区段及相邻同名空区段按语法边界保留。
    @Test
    void preservesBracketedAndExplicitGlobalNamespaceSections() {
        IrFile file = file("""
                namespace EmptySection {}
                namespace Same { const FIRST = 1; }
                namespace Same { const SECOND = 2; }
                namespace { echo 3; }
                namespace {}
                """);
        assertEquals(List.of("EmptySection", "Same", "Same", "", ""), names(file));
        assertTrue(statements(file, 0).isEmpty());
        assertEquals("FIRST", assertInstanceOf(IrConstantDeclaration.class, statements(file, 1).getFirst()).name());
        assertEquals("SECOND", assertInstanceOf(IrConstantDeclaration.class, statements(file, 2).getFirst()).name());
        assertEcho(statements(file, 3).getFirst(), 3);
        assertTrue(statements(file, 4).isEmpty());
    }

    // 混合 namespace 写法不做额外合法性检查，花括号之后的连续代码回到新的全局区段。
    @Test
    void matchesExistingSectionBoundariesAcrossMixedNamespaceForms() {
        NodeProgram syntax = parse("""
                declare(strict_types=1);
                namespace A;
                function a() {}
                namespace B { function b() {} }
                echo 1;
                function outside() {}
                namespace C;
                function c() {}
                namespace {}
                echo 2;
                """);
        IrFile file = SyntaxConverter.convertFile(syntax, "file.php");
        assertEquals(List.of("", "A", "B", "", "C", "", ""), names(file));
        var extracted = DeclarationExtractor.extract(syntax, "file.php");
        assertEquals(extracted.namespaceSections().stream().map(NamespaceSection::namespaceName).toList(), names(file));
        assertInstanceOf(IrDeclare.class, statements(file, 0).getFirst());
        assertEquals("a", assertInstanceOf(IrFunctionDeclaration.class, statements(file, 1).getFirst()).name());
        assertEquals("b", assertInstanceOf(IrFunctionDeclaration.class, statements(file, 2).getFirst()).name());
        assertEquals(2, statements(file, 3).size());
        assertEcho(statements(file, 3).getFirst(), 1);
        assertEquals("outside", assertInstanceOf(IrFunctionDeclaration.class, statements(file, 3).get(1)).name());
        assertEquals("c", assertInstanceOf(IrFunctionDeclaration.class, statements(file, 4).getFirst()).name());
        assertTrue(statements(file, 5).isEmpty());
        assertEcho(statements(file, 6).getFirst(), 2);
    }

    // 普通、函数和常量导入逐条保留；绝对前缀归一化但别名拼写和类别不改变。
    @Test
    void convertsOrdinaryTypedAndAbsoluteImports() {
        List<IrStatement> statements = statements(file("""
                namespace CurrentSpace;
                use A\\B, \\C\\D as Alias;
                use function F\\one, \\F\\two as Two;
                use const G\\THREE, \\G\\FOUR as Four;
                """), 0);
        assertEquals(3, statements.size());
        List<ImportDeclaration> classes = assertInstanceOf(IrUse.class, statements.getFirst()).imports();
        List<ImportDeclaration> functions = assertInstanceOf(IrUse.class, statements.get(1)).imports();
        List<ImportDeclaration> constants = assertInstanceOf(IrUse.class, statements.get(2)).imports();
        assertEquals(2, classes.size());
        assertEquals(2, functions.size());
        assertEquals(2, constants.size());
        assertImport(classes.getFirst(), ImportKind.CLASS, "A\\B", null, "B");
        assertImport(classes.get(1), ImportKind.CLASS, "C\\D", "Alias", "Alias");
        assertImport(functions.getFirst(), ImportKind.FUNCTION, "F\\one", null, "one");
        assertImport(functions.get(1), ImportKind.FUNCTION, "F\\two", "Two", "Two");
        assertImport(constants.getFirst(), ImportKind.CONST, "G\\THREE", null, "THREE");
        assertImport(constants.get(1), ImportKind.CONST, "G\\FOUR", "Four", "Four");
    }

    // 分组在一条 IrUse 内展开，混合类别、组级类别、绝对前缀与尾随逗号均按原顺序处理。
    @Test
    void expandsEveryGroupImportFormWithinItsOwnStatement() {
        List<IrStatement> statements = statements(file("""
                use P\\{C, D as D1, function f, function g as G1, const K, const L as L1};
                use \\Q\\{Sub\\Clazz as SC, function Sub\\run, const Sub\\VALUE};
                use function \\R\\{f, sub\\g as rg,};
                use const S\\{K, sub\\L as sl,};
                use Plain\\{One, Two,};
                """), 0);
        assertEquals(5, statements.size());
        List<IrUse> uses = statements.stream().map(value -> assertInstanceOf(IrUse.class, value)).toList();
        assertEquals(List.of(6, 3, 2, 2, 2), uses.stream().map(value -> value.imports().size()).toList());
        List<ImportDeclaration> imports = uses.stream().flatMap(value -> value.imports().stream()).toList();
        assertEquals(List.of("P\\C", "P\\D", "P\\f", "P\\g", "P\\K", "P\\L", "Q\\Sub\\Clazz",
                "Q\\Sub\\run", "Q\\Sub\\VALUE", "R\\f", "R\\sub\\g", "S\\K", "S\\sub\\L",
                "Plain\\One", "Plain\\Two"), imports.stream().map(ImportDeclaration::targetName).toList());
        assertEquals(List.of(ImportKind.CLASS, ImportKind.CLASS, ImportKind.FUNCTION, ImportKind.FUNCTION,
                ImportKind.CONST, ImportKind.CONST, ImportKind.CLASS, ImportKind.FUNCTION, ImportKind.CONST,
                ImportKind.FUNCTION, ImportKind.FUNCTION, ImportKind.CONST, ImportKind.CONST,
                ImportKind.CLASS, ImportKind.CLASS), imports.stream().map(ImportDeclaration::kind).toList());
        assertImport(imports.get(6), ImportKind.CLASS, "Q\\Sub\\Clazz", "SC", "SC");
        assertImport(imports.get(10), ImportKind.FUNCTION, "R\\sub\\g", "rg", "rg");
        assertImport(imports.get(12), ImportKind.CONST, "S\\sub\\L", "sl", "sl");
    }

    // use 不提升到区段表，多项 const 原位展平且没有合成块，重复导入和声明仍全部保留。
    @Test
    void preservesInterleavedImportsConstantsAndExecutableStatements() {
        List<IrStatement> statements = statements(file("""
                echo 0;
                use Vendor\\Item as Item, Vendor\\Item as Item;
                const A = 1, A = 2;
                echo 3;
                use function Vendor\\run;
                const B = 4;
                run();
                """), 0);
        assertEquals(8, statements.size());
        assertEcho(statements.getFirst(), 0);
        IrUse firstUse = assertInstanceOf(IrUse.class, statements.get(1));
        assertEquals(2, firstUse.imports().size());
        for (ImportDeclaration item : firstUse.imports()) {
            assertImport(item, ImportKind.CLASS, "Vendor\\Item", "Item", "Item");
        }
        for (int i = 2; i <= 3; i++) {
            IrConstantDeclaration constant = assertInstanceOf(IrConstantDeclaration.class, statements.get(i));
            assertEquals("A", constant.name());
            assertInteger(constant.value(), i - 1);
        }
        assertEcho(statements.get(4), 3);
        assertInstanceOf(IrUse.class, statements.get(5));
        assertEquals("B", assertInstanceOf(IrConstantDeclaration.class, statements.get(6)).name());
        assertCall(assertInstanceOf(IrExpressionStatement.class, statements.get(7)).expression(), "run");
        assertTrue(statements.stream().noneMatch(IrBlock.class::isInstance));
    }

    // 常量值直接复用数值和字符串解码，复合表达式保留结构，不折叠或检查常量表达式合法性。
    @Test
    void decodesConstantValuesWithoutEvaluatingExpressions() {
        List<IrStatement> statements = statements(file("""
                namespace Demo;
                const INTEGER = 0x2A, FLOATING = 1.25, HUGE = 1e9999, TEXT = "A\\n\\x42",
                    NIL = null, SUM = 1 + 2, CALL = next(), MAGIC = __NAMESPACE__;
                """), 0);
        assertEquals(8, statements.size());
        List<IrConstantDeclaration> values = statements.stream()
                .map(value -> assertInstanceOf(IrConstantDeclaration.class, value)).toList();
        assertEquals(List.of("INTEGER", "FLOATING", "HUGE", "TEXT", "NIL", "SUM", "CALL", "MAGIC"),
                values.stream().map(IrConstantDeclaration::name).toList());
        assertInteger(values.getFirst().value(), 42);
        assertEquals(1.25, assertInstanceOf(IrFloatLiteral.class, values.get(1).value()).value());
        assertEquals(Double.POSITIVE_INFINITY, assertInstanceOf(IrFloatLiteral.class, values.get(2).value()).value());
        assertBytes(values.get(3).value(), "A\nB");
        assertInstanceOf(IrNullLiteral.class, values.get(4).value());
        IrBinary sum = assertInstanceOf(IrBinary.class, values.get(5).value());
        assertEquals(BinaryOperator.ADD, sum.operator());
        assertInteger(sum.left(), 1);
        assertInteger(sum.right(), 2);
        assertCall(values.get(6).value(), "next");
        assertEquals(MagicConstantKind.NAMESPACE, assertInstanceOf(IrMagicConstant.class, values.get(7).value()).kind());
    }

    // 完整文件包含四类具名声明，深层闭包、条件、类成员与生成器仍保持原有嵌套归属。
    @Test
    void recursivelyConvertsDeclarationsAndDeeplyNestedExecutableBodies() {
        List<IrStatement> statements = statements(file("""
                namespace App;
                use Vendor\\Base;
                function outer($value = "text") {
                    return function() {
                        if ($ready) {
                            class Local { function values() { yield 0x2A; } }
                        }
                    };
                }
                class Worker extends Base { use Feature; function run() { return outer(); } }
                interface Contract { public function run(); }
                trait Feature { function name() { return "feature"; } }
                """), 0);
        assertEquals(5, statements.size());
        assertInstanceOf(IrUse.class, statements.getFirst());
        IrFunctionDeclaration outer = assertInstanceOf(IrFunctionDeclaration.class, statements.get(1));
        assertEquals("outer", outer.name());
        assertBytes(outer.parameters().getFirst().defaultValue(), "text");
        IrClosure closure = assertInstanceOf(IrClosure.class,
                assertInstanceOf(IrReturn.class, outer.body().statements().getFirst()).value());
        IrIf condition = assertInstanceOf(IrIf.class, closure.body().statements().getFirst());
        IrClassDeclaration local = assertInstanceOf(IrClassDeclaration.class,
                condition.branches().getFirst().body().statements().getFirst());
        assertEquals("Local", local.name());
        IrMethod values = assertInstanceOf(IrMethod.class, local.members().getFirst());
        assertNotNull(values.body());
        IrYield yield = assertInstanceOf(IrYield.class,
                assertInstanceOf(IrExpressionStatement.class, values.body().statements().getFirst()).expression());
        assertInteger(yield.value(), 42);
        IrClassDeclaration worker = assertInstanceOf(IrClassDeclaration.class, statements.get(2));
        assertEquals("Base", worker.parentType().spelling());
        assertEquals("Feature", assertInstanceOf(IrTraitUse.class, worker.members().getFirst()).traits().getFirst().spelling());
        assertEquals("Contract", assertInstanceOf(IrInterfaceDeclaration.class, statements.get(3)).name());
        assertEquals("Feature", assertInstanceOf(IrTraitDeclaration.class, statements.get(4)).name());
    }

    // namespace 和 use 只保留语法环境，不把后续类名、函数名或常量引用提前解析成导入目标。
    @Test
    void leavesNamesUnboundAcrossImportsAndDeclarations() {
        List<IrStatement> statements = statements(file("""
                namespace App;
                use Vendor\\Thing as Alias;
                use function Vendor\\make as create;
                use const Vendor\\VALUE as LIMIT;
                class Local extends Alias {}
                const COUNT = LIMIT;
                create();
                """), 0);
        IrClassDeclaration local = assertInstanceOf(IrClassDeclaration.class, statements.get(3));
        assertEquals("Local", local.name());
        assertEquals("Alias", local.parentType().spelling());
        assertEquals(NameForm.UNQUALIFIED, local.parentType().form());
        IrConstantDeclaration constant = assertInstanceOf(IrConstantDeclaration.class, statements.get(4));
        assertEquals("COUNT", constant.name());
        IrConstantReference reference = assertInstanceOf(IrConstantReference.class, constant.value());
        assertEquals("LIMIT", reference.name().spelling());
        assertCall(assertInstanceOf(IrExpressionStatement.class, statements.get(5)).expression(), "create");
    }

    // declare 保持语句，define 仍是普通调用；不能把它们误识别成 const 声明或执行指令。
    @Test
    void distinguishesDeclareAndDefineFromConstantDeclarations() {
        List<IrStatement> statements = statements(file("""
                declare(strict_types=1);
                define('DYNAMIC', next());
                const STATIC_VALUE = 2;
                """), 0);
        assertEquals(3, statements.size());
        IrDeclare declare = assertInstanceOf(IrDeclare.class, statements.getFirst());
        assertNull(declare.body());
        assertEquals("strict_types", declare.directives().getFirst().name());
        assertInteger(declare.directives().getFirst().value(), 1);
        IrCall define = assertCall(assertInstanceOf(IrExpressionStatement.class, statements.get(1)).expression(), "define");
        assertEquals(2, define.arguments().size());
        assertBytes(define.arguments().getFirst().expression(), "DYNAMIC");
        assertCall(define.arguments().get(1).expression(), "next");
        assertInstanceOf(IrConstantDeclaration.class, statements.get(2));
    }

    // halt 留在原语句位置，词法忽略的尾随内容不重新解析、存储或执行。
    @Test
    void preservesHaltCompilerWithoutRecoveringIgnoredTailData() {
        for (String marker : List.of("__halt_compiler();", "__HaLt_CoMpIlEr ( \t ) ;")) {
            IrFile file = file("namespace App; echo 1; " + marker + " invalid {{{ `echo sentinel` <?php const BAD = 2;");
            assertEquals(List.of("App"), names(file));
            List<IrStatement> statements = statements(file, 0);
            assertEquals(2, statements.size());
            assertEcho(statements.getFirst(), 1);
            assertEquals("file.php", assertInstanceOf(IrHaltCompiler.class, statements.get(1)).source().sourceId());
        }
    }

    // 按需主体入口同样接收 use/多项 const/halt，顶层常量与文件入口有相同展平结果。
    @Test
    void sharesTopLevelStatementConversionWithSelectedBodies() {
        NodeProgram syntax = parse("use A\\B; const X = 1, Y = 2; echo Y; __halt_compiler();ignored");
        IrFile file = SyntaxConverter.convertFile(syntax, "file.php");
        IrBlock body = SyntaxConverter.convertBody(new SyntaxBody(
                List.copyOf(syntax.getStmts().getValue()), new SourceInfo("file.php", null)));
        assertEquals(statements(file, 0), body.statements());
        assertEquals(5, body.statements().size());
        assertInstanceOf(IrUse.class, body.statements().getFirst());
        assertInstanceOf(IrConstantDeclaration.class, body.statements().get(1));
        assertInstanceOf(IrConstantDeclaration.class, body.statements().get(2));
        assertInstanceOf(IrEcho.class, body.statements().get(3));
        assertInstanceOf(IrHaltCompiler.class, body.statements().get(4));
    }

    // namespace 边界只允许整文件入口处理，按需主体不能悄悄丢失或吸收区段。
    @Test
    void rejectsNamespaceBoundariesAtTheSelectedBodyEntryPoint() {
        for (String code : List.of("namespace A;", "namespace A {}", "namespace {}")) {
            NodeProgram syntax = parse(code);
            SyntaxBody body = new SyntaxBody(List.copyOf(syntax.getStmts().getValue()), new SourceInfo("file.php", null));
            assertDoesNotThrow(() -> SyntaxConverter.convertFile(syntax, "file.php"), code);
            var error = assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(body), code);
            assertEquals("file.php", error.source().sourceId());
            assertFalse(error.fieldPath().isBlank());
        }
    }

    // 文法可构造的嵌套 namespace 明确失败，不能把内层区段提升或合并到外层文件。
    @Test
    void rejectsNestedNamespaceSectionsWithoutPromotingThem() {
        for (String code : List.of("namespace Outer { namespace Inner; }",
                "namespace Outer { namespace Inner {} }", "namespace Outer { namespace {} }",
                "namespace { namespace Inner {} }")) {
            NodeProgram syntax = assertDoesNotThrow(() -> parse(code), code);
            var error = assertThrows(SyntaxConversionException.class,
                    () -> SyntaxConverter.convertFile(syntax, "file.php"), code);
            assertEquals("file.php", error.source().sourceId());
            assertFalse(error.fieldPath().isBlank(), code);
            assertFalse(error.reason().isBlank(), code);
        }
    }

    // 文件遍历不能吞掉任何深度的不允许的读取子树，后置命名空间和第二项常量也必须失败。
    @Test
    void propagatesUnsupportedSubtreesFromEveryFileContainer() {
        for (String code : List.of("const OK = 1, BAD = ($invalid[]);",
                "namespace A; echo 1; namespace B; const BAD = ($invalid[]);",
                "namespace A { function f($value = ($invalid[])) {} }",
                "namespace A { class C { function run() { return ($invalid[]); } } }",
                "namespace { interface I { const BAD = ($invalid[]); } }",
                "trait T { function f() { return function() { ($invalid[]); }; } }")) {
            NodeProgram syntax = assertDoesNotThrow(() -> parse(code), code);
            var error = assertThrows(SyntaxConversionException.class,
                    () -> SyntaxConverter.convertFile(syntax, "file.php"), code);
            assertEquals("file.php", error.source().sourceId());
            assertFalse(error.fieldPath().isBlank(), code);
            assertFalse(error.reason().isBlank(), code);
        }
    }

    // 已选函数可独立转换，但完整文件必须检查未选函数及常量初始化值，不跳过失败部分。
    @Test
    void distinguishesCompleteFileConversionFromSelectedBodyConversion() {
        AstNode syntax = Main.parse("""
                <?php
                namespace App;
                function good() { return 1; }
                function unrelated() { ($invalid[]); }
                const ALSO_BAD = ($invalid[]);
                """);
        var extracted = DeclarationExtractor.extract(syntax, "file.php");
        FunctionDefinition good = assertInstanceOf(FunctionDefinition.class,
                extracted.namespaceSections().getFirst().declarations().getFirst());
        IrBlock selected = SyntaxConverter.convertBody(good.body());
        assertInteger(assertInstanceOf(IrReturn.class, selected.statements().getFirst()).value(), 1);
        assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertFile(syntax, "file.php"));
        assertEquals(selected, SyntaxConverter.convertBody(good.body()));
    }

    // 重复和并行转换共享只读 AST 也互不污染，字符串解码缓冲区及区段状态属于每次调用。
    @Test
    void keepsRepeatedAndConcurrentFileConversionsIndependent() throws Exception {
        NodeProgram syntax = parse("""
                namespace First;
                use Vendor\\Thing;
                const TEXT = "A\\n\\x42";
                function run() { return "你好"; }
                namespace Second { const NUMBER = 0x2A; }
                echo 3;
                """);
        String before = syntax.toTreeString(false);
        IrFile expected = SyntaxConverter.convertFile(syntax, "file.php");
        assertEquals(expected, SyntaxConverter.convertFile(syntax, "file.php"));
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<IrFile>>();
            for (int i = 0; i < 12; i++) tasks.add(() -> SyntaxConverter.convertFile(syntax, "file.php"));
            for (var future : executor.invokeAll(tasks)) assertEquals(expected, future.get());
        }
        assertEquals(before, syntax.toTreeString(false));
        assertBytes(assertInstanceOf(IrConstantDeclaration.class, statements(expected, 0).get(1)).value(), "A\nB");
        IrFile another = SyntaxConverter.convertFile(syntax, "another.php");
        assertEquals("another.php", another.source().sourceId());
        assertEquals("file.php", expected.source().sourceId());
    }

    private static IrFile file(String code) {
        return SyntaxConverter.convertFile(parse(code), "file.php");
    }

    private static NodeProgram parse(String code) {
        return assertInstanceOf(NodeProgram.class, Main.parse("<?php " + code));
    }

    private static List<String> names(IrFile file) {
        return file.namespaceSections().stream().map(IrNamespaceSection::namespaceName).toList();
    }

    private static List<IrStatement> statements(IrFile file, int section) {
        return file.namespaceSections().get(section).body().statements();
    }

    private static void assertImport(ImportDeclaration actual, ImportKind kind, String target,
                                     String declaredAlias, String effectiveAlias) {
        assertEquals(kind, actual.kind());
        assertEquals(target, actual.targetName());
        assertEquals(declaredAlias, actual.declaredAlias());
        assertEquals(effectiveAlias, actual.alias());
    }

    private static void assertInteger(IrExpression expression, long value) {
        assertEquals(value, assertInstanceOf(IrIntegerLiteral.class, expression).value());
    }

    private static void assertEcho(IrStatement statement, long value) {
        IrEcho echo = assertInstanceOf(IrEcho.class, statement);
        assertEquals(1, echo.expressions().size());
        assertInteger(echo.expressions().getFirst(), value);
    }

    private static void assertBytes(IrExpression expression, String value) {
        assertArrayEquals(value.getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringLiteral.class, expression).value().toByteArray());
    }

    private static IrCall assertCall(IrExpression expression, String name) {
        IrCall call = assertInstanceOf(IrCall.class, expression);
        assertEquals(name, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
        return call;
    }
}
