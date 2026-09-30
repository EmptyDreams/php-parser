package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 具名声明完整进入独立 IR，保留原位置与递归结构，不注册符号或执行声明合法性检查。 */
class NamedDeclarationConversionTest {

    // 顶层四种声明均可作为语句转换，空列表和缺省字段保持明确的空值。
    @Test
    void convertsAllFourEmptyTopLevelDeclarations() {
        List<IrStatement> statements = body("function Work() {} class Item {} interface Contract {} trait Feature {}").statements();
        assertEquals(4, statements.size());
        IrFunctionDeclaration function = assertInstanceOf(IrFunctionDeclaration.class, statements.getFirst());
        assertEquals("Work", function.name());
        assertTrue(function.parameters().isEmpty());
        assertNull(function.returnType());
        assertFalse(function.returnsReference());
        assertTrue(function.body().statements().isEmpty());
        IrClassDeclaration clazz = assertInstanceOf(IrClassDeclaration.class, statements.get(1));
        assertEquals("Item", clazz.name());
        assertTrue(clazz.declaredModifiers().isEmpty());
        assertNull(clazz.parentType());
        assertTrue(clazz.interfaces().isEmpty());
        assertTrue(clazz.members().isEmpty());
        IrInterfaceDeclaration iface = assertInstanceOf(IrInterfaceDeclaration.class, statements.get(2));
        assertEquals("Contract", iface.name());
        assertTrue(iface.parentTypes().isEmpty());
        assertTrue(iface.members().isEmpty());
        IrTraitDeclaration trait = assertInstanceOf(IrTraitDeclaration.class, statements.get(3));
        assertEquals("Feature", trait.name());
        assertTrue(trait.members().isEmpty());
    }

    // 函数中的四种声明与普通语句交错保留，重复名称不会覆盖或被无条件提升。
    @Test
    void keepsNestedDeclarationsInOriginalStatementOrder() {
        IrFunctionDeclaration outer = function("""
                function outer() {
                    echo 0;
                    function repeated() {}
                    class Local {}
                    interface Contract {}
                    trait Feature {}
                    function repeated() { return 1; }
                    echo 2;
                }
                """);
        List<IrStatement> statements = outer.body().statements();
        assertEquals(7, statements.size());
        assertInteger(assertInstanceOf(IrEcho.class, statements.getFirst()).expressions().getFirst(), 0);
        assertEquals("repeated", assertInstanceOf(IrFunctionDeclaration.class, statements.get(1)).name());
        assertEquals("Local", assertInstanceOf(IrClassDeclaration.class, statements.get(2)).name());
        assertEquals("Contract", assertInstanceOf(IrInterfaceDeclaration.class, statements.get(3)).name());
        assertEquals("Feature", assertInstanceOf(IrTraitDeclaration.class, statements.get(4)).name());
        IrFunctionDeclaration repeated = assertInstanceOf(IrFunctionDeclaration.class, statements.get(5));
        assertEquals("repeated", repeated.name());
        assertInteger(assertInstanceOf(IrReturn.class, repeated.body().statements().getFirst()).value(), 1);
        assertInteger(assertInstanceOf(IrEcho.class, statements.get(6)).expressions().getFirst(), 2);
    }

    // 函数复用方法和闭包的签名规则；类型、引用、变长、缺省与显式 null 分别保留。
    @Test
    void preservesFunctionSignatureTypesFlagsAndDefaultExpressions() {
        IrFunctionDeclaration function = function("""
                function &Work($plain, ?\\Pkg\\Value &$value = null, array $items = [1],
                               callable $factory = makeFactory(), int ...$rest): ?namespace\\Result {
                    return $value;
                }
                """);
        assertEquals("Work", function.name());
        assertTrue(function.returnsReference());
        assertEquals(List.of("plain", "value", "items", "factory", "rest"),
                function.parameters().stream().map(IrParameter::name).toList());
        IrParameter plain = function.parameters().getFirst();
        assertNull(plain.declaredType());
        assertNull(plain.defaultValue());
        assertFalse(plain.byReference());
        assertFalse(plain.variadic());
        IrParameter value = function.parameters().get(1);
        assertTrue(value.byReference());
        assertFalse(value.variadic());
        assertNotNull(value.declaredType());
        assertTrue(value.declaredType().nullable());
        assertName(value.declaredType().name(), "\\Pkg\\Value", NameForm.FULLY_QUALIFIED);
        assertEquals(LiteralKind.NULL, assertInstanceOf(IrLiteral.class, value.defaultValue()).kind());
        IrParameter items = function.parameters().get(2);
        assertNotNull(items.declaredType());
        assertFalse(items.declaredType().nullable());
        assertName(items.declaredType().name(), "array", NameForm.UNQUALIFIED);
        assertEquals(1, assertInstanceOf(IrArrayLiteral.class, items.defaultValue()).entries().size());
        IrParameter factory = function.parameters().get(3);
        assertNotNull(factory.declaredType());
        assertName(factory.declaredType().name(), "callable", NameForm.UNQUALIFIED);
        assertCall(factory.defaultValue(), "makeFactory");
        IrParameter rest = function.parameters().get(4);
        assertTrue(rest.variadic());
        assertFalse(rest.byReference());
        assertNull(rest.defaultValue());
        assertNotNull(rest.declaredType());
        assertName(rest.declaredType().name(), "int", NameForm.UNQUALIFIED);
        assertNotNull(function.returnType());
        assertTrue(function.returnType().nullable());
        assertName(function.returnType().name(), "namespace\\Result", NameForm.NAMESPACE_RELATIVE);
        assertVariable(assertInstanceOf(IrReturn.class, function.body().statements().getFirst()).value(), "value");
    }

    // 类修饰符和继承列表保留重复与顺序，四种名称形式均不绑定或自动限定。
    @Test
    void preservesClassModifiersAndInheritanceNameForms() {
        IrClassDeclaration clazz = assertInstanceOf(IrClassDeclaration.class, only("""
                abstract final abstract class Mixed extends \\Base
                    implements Local, Rel\\Contract, \\Root\\Contract, namespace\\Contract, Local {}
                """));
        assertEquals("Mixed", clazz.name());
        assertEquals(List.of(Modifier.ABSTRACT, Modifier.FINAL, Modifier.ABSTRACT), clazz.declaredModifiers());
        assertName(clazz.parentType(), "\\Base", NameForm.FULLY_QUALIFIED);
        assertEquals(5, clazz.interfaces().size());
        assertName(clazz.interfaces().getFirst(), "Local", NameForm.UNQUALIFIED);
        assertName(clazz.interfaces().get(1), "Rel\\Contract", NameForm.QUALIFIED);
        assertName(clazz.interfaces().get(2), "\\Root\\Contract", NameForm.FULLY_QUALIFIED);
        assertName(clazz.interfaces().get(3), "namespace\\Contract", NameForm.NAMESPACE_RELATIVE);
        assertName(clazz.interfaces().get(4), "Local", NameForm.UNQUALIFIED);
        for (String parent : List.of("Base", "Rel\\Base", "namespace\\Base")) {
            var declaration = assertInstanceOf(IrClassDeclaration.class, only("class C extends " + parent + " {}"));
            assertNotNull(declaration.parentType());
            assertEquals(parent, declaration.parentType().spelling());
        }
    }

    // 接口继承是有序多父接口列表，不能套用类的单父类字段；方法分号体保持 null。
    @Test
    void preservesInterfaceParentsConstantsAndBodylessMethods() {
        IrInterfaceDeclaration iface = assertInstanceOf(IrInterfaceDeclaration.class, only("""
                interface Contract extends Local, Rel\\Contract, \\Root\\Contract, namespace\\Contract, Local {
                    const FIRST = 1, SECOND = 2;
                    public function &read(?Foo &$value = null): ?Bar;
                }
                """));
        assertEquals(5, iface.parentTypes().size());
        assertName(iface.parentTypes().getFirst(), "Local", NameForm.UNQUALIFIED);
        assertName(iface.parentTypes().get(1), "Rel\\Contract", NameForm.QUALIFIED);
        assertName(iface.parentTypes().get(2), "\\Root\\Contract", NameForm.FULLY_QUALIFIED);
        assertName(iface.parentTypes().get(3), "namespace\\Contract", NameForm.NAMESPACE_RELATIVE);
        assertName(iface.parentTypes().get(4), "Local", NameForm.UNQUALIFIED);
        assertEquals(3, iface.members().size());
        assertEquals("FIRST", assertInstanceOf(IrClassConstant.class, iface.members().getFirst()).name());
        assertInteger(assertInstanceOf(IrClassConstant.class, iface.members().get(1)).value(), 2);
        IrMethod method = assertInstanceOf(IrMethod.class, iface.members().get(2));
        assertEquals("read", method.name());
        assertEquals(List.of(Modifier.PUBLIC), method.declaredModifiers());
        assertTrue(method.returnsReference());
        assertNull(method.body());
        assertTrue(method.parameters().getFirst().byReference());
        assertNotNull(method.returnType());
        assertTrue(method.returnType().nullable());
    }

    // 类和 trait 复用完整成员转换；成组属性／常量原位展开，半保留名与 VAR 不被改写。
    @Test
    void sharesOrderedMemberConversionAcrossClassesAndTraits() {
        String members = """
                public public static $first, $second = null;
                function echo() {}
                private protected const list = 1, VALUE = 2;
                use Feature;
                abstract function missing($value);
                var $legacy;
                """;
        for (List<IrClassMember> converted : List.of(
                assertInstanceOf(IrClassDeclaration.class, only("class C { " + members + " }")).members(),
                assertInstanceOf(IrTraitDeclaration.class, only("trait T { " + members + " }")).members())) {
            assertEquals(8, converted.size());
            IrProperty first = assertInstanceOf(IrProperty.class, converted.getFirst());
            assertEquals("first", first.name());
            assertEquals(List.of(Modifier.PUBLIC, Modifier.PUBLIC, Modifier.STATIC), first.declaredModifiers());
            assertNull(first.initialValue());
            IrProperty second = assertInstanceOf(IrProperty.class, converted.get(1));
            assertEquals("second", second.name());
            assertEquals(LiteralKind.NULL, assertInstanceOf(IrLiteral.class, second.initialValue()).kind());
            IrMethod method = assertInstanceOf(IrMethod.class, converted.get(2));
            assertEquals("echo", method.name());
            assertTrue(method.declaredModifiers().isEmpty());
            assertNotNull(method.body());
            assertTrue(method.body().statements().isEmpty());
            IrClassConstant constant = assertInstanceOf(IrClassConstant.class, converted.get(3));
            assertEquals("list", constant.name());
            assertEquals(List.of(Modifier.PRIVATE, Modifier.PROTECTED), constant.declaredModifiers());
            assertInteger(constant.value(), 1);
            assertEquals("VALUE", assertInstanceOf(IrClassConstant.class, converted.get(4)).name());
            assertInstanceOf(IrTraitUse.class, converted.get(5));
            assertNull(assertInstanceOf(IrMethod.class, converted.get(6)).body());
            assertEquals(List.of(Modifier.VAR), assertInstanceOf(IrProperty.class, converted.get(7)).declaredModifiers());
        }
    }

    // trait 中的使用规则保留显式方法归属、重复排除项、别名和仅修饰符调整。
    @Test
    void preservesTraitAdaptationsWithoutMergingMethods() {
        IrTraitDeclaration declaration = assertInstanceOf(IrTraitDeclaration.class, only("""
                trait Combined {
                    use Feature, Other, Feature {
                        Feature::work insteadof Other, Other;
                        Feature::work as protected alias;
                        work as private;
                        work as clone;
                    }
                }
                """));
        IrTraitUse use = assertInstanceOf(IrTraitUse.class, declaration.members().getFirst());
        assertEquals(List.of("Feature", "Other", "Feature"), use.traits().stream().map(NameReference::spelling).toList());
        assertEquals(4, use.adaptations().size());
        IrTraitPrecedence precedence = assertInstanceOf(IrTraitPrecedence.class, use.adaptations().getFirst());
        assertName(precedence.method().trait(), "Feature", NameForm.UNQUALIFIED);
        assertEquals("work", precedence.method().method());
        assertEquals(List.of("Other", "Other"), precedence.insteadOf().stream().map(NameReference::spelling).toList());
        IrTraitAlias alias = assertInstanceOf(IrTraitAlias.class, use.adaptations().get(1));
        assertEquals(Modifier.PROTECTED, alias.modifier());
        assertEquals("alias", alias.newName());
        IrTraitAlias modifierOnly = assertInstanceOf(IrTraitAlias.class, use.adaptations().get(2));
        assertNull(modifierOnly.method().trait());
        assertEquals(Modifier.PRIVATE, modifierOnly.modifier());
        assertNull(modifierOnly.newName());
        assertEquals("clone", assertInstanceOf(IrTraitAlias.class, use.adaptations().get(3)).newName());
    }

    // 各类声明留在实际条件或循环分支内，不因为有名称而提升到函数体开头。
    @Test
    void keepsDeclarationsInsideTheirControlFlowBranches() {
        List<IrStatement> statements = body("""
                if ($ready) { function conditional() {} } else { trait Alternative {} }
                while ($again) { class Repeated {} }
                switch ($kind) { default: interface Chosen {} }
                """).statements();
        assertEquals(3, statements.size());
        IrIf conditional = assertInstanceOf(IrIf.class, statements.getFirst());
        assertEquals("conditional", assertInstanceOf(IrFunctionDeclaration.class,
                conditional.branches().getFirst().body().statements().getFirst()).name());
        assertNotNull(conditional.elseBlock());
        assertEquals("Alternative", assertInstanceOf(IrTraitDeclaration.class,
                conditional.elseBlock().statements().getFirst()).name());
        IrWhile loop = assertInstanceOf(IrWhile.class, statements.get(1));
        assertEquals("Repeated", assertInstanceOf(IrClassDeclaration.class, loop.body().statements().getFirst()).name());
        IrSwitch selection = assertInstanceOf(IrSwitch.class, statements.get(2));
        assertEquals("Chosen", assertInstanceOf(IrInterfaceDeclaration.class,
                selection.cases().getFirst().body().statements().getFirst()).name());
    }

    // 具名函数、闭包和匿名类可交叉递归，内部声明不会变成捕获项或匿名类成员。
    @Test
    void recursivelyConvertsNamedDeclarationsClosuresAndAnonymousClasses() {
        IrFunctionDeclaration outer = function("""
                function outer($factory = function() { class FromClosure {} }) {
                    return new class { function run() { trait FromMethod {} } };
                }
                """);
        IrClosure factory = assertInstanceOf(IrClosure.class, outer.parameters().getFirst().defaultValue());
        assertTrue(factory.captures().isEmpty());
        assertEquals("FromClosure", assertInstanceOf(IrClassDeclaration.class,
                factory.body().statements().getFirst()).name());
        IrNewAnonymous instance = assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrReturn.class, outer.body().statements().getFirst()).value());
        assertEquals(1, instance.definition().members().size());
        IrMethod run = assertInstanceOf(IrMethod.class, instance.definition().members().getFirst());
        assertNotNull(run.body());
        assertEquals("FromMethod", assertInstanceOf(IrTraitDeclaration.class, run.body().statements().getFirst()).name());

        IrClassDeclaration clazz = assertInstanceOf(IrClassDeclaration.class, only("""
                class Container {
                    function run() {
                        function nested() { return function() { interface Deep {} }; }
                        return nested();
                    }
                }
                """));
        IrMethod method = assertInstanceOf(IrMethod.class, clazz.members().getFirst());
        assertNotNull(method.body());
        assertEquals(2, method.body().statements().size());
        IrFunctionDeclaration nested = assertInstanceOf(IrFunctionDeclaration.class, method.body().statements().getFirst());
        IrClosure closure = assertInstanceOf(IrClosure.class,
                assertInstanceOf(IrReturn.class, nested.body().statements().getFirst()).value());
        assertEquals("Deep", assertInstanceOf(IrInterfaceDeclaration.class, closure.body().statements().getFirst()).name());
        assertCall(assertInstanceOf(IrReturn.class, method.body().statements().get(1)).value(), "nested");
    }

    // 新声明中的作用域语句、生成器和字符串继续走原转换链，不丢失匿名类或解码后的字节。
    @Test
    void preservesExistingStatementsGeneratorsAndDecodedStringsInsideNamedBodies() {
        String statements = """
                global $shared;
                static $memo = "A\\n\\x42";
                declare(ticks = 1) { goto done; }
                done:
                yield "C\\tD";
                yield from $items;
                return new class { public $text = "\\x45"; };
                """;
        IrFunctionDeclaration function = function("function generate() { " + statements + " }");
        IrClassDeclaration clazz = assertInstanceOf(IrClassDeclaration.class,
                only("class GeneratorClass { function generate() { " + statements + " } }"));
        IrTraitDeclaration trait = assertInstanceOf(IrTraitDeclaration.class,
                only("trait GeneratorTrait { function generate() { " + statements + " } }"));
        IrBlock classBody = assertInstanceOf(IrMethod.class, clazz.members().getFirst()).body();
        IrBlock traitBody = assertInstanceOf(IrMethod.class, trait.members().getFirst()).body();
        assertNotNull(classBody);
        assertNotNull(traitBody);
        for (IrBlock block : List.of(function.body(), classBody, traitBody)) {
            assertEquals(7, block.statements().size());
            IrGlobal global = assertInstanceOf(IrGlobal.class, block.statements().getFirst());
            assertEquals("shared", assertInstanceOf(IrFixedName.class, global.variables().getFirst().name()).value());
            IrStaticVariable memo = assertInstanceOf(IrStaticVariables.class, block.statements().get(1)).variables().getFirst();
            assertEquals("memo", memo.name());
            assertBytes(memo.initializer(), "A\nB");
            IrDeclare declare = assertInstanceOf(IrDeclare.class, block.statements().get(2));
            assertEquals("ticks", declare.directives().getFirst().name());
            assertInteger(declare.directives().getFirst().value(), 1);
            assertNotNull(declare.body());
            assertEquals("done", assertInstanceOf(IrGoto.class, declare.body().statements().getFirst()).label());
            assertEquals("done", assertInstanceOf(IrLabel.class, block.statements().get(3)).name());
            IrYield yield = assertInstanceOf(IrYield.class,
                    assertInstanceOf(IrExpressionStatement.class, block.statements().get(4)).expression());
            assertNull(yield.key());
            assertBytes(yield.value(), "C\tD");
            IrYieldFrom yieldFrom = assertInstanceOf(IrYieldFrom.class,
                    assertInstanceOf(IrExpressionStatement.class, block.statements().get(5)).expression());
            assertVariable(yieldFrom.expression(), "items");
            IrNewAnonymous instance = assertInstanceOf(IrNewAnonymous.class,
                    assertInstanceOf(IrReturn.class, block.statements().get(6)).value());
            IrProperty property = assertInstanceOf(IrProperty.class, instance.definition().members().getFirst());
            assertEquals("text", property.name());
            assertBytes(property.initialValue(), "E");
        }
    }

    // try、catch、finally 分别拥有自己的声明，不能把分支序列拼成外层声明列表。
    @Test
    void keepsNamedDeclarationsInsideTryCatchAndFinallyBodies() {
        IrFunctionDeclaration function = function("""
                function guarded() {
                    try { echo 1; function InTry() {} }
                    catch (First | Second $caught) { class InCatch {} }
                    finally { trait InFinally {} interface FinalContract {} }
                }
                """);
        assertEquals(1, function.body().statements().size());
        IrTry statement = assertInstanceOf(IrTry.class, function.body().statements().getFirst());
        assertEquals(2, statement.body().statements().size());
        assertInteger(assertInstanceOf(IrEcho.class, statement.body().statements().getFirst()).expressions().getFirst(), 1);
        assertEquals("InTry", assertInstanceOf(IrFunctionDeclaration.class, statement.body().statements().get(1)).name());
        assertEquals(1, statement.catches().size());
        IrCatch catcher = statement.catches().getFirst();
        assertEquals(List.of("First", "Second"), catcher.exceptionTypes().stream().map(NameReference::spelling).toList());
        assertEquals("caught", catcher.variableName());
        assertEquals(1, catcher.body().statements().size());
        assertEquals("InCatch", assertInstanceOf(IrClassDeclaration.class, catcher.body().statements().getFirst()).name());
        assertNotNull(statement.finallyBlock());
        assertEquals(2, statement.finallyBlock().statements().size());
        assertEquals("InFinally", assertInstanceOf(IrTraitDeclaration.class, statement.finallyBlock().statements().getFirst()).name());
        assertEquals("FinalContract", assertInstanceOf(IrInterfaceDeclaration.class, statement.finallyBlock().statements().get(1)).name());
    }

    // 冒号式循环同样保留声明的块内归属，for 的空列表和 foreach 的两个声明均不被合并。
    @Test
    void keepsNamedDeclarationsInsideAlternativeLoopBodies() {
        IrFunctionDeclaration function = function("""
                function loops() {
                    while ($again): function InWhile() {} endwhile;
                    for (;;): interface InFor {} endfor;
                    foreach ($items as $item): trait InForeach {} class AnotherForeach {} endforeach;
                }
                """);
        assertEquals(3, function.body().statements().size());
        IrWhile whileLoop = assertInstanceOf(IrWhile.class, function.body().statements().getFirst());
        assertVariable(whileLoop.condition(), "again");
        assertEquals(1, whileLoop.body().statements().size());
        assertEquals("InWhile", assertInstanceOf(IrFunctionDeclaration.class, whileLoop.body().statements().getFirst()).name());
        IrFor forLoop = assertInstanceOf(IrFor.class, function.body().statements().get(1));
        assertTrue(forLoop.initializers().isEmpty());
        assertTrue(forLoop.conditions().isEmpty());
        assertTrue(forLoop.updates().isEmpty());
        assertEquals(1, forLoop.body().statements().size());
        assertEquals("InFor", assertInstanceOf(IrInterfaceDeclaration.class, forLoop.body().statements().getFirst()).name());
        IrForeach foreachLoop = assertInstanceOf(IrForeach.class, function.body().statements().get(2));
        assertEquals(2, foreachLoop.body().statements().size());
        assertEquals("InForeach", assertInstanceOf(IrTraitDeclaration.class, foreachLoop.body().statements().getFirst()).name());
        assertEquals("AnotherForeach", assertInstanceOf(IrClassDeclaration.class, foreachLoop.body().statements().get(1)).name());
    }

    // 前端可解析的冲突修饰符、重复成员和接口方法体按结构保留，不额外校验 PHP 编译合法性。
    @Test
    void retainsParseableDeclarationsWithoutAddingSemanticValidation() {
        List<IrStatement> statements = body("""
                interface Loose { public $value = make(); function run() {} use Feature; }
                final abstract class Conflicting { public $same, $same; const X = make(), X = 2; }
                function duplicated($x, $x = compute()) {}
                """).statements();
        IrInterfaceDeclaration iface = assertInstanceOf(IrInterfaceDeclaration.class, statements.getFirst());
        assertEquals(3, iface.members().size());
        assertCall(assertInstanceOf(IrProperty.class, iface.members().getFirst()).initialValue(), "make");
        assertNotNull(assertInstanceOf(IrMethod.class, iface.members().get(1)).body());
        assertInstanceOf(IrTraitUse.class, iface.members().get(2));
        IrClassDeclaration clazz = assertInstanceOf(IrClassDeclaration.class, statements.get(1));
        assertEquals(List.of(Modifier.FINAL, Modifier.ABSTRACT), clazz.declaredModifiers());
        assertEquals("same", assertInstanceOf(IrProperty.class, clazz.members().getFirst()).name());
        assertEquals("same", assertInstanceOf(IrProperty.class, clazz.members().get(1)).name());
        assertCall(assertInstanceOf(IrClassConstant.class, clazz.members().get(2)).value(), "make");
        assertEquals("X", assertInstanceOf(IrClassConstant.class, clazz.members().get(3)).name());
        IrFunctionDeclaration function = assertInstanceOf(IrFunctionDeclaration.class, statements.get(2));
        assertEquals(List.of("x", "x"), function.parameters().stream().map(IrParameter::name).toList());
        assertCall(function.parameters().get(1).defaultValue(), "compute");
    }

    // 转换命名空间区段里的声明不改变声明索引；声明名保持原文而非推算限定名称。
    @Test
    void keepsOriginalNamesWithoutRegisteringNestedDeclarations() {
        var file = DeclarationExtractor.extract(Main.parse("""
                <?php namespace App;
                function outer() { class Inner {} function helper() {} }
                class Existing {}
                """), "named.php");
        var section = file.namespaceSections().getFirst();
        assertEquals(2, section.declarations().size());
        var originalIndex = file.declarationIndex();
        IrBlock converted = SyntaxConverter.convertBody(section.body());
        IrFunctionDeclaration outer = assertInstanceOf(IrFunctionDeclaration.class, converted.statements().getFirst());
        assertEquals("outer", outer.name());
        assertEquals("Inner", assertInstanceOf(IrClassDeclaration.class, outer.body().statements().getFirst()).name());
        assertEquals("helper", assertInstanceOf(IrFunctionDeclaration.class, outer.body().statements().get(1)).name());
        assertEquals("Existing", assertInstanceOf(IrClassDeclaration.class, converted.statements().get(1)).name());
        assertSame(originalIndex, file.declarationIndex());
        assertEquals(2, section.declarations().size());
        assertEquals(1, originalIndex.findTopLevel(TopLevelKind.FUNCTION, "App\\outer").size());
        assertTrue(originalIndex.findTopLevel(TopLevelKind.TYPE, "App\\Inner").isEmpty());
        assertTrue(originalIndex.findTopLevel(TopLevelKind.FUNCTION, "App\\helper").isEmpty());
    }

    // 新声明外壳仍完整递归，默认值、成员值和深层方法体中的未支持内容必须明确失败。
    @Test
    void rejectsUnsupportedContentsInsideNamedDeclarations() {
        for (String code : List.of(
                "function f($value = `echo sentinel`) {}", "function f() { return `echo sentinel`; }",
                "class C { public $value = `echo sentinel`; }", "trait T { const X = `echo sentinel`; }",
                "interface I { function f($value = `echo sentinel`); }",
                "class C { function run() { function inner() { return `echo sentinel`; } } }")) {
            SyntaxBody syntax = syntax(code);
            var error = assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(syntax), code);
            assertFalse(error.fieldPath().isBlank());
            assertFalse(error.reason().isBlank());
        }
    }

    private static IrFunctionDeclaration function(String code) {
        return assertInstanceOf(IrFunctionDeclaration.class, only(code));
    }

    private static IrStatement only(String code) {
        IrBlock block = body(code);
        assertEquals(1, block.statements().size());
        return block.statements().getFirst();
    }

    private static IrBlock body(String code) {
        return SyntaxConverter.convertBody(syntax(code));
    }

    private static SyntaxBody syntax(String code) {
        var program = (NodeProgram) Main.parse("<?php " + code);
        return new SyntaxBody(List.copyOf(program.getStmts().getValue()), new SourceInfo("named.php", null));
    }

    private static void assertName(NameReference name, String spelling, NameForm form) {
        assertNotNull(name);
        assertEquals(spelling, name.spelling());
        assertEquals(form, name.form());
    }

    private static void assertInteger(IrExpression expression, long expected) {
        assertEquals(expected, assertInstanceOf(IrIntegerLiteral.class, expression).value());
    }

    private static void assertVariable(IrExpression expression, String name) {
        assertEquals(name, assertInstanceOf(IrFixedName.class,
                assertInstanceOf(IrVariable.class, expression).name()).value());
    }

    private static void assertBytes(IrExpression expression, String expected) {
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringLiteral.class, expression).value().toByteArray());
    }

    private static void assertCall(IrExpression expression, String name) {
        IrCall call = assertInstanceOf(IrCall.class, expression);
        assertEquals(name, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
    }
}