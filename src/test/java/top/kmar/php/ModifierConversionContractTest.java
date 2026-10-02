package top.kmar.php;

import java_cup.runtime.AstNode;
import java_cup.runtime.symbol.complex.ComplexLocation;
import org.junit.jupiter.api.Test;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.*;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 修饰符规范化的模型字段、失败路径、来源及声明层隔离契约。 */
class ModifierConversionContractTest {
    private static final ComplexLocation OWNER = ComplexLocation.of(3, 1, 10, 30);
    private static final ComplexLocation GROUP = ComplexLocation.of(4, 2, 9, 20);
    private static final ComplexLocation FIRST = ComplexLocation.of(5, 2, 5, 12);
    private static final ComplexLocation CURRENT = ComplexLocation.of(7, 2, 7, 10);
    private static final ComplexLocation TOKEN = ComplexLocation.of(7, 3, 7, 8);
    private static final SourceInfo SOURCE = new SourceInfo("modifiers.php", null);

    // IR 仅保留处理后的可见性和布尔标志，不能留下旧字段或兼容构造器。
    @Test
    void exposesOnlyNormalizedModifierFields() {
        assertEquals(List.of(Visibility.PUBLIC, Visibility.PROTECTED, Visibility.PRIVATE), List.of(Visibility.values()));
        assertShape(IrClassDeclaration.class,
                List.of("name", "isAbstract", "isFinal", "parentType", "interfaces", "members", "source"),
                List.of(String.class, boolean.class, boolean.class, IrNameReference.class, List.class, List.class, SourceInfo.class));
        assertShape(IrMethod.class,
                List.of("name", "visibility", "isStatic", "isAbstract", "isFinal", "parameters", "returnType",
                        "returnsReference", "body", "source"),
                List.of(String.class, Visibility.class, boolean.class, boolean.class, boolean.class, List.class,
                        IrTypeReference.class, boolean.class, IrBlock.class, SourceInfo.class));
        assertShape(IrProperty.class,
                List.of("name", "visibility", "isStatic", "initialValue", "source"),
                List.of(String.class, Visibility.class, boolean.class, IrExpression.class, SourceInfo.class));
        assertShape(IrClassConstant.class,
                List.of("name", "visibility", "value", "source"),
                List.of(String.class, Visibility.class, IrExpression.class, SourceInfo.class));
        assertShape(IrTraitAlias.class,
                List.of("method", "visibility", "newName", "source"),
                List.of(IrTraitMethodReference.class, Visibility.class, String.class, SourceInfo.class));
    }

    // 类修饰符重复或冲突时，错误指向触发失败的当前条目，而非整个类或列表。
    @Test
    void rejectsDuplicateAndConflictingClassModifiersAtCurrentEntry() {
        for (List<Modifier> flags : List.of(
                List.of(Modifier.ABSTRACT, Modifier.ABSTRACT), List.of(Modifier.FINAL, Modifier.FINAL),
                List.of(Modifier.ABSTRACT, Modifier.FINAL), List.of(Modifier.FINAL, Modifier.ABSTRACT))) {
            var first = classModifier(flags.getFirst(), FIRST);
            var current = classModifier(flags.getLast(), CURRENT);
            var declaration = new NodeClassDeclarationStatement.ClassWithModifiers(
                    new NodeListNodeClassModifier(List.of(first, current), GROUP), token("C"),
                    new NodeExtendsFrom(), new NodeImplementsList(), members(), OWNER);
            assertFailure(declaration, ".mods[1]", CURRENT);
        }
    }

    // 方法拒绝重复标志、多种可见性和 abstract 冲突，正反顺序都定位第二项。
    @Test
    void rejectsDuplicateAndConflictingMethodModifiersAtCurrentEntry() {
        for (Modifier flag : List.of(Modifier.PUBLIC, Modifier.PROTECTED, Modifier.PRIVATE,
                Modifier.STATIC, Modifier.ABSTRACT, Modifier.FINAL)) {
            assertFailure(clazz(method(modifiers(flag, flag))), ".methodMods[1]", CURRENT);
        }
        for (Modifier first : List.of(Modifier.PUBLIC, Modifier.PROTECTED, Modifier.PRIVATE)) {
            for (Modifier second : List.of(Modifier.PUBLIC, Modifier.PROTECTED, Modifier.PRIVATE)) {
                if (first != second) assertFailure(clazz(method(modifiers(first, second))), ".methodMods[1]", CURRENT);
            }
        }
        for (List<Modifier> flags : List.of(
                List.of(Modifier.ABSTRACT, Modifier.FINAL), List.of(Modifier.FINAL, Modifier.ABSTRACT),
                List.of(Modifier.ABSTRACT, Modifier.PRIVATE), List.of(Modifier.PRIVATE, Modifier.ABSTRACT))) {
            assertFailure(clazz(method(modifiers(flags.toArray(Modifier[]::new)))), ".methodMods[1]", CURRENT);
        }
    }

    // 属性和常量仅接受其适用修饰符；重复或冲突不会被归一化时静默吞掉。
    @Test
    void rejectsInapplicableAndConflictingPropertyAndConstantModifiers() {
        for (Modifier flag : List.of(Modifier.ABSTRACT, Modifier.FINAL)) {
            assertFailure(clazz(property(modifiers(flag))), ".propMods[0]", CURRENT);
        }
        for (Modifier flag : List.of(Modifier.STATIC, Modifier.ABSTRACT, Modifier.FINAL)) {
            assertFailure(clazz(constant(modifiers(flag))), ".constMods[0]", CURRENT);
        }
        for (List<Modifier> flags : List.of(List.of(Modifier.PUBLIC, Modifier.PUBLIC),
                List.of(Modifier.PUBLIC, Modifier.PRIVATE))) {
            var values = flags.toArray(Modifier[]::new);
            assertFailure(clazz(property(modifiers(values))), ".propMods[1]", CURRENT);
            assertFailure(clazz(constant(modifiers(values))), ".constMods[1]", CURRENT);
        }
        assertFailure(clazz(property(modifiers(Modifier.STATIC, Modifier.STATIC))), ".propMods[1]", CURRENT);
    }

    // trait 别名只能改变可见性，错误仍保留 AST 字段名 modifier。
    @Test
    void rejectsNonVisibilityTraitAliasModifiersAtTheirOwnSource() {
        var method = new NodeTraitMethodReference.SelfMethod(identifier("run"), FIRST);
        for (Modifier flag : List.of(Modifier.STATIC, Modifier.ABSTRACT, Modifier.FINAL)) {
            var alias = new NodeTraitAlias.AliasModifier(method, memberModifier(flag, CURRENT), OWNER);
            assertFailure(clazz(traitUse(alias)), ".alias.modifier", CURRENT);
            var renamed = new NodeTraitAlias.AliasModifierNewName(method, memberModifier(flag, CURRENT),
                    identifier("renamed"), OWNER);
            assertFailure(clazz(traitUse(renamed)), ".alias.modifier", CURRENT);
        }
    }

    // 接口方法隐式 abstract，源码只能显式 public/static；接口常量只能 public。
    @Test
    void rejectsDisallowedInterfaceModifiersWithoutLosingEntryOrigins() {
        for (Modifier flag : List.of(Modifier.ABSTRACT, Modifier.FINAL, Modifier.PROTECTED, Modifier.PRIVATE)) {
            assertFailure(iface(method(modifiers(flag))), ".methodMods[0]", CURRENT);
        }
        for (Modifier flag : List.of(Modifier.PROTECTED, Modifier.PRIVATE, Modifier.STATIC,
                Modifier.ABSTRACT, Modifier.FINAL)) {
            assertFailure(iface(constant(modifiers(flag))), ".constMods[0]", CURRENT);
        }
        assertFailure(iface(method(modifiers(Modifier.PUBLIC, Modifier.PUBLIC))), ".methodMods[1]", CURRENT);
        assertFailure(iface(method(modifiers(Modifier.STATIC, Modifier.STATIC))), ".methodMods[1]", CURRENT);
        assertFailure(iface(constant(modifiers(Modifier.PUBLIC, Modifier.PUBLIC))), ".constMods[1]", CURRENT);
    }

    // Java 的 Unicode equalsIgnoreCase 会折叠这些字符，IR 关键字只接受 ASCII 大小写变化。
    @Test
    void rejectsUnicodeCaseFoldedKeywordsAtOriginalKeywordPaths() {
        for (NodeMemberModifier flag : List.of(
                new NodeMemberModifier.Public(token("publıc"), CURRENT),
                new NodeMemberModifier.Private(token("prıvate"), CURRENT),
                new NodeMemberModifier.Static(token("ſtatic"), CURRENT),
                new NodeMemberModifier.Abstract(token("abſtract"), CURRENT),
                new NodeMemberModifier.Final(token("fınal"), CURRENT))) {
            assertFailure(clazz(method(new NodeListNodeMemberModifier(List.of(flag), GROUP))), ".methodMods[0].kw", CURRENT);
        }
        for (NodeClassModifier flag : List.of(
                new NodeClassModifier.Abstract(token("abſtract"), CURRENT),
                new NodeClassModifier.Final(token("fınal"), CURRENT))) {
            var declaration = new NodeClassDeclarationStatement.ClassWithModifiers(
                    new NodeListNodeClassModifier(List.of(flag), GROUP), token("C"),
                    new NodeExtendsFrom(), new NodeImplementsList(), members(), OWNER);
            assertFailure(declaration, ".mods[0].kw", CURRENT);
        }
        var method = new NodeTraitMethodReference.SelfMethod(identifier("run"), FIRST);
        var alias = new NodeTraitAlias.AliasModifier(method,
                new NodeMemberModifier.Private(token("prıvate"), CURRENT), OWNER);
        assertFailure(clazz(traitUse(alias)), ".alias.modifier.kw", CURRENT);
    }

    // 属性列表本身存在但为空时，继续使用属性组作为来源，不改成修饰符列表范围。
    @Test
    void retainsPropertyOwnerForEmptyModifierListErrors() {
        assertFailure(clazz(property(new NodeListNodeMemberModifier(List.of(), GROUP))), ".propMods", OWNER);
    }

    // 当前违规条目未知或零宽来源也必须原样保留，不能回退到已知外层范围。
    @Test
    void preservesUnknownAndZeroWidthInvalidModifierSources() {
        for (ComplexLocation location : List.of(ComplexLocation.NO_LOCATION, ComplexLocation.of(8, 4, 8, 4))) {
            var flags = new NodeListNodeMemberModifier(List.of(memberModifier(Modifier.PUBLIC, FIRST),
                    memberModifier(Modifier.PRIVATE, location)), GROUP);
            assertFailure(clazz(method(flags)), ".methodMods[1]", location);
        }
    }

    // 转换失败不修改 AST 或声明索引；声明层仍保留修饰符原顺序和重复项。
    @Test
    void preservesRawDeclarationModifiersAcrossFailedNormalization() {
        var syntax = Main.parse("""
                <?php
                abstract abstract class C {
                    public private static function run();
                    static public public $value;
                    private protected const VALUE = 1;
                }
                """);
        var file = DeclarationExtractor.extract(syntax, "modifiers.php");
        var declaration = assertInstanceOf(ClassLikeDefinition.class,
                file.namespaceSections().getFirst().declarations().getFirst());
        assertEquals(List.of(Modifier.ABSTRACT, Modifier.ABSTRACT), declaration.declaredModifiers());
        assertEquals(List.of(Modifier.PUBLIC, Modifier.PRIVATE, Modifier.STATIC),
                assertInstanceOf(MethodDefinition.class, declaration.members().getFirst()).declaredModifiers());
        assertEquals(List.of(Modifier.STATIC, Modifier.PUBLIC, Modifier.PUBLIC),
                assertInstanceOf(PropertyDefinition.class, declaration.members().get(1)).declaredModifiers());
        assertEquals(List.of(Modifier.PRIVATE, Modifier.PROTECTED),
                assertInstanceOf(ClassConstantDefinition.class, declaration.members().get(2)).declaredModifiers());
        String before = syntax.toTreeString(false);
        assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertFile(syntax, "modifiers.php"));
        assertEquals(before, syntax.toTreeString(false));
        assertSame(declaration, file.declarationIndex().findById(declaration.id()).orElseThrow());
        assertEquals(file.namespaceSections(), DeclarationExtractor.extract(syntax, "modifiers.php").namespaceSections());
        assertDoesNotThrow(() -> SyntaxConverter.convertFile(Main.parse("<?php class Valid { function run(); }")));
    }

    private static void assertShape(Class<?> type, List<String> names, List<Class<?>> types) {
        assertEquals(names, Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList());
        assertEquals(types, Arrays.stream(type.getRecordComponents()).map(RecordComponent::getType).toList());
        assertEquals(1, type.getDeclaredConstructors().length);
        for (RecordComponent component : type.getRecordComponents()) {
            assertFalse(component.getGenericType().getTypeName().contains("model.Modifier"));
        }
    }

    private static void assertFailure(AstNode declaration, String suffix, ComplexLocation location) {
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                () -> SyntaxConverter.convertBody(new SyntaxBody(List.of(declaration), SOURCE)));
        assertTrue(error.fieldPath().endsWith(suffix), error.fieldPath());
        assertEquals("modifiers.php", error.source().sourceId());
        assertEquals(location.isNoLocation() ? null : new SourceRange(location.getStartLine(), location.getStartColumn(),
                location.getEndLine(), location.getEndColumn()), error.source().range());
        assertFalse(error.reason().isBlank());
    }

    private static NodeClassDeclarationStatement clazz(NodeClassStatement... values) {
        return new NodeClassDeclarationStatement.Class(token("C"), new NodeExtendsFrom(),
                new NodeImplementsList(), members(values), OWNER);
    }

    private static NodeInterfaceDeclarationStatement iface(NodeClassStatement... values) {
        return new NodeInterfaceDeclarationStatement.InterfaceDecl(token("I"), new NodeInterfaceExtendsList(),
                members(values), OWNER);
    }

    private static NodeListNodeClassStatement members(NodeClassStatement... values) {
        return new NodeListNodeClassStatement(List.of(values), GROUP);
    }

    private static NodeClassStatement method(NodeListNodeMemberModifier flags) {
        return new NodeClassStatement.Method(flags, null, identifier("run"),
                new NodeListNodeParameter(List.of(), GROUP), new NodeReturnType(), new NodeMethodBody(), OWNER);
    }

    private static NodeClassStatement property(NodeListNodeMemberModifier flags) {
        return new NodeClassStatement.PropertyDecl(flags, new NodeListNodeProperty(List.of(
                new NodeProperty.Property(token("value"), FIRST)), GROUP), OWNER);
    }

    private static NodeClassStatement constant(NodeListNodeMemberModifier flags) {
        var expression = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(token("1"), FIRST), FIRST), FIRST);
        return new NodeClassStatement.ConstDecl(flags, new NodeListNodeClassConstDecl(List.of(
                new NodeClassConstDecl.ClassConstDecl(identifier("VALUE"), expression, FIRST)), GROUP), OWNER);
    }

    private static NodeClassStatement traitUse(NodeTraitAlias alias) {
        var name = new NodeName.Unqualified(new NodeNamespaceName.Part(token("T"), FIRST), FIRST);
        return new NodeClassStatement.UseTrait(new NodeListNodeName(List.of(name), GROUP),
                new NodeTraitAdaptations.Block(new NodeListNodeTraitAdaptation(List.of(
                        new NodeTraitAdaptation.Alias(alias, GROUP)), GROUP), GROUP), OWNER);
    }

    private static NodeListNodeMemberModifier modifiers(Modifier... values) {
        var result = new ArrayList<NodeMemberModifier>();
        for (int i = 0; i < values.length; i++) {
            result.add(memberModifier(values[i], i == values.length - 1 ? CURRENT : FIRST));
        }
        return new NodeListNodeMemberModifier(result, GROUP);
    }

    private static NodeMemberModifier memberModifier(Modifier flag, ComplexLocation location) {
        var keyword = token(flag.name());
        return switch (flag) {
            case PUBLIC -> new NodeMemberModifier.Public(keyword, location);
            case PROTECTED -> new NodeMemberModifier.Protected(keyword, location);
            case PRIVATE -> new NodeMemberModifier.Private(keyword, location);
            case STATIC -> new NodeMemberModifier.Static(keyword, location);
            case ABSTRACT -> new NodeMemberModifier.Abstract(keyword, location);
            case FINAL -> new NodeMemberModifier.Final(keyword, location);
            case VAR -> throw new AssertionError("var 是独立属性产生式");
        };
    }

    private static NodeClassModifier classModifier(Modifier flag, ComplexLocation location) {
        return switch (flag) {
            case ABSTRACT -> new NodeClassModifier.Abstract(token("ABSTRACT"), location);
            case FINAL -> new NodeClassModifier.Final(token("FINAL"), location);
            default -> throw new AssertionError(flag);
        };
    }

    private static NodeIdentifier identifier(String value) {
        return new NodeIdentifier.Identifier(token(value), FIRST);
    }

    private static NodeString token(String value) { return new NodeString(value, TOKEN); }
}
