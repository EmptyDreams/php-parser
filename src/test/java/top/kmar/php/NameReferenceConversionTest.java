package top.kmar.php;

import java_cup.runtime.AstNode;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 IR 名称主体规范化、分类边界、错误定位及只委托来源的包装节点。 */
@SuppressWarnings("UnnecessaryUnicodeEscape")
class NameReferenceConversionTest {
    private static final ComplexLocation OUTER = ComplexLocation.of(2, 1, 8, 30);
    private static final ComplexLocation REFERENCE = ComplexLocation.of(3, 2, 3, 25);
    private static final ComplexLocation NAME = ComplexLocation.of(3, 6, 3, 20);
    private static final ComplexLocation COMPONENT = ComplexLocation.of(3, 8, 3, 18);
    private static final ComplexLocation TOKEN = ComplexLocation.of(3, 10, 3, 16);
    private static final SourceInfo SOURCE = new SourceInfo("names.php", new SourceRange(99, 1, 99, 9));
    private static final String CONSTANT_NAME_PATH = "expression.ev.scalar.c.n";

    // 四种限定形式只移除外层语法前缀；大小写、Unicode 名称及内部的分隔符原样保留。
    @Test
    void normalizesAllNameFormsAcrossExpressionReferences() {
        for (NameCase sample : List.of(
                new NameCase("WiDget", "WiDget", NameForm.UNQUALIFIED),
                new NameCase("应用\\WiDget", "应用\\WiDget", NameForm.QUALIFIED),
                new NameCase("\\WiDget", "WiDget", NameForm.FULLY_QUALIFIED),
                new NameCase("\\应用\\WiDget", "应用\\WiDget", NameForm.FULLY_QUALIFIED),
                new NameCase("namespace\\WiDget", "WiDget", NameForm.NAMESPACE_RELATIVE),
                new NameCase("NaMeSpAcE\\应用\\WiDget", "应用\\WiDget", NameForm.NAMESPACE_RELATIVE))) {
            String spelling = sample.spelling();
            assertName(sample, assertInstanceOf(IrConstantReference.class, parsed(spelling)).name());
            IrCall call = assertInstanceOf(IrCall.class, parsed(spelling + "()"));
            assertName(sample, assertInstanceOf(IrNamedCallTarget.class, call.target()).name());
            assertClassName(sample, assertInstanceOf(IrNew.class, parsed("new " + spelling + "()")).classReference());
            assertClassName(sample, assertInstanceOf(IrInstanceOf.class,
                    parsed("$value instanceof " + spelling)).classReference());
            assertClassName(sample, assertInstanceOf(IrStaticCall.class, parsed(spelling + "::run()")).classReference());
            assertClassName(sample, assertInstanceOf(IrClassConstantReference.class,
                    parsed(spelling + "::FLAG")).classReference());
            assertClassName(sample, assertInstanceOf(IrClassName.class, parsed(spelling + "::class")).classReference());
        }
    }

    // 继承、接口、匿名类、异常类型及 trait 适配规则使用同一名称模型，顺序和重复项不变。
    @Test
    void normalizesNamesInDeclarationsInheritanceCatchAndTraitAdaptations() throws ReflectiveOperationException {
        NodeProgram syntax = program("""
                namespace Demo;
                function sample(?\\App\\Widget $value): namespace\\Result {}
                class Box extends \\Base implements Local, Rel\\Contract, namespace\\Contract, Local {
                    use \\Traits\\A, namespace\\B, Rel\\C {
                        namespace\\B::run insteadof \\Traits\\A, Rel\\C, Rel\\C;
                        Rel\\C::run as Alias;
                        run as Other;
                    }
                    function method(\\App\\Widget $value): namespace\\Result {}
                }
                interface Contract extends Local, Rel\\Contract, \\Root\\Contract, namespace\\Contract {}
                $object = new class extends \\Base implements namespace\\Contract, Rel\\Contract {};
                try {} catch (Local|Rel\\Failure|\\Root\\Failure|namespace\\Failure $error) {}
                """);
        IrFile file = SyntaxConverter.convertFile(syntax, SOURCE.sourceId());
        List<IrStatement> statements = file.namespaceSections().getFirst().body().statements();
        IrFunctionDeclaration function = assertInstanceOf(IrFunctionDeclaration.class, statements.getFirst());
        assertName("App\\Widget", NameForm.FULLY_QUALIFIED,
                assertInstanceOf(IrNamedType.class, function.parameters().getFirst().declaredType().type()).name());
        assertTrue(function.parameters().getFirst().declaredType().nullable());
        assertName("Result", NameForm.NAMESPACE_RELATIVE,
                assertInstanceOf(IrNamedType.class, function.returnType().type()).name());

        IrClassDeclaration clazz = assertInstanceOf(IrClassDeclaration.class, statements.get(1));
        assertName("Base", NameForm.FULLY_QUALIFIED, clazz.parentType());
        assertNames(clazz.interfaces(), List.of("Local", "Rel\\Contract", "Contract", "Local"),
                List.of(NameForm.UNQUALIFIED, NameForm.QUALIFIED, NameForm.NAMESPACE_RELATIVE, NameForm.UNQUALIFIED));
        IrTraitUse use = assertInstanceOf(IrTraitUse.class, clazz.members().getFirst());
        assertNames(use.traits(), List.of("Traits\\A", "B", "Rel\\C"),
                List.of(NameForm.FULLY_QUALIFIED, NameForm.NAMESPACE_RELATIVE, NameForm.QUALIFIED));
        IrTraitPrecedence precedence = assertInstanceOf(IrTraitPrecedence.class, use.adaptations().getFirst());
        assertName("B", NameForm.NAMESPACE_RELATIVE, precedence.method().trait());
        assertEquals("run", precedence.method().method());
        assertNames(precedence.insteadOf(), List.of("Traits\\A", "Rel\\C", "Rel\\C"),
                List.of(NameForm.FULLY_QUALIFIED, NameForm.QUALIFIED, NameForm.QUALIFIED));
        IrTraitAlias alias = assertInstanceOf(IrTraitAlias.class, use.adaptations().get(1));
        assertName("Rel\\C", NameForm.QUALIFIED, alias.method().trait());
        assertEquals("Alias", alias.newName());
        IrTraitAlias unqualified = assertInstanceOf(IrTraitAlias.class, use.adaptations().get(2));
        assertNull(unqualified.method().trait());
        assertEquals("Other", unqualified.newName());
        IrMethod method = assertInstanceOf(IrMethod.class, clazz.members().get(1));
        assertName("App\\Widget", NameForm.FULLY_QUALIFIED,
                assertInstanceOf(IrNamedType.class, method.parameters().getFirst().declaredType().type()).name());
        assertName("Result", NameForm.NAMESPACE_RELATIVE,
                assertInstanceOf(IrNamedType.class, method.returnType().type()).name());

        IrInterfaceDeclaration contract = assertInstanceOf(IrInterfaceDeclaration.class, statements.get(2));
        assertNames(contract.parentTypes(), List.of("Local", "Rel\\Contract", "Root\\Contract", "Contract"),
                List.of(NameForm.UNQUALIFIED, NameForm.QUALIFIED, NameForm.FULLY_QUALIFIED, NameForm.NAMESPACE_RELATIVE));
        IrAnonymousClass anonymous = assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrAssignment.class, expression(statements.get(3))).value()).definition();
        assertName("Base", NameForm.FULLY_QUALIFIED, anonymous.parentType());
        assertNames(anonymous.interfaces(), List.of("Contract", "Rel\\Contract"),
                List.of(NameForm.NAMESPACE_RELATIVE, NameForm.QUALIFIED));
        IrCatch caught = assertInstanceOf(IrTry.class, statements.get(4)).catches().getFirst();
        assertNames(caught.exceptionTypes(), List.of("Local", "Rel\\Failure", "Root\\Failure", "Failure"),
                List.of(NameForm.UNQUALIFIED, NameForm.QUALIFIED, NameForm.FULLY_QUALIFIED, NameForm.NAMESPACE_RELATIVE));
        assertNoSyntaxOrDeclarationNames(file, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    // 去除全限定前缀后仍正确识别 true/false/null；限定名称和 namespace 相对名称不能被误识别为字面量。
    @Test
    void preservesBooleanAndNullClassificationAfterPrefixRemoval() {
        for (String spelling : List.of("true", "TrUe", "\\TRUE")) {
            assertTrue(assertInstanceOf(IrBooleanLiteral.class, parsed(spelling)).value());
        }
        for (String spelling : List.of("false", "FaLsE", "\\FALSE")) {
            assertFalse(assertInstanceOf(IrBooleanLiteral.class, parsed(spelling)).value());
        }
        for (String spelling : List.of("null", "NuLl", "\\NULL")) {
            assertInstanceOf(IrNullLiteral.class, parsed(spelling));
        }
        for (String spelling : List.of("namespace\\true", "namespace\\false", "namespace\\null",
                "N\\true", "\\N\\false", "\\N\\null", "INF", "NAN")) {
            assertInstanceOf(IrConstantReference.class, parsed(spelling), spelling);
        }
        assertInstanceOf(IrNamedCallTarget.class, assertInstanceOf(IrCall.class, parsed("true()")).target());
        assertEquals("true", assertInstanceOf(IrClassConstantReference.class, parsed("Box::true")).constantName());
        assertEquals(42L, assertInstanceOf(IrIntegerLiteral.class, parsed("42")).value());
        assertEquals(1.5, assertInstanceOf(IrFloatLiteral.class, parsed("1.5")).value());
    }

    // 内置类型仍限非限定形式；合成 AST 验证关键字不能作为真实限定名 token 时的结构边界。
    @Test
    void preservesAllBuiltinTypeKindsAndQualifiedLookalikes() {
        Map<String, BuiltinTypeKind> builtins = Map.ofEntries(
                Map.entry("InT", BuiltinTypeKind.INTEGER), Map.entry("FlOaT", BuiltinTypeKind.FLOAT),
                Map.entry("StRiNg", BuiltinTypeKind.STRING), Map.entry("BoOl", BuiltinTypeKind.BOOLEAN),
                Map.entry("ArRaY", BuiltinTypeKind.ARRAY), Map.entry("CaLlAbLe", BuiltinTypeKind.CALLABLE),
                Map.entry("ItErAbLe", BuiltinTypeKind.ITERABLE), Map.entry("ObJeCt", BuiltinTypeKind.OBJECT),
                Map.entry("VoId", BuiltinTypeKind.VOID));
        builtins.forEach((spelling, kind) -> {
            assertEquals(kind, assertInstanceOf(IrBuiltinType.class,
                    type(name(spelling, NameForm.UNQUALIFIED, NAME)).type()).kind());
            for (NameForm form : List.of(NameForm.QUALIFIED, NameForm.FULLY_QUALIFIED, NameForm.NAMESPACE_RELATIVE)) {
                String value = form == NameForm.QUALIFIED ? "App\\" + spelling : spelling;
                IrNamedType named = assertInstanceOf(IrNamedType.class, type(name(value, form, NAME)).type());
                assertName(value, form, named.name());
                assertSame(named.name().source(), named.source());
            }
        });
        for (String value : List.of("\u0131nt", "\u017Ftring", "\u0130NT", "b\u03BFol", "应用", "static")) {
            assertName(value, NameForm.UNQUALIFIED,
                    assertInstanceOf(IrNamedType.class, type(name(value, NameForm.UNQUALIFIED, NAME)).type()).name());
        }
    }

    // 声明类型中的相对 self/parent 与普通类引用的规则不同，不因名称主体相同而合并分类。
    @Test
    void keepsDeclarationTypeAndClassReferenceSpecialNamesDistinct() {
        Map.of("SeLf", SpecialTypeKind.SELF, "PaReNt", SpecialTypeKind.PARENT).forEach((value, kind) -> {
            for (NameForm form : List.of(NameForm.UNQUALIFIED, NameForm.NAMESPACE_RELATIVE)) {
                assertEquals(kind, assertInstanceOf(IrSpecialType.class, type(name(value, form, NAME)).type()).kind());
            }
            for (NameForm form : List.of(NameForm.QUALIFIED, NameForm.FULLY_QUALIFIED)) {
                String qualified = form == NameForm.QUALIFIED ? "App\\" + value : value;
                assertName(qualified, form, assertInstanceOf(IrNamedType.class,
                        type(name(qualified, form, NAME)).type()).name());
            }
            assertName(value, NameForm.NAMESPACE_RELATIVE, assertInstanceOf(IrNamedClassReference.class,
                    assertInstanceOf(IrNew.class, parsed("new namespace\\" + value + "()")).classReference()).name());
            assertName(value, NameForm.FULLY_QUALIFIED, assertInstanceOf(IrNamedClassReference.class,
                    assertInstanceOf(IrNew.class, parsed("new \\" + value + "()")).classReference()).name());
        });
        assertEquals(SpecialClassKind.SELF, assertInstanceOf(IrSpecialClassReference.class,
                assertInstanceOf(IrNew.class, parsed("new self()")).classReference()).kind());
        assertEquals(SpecialClassKind.PARENT, assertInstanceOf(IrSpecialClassReference.class,
                assertInstanceOf(IrNew.class, parsed("new parent()")).classReference()).kind());
        assertEquals(SpecialClassKind.STATIC, assertInstanceOf(IrSpecialClassReference.class,
                assertInstanceOf(IrNew.class, parsed("new static()")).classReference()).kind());
    }

    // 公开模型只验证规范化主体及 form 的结构关系，不执行 PHP 标识符或绑定规则。
    @Test
    void enforcesNormalizedNameModelInvariantsWithoutIdentifierValidation() {
        for (NameForm form : NameForm.values()) {
            for (String value : List.of("", "\\Name", "Name\\", "A\\\\B")) {
                assertThrows(IllegalArgumentException.class, () -> new IrNameReference(value, form, SOURCE));
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> new IrNameReference("A\\B", NameForm.UNQUALIFIED, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> new IrNameReference("Name", NameForm.QUALIFIED, SOURCE));
        for (NameForm form : List.of(NameForm.FULLY_QUALIFIED, NameForm.NAMESPACE_RELATIVE)) {
            assertName("Name", form, new IrNameReference("Name", form, SOURCE));
            assertName("A\\B", form, new IrNameReference("A\\B", form, SOURCE));
        }
        for (String value : List.of("123", "$variable", "has space", "名称", "\u017Felf")) {
            assertName(value, NameForm.UNQUALIFIED, new IrNameReference(value, NameForm.UNQUALIFIED, SOURCE));
        }
        for (Executable constructor : List.<Executable>of(
                () -> new IrNameReference(null, NameForm.UNQUALIFIED, SOURCE),
                () -> new IrNameReference("Name", null, SOURCE),
                () -> new IrNameReference("Name", NameForm.UNQUALIFIED, null))) {
            assertThrows(NullPointerException.class, constructor);
        }
        assertEquals(List.of("value", "form", "source"), fields(IrNameReference.class));
    }

    // 即使前缀不再保存，也校验 AST 标记；namespace 仅允许 ASCII 大小写变化。
    @Test
    void acceptsExactGlobalMarkerAndAsciiNamespaceMarkers() {
        IrNameReference global = constantName(new NodeName.FullyQualified(token("\\"), component("Widget"), NAME));
        assertName("Widget", NameForm.FULLY_QUALIFIED, global);
        for (String marker : List.of("namespace", "NAMESPACE", "NaMeSpAcE")) {
            IrNameReference relative = constantName(new NodeName.Relative(token(marker), component("应用\\Widget"), NAME));
            assertName("应用\\Widget", NameForm.NAMESPACE_RELATIVE, relative);
            assertSource(NAME, relative.source());
        }
    }

    // 前缀缺失、空值、错误拼写及 Unicode 近似字符都定位到所属 NodeName 的 kw，而非 token 来源。
    @Test
    void rejectsMalformedNameMarkersWithContextualKeywordPaths() {
        for (NodeString marker : Arrays.asList(null, token(null), token(""), token("\\\\"), token("/"),
                token("namespace"), token(" \\"), token("\uFF3C"))) {
            assertFailure(new NodeName.FullyQualified(marker, component("Widget"), NAME),
                    CONSTANT_NAME_PATH + ".kw", NAME);
        }
        for (NodeString marker : Arrays.asList(null, token(null), token(""), token("namespace\\"), token("\\"),
                token(" namespace"), token("namespace "), token("name\u017Fpace"), token("name\u017FpaCe"))) {
            assertFailure(new NodeName.Relative(marker, component("Widget"), NAME),
                    CONSTANT_NAME_PATH + ".kw", NAME);
        }
    }

    // 非法主体不能泄露 record 的 IllegalArgumentException，应转为 NodeName 的 n 字段诊断。
    @Test
    void rejectsEmptyNameSegmentsWithContextualNamePaths() {
        for (String value : List.of("\\Widget", "Widget\\", "App\\\\Widget")) {
            for (NameForm form : List.of(NameForm.UNQUALIFIED, NameForm.FULLY_QUALIFIED, NameForm.NAMESPACE_RELATIVE)) {
                NodeNamespaceName body = component(value);
                NodeName syntax = switch (form) {
                    case UNQUALIFIED -> new NodeName.Unqualified(body, NAME);
                    case FULLY_QUALIFIED -> new NodeName.FullyQualified(token("\\"), body, NAME);
                    case NAMESPACE_RELATIVE -> new NodeName.Relative(token("namespace"), body, NAME);
                    default -> throw new AssertionError(form);
                };
                assertFailure(syntax, CONSTANT_NAME_PATH + ".n", NAME);
            }
        }
    }

    // 名称内部 required/text 的错误仍指向具体缺失层级，不被外层规范化吞掉或缩短路径。
    @Test
    void preservesDeepMissingFieldPathsAndComponentSources() {
        assertFailure(new NodeName.Unqualified(null, NAME), CONSTANT_NAME_PATH + ".n", NAME);
        assertFailure(new NodeName.Unqualified(new NodeNamespaceName.Part(null, COMPONENT), NAME),
                CONSTANT_NAME_PATH + ".n.name", COMPONENT);
        assertFailure(new NodeName.Unqualified(new NodeNamespaceName.Part(token(""), COMPONENT), NAME),
                CONSTANT_NAME_PATH + ".n.name", COMPONENT);
        NodeNamespaceName brokenParent = new NodeNamespaceName.Nested(null, token("Middle"), COMPONENT);
        assertFailure(new NodeName.Unqualified(new NodeNamespaceName.Nested(brokenParent, token("Leaf"), REFERENCE), NAME),
                CONSTANT_NAME_PATH + ".n.parent.parent", COMPONENT);
        NodeNamespaceName brokenPart = new NodeNamespaceName.Nested(component("Root"), token(null), COMPONENT);
        assertFailure(new NodeName.Unqualified(new NodeNamespaceName.Nested(brokenPart, token("Leaf"), REFERENCE), NAME),
                CONSTANT_NAME_PATH + ".n.parent.part", COMPONENT);
    }

    // 未知名称变体必须失败，不能只读取公共 getter 后将其当成已知形式。
    @Test
    void rejectsUnknownNameAndNamespaceVariants() {
        NodeName unknownName = new NodeName() {
            @Override public NodeNamespaceName getN() { return component("Widget"); }
            @Override public ComplexLocation getLocation() { return NAME; }
        };
        assertFailure(unknownName, CONSTANT_NAME_PATH, NAME);
        NodeNamespaceName unknownBody = new NodeNamespaceName() {
            @Override public NodeString getName() { return token("Widget"); }
            @Override public ComplexLocation getLocation() { return COMPONENT; }
        };
        assertFailure(new NodeName.Unqualified(unknownBody, NAME), CONSTANT_NAME_PATH + ".n", COMPONENT);
    }

    // 调用与类型中的名称也复用完整上下文路径；失败后再次转换合法表达式不受污染。
    @Test
    void preservesNameFailurePathsInsideCallsAndTypes() {
        NodeName malformed = new NodeName.Relative(token("name\u017Fpace"), component("Widget"), NAME);
        SyntaxConversionException callError = assertThrows(SyntaxConversionException.class, () -> convert(call(malformed)));
        assertEquals("expression.v.cv.call.n.kw", callError.fieldPath());
        assertSource(NAME, callError.source());
        SyntaxConversionException typeError = assertThrows(SyntaxConversionException.class, () -> type(malformed));
        assertEquals("expression.ev.params[0].type.t.n.kw", typeError.fieldPath());
        assertSource(NAME, typeError.source());
        assertName("Widget", NameForm.FULLY_QUALIFIED,
                constantName(name("Widget", NameForm.FULLY_QUALIFIED, NAME)));
    }

    // 名称来源覆盖完整原名称；常量与类引用外壳各有独立范围，不能用子节点范围替代。
    @Test
    void preservesCompleteNameSourceAndIndependentReferenceSources() {
        NodeName syntax = name("App\\Widget", NameForm.FULLY_QUALIFIED, NAME);
        IrConstantReference constant = assertInstanceOf(IrConstantReference.class, convert(constant(syntax)));
        assertSource(REFERENCE, constant.source());
        assertSource(NAME, constant.name().source());
        IrNamedCallTarget target = assertInstanceOf(IrNamedCallTarget.class, assertInstanceOf(IrCall.class,
                convert(call(syntax))).target());
        assertSource(NAME, target.source());
        assertSame(target.name().source(), target.source());
        IrNamedClassReference clazz = assertInstanceOf(IrNamedClassReference.class,
                assertInstanceOf(IrNew.class, convert(creation(syntax))).classReference());
        assertSource(REFERENCE, clazz.source());
        assertSource(NAME, clazz.name().source());
        IrTypeReference type = type(syntax);
        assertSource(REFERENCE, type.source());
        assertSource(NAME, type.type().source());
        assertSource(REFERENCE, convert(constant(name("TRUE", NameForm.FULLY_QUALIFIED, NAME))).source());
    }

    // 内部未知位置不回填入口范围，零宽位置仍是已知位置；source 委托不创建替代对象。
    @Test
    void preservesUnknownAndZeroWidthNameSources() {
        for (ComplexLocation location : Arrays.asList(null, ComplexLocation.NO_LOCATION, ComplexLocation.of(4, 7, 4, 7))) {
            NodeName syntax = name("Widget", NameForm.FULLY_QUALIFIED, location);
            IrConstantReference constant = assertInstanceOf(IrConstantReference.class, convert(constant(syntax)));
            assertSource(location, constant.name().source());
            assertSource(REFERENCE, constant.source());
            IrNamedCallTarget call = assertInstanceOf(IrNamedCallTarget.class,
                    assertInstanceOf(IrCall.class, convert(call(syntax))).target());
            assertSource(location, call.source());
            assertSame(call.name().source(), call.source());
            IrNamedType named = assertInstanceOf(IrNamedType.class, type(syntax).type());
            assertSource(location, named.source());
            assertSame(named.name().source(), named.source());
        }
    }

    // 三个来源完全相同的包装只保存子节点，source 委托必须返回同一对象，而非重建相等值。
    @Test
    void delegatesWrapperSourcesWithoutDuplicateRecordComponents() {
        for (SourceInfo source : List.of(SOURCE, new SourceInfo(null, null),
                new SourceInfo("empty.php", new SourceRange(1, 4, 1, 4)))) {
            IrNameReference name = new IrNameReference("Widget", NameForm.UNQUALIFIED, source);
            IrNamedCallTarget target = new IrNamedCallTarget(name);
            IrIntegerLiteral expression = new IrIntegerLiteral(1, source);
            IrExpressionWriteBase base = new IrExpressionWriteBase(expression);
            IrFixedName variable = new IrFixedName("value", source);
            IrVariableTarget variableTarget = new IrVariableTarget(variable);
            assertSame(name, target.name());
            assertSame(expression, base.expression());
            assertSame(variable, variableTarget.name());
            assertSame(source, target.source());
            assertSame(source, base.source());
            assertSame(source, variableTarget.source());
        }
        assertEquals(List.of("name"), fields(IrNamedCallTarget.class));
        assertEquals(List.of("expression"), fields(IrExpressionWriteBase.class));
        assertEquals(List.of("name"), fields(IrVariableTarget.class));
        assertThrows(NullPointerException.class, () -> new IrNamedCallTarget(null));
        assertThrows(NullPointerException.class, () -> new IrExpressionWriteBase(null));
        assertThrows(NullPointerException.class, () -> new IrVariableTarget(null));
    }

    // 转换器产出的变量目标及调用结果写链同样委托来源，不只验证手工构造的模型。
    @Test
    void delegatesConvertedVariableAndExpressionWriteBaseSources() {
        NodeSimpleVariable simple = new NodeSimpleVariable.NamedVar(token("value"), NAME);
        NodeVariable variable = new NodeVariable.CallableVariable(new NodeCallableVariable.SimpleVar(simple, OUTER), OUTER);
        NodeExpr value = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(token("1"), REFERENCE), OUTER), OUTER);
        NodeExpr assignment = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Assign(variable, value, OUTER), OUTER);
        IrVariableTarget target = assertInstanceOf(IrVariableTarget.class,
                assertInstanceOf(IrAssignment.class, convert(assignment)).target());
        assertSource(NAME, target.source());
        assertSame(target.name().source(), target.source());
        IrPropertyTarget property = assertInstanceOf(IrPropertyTarget.class,
                assertInstanceOf(IrAssignment.class, parsed("factory()->field = 1")).target());
        IrExpressionWriteBase base = assertInstanceOf(IrExpressionWriteBase.class, property.receiver());
        assertInstanceOf(IrCall.class, base.expression());
        assertSame(base.expression().source(), base.source());
    }

    // 文件、语法体与表达式入口输出一致；原 AST、声明层拼写、声明索引及对象身份不受影响。
    @Test
    void keepsEntrypointsConsistentWithoutChangingSyntaxOrDeclarationModels() throws ReflectiveOperationException {
        NodeProgram syntax = program("""
                namespace Demo;
                function sample(?\\App\\Widget $value): namespace\\Result {}
                class Box extends \\Base implements namespace\\Contract {}
                \\Pkg\\run(namespace\\FLAG);
                """);
        String before = syntax.toTreeString(false);
        PhpFile declarations = DeclarationExtractor.extract(syntax, SOURCE.sourceId());
        NamespaceSection section = declarations.namespaceSections().getFirst();
        FunctionDefinition function = assertInstanceOf(FunctionDefinition.class, section.declarations().getFirst());
        ClassLikeDefinition clazz = assertInstanceOf(ClassLikeDefinition.class, section.declarations().get(1));
        IrFile file = SyntaxConverter.convertFile(syntax, SOURCE.sourceId());
        IrBlock converted = file.namespaceSections().getFirst().body();
        assertEquals(converted.statements(), SyntaxConverter.convertBody(section.body()).statements());
        NodeExpr expression = assertInstanceOf(NodeTopStatement.class, section.body().statements().get(2))
                .getStmt().getExpression();
        assertEquals(expression(converted.statements().get(2)), convert(expression));
        assertEquals("\\App\\Widget", function.signature().parameters().getFirst().declaredType().name().spelling());
        assertEquals("namespace\\Result", function.signature().returnType().name().spelling());
        assertEquals("\\Base", clazz.parentTypes().getFirst().spelling());
        assertEquals("namespace\\Contract", clazz.interfaces().getFirst().spelling());
        assertSame(function, declarations.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "Demo\\sample").getFirst());
        assertEquals(before, syntax.toTreeString(false));
        PhpFile repeatedDeclarations = DeclarationExtractor.extract(syntax, SOURCE.sourceId());
        assertEquals(declarations.namespaceSections(), repeatedDeclarations.namespaceSections());
        assertEquals(function, repeatedDeclarations.declarationIndex()
                .findTopLevel(TopLevelKind.FUNCTION, "Demo\\sample").getFirst());
        assertEquals(file, SyntaxConverter.convertFile(syntax, SOURCE.sourceId()));
        assertNoSyntaxOrDeclarationNames(file, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    // 所有曾保存声明层 NameReference 的 IR 字段均迁移，列表泛型也不能留下旧模型。
    @Test
    void excludesDeclarationNameReferencesFromAllNameBearingIrRecords() {
        for (Class<?> record : List.of(IrAnonymousClass.class, IrCatch.class, IrClassDeclaration.class,
                IrConstantReference.class, IrInterfaceDeclaration.class, IrNamedClassReference.class,
                IrNamedCallTarget.class, IrNamedType.class, IrTraitMethodReference.class,
                IrTraitPrecedence.class, IrTraitUse.class)) {
            for (RecordComponent component : record.getRecordComponents()) {
                assertFalse(component.getGenericType().getTypeName().contains("top.kmar.php.model.NameReference"),
                        record.getSimpleName() + "." + component.getName());
            }
        }
    }

    private static NodeProgram program(String code) {
        return assertDoesNotThrow(() -> (NodeProgram) Main.parse("<?php " + code), code);
    }

    private static IrExpression parsed(String code) {
        NodeProgram syntax = program(code + ";");
        return convert(syntax.getStmts().getValue().getFirst().getStmt().getExpression());
    }

    private static IrExpression convert(NodeExpr syntax) {
        return SyntaxConverter.convertExpression(new SyntaxExpression(syntax, SOURCE));
    }

    private static IrExpression expression(IrStatement statement) {
        return assertInstanceOf(IrExpressionStatement.class, statement).expression();
    }

    private static IrNameReference constantName(NodeName syntax) {
        return assertInstanceOf(IrConstantReference.class, convert(constant(syntax))).name();
    }

    private static NodeExpr constant(NodeName name) {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(new NodeScalar.Constant(
                new NodeConstant.NamedConstant(name, REFERENCE), OUTER), OUTER), OUTER);
    }

    private static NodeExpr call(NodeName name) {
        NodeFunctionCall call = new NodeFunctionCall.Call(name,
                new NodeArgumentList.Args(new NodeListNodeArgument(List.of(), OUTER), OUTER), OUTER);
        return new NodeExpr.VariableExpr(new NodeVariable.CallableVariable(
                new NodeCallableVariable.FunctionCall(call, OUTER), OUTER), OUTER);
    }

    private static NodeExpr creation(NodeName name) {
        NodeClassNameReference clazz = new NodeClassNameReference.ClassName(new NodeClassName.NamedClass(name, REFERENCE), OUTER);
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.New(new NodeNewExpr.New(clazz, null, OUTER), OUTER), OUTER);
    }

    private static IrTypeReference type(NodeName name) {
        NodeTypeExpr type = new NodeTypeExpr.Type(new NodeType.NameType(name, OUTER), REFERENCE);
        NodeParameter parameter = new NodeParameter.Param(type, null, null, token("value"), OUTER);
        NodeExpr syntax = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Closure(null,
                new NodeListNodeParameter(List.of(parameter), OUTER), new NodeLexicalVars(), new NodeReturnType(),
                new NodeListNodeInnerStatement(List.of(), OUTER), OUTER), OUTER);
        return assertInstanceOf(IrClosure.class, convert(syntax)).parameters().getFirst().declaredType();
    }

    private static NodeName name(String value, NameForm form, ComplexLocation location) {
        String[] parts = value.split("\\\\", -1);
        NodeNamespaceName component = component(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            component = new NodeNamespaceName.Nested(component, token(parts[i]), COMPONENT);
        }
        return switch (form) {
            case UNQUALIFIED, QUALIFIED -> new NodeName.Unqualified(component, location);
            case FULLY_QUALIFIED -> new NodeName.FullyQualified(token("\\"), component, location);
            case NAMESPACE_RELATIVE -> new NodeName.Relative(token("namespace"), component, location);
        };
    }

    private static NodeNamespaceName component(String value) {
        return new NodeNamespaceName.Part(token(value), COMPONENT);
    }

    private static NodeString token(String value) {
        return new NodeString(value, TOKEN);
    }

    private static void assertFailure(NodeName syntax, String path, ComplexLocation location) {
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class, () -> convert(constant(syntax)));
        assertEquals(path, error.fieldPath());
        assertSource(location, error.source());
        assertFalse(error.reason().isBlank());
    }

    private static void assertClassName(NameCase expected, IrClassReference actual) {
        assertName(expected, assertInstanceOf(IrNamedClassReference.class, actual).name());
    }

    private static void assertName(NameCase expected, IrNameReference actual) {
        assertName(expected.value(), expected.form(), actual);
    }

    private static void assertName(String value, NameForm form, IrNameReference actual) {
        assertNotNull(actual);
        assertEquals(value, actual.value());
        assertEquals(form, actual.form());
    }

    private static void assertNames(List<IrNameReference> names, List<String> values, List<NameForm> forms) {
        assertEquals(values, names.stream().map(IrNameReference::value).toList());
        assertEquals(forms, names.stream().map(IrNameReference::form).toList());
    }

    private static void assertSource(ComplexLocation location, SourceInfo source) {
        assertEquals(SOURCE.sourceId(), source.sourceId());
        if (location == null || location.isNoLocation()) assertNull(source.range());
        else assertEquals(new SourceRange(location.getStartLine(), location.getStartColumn(),
                location.getEndLine(), location.getEndColumn()), source.range());
    }

    private static List<String> fields(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static void assertNoSyntaxOrDeclarationNames(Object value, Set<Object> visited) throws ReflectiveOperationException {
        if (value == null || !visited.add(value)) return;
        assertFalse(value instanceof AstNode, "IR 不应引用 AST");
        assertFalse(value instanceof NameReference, "IR 不应引用声明层 NameReference");
        if (value instanceof List<?> list) {
            for (Object item : list) assertNoSyntaxOrDeclarationNames(item, visited);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertNoSyntaxOrDeclarationNames(component.getAccessor().invoke(value), visited);
            }
        }
    }

    private record NameCase(String spelling, String value, NameForm form) {}
}