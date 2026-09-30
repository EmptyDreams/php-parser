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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证布尔和 null 的强类型规范化不改变名称分类、缺省值、来源及 AST 契约。 */
class BooleanAndNullLiteralConversionTest {
    private static final ComplexLocation WRAPPER = ComplexLocation.of(2, 1, 2, 30);
    private static final ComplexLocation CONSTANT = ComplexLocation.of(2, 3, 2, 25);
    private static final ComplexLocation NAME = ComplexLocation.of(2, 5, 2, 20);
    private static final ComplexLocation COMPONENT = ComplexLocation.of(2, 7, 2, 17);
    private static final ComplexLocation TOKEN = ComplexLocation.of(2, 9, 2, 13);
    private static final SourceInfo SOURCE = new SourceInfo("typed-literals.php", new SourceRange(99, 1, 99, 9));

    // ASCII 大小写及全局前缀只影响原始拼写，布尔节点必须保存实际 true/false 值。
    @Test
    void normalizesBooleanValuesAndNullAcrossSupportedSpellings() {
        for (String spelling : List.of("true", "TRUE", "TrUe", "\\TRUE", "\\tRuE")) {
            assertTrue(assertInstanceOf(IrBooleanLiteral.class, expression(spelling)).value(), spelling);
        }
        for (String spelling : List.of("false", "FALSE", "FaLsE", "\\FALSE", "\\fAlSe")) {
            assertFalse(assertInstanceOf(IrBooleanLiteral.class, expression(spelling)).value(), spelling);
        }
        for (String spelling : List.of("null", "NULL", "NuLl", "\\NULL", "\\nUlL")) {
            assertInstanceOf(IrNullLiteral.class, expression(spelling), spelling);
        }
    }

    // 限定名称、namespace 相对名和近似 Unicode 拼写仍是名称引用，不能按末段或宽泛大小写折叠。
    @Test
    void preservesQualifiedRelativeAndSimilarUnicodeConstantNames() {
        Map<String, NameForm> names = Map.ofEntries(
                Map.entry("App\\true", NameForm.QUALIFIED),
                Map.entry("App\\FALSE", NameForm.QUALIFIED),
                Map.entry("App\\null", NameForm.QUALIFIED),
                Map.entry("\\App\\true", NameForm.FULLY_QUALIFIED),
                Map.entry("\\App\\NuLl", NameForm.FULLY_QUALIFIED),
                Map.entry("namespace\\TRUE", NameForm.NAMESPACE_RELATIVE),
                Map.entry("namespace\\false", NameForm.NAMESPACE_RELATIVE),
                Map.entry("namespace\\NULL", NameForm.NAMESPACE_RELATIVE),
                Map.entry("fal\u017Fe", NameForm.UNQUALIFIED),
                Map.entry("FAL\u017FE", NameForm.UNQUALIFIED),
                Map.entry("tru\u0435", NameForm.UNQUALIFIED),
                Map.entry("\\fal\u017Fe", NameForm.FULLY_QUALIFIED));
        names.forEach((spelling, form) -> {
            IrConstantReference reference = assertInstanceOf(IrConstantReference.class, expression(spelling), spelling);
            assertEquals(spelling, reference.name().spelling());
            assertEquals(form, reference.name().form());
        });
    }

    // 同样的拼写处于类常量、函数调用或类名位置时，不应被替换成字面量。
    @Test
    void keepsLiteralLikeNamesInOtherSyntacticRoles() {
        for (String spelling : List.of("true", "FALSE", "NuLl")) {
            IrClassConstantReference constant = assertInstanceOf(IrClassConstantReference.class,
                    expression("Box::" + spelling));
            assertEquals(spelling, constant.constantName());
            assertEquals("Box", assertInstanceOf(IrNamedClassReference.class, constant.classReference()).name().spelling());
        }
        IrClassConstantReference dynamic = assertInstanceOf(IrClassConstantReference.class, expression("$type::TRUE"));
        assertEquals("TRUE", dynamic.constantName());
        assertInstanceOf(IrDynamicClassReference.class, dynamic.classReference());
        IrCall call = assertInstanceOf(IrCall.class, expression("\\TRUE()"));
        assertEquals("\\TRUE", assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
        IrNew creation = assertInstanceOf(IrNew.class, expression("new FALSE()"));
        assertEquals("FALSE", assertInstanceOf(IrNamedClassReference.class, creation.classReference()).name().spelling());
    }

    // 整文件、选定正文和单表达式入口使用同一规范化规则，结果及实际节点来源保持一致。
    @Test
    void producesEquivalentValuesThroughAllThreeConversionEntrypoints() {
        NodeProgram syntax = (NodeProgram) Main.parse("<?php TrUe; \\FaLsE; NuLl;");
        IrBlock fileBody = SyntaxConverter.convertFile(syntax, SOURCE.sourceId()).namespaceSections().getFirst().body();
        IrBlock selectedBody = SyntaxConverter.convertBody(new SyntaxBody(new ArrayList<>(
            syntax.getStmts().getValue()), SOURCE));
        for (int i = 0; i < syntax.getStmts().getValue().size(); i++) {
            NodeExpr node = syntax.getStmts().getValue().get(i).getStmt().getExpression();
            IrExpression direct = convert(node);
            assertEquals(direct, statementExpression(fileBody, i));
            assertEquals(direct, statementExpression(selectedBody, i));
            assertEquals(SOURCE.sourceId(), direct.source().sourceId());
        }
        assertTrue(assertInstanceOf(IrBooleanLiteral.class, statementExpression(fileBody, 0)).value());
        assertFalse(assertInstanceOf(IrBooleanLiteral.class, statementExpression(fileBody, 1)).value());
        assertInstanceOf(IrNullLiteral.class, statementExpression(fileBody, 2));
        assertNull(statementExpression(SyntaxConverter.convertFile(syntax).namespaceSections().getFirst().body(), 2)
                .source().sourceId());
    }

    // Java null 继续表示省略；显式 PHP null 在默认值、return、yield、exit 和写下标中始终是独立节点。
    @Test
    void distinguishesMissingValuesFromExplicitNullLiterals() {
        IrFile file = SyntaxConverter.convertFile(Main.parse("""
                <?php
                function sample($absent, $explicit = null, $flag = false) {
                    return;
                    return null;
                    yield;
                    yield null;
                    exit;
                    exit(null);
                    $items[] = false;
                    $items[null] = true;
                }
                """), SOURCE.sourceId());
        IrFunctionDeclaration function = assertInstanceOf(IrFunctionDeclaration.class,
                file.namespaceSections().getFirst().body().statements().getFirst());
        assertNull(function.parameters().getFirst().defaultValue());
        assertInstanceOf(IrNullLiteral.class, function.parameters().get(1).defaultValue());
        assertFalse(assertInstanceOf(IrBooleanLiteral.class, function.parameters().get(2).defaultValue()).value());
        IrBlock body = function.body();
        assertNull(assertInstanceOf(IrReturn.class, body.statements().getFirst()).value());
        assertInstanceOf(IrNullLiteral.class, assertInstanceOf(IrReturn.class, body.statements().get(1)).value());
        assertNull(assertInstanceOf(IrYield.class, statementExpression(body, 2)).value());
        assertInstanceOf(IrNullLiteral.class, assertInstanceOf(IrYield.class, statementExpression(body, 3)).value());
        assertNull(assertInstanceOf(IrExit.class, statementExpression(body, 4)).expression());
        assertInstanceOf(IrNullLiteral.class, assertInstanceOf(IrExit.class, statementExpression(body, 5)).expression());
        IrAssignment append = assertInstanceOf(IrAssignment.class, statementExpression(body, 6));
        assertNull(assertInstanceOf(IrIndexTarget.class, append.target()).index());
        assertFalse(assertInstanceOf(IrBooleanLiteral.class, append.value()).value());
        IrAssignment indexed = assertInstanceOf(IrAssignment.class, statementExpression(body, 7));
        assertInstanceOf(IrNullLiteral.class, assertInstanceOf(IrIndexTarget.class, indexed.target()).index());
        assertTrue(assertInstanceOf(IrBooleanLiteral.class, indexed.value()).value());
    }

    // 字面量来源使用 NodeConstant，自身位置不能被表达式、名称包装或末端 token 覆盖。
    @Test
    void preservesTheConstantNodeSourceInsteadOfWrapperOrTokenSources() {
        for (String spelling : List.of("TrUe", "FaLsE", "NuLl")) {
            NodeName name = new NodeName.FullyQualified(new NodeString("\\", TOKEN), component(spelling), NAME);
            IrExpression literal = convert(wrap(new NodeConstant.NamedConstant(name, CONSTANT)));
            assertSource(CONSTANT, literal.source());
            assertTrue(literal instanceof IrBooleanLiteral || literal instanceof IrNullLiteral);
        }
    }

    // 未知范围不能借用包装范围补齐，真实零宽范围则必须保留。
    @Test
    void preservesUnknownAndZeroWidthLiteralSources() {
        for (ComplexLocation location : Arrays.asList(null, ComplexLocation.NO_LOCATION, ComplexLocation.of(3, 7, 3, 7))) {
            for (String spelling : List.of("true", "false", "null")) {
                IrExpression literal = convert(wrap(new NodeConstant.NamedConstant(name(spelling), location)));
                assertSource(location, literal.source());
            }
        }
    }

    // 两种模型只保存类型化值及来源，来源不可缺失；null 节点仍保留每次出现的独立位置。
    @Test
    void requiresNonNullSourcesAndStoresOnlyTypedLiteralData() {
        assertThrows(NullPointerException.class, () -> new IrBooleanLiteral(true, null));
        assertThrows(NullPointerException.class, () -> new IrNullLiteral(null));
        IrBooleanLiteral positive = new IrBooleanLiteral(true, SOURCE);
        IrBooleanLiteral negative = new IrBooleanLiteral(false, SOURCE);
        assertTrue(positive.value());
        assertFalse(negative.value());
        assertSame(SOURCE, positive.source());
        assertNotEquals(positive, negative);
        IrNullLiteral literal = new IrNullLiteral(SOURCE);
        assertSame(SOURCE, literal.source());
        assertNotEquals(literal, new IrNullLiteral(new SourceInfo("other.php", null)));
        assertEquals(List.of("value", "source"), Arrays.stream(IrBooleanLiteral.class.getRecordComponents())
                .map(RecordComponent::getName).toList());
        assertEquals(boolean.class, IrBooleanLiteral.class.getRecordComponents()[0].getType());
        assertEquals(List.of("source"), Arrays.stream(IrNullLiteral.class.getRecordComponents())
                .map(RecordComponent::getName).toList());
    }

    // IR 不再保存拼写，但原 AST 和第一阶段声明视图不变，大小写与全局前缀仍可从原语法读取。
    @Test
    void leavesOriginalSpellingsAndDeclarationModelsUnchanged() {
        AstNode syntax = Main.parse("<?php const FLAG = TrUe, EMPTY_VALUE = \\NuLl; function sample($value = fAlSe) { return \\TRUE; }");
        PhpFile declarations = DeclarationExtractor.extract(syntax, SOURCE.sourceId());
        List<TopLevelDeclaration> items = declarations.namespaceSections().getFirst().declarations();
        ConstantDefinition flag = assertInstanceOf(ConstantDefinition.class, items.getFirst());
        ConstantDefinition empty = assertInstanceOf(ConstantDefinition.class, items.get(1));
        FunctionDefinition function = assertInstanceOf(FunctionDefinition.class, items.get(2));
        String before = syntax.toTreeString(false);
        IrFile first = SyntaxConverter.convertFile(syntax, SOURCE.sourceId());
        assertEquals(first, SyntaxConverter.convertFile(syntax, SOURCE.sourceId()));
        assertTrue(assertInstanceOf(IrBooleanLiteral.class, SyntaxConverter.convertExpression(flag.value())).value());
        assertInstanceOf(IrNullLiteral.class, SyntaxConverter.convertExpression(empty.value()));
        assertFalse(assertInstanceOf(IrBooleanLiteral.class,
                SyntaxConverter.convertExpression(function.signature().parameters().getFirst().defaultValue())).value());
        assertEquals("TrUe", constantName(flag.value()).getN().getName().getValue());
        assertEquals("NuLl", constantName(empty.value()).getN().getName().getValue());
        assertEquals("\\", constantName(empty.value()).getKw().getValue());
        assertEquals("fAlSe", constantName(function.signature().parameters().getFirst().defaultValue()).getN().getName().getValue());
        assertEquals(before, syntax.toTreeString(false));
        assertSame(syntax, declarations.syntax());
        assertSame(function, declarations.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "sample").getFirst());
    }

    // 损坏或未知常量包装仍产生带路径的转换异常，不因规范化而变成 false、null 或普通引用。
    @Test
    void rejectsMalformedConstantNamesWithContextualDiagnostics() {
        assertFailure(new NodeConstant.NamedConstant(null, CONSTANT), ".n", CONSTANT);
        assertFailure(new NodeConstant.NamedConstant(new NodeName.Unqualified(null, NAME), CONSTANT), ".n.n", NAME);
        for (NodeString token : Arrays.asList(null, new NodeString(null, TOKEN), new NodeString("", TOKEN))) {
            NodeName name = new NodeName.Unqualified(new NodeNamespaceName.Part(token, COMPONENT), NAME);
            assertFailure(new NodeConstant.NamedConstant(name, CONSTANT), ".n.n.name", COMPONENT);
        }
        assertFailure(new NodeConstant.NamedConstant(new NodeName.FullyQualified(null, component("true"), NAME), CONSTANT),
                ".n.kw", NAME);
        assertFailure(new NodeConstant() {
            @Override public NodeName getN() { return name("true"); }
            @Override public ComplexLocation getLocation() { return CONSTANT; }
        }, "", CONSTANT);
        assertFailure(new NodeConstant.NamedConstant(new NodeName() {
            @Override public NodeNamespaceName getN() { return component("null"); }
            @Override public ComplexLocation getLocation() { return NAME; }
        }, CONSTANT), ".n", NAME);
        assertFailure(new NodeConstant.NamedConstant(new NodeName.Unqualified(new NodeNamespaceName() {
            @Override public NodeString getName() { return new NodeString("false", TOKEN); }
            @Override public ComplexLocation getLocation() { return COMPONENT; }
        }, NAME), CONSTANT), ".n.n", COMPONENT);
        assertFalse(assertInstanceOf(IrBooleanLiteral.class, expression("\\FALSE")).value());
        assertInstanceOf(IrNullLiteral.class, expression("null"));
    }

    private static IrExpression expression(String code) {
        NodeProgram program = (NodeProgram) Main.parse("<?php " + code + ";");
        return convert(program.getStmts().getValue().getFirst().getStmt().getExpression());
    }

    private static IrExpression convert(NodeExpr expression) {
        return SyntaxConverter.convertExpression(new SyntaxExpression(expression, SOURCE));
    }

    private static IrExpression statementExpression(IrBlock body, int index) {
        return assertInstanceOf(IrExpressionStatement.class, body.statements().get(index)).expression();
    }

    private static NodeNamespaceName component(String spelling) {
        return new NodeNamespaceName.Part(new NodeString(spelling, TOKEN), COMPONENT);
    }

    private static NodeName name(String spelling) {
        return new NodeName.Unqualified(component(spelling), NAME);
    }

    private static NodeExpr wrap(NodeConstant constant) {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Constant(constant, WRAPPER), WRAPPER), WRAPPER);
    }

    private static NodeName constantName(SyntaxExpression expression) {
        return assertInstanceOf(NodeExpr.class, expression.syntax()).getEv().getScalar().getC().getN();
    }

    private static void assertFailure(NodeConstant constant, String suffix, ComplexLocation location) {
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class, () -> convert(wrap(constant)));
        assertEquals("expression.ev.scalar.c" + suffix, error.fieldPath());
        assertFalse(error.reason().isBlank());
        assertSource(location, error.source());
    }

    private static void assertSource(ComplexLocation location, SourceInfo source) {
        assertEquals(SOURCE.sourceId(), source.sourceId());
        if (location == null || location.isNoLocation()) assertNull(source.range());
        else assertEquals(new SourceRange(location.getStartLine(), location.getStartColumn(),
                location.getEndLine(), location.getEndColumn()), source.range());
    }
}