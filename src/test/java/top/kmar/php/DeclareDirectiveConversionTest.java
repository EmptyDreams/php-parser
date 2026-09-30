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

import static org.junit.jupiter.api.Assertions.*;

/** 验证 declare 指令名称的强类型规范化、严格识别及跨入口诊断契约。 */
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

    // 未知指令和 Unicode 近似拼写都拒绝；错误定位到当前条目的 name，而不是名称 token 或语句外壳。
    @Test
    void rejectsUnknownAndUnicodeLookalikeNamesAtTheDirectiveEntry() {
        for (String spelling : List.of("custom", "tick", "ticks ", " ticks", "strict-types",
                "tic\u212As", "tick\u017F", "\u017Ftrict_types", "encod\u0131ng")) {
            NodeStatement.Declare syntax = statement(
                    directive("ticks", ENTRY), directive(spelling, ENTRY));
            assertFailure(() -> convert(syntax), "body.statements[0].directives[1].name", ENTRY);
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

    // 三个入口都不能忽略未知指令，解析成功后应沿原 AST 层级报告完整 name 路径。
    @Test
    void propagatesUnknownDirectiveFailuresThroughEveryEntrypoint() {
        NodeProgram syntax = assertDoesNotThrow(() -> program("declare(ticks=1, custom=2);"));
        NodeStatement declaration = syntax.getStmts().getValue().getFirst().getStmt();
        ComplexLocation entry = declaration.getDirectives().getValue().get(1).getLocation();
        assertFailure(() -> SyntaxConverter.convertFile(syntax, SOURCE.sourceId()),
                "program.stmts[0].stmt.directives[1].name", entry);
        assertFailure(() -> convert(declaration), "body.statements[0].directives[1].name", entry);

        NodeExpr closure = assertDoesNotThrow(() -> expression("function() { "
                + "return function() { declare(custom=1); }; }"));
        NodeConstDecl nestedEntry = closure.getEv().getStmts().getValue().getFirst().getStmt().getValue()
                .getEv().getStmts().getValue().getFirst().getStmt().getDirectives().getValue().getFirst();
        assertFailure(() -> SyntaxConverter.convertExpression(new SyntaxExpression(closure, SOURCE)),
                "expression.ev.stmts[0].stmt.value.ev.stmts[0].stmt.directives[0].name", nestedEntry.getLocation());
    }

    // 新增名称诊断使用条目自身来源；未知和零宽位置都不从已知名称 token 或包装回填。
    @Test
    void preservesUnknownAndZeroWidthSourcesForRejectedNames() {
        for (ComplexLocation location : Arrays.asList(null, ComplexLocation.NO_LOCATION, ComplexLocation.of(4, 7, 4, 7))) {
            assertFailure(() -> convert(statement(directive("custom", location))),
                    "body.statements[0].directives[0].name", location);
        }
    }

    // 指令模型只保存 kind、value 和 source，不重复保存原始名称，也不增加指令值类型限制。
    @Test
    void storesOnlyTypedKindsWithoutAddingValueRestrictions() {
        assertEquals(List.of(DeclareDirectiveKind.TICKS, DeclareDirectiveKind.ENCODING, DeclareDirectiveKind.STRICT_TYPES),
                List.of(DeclareDirectiveKind.values()));
        assertEquals(List.of("kind", "value", "source"), Arrays.stream(IrDeclareDirective.class.getRecordComponents())
                .map(RecordComponent::getName).toList());
        assertEquals(DeclareDirectiveKind.class, IrDeclareDirective.class.getRecordComponents()[0].getType());
        IrExpression value = new IrVariable(new IrFixedName("value", SOURCE), SOURCE);
        for (DeclareDirectiveKind kind : DeclareDirectiveKind.values()) {
            IrDeclareDirective directive = new IrDeclareDirective(kind, value, SOURCE);
            assertSame(kind, directive.kind());
            assertSame(value, directive.value());
            assertSame(SOURCE, directive.source());
        }
    }

    // 未知指令失败后仍能独立转换选定函数；原始拼写、AST、声明对象和索引均保持不变。
    @Test
    void isolatesFailuresAndPreservesOriginalSyntaxAndDeclarations() {
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
        assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(invalid.body()));
        assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertFile(syntax, SOURCE.sourceId()));
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

    private static void assertFailure(Executable conversion, String path, ComplexLocation location) {
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class, conversion);
        assertEquals(path, error.fieldPath());
        assertFalse(error.reason().isBlank());
        assertEquals(SOURCE.sourceId(), error.source().sourceId());
        if (location == null || location.isNoLocation()) assertNull(error.source().range());
        else assertEquals(new SourceRange(location.getStartLine(), location.getStartColumn(),
                location.getEndLine(), location.getEndColumn()), error.source().range());
    }
}