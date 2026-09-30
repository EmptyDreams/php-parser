package top.kmar.php;

import java_cup.runtime.symbol.complex.ComplexLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.*;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 IR 类型分类、双层来源、签名接入及与第一阶段类型模型的隔离契约。 */
class TypeReferenceConversionTest {
    private static final ComplexLocation OUTER = ComplexLocation.of(2, 1, 8, 30);
    private static final ComplexLocation FULL_TYPE = ComplexLocation.of(3, 2, 3, 25);
    private static final ComplexLocation TYPE = ComplexLocation.of(3, 4, 3, 22);
    private static final ComplexLocation NAME = ComplexLocation.of(3, 6, 3, 20);
    private static final ComplexLocation COMPONENT = ComplexLocation.of(3, 8, 3, 18);
    private static final ComplexLocation TOKEN = ComplexLocation.of(3, 10, 3, 16);
    private static final SourceInfo SOURCE = new SourceInfo("types.php", new SourceRange(99, 1, 99, 9));

    // 九种内置类型均按 ASCII 大小写规范化，参数和返回值共享分类；不验证 void 的使用位置。
    @Test
    void classifiesEveryBuiltinTypeInParametersAndReturnTypes() {
        Map<String, BuiltinTypeKind> types = Map.ofEntries(
                Map.entry("InT", BuiltinTypeKind.INTEGER), Map.entry("FlOaT", BuiltinTypeKind.FLOAT),
                Map.entry("StRiNg", BuiltinTypeKind.STRING), Map.entry("BoOl", BuiltinTypeKind.BOOLEAN),
                Map.entry("ArRaY", BuiltinTypeKind.ARRAY), Map.entry("CaLlAbLe", BuiltinTypeKind.CALLABLE),
                Map.entry("ItErAbLe", BuiltinTypeKind.ITERABLE), Map.entry("ObJeCt", BuiltinTypeKind.OBJECT),
                Map.entry("VoId", BuiltinTypeKind.VOID));
        types.forEach((spelling, kind) -> {
            IrClosure closure = parsedClosure("function(" + spelling + " $value): " + spelling + " {}");
            assertBuiltin(kind, closure.parameters().getFirst().declaredType());
            assertBuiltin(kind, closure.returnType());
            assertFalse(closure.parameters().getFirst().declaredType().nullable());
            assertFalse(closure.returnType().nullable());
        });
    }

    // self 和 parent 只标记特殊类型，namespace 相对形式也参与分类；不绑定或要求存在所属类。
    @Test
    void classifiesSelfAndParentWithoutResolvingTheirOwners() {
        Map.of("SeLf", SpecialTypeKind.SELF, "PaReNt", SpecialTypeKind.PARENT).forEach((spelling, kind) -> {
            IrClosure closure = parsedClosure("function(" + spelling + " $value): " + spelling + " {}");
            assertEquals(kind, assertInstanceOf(IrSpecialType.class, closure.parameters().getFirst().declaredType().type()).kind());
            assertEquals(kind, assertInstanceOf(IrSpecialType.class, closure.returnType().type()).kind());
            IrTypeReference relative = convertType(new NodeType.NameType(name(spelling, NameForm.NAMESPACE_RELATIVE, NAME), TYPE));
            assertEquals(kind, assertInstanceOf(IrSpecialType.class, relative.type()).kind());
            assertSource(NAME, relative.type().source());
        });
        IrClosure relative = parsedClosure("function(namespace\\SeLf $value): namespace\\PaReNt {}");
        assertEquals(SpecialTypeKind.SELF, assertInstanceOf(IrSpecialType.class, relative.parameters().getFirst().declaredType().type()).kind());
        assertEquals(SpecialTypeKind.PARENT, assertInstanceOf(IrSpecialType.class, relative.returnType().type()).kind());
    }

    // 内置类型仅识别非限定名称；限定及全局 self/parent 也保持引用，不套用 namespace 相对形式的规则。
    @Test
    void preservesQualifiedAndRelativeBuiltinNamesAsNamedTypes() {
        for (String component : List.of("int", "self", "parent", "array")) {
            for (NameForm form : List.of(NameForm.QUALIFIED, NameForm.FULLY_QUALIFIED, NameForm.NAMESPACE_RELATIVE)) {
                if (form == NameForm.NAMESPACE_RELATIVE && (component.equals("self") || component.equals("parent"))) continue;
                NodeName name = name(component, form, NAME);
                IrTypeReference type = convertType(new NodeType.NameType(name, TYPE));
                IrNamedType named = assertInstanceOf(IrNamedType.class, type.type());
                String expected = switch (form) {
                    case QUALIFIED -> "App\\" + component;
                    case FULLY_QUALIFIED -> "\\" + component;
                    case NAMESPACE_RELATIVE -> "namespace\\" + component;
                    default -> throw new AssertionError(form);
                };
                assertEquals(expected, named.name().spelling());
                assertEquals(form, named.name().form());
                assertSame(named.name().source(), named.source());
            }
        }
    }

    // 强转别名、新版类型名、static 及 Unicode 近似拼写不扩展 PHP 7.2 的内置类型分类。
    @Test
    void keepsAliasesNewerNamesAndUnicodeLookalikesAsNamedTypes() {
        for (String spelling : List.of("Widget", "integer", "boolean", "double", "real", "mixed", "never", "static",
                "\u0131nt", "\u017Ftring", "\u0130NT", "b\u03BFol", "v\u03BFid")) {
            IrNamedType named = assertInstanceOf(IrNamedType.class,
                    convertType(new NodeType.NameType(name(spelling, NameForm.UNQUALIFIED, NAME), TYPE)).type());
            assertEquals(spelling, named.name().spelling());
            assertEquals(NameForm.UNQUALIFIED, named.name().form());
        }
    }

    // array/callable 的专有 AST 变体仍需合法 ASCII token，损坏 token 在参数和返回位置均明确失败。
    @Test
    void rejectsMalformedDedicatedTypeKeywordsWithContextualPaths() {
        for (NodeType syntax : List.of(
                new NodeType.ArrayType(new NodeString("arr\u0430y", TOKEN), TYPE),
                new NodeType.CallableType(new NodeString("c\u0430llable", TOKEN), TYPE),
                new NodeType.ArrayType(new NodeString(" array", TOKEN), TYPE),
                new NodeType.CallableType(new NodeString("callable ", TOKEN), TYPE))) {
            NodeTypeExpr fullType = new NodeTypeExpr.Type(syntax, FULL_TYPE);
            for (boolean parameter : List.of(true, false)) {
                SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                        () -> convertClosure(fullType, parameter));
                assertEquals(parameter ? "expression.ev.params[0].type.t.kw" : "expression.ev.returnType.t.t.kw",
                        error.fieldPath());
                assertSource(TYPE, error.source());
                assertFalse(error.reason().isBlank());
            }
        }
    }

    // 完整类型和具体名称各自保留来源；专有关键字取 token，NameType 则取 NodeName，不借用外壳。
    @Test
    void preservesDistinctFullTypeAndTypeNameSources() {
        for (String spelling : List.of("InT", "SeLf", "Widget")) {
            IrTypeReference result = convertType(new NodeTypeExpr.NullableType(new NodeString("?", OUTER),
                    new NodeType.NameType(name(spelling, NameForm.UNQUALIFIED, NAME), TYPE), FULL_TYPE));
            assertTrue(result.nullable());
            assertSource(FULL_TYPE, result.source());
            assertSource(NAME, result.type().source());
        }
        for (NodeType syntax : List.of(new NodeType.ArrayType(new NodeString("ArRaY", TOKEN), TYPE),
                new NodeType.CallableType(new NodeString("CaLlAbLe", TOKEN), TYPE))) {
            IrTypeReference result = convertType(syntax);
            assertSource(FULL_TYPE, result.source());
            assertSource(TOKEN, result.type().source());
        }
    }

    // 两层未知位置互不回填，零宽位置保持已知；调用方的备用范围不参与内部来源推断。
    @Test
    void keepsUnknownAndZeroWidthTypeSourcesIndependent() {
        for (ComplexLocation location : Arrays.asList(null, ComplexLocation.NO_LOCATION, ComplexLocation.of(4, 7, 4, 7))) {
            for (String spelling : List.of("int", "self", "Widget")) {
                IrTypeReference unknownName = convertType(new NodeType.NameType(
                        name(spelling, NameForm.UNQUALIFIED, location), TYPE));
                assertSource(FULL_TYPE, unknownName.source());
                assertSource(location, unknownName.type().source());
                IrTypeReference unknownFull = convertType(new NodeTypeExpr.Type(
                        new NodeType.NameType(name(spelling, NameForm.UNQUALIFIED, NAME), TYPE), location));
                assertSource(location, unknownFull.source());
                assertSource(NAME, unknownFull.type().source());
            }
            IrTypeReference keyword = convertType(new NodeTypeExpr.Type(
                    new NodeType.ArrayType(new NodeString("array", location), TYPE), location));
            assertSource(location, keyword.source());
            assertSource(location, keyword.type().source());
        }
    }

    // 模型只保存枚举或名称引用；必需字段非空，具名类型的 source 直接委托原 NameReference。
    @Test
    void enforcesTypedModelContractsWithoutDuplicatingNameSources() throws ReflectiveOperationException {
        IrBuiltinType builtin = new IrBuiltinType(BuiltinTypeKind.INTEGER, SOURCE);
        NameReference reference = new NameReference("\\App\\Widget", NameForm.FULLY_QUALIFIED, SOURCE);
        IrNamedType named = new IrNamedType(reference);
        assertSame(reference, named.name());
        assertSame(SOURCE, named.source());
        for (Executable constructor : List.<Executable>of(
                () -> new IrTypeReference(null, false, SOURCE),
                () -> new IrTypeReference(builtin, false, null),
                () -> new IrBuiltinType(null, SOURCE), () -> new IrBuiltinType(BuiltinTypeKind.INTEGER, null),
                () -> new IrSpecialType(null, SOURCE), () -> new IrSpecialType(SpecialTypeKind.SELF, null),
                () -> new IrNamedType(null))) {
            assertThrows(NullPointerException.class, constructor);
        }
        assertEquals(Set.of(BuiltinTypeKind.INTEGER, BuiltinTypeKind.FLOAT, BuiltinTypeKind.STRING, BuiltinTypeKind.BOOLEAN,
                        BuiltinTypeKind.ARRAY, BuiltinTypeKind.CALLABLE, BuiltinTypeKind.ITERABLE, BuiltinTypeKind.OBJECT,
                        BuiltinTypeKind.VOID), Set.of(BuiltinTypeKind.values()));
        assertEquals(Set.of(SpecialTypeKind.SELF, SpecialTypeKind.PARENT), Set.of(SpecialTypeKind.values()));
        assertEquals(List.of("type", "nullable", "source"), fields(IrTypeReference.class));
        assertEquals(List.of("kind", "source"), fields(IrBuiltinType.class));
        assertEquals(List.of("kind", "source"), fields(IrSpecialType.class));
        assertEquals(List.of("name"), fields(IrNamedType.class));
        assertTrue(IrTypeName.class.isSealed());
        assertEquals(Set.of(IrBuiltinType.class, IrSpecialType.class, IrNamedType.class),
                Set.of(IrTypeName.class.getPermittedSubclasses()));
        assertEquals(IrTypeReference.class, IrParameter.class.getMethod("declaredType").getReturnType());
        for (Class<?> callable : List.of(IrFunctionDeclaration.class, IrMethod.class, IrClosure.class)) {
            assertEquals(IrTypeReference.class, callable.getMethod("returnType").getReturnType());
        }
        assertEquals(TypeReference.class, ParameterDefinition.class.getMethod("declaredType").getReturnType());
    }

    // nullable 仅来自显式问号；默认值 null 不改变类型，无类型与可空类型仍不同，也不校验 void 的合法位置。
    @Test
    void derivesNullabilityOnlyFromTheExplicitTypeMarker() {
        IrClosure closure = parsedClosure("function(int $implicit=null, ?int $explicit=null, $untyped=null): ?void {}");
        IrParameter implicit = closure.parameters().getFirst();
        assertBuiltin(BuiltinTypeKind.INTEGER, implicit.declaredType());
        assertFalse(implicit.declaredType().nullable());
        assertInstanceOf(IrNullLiteral.class, implicit.defaultValue());
        IrParameter explicit = closure.parameters().get(1);
        assertBuiltin(BuiltinTypeKind.INTEGER, explicit.declaredType());
        assertTrue(explicit.declaredType().nullable());
        assertInstanceOf(IrNullLiteral.class, explicit.defaultValue());
        assertNull(closure.parameters().get(2).declaredType());
        assertInstanceOf(IrNullLiteral.class, closure.parameters().get(2).defaultValue());
        assertBuiltin(BuiltinTypeKind.VOID, closure.returnType());
        assertTrue(closure.returnType().nullable());
    }

    // 函数、方法、闭包和匿名类共享类型转换；整文件及局部入口一致，声明层继续保留原始类型拼写。
    @Test
    void sharesTypedSignaturesAcrossEntrypointsWithoutChangingDeclarationModels() {
        NodeProgram syntax = (NodeProgram) Main.parse("""
                <?php
                namespace Demo;
                function sample(?InT $value): VoId {}
                class Box { public function method(CaLlAbLe $fn): SeLf {} }
                $closure = function(FlOaT $value): PaReNt {};
                $object = new class { public function method(ItErAbLe $items): ObJeCt {} };
                """);
        PhpFile declarations = DeclarationExtractor.extract(syntax, SOURCE.sourceId());
        NamespaceSection section = declarations.namespaceSections().getFirst();
        FunctionDefinition originalFunction = assertInstanceOf(FunctionDefinition.class, section.declarations().getFirst());
        ClassLikeDefinition originalClass = assertInstanceOf(ClassLikeDefinition.class, section.declarations().get(1));
        MethodDefinition originalMethod = assertInstanceOf(MethodDefinition.class, originalClass.members().getFirst());
        String before = syntax.toTreeString(false);
        IrFile file = SyntaxConverter.convertFile(syntax, SOURCE.sourceId());
        IrBlock body = file.namespaceSections().getFirst().body();
        assertEquals(body.statements(), SyntaxConverter.convertBody(section.body()).statements());
        IrFunctionDeclaration function = assertInstanceOf(IrFunctionDeclaration.class, body.statements().getFirst());
        assertBuiltin(BuiltinTypeKind.INTEGER, function.parameters().getFirst().declaredType());
        assertTrue(function.parameters().getFirst().declaredType().nullable());
        assertBuiltin(BuiltinTypeKind.VOID, function.returnType());
        IrMethod method = assertInstanceOf(IrMethod.class,
                assertInstanceOf(IrClassDeclaration.class, body.statements().get(1)).members().getFirst());
        assertBuiltin(BuiltinTypeKind.CALLABLE, method.parameters().getFirst().declaredType());
        assertEquals(SpecialTypeKind.SELF, assertInstanceOf(IrSpecialType.class, method.returnType().type()).kind());
        IrClosure closure = assertInstanceOf(IrClosure.class, assignmentValue(body, 2));
        assertBuiltin(BuiltinTypeKind.FLOAT, closure.parameters().getFirst().declaredType());
        assertEquals(SpecialTypeKind.PARENT, assertInstanceOf(IrSpecialType.class, closure.returnType().type()).kind());
        IrNewAnonymous creation = assertInstanceOf(IrNewAnonymous.class, assignmentValue(body, 3));
        IrMethod anonymousMethod = assertInstanceOf(IrMethod.class, creation.definition().members().getFirst());
        assertBuiltin(BuiltinTypeKind.ITERABLE, anonymousMethod.parameters().getFirst().declaredType());
        assertBuiltin(BuiltinTypeKind.OBJECT, anonymousMethod.returnType());

        NodeExpr closureSyntax = assertInstanceOf(NodeTopStatement.class, section.body().statements().get(2))
                .getStmt().getExpression().getEv().getValue();
        NodeExpr anonymousSyntax = assertInstanceOf(NodeTopStatement.class, section.body().statements().get(3))
                .getStmt().getExpression().getEv().getValue();
        assertEquals(closure, SyntaxConverter.convertExpression(new SyntaxExpression(closureSyntax, SOURCE)));
        assertEquals(creation, SyntaxConverter.convertExpression(new SyntaxExpression(anonymousSyntax, SOURCE)));
        assertEquals("InT", originalFunction.signature().parameters().getFirst().declaredType().name().spelling());
        assertEquals("VoId", originalFunction.signature().returnType().name().spelling());
        assertEquals("CaLlAbLe", originalMethod.signature().parameters().getFirst().declaredType().name().spelling());
        assertEquals("SeLf", originalMethod.signature().returnType().name().spelling());
        assertEquals(originalFunction.signature().parameters().getFirst().declaredType().source(),
                function.parameters().getFirst().declaredType().source());
        assertEquals(before, syntax.toTreeString(false));
        assertSame(originalFunction, declarations.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "Demo\\sample").getFirst());
        assertEquals(file, SyntaxConverter.convertFile(syntax, SOURCE.sourceId()));
    }

    private static IrExpression assignmentValue(IrBlock body, int index) {
        return assertInstanceOf(IrAssignment.class,
                assertInstanceOf(IrExpressionStatement.class, body.statements().get(index)).expression()).value();
    }

    private static IrClosure parsedClosure(String code) {
        NodeProgram syntax = (NodeProgram) Main.parse("<?php " + code + ";");
        NodeExpr expression = syntax.getStmts().getValue().getFirst().getStmt().getExpression();
        return assertInstanceOf(IrClosure.class, SyntaxConverter.convertExpression(new SyntaxExpression(expression, SOURCE)));
    }

    private static IrTypeReference convertType(NodeType type) {
        return convertType(new NodeTypeExpr.Type(type, FULL_TYPE));
    }

    private static IrTypeReference convertType(NodeTypeExpr type) {
        return convertClosure(type, true).parameters().getFirst().declaredType();
    }

    private static IrClosure convertClosure(NodeTypeExpr type, boolean parameter) {
        List<NodeParameter> parameters = parameter
                ? List.of(new NodeParameter.Param(type, null, null, new NodeString("value", TOKEN), OUTER)) : List.of();
        NodeReturnType returnType = parameter ? new NodeReturnType() : new NodeReturnType.ReturnType(type, OUTER);
        NodeExpr syntax = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Closure(null,
                new NodeListNodeParameter(parameters, OUTER), new NodeLexicalVars(), returnType,
                new NodeListNodeInnerStatement(List.of(), OUTER), OUTER), OUTER);
        return assertInstanceOf(IrClosure.class, SyntaxConverter.convertExpression(new SyntaxExpression(syntax, SOURCE)));
    }

    private static NodeName name(String spelling, NameForm form, ComplexLocation location) {
        NodeNamespaceName component = new NodeNamespaceName.Part(new NodeString(spelling, TOKEN), COMPONENT);
        return switch (form) {
            case UNQUALIFIED -> new NodeName.Unqualified(component, location);
            case QUALIFIED -> new NodeName.Unqualified(new NodeNamespaceName.Nested(
                    new NodeNamespaceName.Part(new NodeString("App", TOKEN), COMPONENT),
                    new NodeString(spelling, TOKEN), COMPONENT), location);
            case FULLY_QUALIFIED -> new NodeName.FullyQualified(new NodeString("\\", TOKEN), component, location);
            case NAMESPACE_RELATIVE -> new NodeName.Relative(new NodeString("namespace", TOKEN), component, location);
        };
    }

    private static List<String> fields(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static void assertBuiltin(BuiltinTypeKind kind, IrTypeReference type) {
        assertNotNull(type);
        assertEquals(kind, assertInstanceOf(IrBuiltinType.class, type.type()).kind());
    }

    private static void assertSource(ComplexLocation location, SourceInfo source) {
        assertEquals(SOURCE.sourceId(), source.sourceId());
        if (location == null || location.isNoLocation()) assertNull(source.range());
        else assertEquals(new SourceRange(location.getStartLine(), location.getStartColumn(),
                location.getEndLine(), location.getEndColumn()), source.range());
    }
}
