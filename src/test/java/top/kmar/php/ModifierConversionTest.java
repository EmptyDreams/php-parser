package top.kmar.php;

import java_cup.runtime.AstNode;
import java_cup.runtime.symbol.complex.ComplexLocation;
import org.junit.jupiter.api.Test;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 修饰符在 IR 中归一为有效可见性和标志，不扩展为声明合法性分析。 */
class ModifierConversionTest {
    private static final String SOURCE_ID = "modifiers.php";

    // 省略可见性、仅 static 和旧式 var 均归一为 public；var 不产生 static 标志。
    @Test
    void normalizesDefaultsAndLegacyProperties() {
        List<IrClassMember> members = clazz("""
                class C {
                    var $legacy;
                    static $shared;
                    function ordinary() {}
                    static function shared() {}
                    const VALUE = 1;
                }
                """).members();
        IrProperty legacy = assertInstanceOf(IrProperty.class, members.getFirst());
        assertEquals(Visibility.PUBLIC, legacy.visibility());
        assertFalse(legacy.isStatic());
        IrProperty shared = assertInstanceOf(IrProperty.class, members.get(1));
        assertEquals(Visibility.PUBLIC, shared.visibility());
        assertTrue(shared.isStatic());
        assertMethodFlags(assertInstanceOf(IrMethod.class, members.get(2)), Visibility.PUBLIC, false, false, false);
        assertMethodFlags(assertInstanceOf(IrMethod.class, members.get(3)), Visibility.PUBLIC, true, false, false);
        assertEquals(Visibility.PUBLIC, assertInstanceOf(IrClassConstant.class, members.get(4)).visibility());
    }

    // 穷举可见性与 static、abstract、final 的有效组合，方法体不参与标志推导。
    @Test
    void normalizesEveryValidMethodFlagCombination() {
        for (Visibility visibility : Visibility.values()) {
            for (boolean isStatic : List.of(false, true)) {
                for (String mode : List.of("", "abstract", "final")) {
                    if (mode.equals("abstract") && visibility == Visibility.PRIVATE) continue;
                    String modifiers = visibility.name() + " " + (isStatic ? "static " : "") + mode;
                    IrMethod method = assertInstanceOf(IrMethod.class,
                            clazz("class C { " + modifiers + " function run(); }").members().getFirst());
                    assertMethodFlags(method, visibility, isStatic, mode.equals("abstract"), mode.equals("final"));
                    assertNull(method.body());
                }
            }
        }
    }

    // 类标志只来自类本身的修饰符，成员中有抽象方法也不把普通类升级为抽象类。
    @Test
    void normalizesClassFlagsWithoutInferringThemFromMembers() {
        IrClassDeclaration plain = clazz("class C { abstract function run(); }");
        assertFalse(plain.isAbstract());
        assertFalse(plain.isFinal());
        assertTrue(assertInstanceOf(IrMethod.class, plain.members().getFirst()).isAbstract());
        IrClassDeclaration abstractClass = clazz("abstract class C {}");
        assertTrue(abstractClass.isAbstract());
        assertFalse(abstractClass.isFinal());
        IrClassDeclaration finalClass = clazz("final class C {}");
        assertFalse(finalClass.isAbstract());
        assertTrue(finalClass.isFinal());
    }

    // ASCII 大小写及修饰符顺序不改变有效值；三种可见性对属性和常量均可用。
    @Test
    void normalizesAsciiCaseAndOrderAcrossDeclarationKinds() {
        IrClassDeclaration declaration = clazz("""
                FiNaL class C {
                    StAtIc PrOtEcTeD $field;
                    PrIvAtE const VALUE = 1;
                    fInAl PuBlIc sTaTiC function first() {}
                    sTaTiC fInAl pUbLiC function second() {}
                    VaR $legacy;
                }
                """);
        assertTrue(declaration.isFinal());
        IrProperty field = assertInstanceOf(IrProperty.class, declaration.members().getFirst());
        assertEquals(Visibility.PROTECTED, field.visibility());
        assertTrue(field.isStatic());
        assertEquals(Visibility.PRIVATE,
                assertInstanceOf(IrClassConstant.class, declaration.members().get(1)).visibility());
        for (int i : List.of(2, 3)) {
            assertMethodFlags(assertInstanceOf(IrMethod.class, declaration.members().get(i)),
                    Visibility.PUBLIC, true, false, true);
        }
        assertEquals(Visibility.PUBLIC,
                assertInstanceOf(IrProperty.class, declaration.members().get(4)).visibility());
        for (Visibility visibility : Visibility.values()) {
            List<IrClassMember> members = clazz("class C { " + visibility.name()
                    + " $p; " + visibility.name() + " const V = 1; }").members();
            assertEquals(visibility, assertInstanceOf(IrProperty.class, members.getFirst()).visibility());
            assertEquals(visibility, assertInstanceOf(IrClassConstant.class, members.get(1)).visibility());
        }
    }

    // 接口方法统一 public 且隐式 abstract，可选 static；接口常量的缺省可见性也是 public。
    @Test
    void addsImplicitAbstractFlagsToInterfaceMethods() {
        IrInterfaceDeclaration declaration = assertInstanceOf(IrInterfaceDeclaration.class, only("""
                interface I {
                    function first();
                    public function second();
                    static function third();
                    StAtIc PuBlIc function fourth();
                    const FIRST = 1;
                    public const SECOND = 2;
                }
                """));
        for (int i = 0; i < 4; i++) {
            assertMethodFlags(assertInstanceOf(IrMethod.class, declaration.members().get(i)),
                    Visibility.PUBLIC, i >= 2, true, false);
        }
        for (int i : List.of(4, 5)) {
            assertEquals(Visibility.PUBLIC,
                    assertInstanceOf(IrClassConstant.class, declaration.members().get(i)).visibility());
        }
    }

    // 整文件、按需正文和单表达式入口共享规范化，匿名类也获得相同有效成员字段。
    @Test
    void sharesNormalizationAcrossFileBodyAndExpressionEntrypoints() {
        NodeProgram syntax = parse("""
                final class C { protected static $value; final function run() {} }
                interface I { static function run(); }
                new class { private static $value; public final function run() {} };
                """);
        List<IrStatement> file = SyntaxConverter.convertFile(syntax, SOURCE_ID)
                .namespaceSections().getFirst().body().statements();
        IrBlock body = SyntaxConverter.convertBody(new SyntaxBody(
                List.copyOf(syntax.getStmts().getValue()), new SourceInfo(SOURCE_ID, null)));
        assertEquals(file, body.statements());
        NodeExpr expression = syntax.getStmts().getValue().get(2).getStmt().getExpression();
        IrNewAnonymous direct = assertInstanceOf(IrNewAnonymous.class,
                SyntaxConverter.convertExpression(new SyntaxExpression(expression, new SourceInfo(SOURCE_ID, null))));
        assertEquals(assertInstanceOf(IrExpressionStatement.class, file.get(2)).expression(), direct);
        IrProperty property = assertInstanceOf(IrProperty.class, direct.definition().members().getFirst());
        assertEquals(Visibility.PRIVATE, property.visibility());
        assertTrue(property.isStatic());
        assertMethodFlags(assertInstanceOf(IrMethod.class, direct.definition().members().get(1)),
                Visibility.PUBLIC, false, false, true);
    }

    // 接口上下文只影响接口的直接成员，不泄漏到其方法体内的类、trait 或匿名类。
    @Test
    void keepsInterfaceContextLocalAcrossNestedDeclarations() {
        IrInterfaceDeclaration owner = assertInstanceOf(IrInterfaceDeclaration.class, only("""
                interface I {
                    function outer() {
                        class Nested { private function run() {} }
                        trait Feature { protected abstract function run(); }
                        interface Inner { static function run(); }
                        return new class { private final function run() {} };
                    }
                }
                """));
        IrMethod outer = assertInstanceOf(IrMethod.class, owner.members().getFirst());
        assertTrue(outer.isAbstract());
        assertNotNull(outer.body());
        List<IrStatement> nested = outer.body().statements();
        IrClassDeclaration clazz = assertInstanceOf(IrClassDeclaration.class, nested.getFirst());
        assertMethodFlags(assertInstanceOf(IrMethod.class, clazz.members().getFirst()),
                Visibility.PRIVATE, false, false, false);
        IrTraitDeclaration trait = assertInstanceOf(IrTraitDeclaration.class, nested.get(1));
        assertMethodFlags(assertInstanceOf(IrMethod.class, trait.members().getFirst()),
                Visibility.PROTECTED, false, true, false);
        IrInterfaceDeclaration iface = assertInstanceOf(IrInterfaceDeclaration.class, nested.get(2));
        assertMethodFlags(assertInstanceOf(IrMethod.class, iface.members().getFirst()),
                Visibility.PUBLIC, true, true, false);
        IrNewAnonymous anonymous = assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrReturn.class, nested.get(3)).value());
        assertMethodFlags(assertInstanceOf(IrMethod.class, anonymous.definition().members().getFirst()),
                Visibility.PRIVATE, false, false, true);
    }

    // 修饰符字段移除不扩大来源范围，成组属性和常量仍取各自元素而非整个声明。
    @Test
    void preservesMemberAndTraitAliasSourcesAfterNormalization() {
        NodeProgram syntax = parse("""
                final class C {
                    protected static $first, $second = 2;
                    private const FIRST = 1, SECOND = 2;
                    final function run() {}
                    use Missing { run as protected renamed; }
                }
                """);
        NodeClassDeclarationStatement original = syntax.getStmts().getValue().getFirst().getClazz();
        List<NodeClassStatement> originalMembers = original.getMembers().getValue();
        IrClassDeclaration result = assertInstanceOf(IrClassDeclaration.class,
                SyntaxConverter.convertBody(new SyntaxBody(List.copyOf(syntax.getStmts().getValue()),
                        new SourceInfo(SOURCE_ID, null))).statements().getFirst());
        assertEquals(source(original), result.source());
        for (int i = 0; i < 2; i++) {
            assertEquals(source(originalMembers.getFirst().getProps().getValue().get(i)),
                    result.members().get(i).source());
            assertEquals(source(originalMembers.get(1).getConsts().getValue().get(i)),
                    result.members().get(i + 2).source());
        }
        assertEquals(source(originalMembers.get(2)), result.members().get(4).source());
        NodeTraitAlias originalAlias = originalMembers.get(3).getAdaptations().getAdaptations()
                .getValue().getFirst().getAlias();
        IrTraitAlias alias = assertInstanceOf(IrTraitAlias.class,
                assertInstanceOf(IrTraitUse.class, result.members().get(5)).adaptations().getFirst());
        assertEquals(source(originalAlias), alias.source());
    }

    // IR 不改写 AST 或声明层修饰符顺序；转换失败后原始重复项与索引同样保留。
    @Test
    void leavesSyntaxDeclaredModifiersAndIndexesUnchanged() {
        NodeProgram syntax = parse("""
                final class C {
                    static protected $value;
                    public final static function run() {}
                    var $legacy;
                }
                """);
        String originalTree = syntax.toTreeString(false);
        var file = DeclarationExtractor.extract(syntax, SOURCE_ID);
        ClassLikeDefinition declaration = assertInstanceOf(ClassLikeDefinition.class,
                file.namespaceSections().getFirst().declarations().getFirst());
        var index = file.declarationIndex();
        List<ClassMember> members = declaration.members();
        SyntaxConverter.convertFile(syntax, SOURCE_ID);
        assertEquals(originalTree, syntax.toTreeString(false));
        assertEquals(List.of(Modifier.FINAL), declaration.declaredModifiers());
        assertEquals(List.of(Modifier.STATIC, Modifier.PROTECTED),
                assertInstanceOf(PropertyDefinition.class, members.getFirst()).declaredModifiers());
        assertEquals(List.of(Modifier.PUBLIC, Modifier.FINAL, Modifier.STATIC),
                assertInstanceOf(MethodDefinition.class, members.get(1)).declaredModifiers());
        assertEquals(List.of(Modifier.VAR),
                assertInstanceOf(PropertyDefinition.class, members.get(2)).declaredModifiers());
        assertSame(members, declaration.members());
        assertSame(index, file.declarationIndex());
        assertSame(declaration, index.findById(declaration.id()).orElseThrow());

        NodeProgram duplicateSyntax = parse("abstract abstract class Broken { public public $value; }");
        String duplicateTree = duplicateSyntax.toTreeString(false);
        var duplicateFile = DeclarationExtractor.extract(duplicateSyntax, SOURCE_ID);
        ClassLikeDefinition duplicate = assertInstanceOf(ClassLikeDefinition.class,
                duplicateFile.namespaceSections().getFirst().declarations().getFirst());
        assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertFile(duplicateSyntax, SOURCE_ID));
        assertEquals(duplicateTree, duplicateSyntax.toTreeString(false));
        assertEquals(List.of(Modifier.ABSTRACT, Modifier.ABSTRACT), duplicate.declaredModifiers());
        assertEquals(List.of(Modifier.PUBLIC, Modifier.PUBLIC),
                assertInstanceOf(PropertyDefinition.class, duplicate.members().getFirst()).declaredModifiers());
        assertSame(duplicate, duplicateFile.declarationIndex().findById(duplicate.id()).orElseThrow());
    }

    // 类上重复与冲突的第二个修饰符直接判错，不能随意选择一个标志或返回部分结果。
    @Test
    void rejectsRepeatedAndConflictingClassModifiers() {
        for (String prefix : List.of("abstract abstract", "final final", "abstract final", "final abstract")) {
            String code = prefix + " class C {}";
            assertFailure(code, ".clazz.mods[1]", prefix.substring(prefix.indexOf(' ') + 1));
        }
    }

    // 成员上的重复修饰符、多种可见性和 abstract/final 冲突定位第二个违规条目。
    @Test
    void rejectsRepeatedAndConflictingMemberModifiers() {
        for (String prefix : List.of("public public", "protected protected", "private private", "static static",
                "abstract abstract", "final final", "public private", "protected public", "private protected",
                "abstract final", "final abstract")) {
            assertFailure("class C { " + prefix + " function run(); }", ".members[0].methodMods[1]",
                    prefix.substring(prefix.indexOf(' ') + 1));
        }
        assertFailure("class C { static static $value; }", ".members[0].propMods[1]", "static");
        assertFailure("class C { protected private $value; }", ".members[0].propMods[1]", "private");
        assertFailure("class C { public public const VALUE = 1; }", ".members[0].constMods[1]", "public");
        assertFailure("class C { private public const VALUE = 1; }", ".members[0].constMods[1]", "public");
    }

    // abstract/private 无论书写顺序都冲突；private 的普通或 final 方法仍然有效。
    @Test
    void rejectsPrivateAbstractMethodsInEitherOrder() {
        assertFailure("class C { abstract private function run(); }", ".members[0].methodMods[1]", "private");
        assertFailure("class C { private abstract function run(); }", ".members[0].methodMods[1]", "abstract");
        assertMethodFlags(assertInstanceOf(IrMethod.class,
                clazz("class C { private final function run(); }").members().getFirst()),
                Visibility.PRIVATE, false, false, true);
    }

    // 属性不接受 abstract/final，类常量不接受 static/abstract/final，即使文法可解析也明确失败。
    @Test
    void rejectsModifiersInapplicableToPropertiesAndConstants() {
        for (String modifier : List.of("abstract", "final")) {
            assertFailure("class C { " + modifier + " $value; }", ".members[0].propMods[0]", modifier);
        }
        for (String modifier : List.of("static", "abstract", "final")) {
            assertFailure("class C { " + modifier + " const VALUE = 1; }", ".members[0].constMods[0]", modifier);
        }
    }

    // 接口只增加当前成员的修饰符规则，不允许显式 abstract、final 或非 public 方法／常量。
    @Test
    void rejectsInapplicableInterfaceMethodAndConstantModifiers() {
        for (String modifier : List.of("abstract", "final", "protected", "private")) {
            assertFailure("interface I { " + modifier + " function run(); }",
                    ".members[0].methodMods[0]", modifier);
        }
        for (String modifier : List.of("protected", "private", "static", "abstract", "final")) {
            assertFailure("interface I { " + modifier + " const VALUE = 1; }",
                    ".members[0].constMods[0]", modifier);
        }
        assertFailure("interface I { public static public function run(); }",
                ".members[0].methodMods[2]", "public");
    }

    // trait alias 仅允许可见性调整，static/abstract/final 不被误存为新方法标志。
    @Test
    void rejectsNonVisibilityTraitAliasModifiers() {
        for (String modifier : List.of("static", "abstract", "final")) {
            for (String newName : List.of("", " renamed")) {
                assertFailure("trait T { use Missing { run as " + modifier + newName + "; } }",
                        ".adaptations.adaptations[0].alias.modifier", modifier);
            }
        }
    }

    // 不新增方法体及所属类型结构检查：接口属性和 trait-use、trait 常量及异常方法体组合继续保存。
    @Test
    void doesNotAddLocalDeclarationLegalityChecks() {
        List<IrStatement> statements = body("""
                class Plain { function bodyless(); abstract function withBody() {} }
                interface I { public $value; function withBody() {} use Missing; }
                trait T { const VALUE = 1; }
                """).statements();
        IrClassDeclaration plain = assertInstanceOf(IrClassDeclaration.class, statements.getFirst());
        assertFalse(plain.isAbstract());
        IrMethod bodyless = assertInstanceOf(IrMethod.class, plain.members().getFirst());
        assertFalse(bodyless.isAbstract());
        assertNull(bodyless.body());
        IrMethod withBody = assertInstanceOf(IrMethod.class, plain.members().get(1));
        assertTrue(withBody.isAbstract());
        assertNotNull(withBody.body());
        IrInterfaceDeclaration iface = assertInstanceOf(IrInterfaceDeclaration.class, statements.get(1));
        assertInstanceOf(IrProperty.class, iface.members().getFirst());
        IrMethod interfaceMethod = assertInstanceOf(IrMethod.class, iface.members().get(1));
        assertTrue(interfaceMethod.isAbstract());
        assertNotNull(interfaceMethod.body());
        assertInstanceOf(IrTraitUse.class, iface.members().get(2));
        assertInstanceOf(IrClassConstant.class,
                assertInstanceOf(IrTraitDeclaration.class, statements.get(2)).members().getFirst());
    }

    private static void assertMethodFlags(IrMethod method, Visibility visibility,
                                          boolean isStatic, boolean isAbstract, boolean isFinal) {
        assertEquals(visibility, method.visibility());
        assertEquals(isStatic, method.isStatic());
        assertEquals(isAbstract, method.isAbstract());
        assertEquals(isFinal, method.isFinal());
    }

    private static void assertFailure(String code, String suffix, String offendingKeyword) {
        NodeProgram syntax = assertDoesNotThrow(() -> parse(code));
        SyntaxBody body = new SyntaxBody(List.copyOf(syntax.getStmts().getValue()), new SourceInfo(SOURCE_ID, null));
        for (SyntaxConversionException failure : List.of(
                assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertFile(syntax, SOURCE_ID)),
                assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(body)))) {
            assertEquals(SOURCE_ID, failure.source().sourceId());
            assertTrue(failure.fieldPath().endsWith(suffix), failure.fieldPath());
            assertFalse(failure.reason().isBlank());
            SourceRange range = failure.source().range();
            assertNotNull(range);
            assertEquals(2, range.startLine());
            assertEquals(2, range.endLine());
            assertEquals(offendingKeyword, code.substring(range.startColumn() - 1, range.endColumn() - 1));
            assertEquals(code.lastIndexOf(offendingKeyword) + 1, range.startColumn());
        }
    }

    private static NodeProgram parse(String code) {
        return assertInstanceOf(NodeProgram.class, Main.parse("<?php\n" + code));
    }

    private static IrBlock body(String code) {
        NodeProgram syntax = parse(code);
        return SyntaxConverter.convertBody(new SyntaxBody(
                List.copyOf(syntax.getStmts().getValue()), new SourceInfo(SOURCE_ID, null)));
    }

    private static IrStatement only(String code) {
        IrBlock result = body(code);
        assertEquals(1, result.statements().size());
        return result.statements().getFirst();
    }

    private static IrClassDeclaration clazz(String code) {
        return assertInstanceOf(IrClassDeclaration.class, only(code));
    }

    private static SourceInfo source(AstNode node) {
        ComplexLocation location = assertInstanceOf(ComplexLocation.class, node.getLocation());
        return new SourceInfo(SOURCE_ID, new SourceRange(location.getStartLine(), location.getStartColumn(),
                location.getEndLine(), location.getEndColumn()));
    }
}
