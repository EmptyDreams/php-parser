package top.kmar.php;

import java_cup.runtime.AstNode;
import org.junit.jupiter.api.Test;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 从真实解析结果验证声明视图，同时保留未经转换的语法结构。 */
class SemanticModelTest {

    // 空文件仍有全局区段；多项声明展开后不改变原始顶层语句序列。
    @Test
    void preservesEmptyGlobalSectionsAndTopLevelStatementOrder() {
        PhpFile empty = extract("");
        assertEquals(1, empty.namespaceSections().size());
        NamespaceSection emptyGlobal = empty.namespaceSections().getFirst();
        assertEquals("", emptyGlobal.namespaceName());
        assertTrue(emptyGlobal.imports().isEmpty());
        assertTrue(emptyGlobal.declarations().isEmpty());
        assertTrue(emptyGlobal.body().statements().isEmpty());

        AstNode syntax = Main.parse("<?php echo 1; function f() {} const A = 1, B = null; class C {} echo 2;");
        PhpFile file = DeclarationExtractor.extract(syntax);
        NamespaceSection global = file.namespaceSections().getFirst();
        assertSame(syntax, file.syntax());
        assertEquals("", global.namespaceName());
        assertEquals(List.of("f", "A", "B", "C"), declarationNames(global));
        assertEquals(List.of("f", "A", "B", "C"), global.declarations().stream()
                .map(TopLevelDeclaration::qualifiedName).toList());
        List<NodeTopStatement> statements = topStatements(syntax);
        assertSyntaxIdentity(statements, global.body());
        assertEquals(5, statements.size());

        NamespaceConstantDefinition first = assertInstanceOf(NamespaceConstantDefinition.class,
                global.declarations().get(1));
        NamespaceConstantDefinition second = assertInstanceOf(NamespaceConstantDefinition.class,
                global.declarations().get(2));
        assertSame(statements.get(2).getConsts().getValue().getFirst().getValue(), first.value().syntax());
        assertSame(statements.get(2).getConsts().getValue().get(1).getValue(), second.value().syntax());
    }

    // 重复声明同名命名空间也必须产生独立的导入环境，并保留末尾空区段。
    @Test
    void keepsSemicolonNamespaceImportScopesSeparate() {
        PhpFile file = extract("""
                namespace Same;
                use A\\One as Shared;
                function first() {}
                namespace Same;
                use B\\Two as Shared;
                function second() {}
                namespace EmptySection;
                """);
        List<NamespaceSection> sections = file.namespaceSections();
        assertEquals(List.of("Same", "Same", "EmptySection"), sections.stream()
                .map(NamespaceSection::namespaceName).toList());
        assertNotEquals(sections.getFirst().id(), sections.get(1).id());
        assertImport(sections.getFirst().imports().getFirst(), ImportKind.CLASS, "A\\One", "Shared", true);
        assertImport(sections.get(1).imports().getFirst(), ImportKind.CLASS, "B\\Two", "Shared", true);
        assertEquals(List.of("first"), declarationNames(sections.getFirst()));
        assertEquals(List.of("second"), declarationNames(sections.get(1)));
        assertEquals("Same\\second", sections.get(1).declarations().getFirst().qualifiedName());
        assertTrue(sections.get(2).body().statements().isEmpty());
        assertTrue(sections.get(2).imports().isEmpty());
        assertSyntaxIdentity(topStatements(file.syntax()).subList(1, 3), sections.getFirst().body());
        assertSyntaxIdentity(topStatements(file.syntax()).subList(4, 6), sections.get(1).body());
    }

    // 花括号区段不互相合并，显式全局区段和空区段也应保留。
    @Test
    void preservesBracketedNamespaceSectionsAndExplicitGlobalNamespace() {
        PhpFile file = extract("""
                namespace EmptySection {}
                namespace Same { use A\\One as Shared; function a() {} }
                namespace Same { use B\\Two as Shared; class C {} }
                namespace { const K = 1; echo K; }
                """);
        List<NamespaceSection> sections = file.namespaceSections();
        assertEquals(List.of("EmptySection", "Same", "Same", ""), sections.stream()
                .map(NamespaceSection::namespaceName).toList());
        assertTrue(sections.getFirst().body().statements().isEmpty());
        assertNotEquals(sections.get(1).id(), sections.get(2).id());
        assertEquals("A\\One", sections.get(1).imports().getFirst().targetName());
        assertEquals("B\\Two", sections.get(2).imports().getFirst().targetName());
        assertEquals("K", sections.get(3).declarations().getFirst().qualifiedName());
        for (int i = 0; i < sections.size(); i++) {
            assertSyntaxIdentity(topStatements(file.syntax()).get(i).getStmts().getValue(), sections.get(i).body());
        }
    }

    // 导入目标不附加当前命名空间，普通、函数和常量导入分别保留类别与别名。
    @Test
    void extractsOrdinaryTypedAndAbsoluteImports() {
        NamespaceSection section = extract("""
                namespace CurrentSpace;
                use A\\B, \\C\\D as Alias;
                use function F\\one, \\F\\two as Two;
                use const G\\THREE, \\G\\FOUR as Four;
                """).namespaceSections().getFirst();
        List<ImportDeclaration> imports = section.imports();
        assertEquals(6, imports.size());
        assertImport(imports.getFirst(), ImportKind.CLASS, "A\\B", "B", false);
        assertImport(imports.get(1), ImportKind.CLASS, "C\\D", "Alias", true);
        assertImport(imports.get(2), ImportKind.FUNCTION, "F\\one", "one", false);
        assertImport(imports.get(3), ImportKind.FUNCTION, "F\\two", "Two", true);
        assertImport(imports.get(4), ImportKind.CONST, "G\\THREE", "THREE", false);
        assertImport(imports.get(5), ImportKind.CONST, "G\\FOUR", "Four", true);
        assertEquals(3, section.body().statements().size());
        assertTrue(section.declarations().isEmpty());
    }

    // 所有分组形式都按源码顺序展开，组内导入种类不能被组级默认值覆盖。
    @Test
    void expandsMixedTypedAndAbsoluteGroupImportsInOrder() {
        NamespaceSection section = extract("""
                use P\\{C, D as D1, function f, function g as G1, const K, const L as L1};
                use \\Q\\{Sub\\Clazz as SC, function Sub\\run, const Sub\\VALUE};
                use function \\R\\{f, sub\\g as rg,};
                use const S\\{K, sub\\L as sl,};
                use Plain\\{One, Two,};
                """).namespaceSections().getFirst();
        List<ImportDeclaration> imports = section.imports();
        assertEquals(15, imports.size());
        assertImport(imports.getFirst(), ImportKind.CLASS, "P\\C", "C", false);
        assertImport(imports.get(1), ImportKind.CLASS, "P\\D", "D1", true);
        assertImport(imports.get(2), ImportKind.FUNCTION, "P\\f", "f", false);
        assertImport(imports.get(3), ImportKind.FUNCTION, "P\\g", "G1", true);
        assertImport(imports.get(4), ImportKind.CONST, "P\\K", "K", false);
        assertImport(imports.get(5), ImportKind.CONST, "P\\L", "L1", true);
        assertImport(imports.get(6), ImportKind.CLASS, "Q\\Sub\\Clazz", "SC", true);
        assertImport(imports.get(7), ImportKind.FUNCTION, "Q\\Sub\\run", "run", false);
        assertImport(imports.get(8), ImportKind.CONST, "Q\\Sub\\VALUE", "VALUE", false);
        assertImport(imports.get(9), ImportKind.FUNCTION, "R\\f", "f", false);
        assertImport(imports.get(10), ImportKind.FUNCTION, "R\\sub\\g", "rg", true);
        assertImport(imports.get(11), ImportKind.CONST, "S\\K", "K", false);
        assertImport(imports.get(12), ImportKind.CONST, "S\\sub\\L", "sl", true);
        assertImport(imports.get(13), ImportKind.CLASS, "Plain\\One", "One", false);
        assertImport(imports.get(14), ImportKind.CLASS, "Plain\\Two", "Two", false);
        assertEquals(5, section.body().statements().size());
    }

    // 缺省参数值与 PHP null 分开表示；类型名保持源码形态而不擅自解析为类。
    @Test
    void preservesFunctionSignaturesAndOptionalDefaultExpressions() {
        PhpFile file = extract("""
                function &ref(?Foo &$first, $missing, $nil = null, array $items = [],
                              callable $callback = null, ...$rest): ?namespace\\Result { return $first; }
                function plain(int $value): void {}
                """);
        FunctionDefinition function = assertInstanceOf(FunctionDefinition.class,
                file.namespaceSections().getFirst().declarations().getFirst());
        FunctionSignature signature = function.signature();
        assertEquals("ref", signature.name());
        assertTrue(signature.returnsReference());
        assertEquals(List.of("first", "missing", "nil", "items", "callback", "rest"),
                signature.parameters().stream().map(ParameterDefinition::name).toList());
        List<ParameterDefinition> parameters = signature.parameters();
        assertType(parameters.getFirst().declaredType(), "Foo", NameForm.UNQUALIFIED, true);
        assertTrue(parameters.getFirst().byReference());
        assertFalse(parameters.getFirst().variadic());
        assertNull(parameters.get(1).declaredType());
        assertNull(parameters.get(1).defaultValue());
        assertFalse(parameters.get(1).byReference());
        assertNotNull(parameters.get(2).defaultValue());
        assertType(parameters.get(3).declaredType(), "array", NameForm.UNQUALIFIED, false);
        assertType(parameters.get(4).declaredType(), "callable", NameForm.UNQUALIFIED, false);
        assertTrue(parameters.get(5).variadic());
        assertFalse(parameters.get(5).byReference());
        assertNull(parameters.get(5).declaredType());
        assertType(signature.returnType(), "namespace\\Result", NameForm.NAMESPACE_RELATIVE, true);

        NodeFunctionDeclarationStatement rawFunction = topStatements(file.syntax()).getFirst().getFunction();
        for (int i : List.of(2, 3, 4)) {
            SyntaxExpression defaultValue = parameters.get(i).defaultValue();
            assertNotNull(defaultValue);
            assertSame(rawFunction.getParams().getValue().get(i).getDefaultValue(),
                    defaultValue.syntax());
        }
        assertSyntaxIdentity(rawFunction.getStmts().getValue(), function.body());
        FunctionDefinition plain = assertInstanceOf(FunctionDefinition.class,
                file.namespaceSections().getFirst().declarations().get(1));
        assertFalse(plain.signature().returnsReference());
        assertType(plain.signature().parameters().getFirst().declaredType(),
                "int", NameForm.UNQUALIFIED, false);
        assertType(plain.signature().returnType(), "void", NameForm.UNQUALIFIED, false);
        assertTrue(plain.body().statements().isEmpty());
    }

    // 只提取源码声明信息：保留重复修饰符、var、半保留名称以及 trait 适配原树。
    @Test
    void extractsClassMembersParentsAndTraitAdaptationsWithoutLosingOrder() {
        PhpFile file = extract("""
                namespace Demo;
                abstract final class C extends \\Base implements namespace\\One, Rel\\Two {
                    var $first, $second = null;
                    public public static $third = 1;
                    protected const public = 2, NEXT = 3;
                    abstract public function missing();
                    final protected static function &list(?Foo &$arg, ...$rest): ?namespace\\Result {}
                    use \\Traits\\A, namespace\\B, Rel\\C {
                        \\Traits\\A::run insteadof namespace\\B;
                        run as protected alias;
                    }
                }
                interface I extends \\Root, namespace\\Other, Rel\\End {
                    public function print();
                    const MARK = 1;
                }
                trait T { function echo() {} }
                """);
        List<TopLevelDeclaration> declarations = file.namespaceSections().getFirst().declarations();
        assertEquals(List.of("C", "I", "T"), declarations.stream().map(TopLevelDeclaration::name).toList());
        ClassLikeDefinition clazz = assertInstanceOf(ClassLikeDefinition.class, declarations.getFirst());
        assertEquals(ClassLikeKind.CLASS, clazz.kind());
        assertEquals("Demo\\C", clazz.qualifiedName());
        assertEquals(List.of(Modifier.ABSTRACT, Modifier.FINAL), clazz.declaredModifiers());
        assertName(clazz.parentTypes().getFirst(), "\\Base", NameForm.FULLY_QUALIFIED);
        assertName(clazz.interfaces().getFirst(), "namespace\\One", NameForm.NAMESPACE_RELATIVE);
        assertName(clazz.interfaces().get(1), "Rel\\Two", NameForm.QUALIFIED);
        List<ClassMember> members = clazz.members();
        assertEquals(8, members.size());
        for (ClassMember member : members) assertEquals(clazz.id(), member.ownerId());

        PropertyDefinition first = assertInstanceOf(PropertyDefinition.class, members.getFirst());
        PropertyDefinition second = assertInstanceOf(PropertyDefinition.class, members.get(1));
        PropertyDefinition third = assertInstanceOf(PropertyDefinition.class, members.get(2));
        assertEquals(List.of("first", "second", "third"), List.of(first.name(), second.name(), third.name()));
        assertEquals(List.of(Modifier.VAR), first.declaredModifiers());
        assertEquals(List.of(Modifier.VAR), second.declaredModifiers());
        assertNull(first.initialValue());
        SyntaxExpression secondInitialValue = second.initialValue();
        assertNotNull(secondInitialValue);
        assertEquals(List.of(Modifier.PUBLIC, Modifier.PUBLIC, Modifier.STATIC), third.declaredModifiers());

        ClassConstantDefinition constant = assertInstanceOf(ClassConstantDefinition.class, members.get(3));
        ClassConstantDefinition next = assertInstanceOf(ClassConstantDefinition.class, members.get(4));
        assertEquals("public", constant.name());
        assertEquals("NEXT", next.name());
        assertEquals(List.of(Modifier.PROTECTED), constant.declaredModifiers());
        assertEquals(List.of(Modifier.PROTECTED), next.declaredModifiers());

        MethodDefinition abstractMethod = assertInstanceOf(MethodDefinition.class, members.get(5));
        MethodDefinition method = assertInstanceOf(MethodDefinition.class, members.get(6));
        assertEquals("missing", abstractMethod.name());
        assertNull(abstractMethod.body());
        assertNull(abstractMethod.signature().returnType());
        assertEquals(List.of(Modifier.ABSTRACT, Modifier.PUBLIC), abstractMethod.declaredModifiers());
        assertEquals("list", method.name());
        SyntaxBody methodBody = method.body();
        assertNotNull(methodBody);
        assertTrue(methodBody.statements().isEmpty());
        assertEquals(List.of(Modifier.FINAL, Modifier.PROTECTED, Modifier.STATIC), method.declaredModifiers());
        assertTrue(method.signature().returnsReference());
        assertEquals(List.of("arg", "rest"), method.signature().parameters().stream()
                .map(ParameterDefinition::name).toList());
        assertTrue(method.signature().parameters().getFirst().byReference());
        assertTrue(method.signature().parameters().get(1).variadic());
        assertType(method.signature().returnType(),
                "namespace\\Result", NameForm.NAMESPACE_RELATIVE, true);

        TraitUseDefinition traits = assertInstanceOf(TraitUseDefinition.class, members.get(7));
        assertEquals(List.of("\\Traits\\A", "namespace\\B", "Rel\\C"), traits.traits().stream()
                .map(NameReference::spelling).toList());
        List<NodeClassStatement> rawMembers = topStatements(file.syntax()).get(1).getClazz().getMembers().getValue();
        assertSame(rawMembers.get(5).getAdaptations(), traits.adaptations());
        assertSame(rawMembers.getFirst().getProps().getValue().get(1).getDefaultValue(),
                secondInitialValue.syntax());
        assertSame(rawMembers.get(2).getConsts().getValue().getFirst().getValue(), constant.value().syntax());

        ClassLikeDefinition iface = assertInstanceOf(ClassLikeDefinition.class, declarations.get(1));
        assertEquals(ClassLikeKind.INTERFACE, iface.kind());
        assertEquals(List.of("\\Root", "namespace\\Other", "Rel\\End"), iface.parentTypes().stream()
                .map(NameReference::spelling).toList());
        assertTrue(iface.interfaces().isEmpty());
        assertEquals("print", assertInstanceOf(MethodDefinition.class, iface.members().getFirst()).name());
        assertEquals("MARK", assertInstanceOf(ClassConstantDefinition.class, iface.members().get(1)).name());
        ClassLikeDefinition trait = assertInstanceOf(ClassLikeDefinition.class, declarations.get(2));
        assertEquals(ClassLikeKind.TRAIT, trait.kind());
        assertTrue(trait.parentTypes().isEmpty());
        assertTrue(trait.interfaces().isEmpty());
        assertEquals("echo", assertInstanceOf(MethodDefinition.class, trait.members().getFirst()).name());
    }

    // 条件、循环和函数内声明不提升；闭包、匿名类及块语句仍按原节点保存。
    @Test
    void doesNotPromoteConditionalNestedOrAnonymousDeclarations() {
        PhpFile file = extract("""
                if ($enabled) { function conditional() {} class Conditional {} }
                while (false) { function looped() {} }
                function outer() {
                    function nested() {}
                    class Nested {}
                    $closure = function () { function deeper() {} };
                    $object = new class { function method() {} };
                }
                $closure = function () use ($outside) {};
                $object = new class { public function inner() {} };
                { function blockOnly() {} }
                """);
        NamespaceSection global = file.namespaceSections().getFirst();
        assertEquals(List.of("outer"), declarationNames(global));
        assertTrue(global.imports().isEmpty());
        List<NodeTopStatement> raw = topStatements(file.syntax());
        assertEquals(6, raw.size());
        assertSyntaxIdentity(raw, global.body());
        FunctionDefinition outer = assertInstanceOf(FunctionDefinition.class, global.declarations().getFirst());
        assertEquals(4, outer.body().statements().size());
        assertSyntaxIdentity(raw.get(2).getFunction().getStmts().getValue(), outer.body());
        for (String name : List.of("conditional", "looped", "nested", "deeper", "inner", "method", "blockOnly")) {
            assertTrue(file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, name).isEmpty(), name);
        }
        for (String name : List.of("Conditional", "Nested")) {
            assertTrue(file.declarationIndex().findTopLevel(TopLevelKind.TYPE, name).isEmpty(), name);
        }
    }

    // trait 的空适配与各种适配规则均原样保留，不在声明提取阶段执行方法合并。
    @Test
    void retainsEmptyAndAllSupportedTraitAdaptationForms() {
        PhpFile file = extract("""
                class C {
                    use A;
                    use B {}
                    use A, B {
                        A::run insteadof B;
                        run as alias;
                        run as list;
                        A::run as protected alias;
                        A::run as private;
                    }
                }
                """);
        ClassLikeDefinition type = (ClassLikeDefinition) file.namespaceSections().getFirst().declarations().getFirst();
        List<NodeClassStatement> original = topStatements(file.syntax()).getFirst().getClazz().getMembers().getValue();
        assertEquals(3, type.members().size());
        for (int i = 0; i < original.size(); i++) {
            TraitUseDefinition use = assertInstanceOf(TraitUseDefinition.class, type.members().get(i));
            assertSame(original.get(i).getAdaptations(), use.adaptations());
        }
        assertNull(original.getFirst().getAdaptations().getAdaptations());
        assertNull(original.get(1).getAdaptations().getAdaptations());
        assertEquals(5, original.get(2).getAdaptations().getAdaptations().getValue().size());
    }

    // 提取器按语法边界分段，不额外校验混合 namespace 写法；块外代码回到独立全局区段。
    @Test
    void keepsGlobalCodeAroundNamespaceBlocksAndMixedBoundaries() {
        PhpFile file = extract("""
                declare(strict_types=1);
                namespace A;
                function a() {}
                namespace B { function b() {} }
                function globalFunction() {}
                namespace C;
                function c() {}
                """);
        var sections = file.namespaceSections();
        assertEquals(List.of("", "A", "B", "", "C"),
                sections.stream().map(NamespaceSection::namespaceName).toList());
        assertEquals(1, sections.getFirst().body().statements().size());
        assertTrue(sections.getFirst().declarations().isEmpty());
        assertEquals("globalFunction", sections.get(3).declarations().getFirst().qualifiedName());
        assertEquals("C\\c", sections.get(4).declarations().getFirst().qualifiedName());
    }

    // halt 标记和此前的执行语句都应留在区段语法体，不作为命名声明收集。
    @Test
    void preservesHaltCompilerAlongsideExecutableStatements() {
        PhpFile file = extract("echo 1; __halt_compiler();ignored");
        var section = file.namespaceSections().getFirst();
        assertTrue(section.declarations().isEmpty());
        assertEquals(2, section.body().statements().size());
        assertInstanceOf(NodeTopStatement.HaltCompiler.class, section.body().statements().get(1));
        assertSyntaxIdentity(topStatements(file.syntax()), section.body());
    }

    private static PhpFile extract(String source) {
        return DeclarationExtractor.extract(Main.parse("<?php\n" + source));
    }

    private static List<NodeTopStatement> topStatements(AstNode syntax) {
        return assertInstanceOf(NodeProgram.class, syntax).getStmts().getValue();
    }

    private static List<String> declarationNames(NamespaceSection section) {
        return section.declarations().stream().map(TopLevelDeclaration::name).toList();
    }

    private static void assertSyntaxIdentity(List<? extends AstNode> expected, SyntaxBody actual) {
        assertEquals(expected.size(), actual.statements().size());
        for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.statements().get(i));
    }

    private static void assertImport(ImportDeclaration actual, ImportKind kind, String target,
                                     String alias, boolean explicitAlias) {
        assertEquals(kind, actual.kind());
        assertEquals(target, actual.targetName());
        assertEquals(alias, actual.alias());
        if (explicitAlias) {
            assertNotNull(actual.declaredAlias());
            assertEquals(alias, actual.declaredAlias());
        } else {
            assertNull(actual.declaredAlias());
        }
    }

    private static void assertType(TypeReference actual, String spelling, NameForm form, boolean nullable) {
        assertNotNull(actual);
        assertName(actual.name(), spelling, form);
        assertEquals(nullable, actual.nullable());
    }

    private static void assertName(NameReference actual, String spelling, NameForm form) {
        assertEquals(spelling, actual.spelling());
        assertEquals(form, actual.form());
    }
}