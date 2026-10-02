package top.kmar.php;

import java_cup.runtime.symbol.complex.ComplexLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证标准 declare 指令规范化及未知指令警告后继续转换的行为。 */
@ResourceLock(Resources.SYSTEM_ERR)
class DeclareDirectiveConversionTest {
    private static final ComplexLocation OUTER = ComplexLocation.of(2, 1, 6, 30);
    private static final ComplexLocation ENTRY = ComplexLocation.of(3, 2, 3, 25);
    private static final ComplexLocation TOKEN = ComplexLocation.of(3, 4, 3, 12);
    private static final ComplexLocation VALUE = ComplexLocation.of(3, 20, 3, 24);
    private static final SourceInfo SOURCE = new SourceInfo("declare.php", new SourceRange(99, 1, 99, 9));

    // 三种标准指令按 ASCII 大小写规范化；混合顺序、重复项和值都不能丢失或合并。
    @Test
    void normalizesKnownKindsWithoutChangingDirectiveOrderOrValues() {
        NodeProgram syntax = program("declare(TiCkS=11, ENCODING=22, sTrIcT_tYpEs=33, ticks=44);");
        IrDeclare declaration = convert(syntax.getStmts().getValue().getFirst().getStmt());
        assertEquals(List.of(DeclareDirectiveKind.TICKS, DeclareDirectiveKind.ENCODING,
                        DeclareDirectiveKind.STRICT_TYPES, DeclareDirectiveKind.TICKS),
                declaration.directives().stream().map(IrDeclareDirective::kind).toList());
        assertEquals(List.of(11L, 22L, 33L, 44L), declaration.directives().stream()
                .map(directive -> assertInstanceOf(IrIntegerLiteral.class, directive.value()).value()).toList());
        assertNull(declaration.body());
    }

    // 未知指令和 Unicode 近似拼写均警告并保留，不误识别成标准名称。
    @Test
    void warnsAndPreservesUnknownAndUnicodeLookalikeNames() {
        for (String spelling : List.of("custom", "tick", "ticks ", " ticks", "strict-types",
                "tic\u212As", "tick\u017F", "\u017Ftrict_types", "encod\u0131ng")) {
            NodeStatement.Declare syntax = statement(
                    directive("ticks", ENTRY), directive(spelling, ENTRY));
            assertWarning(() -> {
                IrDeclare declaration = convert(syntax);
                assertEquals(DeclareDirectiveKind.TICKS, declaration.directives().getFirst().kind());
                IrDeclareDirective unknown = declaration.directives().get(1);
                assertEquals(DeclareDirectiveKind.UNKNOWN, unknown.kind());
                assertEquals(spelling, unknown.unknownName());
                assertEquals(1, assertInstanceOf(IrIntegerLiteral.class, unknown.value()).value());
            }, "body.statements[0].directives[1].name", ENTRY, spelling);
        }
    }

    // 整文件、局部正文和闭包表达式复用同一指令分类，闭包中的嵌套声明保留所属语句体。
    @Test
    void convertsKnownDirectivesThroughFileBodyAndNestedClosureEntrypoints() {
        NodeProgram syntax = program("declare(tIcKs=1, encoding=$charset, STRICT_TYPES=false);");
        IrDeclare fromFile = assertInstanceOf(IrDeclare.class,
                SyntaxConverter.convertFile(syntax, SOURCE.sourceId()).namespaceSections().getFirst().body()
                        .statements().getFirst());
        IrDeclare fromBody = convert(syntax.getStmts().getValue().getFirst().getStmt());
        assertEquals(fromFile, fromBody);
        assertEquals(DeclareDirectiveKind.ENCODING, fromBody.directives().get(1).kind());
        assertInstanceOf(IrVariable.class, fromBody.directives().get(1).value());
        assertFalse(assertInstanceOf(IrBooleanLiteral.class, fromBody.directives().get(2).value()).value());

        NodeExpr closureSyntax = expression("function() { declare(EnCoDiNg=$charset); "
                + "return function() { declare(StRiCt_TyPeS=3); }; }");
        IrClosure outer = assertInstanceOf(IrClosure.class,
                SyntaxConverter.convertExpression(new SyntaxExpression(closureSyntax, SOURCE)));
        IrDeclare encoding = assertInstanceOf(IrDeclare.class, outer.body().statements().getFirst());
        assertEquals(DeclareDirectiveKind.ENCODING, encoding.directives().getFirst().kind());
        IrClosure inner = assertInstanceOf(IrClosure.class,
                assertInstanceOf(IrReturn.class, outer.body().statements().get(1)).value());
        IrDeclare strict = assertInstanceOf(IrDeclare.class, inner.body().statements().getFirst());
        assertEquals(DeclareDirectiveKind.STRICT_TYPES, strict.directives().getFirst().kind());
        assertEquals(3, assertInstanceOf(IrIntegerLiteral.class, strict.directives().getFirst().value()).value());
        assertEquals(SOURCE.sourceId(), strict.directives().getFirst().source().sourceId());
        assertNotNull(strict.directives().getFirst().source().range());
    }

    // 三个入口均报告警告并继续转换后续语句，嵌套闭包沿原层级保留指令。
    @Test
    void warnsAndContinuesThroughEveryEntrypoint() {
        NodeProgram syntax = program("declare(ticks=1, custom=2); echo 3;");
        NodeStatement declaration = syntax.getStmts().getValue().getFirst().getStmt();
        ComplexLocation entry = declaration.getDirectives().getValue().get(1).getLocation();
        assertWarning(() -> {
            IrBlock body = SyntaxConverter.convertFile(syntax, SOURCE.sourceId()).namespaceSections().getFirst().body();
            assertEquals(2, body.statements().size());
            IrDeclare converted = assertInstanceOf(IrDeclare.class, body.statements().getFirst());
            assertEquals("custom", converted.directives().get(1).unknownName());
            assertInstanceOf(IrEcho.class, body.statements().get(1));
        }, "program.stmts[0].stmt.directives[1].name", entry, "custom");
        assertWarning(() -> assertEquals(2, convert(declaration).directives().size()),
                "body.statements[0].directives[1].name", entry, "custom");

        NodeExpr closure = assertDoesNotThrow(() -> expression("function() { "
                + "return function() { declare(custom=1); }; }"));
        NodeConstDecl nestedEntry = closure.getEv().getStmts().getValue().getFirst().getStmt().getValue()
                .getEv().getStmts().getValue().getFirst().getStmt().getDirectives().getValue().getFirst();
        assertWarning(() -> {
            IrClosure outer = assertInstanceOf(IrClosure.class,
                    SyntaxConverter.convertExpression(new SyntaxExpression(closure, SOURCE)));
            IrClosure inner = assertInstanceOf(IrClosure.class,
                    assertInstanceOf(IrReturn.class, outer.body().statements().getFirst()).value());
            assertEquals("custom", assertInstanceOf(IrDeclare.class, inner.body().statements().getFirst())
                    .directives().getFirst().unknownName());
        }, "expression.ev.stmts[0].stmt.value.ev.stmts[0].stmt.directives[0].name",
                nestedEntry.getLocation(), "custom");
    }

    // 警告和 IR 来源使用指令项自身位置，未知和零宽位置不从名称 token 回填。
    @Test
    void preservesUnknownAndZeroWidthSourcesForWarnings() {
        for (ComplexLocation location : Arrays.asList(null, ComplexLocation.NO_LOCATION, ComplexLocation.of(4, 7, 4, 7))) {
            assertWarning(() -> {
                var source = convert(statement(directive("custom", location))).directives().getFirst().source();
                assertEquals(SOURCE.sourceId(), source.sourceId());
                if (location == null || location.isNoLocation()) assertNull(source.range());
                else assertEquals(new SourceRange(4, 7, 4, 7), source.range());
            }, "body.statements[0].directives[0].name", location, "custom");
        }
    }

    // 标准指令无需额外名称；未知指令必须提供原名，值仍可为表达式。
    @Test
    void preservesUnknownNamesWithoutAddingValueRestrictions() {
        IrExpression value = new IrVariable(new IrFixedName("value", SOURCE), SOURCE);
        for (DeclareDirectiveKind kind : List.of(DeclareDirectiveKind.TICKS, DeclareDirectiveKind.ENCODING,
                DeclareDirectiveKind.STRICT_TYPES)) {
            IrDeclareDirective directive = new IrDeclareDirective(kind, value, SOURCE);
            assertSame(kind, directive.kind());
            assertSame(value, directive.value());
            assertSame(SOURCE, directive.source());
            assertNull(directive.unknownName());
        }
        IrDeclareDirective unknown = new IrDeclareDirective(DeclareDirectiveKind.UNKNOWN, "Custom", value, SOURCE);
        assertEquals("Custom", unknown.unknownName());
        assertSame(value, unknown.value());
        assertThrows(NullPointerException.class, () -> new IrDeclareDirective(DeclareDirectiveKind.UNKNOWN, value, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> new IrDeclareDirective(DeclareDirectiveKind.UNKNOWN, "", value, SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> new IrDeclareDirective(DeclareDirectiveKind.TICKS, "custom", value, SOURCE));
    }

    // 未知指令警告不修改原始拼写、AST、声明对象或索引，也不影响后续转换。
    @Test
    void preservesOriginalSyntaxAndDeclarationsAfterWarnings() {
        NodeProgram syntax = program("""
                function valid() { declare(TiCkS=$value, EnCoDiNg=1, STRICT_TYPES=false) {} }
                function invalid() { declare(custom=1); }
                """);
        PhpFile file = DeclarationExtractor.extract(syntax, SOURCE.sourceId());
        var declarations = file.namespaceSections().getFirst().declarations();
        FunctionDefinition valid = assertInstanceOf(FunctionDefinition.class, declarations.getFirst());
        FunctionDefinition invalid = assertInstanceOf(FunctionDefinition.class, declarations.get(1));
        String before = syntax.toTreeString(false);
        IrBlock expected = SyntaxConverter.convertBody(valid.body());
        String warnings = captureWarnings(() -> {
            assertDoesNotThrow(() -> SyntaxConverter.convertBody(invalid.body()));
            assertDoesNotThrow(() -> SyntaxConverter.convertFile(syntax, SOURCE.sourceId()));
        });
        assertEquals(2, warnings.lines().count());
        assertEquals(expected, SyntaxConverter.convertBody(valid.body()));
        NodeStatement original = syntax.getStmts().getValue().getFirst().getFunction().getStmts().getValue()
                .getFirst().getStmt();
        assertEquals(List.of("TiCkS", "EnCoDiNg", "STRICT_TYPES"), original.getDirectives().getValue().stream()
                .map(directive -> directive.getName().getValue()).toList());
        assertEquals(before, syntax.toTreeString(false));
        assertSame(valid, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "valid").getFirst());
        assertSame(invalid, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "invalid").getFirst());
        IrDeclare another = assertInstanceOf(IrDeclare.class, SyntaxConverter.convertBody(new SyntaxBody(
                List.of(original), new SourceInfo("other.php", null))).statements().getFirst());
        assertEquals("other.php", another.directives().getFirst().source().sourceId());
        assertEquals(expected, SyntaxConverter.convertBody(valid.body()));
    }

    // 混合及重复未知指令逐项警告，保留顺序和局部主体；标准指令不发出警告。
    @Test
    void preservesMixedRepeatedDirectivesAndBody() {
        String warnings = captureWarnings(() -> {
            IrBlock body = SyntaxConverter.convertFile(program(
                    "declare(custom=1, ticks=2, custom=3, encoding='UTF-8') { echo 4; } echo 5;"))
                    .namespaceSections().getFirst().body();
            IrDeclare declaration = assertInstanceOf(IrDeclare.class, body.statements().getFirst());
            assertEquals(List.of(DeclareDirectiveKind.UNKNOWN, DeclareDirectiveKind.TICKS,
                            DeclareDirectiveKind.UNKNOWN, DeclareDirectiveKind.ENCODING),
                    declaration.directives().stream().map(IrDeclareDirective::kind).toList());
            assertEquals(3, assertInstanceOf(IrIntegerLiteral.class, declaration.directives().get(2).value()).value());
            assertInstanceOf(IrEcho.class, declaration.body().statements().getFirst());
            assertInstanceOf(IrEcho.class, body.statements().get(1));
        });
        assertEquals(2, warnings.lines().count());
        assertTrue(warnings.contains("directives[0].name"));
        assertTrue(warnings.contains("directives[2].name"));
        assertEquals("", captureWarnings(() -> convert(program("declare(ticks=1);")
                .getStmts().getValue().getFirst().getStmt())));
    }

    // 未知名称不再阻断转换，但损坏的值或主体仍按原契约失败。
    @Test
    void doesNotSuppressMalformedValuesOrBodies() {
        NodeConstDecl missingValue = new NodeConstDecl.ConstDecl(new NodeString("custom", TOKEN), null, ENTRY);
        String warnings = captureWarnings(() -> {
            var error = assertThrows(SyntaxConversionException.class, () -> convert(statement(missingValue)));
            assertEquals("body.statements[0].directives[0].value", error.fieldPath());
            var bodyError = assertThrows(SyntaxConversionException.class, () -> convert(
                    new NodeStatement.Declare(new NodeListNodeConstDecl(List.of(directive("custom", ENTRY)), OUTER),
                            null, OUTER)));
            assertEquals("body.statements[0].declareBody", bodyError.fieldPath());
        });
        assertEquals(2, warnings.lines().count());
    }

    private static NodeProgram program(String code) {
        return (NodeProgram) Main.parse("<?php " + code);
    }

    private static NodeExpr expression(String code) {
        return program(code + ";").getStmts().getValue().getFirst().getStmt().getExpression();
    }

    private static IrDeclare convert(NodeStatement statement) {
        return assertInstanceOf(IrDeclare.class, SyntaxConverter.convertBody(new SyntaxBody(List.of(statement), SOURCE))
                .statements().getFirst());
    }

    private static NodeStatement.Declare statement(NodeConstDecl... directives) {
        return new NodeStatement.Declare(new NodeListNodeConstDecl(List.of(directives), OUTER),
                new NodeDeclareStatement.Body(new NodeStatement(), OUTER), OUTER);
    }

    private static NodeConstDecl directive(String name, ComplexLocation location) {
        NodeExpr value = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(new NodeString("1", VALUE), VALUE), VALUE), VALUE);
        return new NodeConstDecl.ConstDecl(new NodeString(name, TOKEN), value, location);
    }

    private static void assertWarning(Executable conversion, String path, ComplexLocation location, String name) {
        String warning = captureWarnings(conversion);
        assertEquals(1, warning.lines().count());
        assertTrue(warning.contains("PHP IR 警告"), warning);
        assertTrue(warning.contains(path), warning);
        assertTrue(warning.contains(SOURCE.sourceId()), warning);
        assertTrue(warning.contains("不支持的 declare 指令: " + name), warning);
        String position = location == null || location.isNoLocation() ? "未知位置"
                : location.getStartLine() + ":" + location.getStartColumn() + "-"
                + location.getEndLine() + ":" + location.getEndColumn();
        assertTrue(warning.contains(position), warning);
    }

    private static String captureWarnings(Executable conversion) {
        PrintStream previous = System.err;
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(stream);
            assertDoesNotThrow(conversion);
        } finally {
            System.setErr(previous);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
