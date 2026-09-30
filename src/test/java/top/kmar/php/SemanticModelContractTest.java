package top.kmar.php;

import java_cup.runtime.AstNode;
import java_cup.runtime.symbol.complex.ComplexLocation;
import org.junit.jupiter.api.Test;
import top.kmar.php.extract.DeclarationExtractionException;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.model.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SemanticModelContractTest {
    // 验证来源标识传递到声明、参数、类型、表达式、方法体及导入，范围直接继承 AST。
    @Test
    void propagatesSourceIdentityAndKnownRanges() {
        NodeProgram ast = (NodeProgram) Main.parse("""
                <?php
                use Vendor\\Thing;
                function f(?Thing $x = null): Thing { return $x; }
                class C { public $p = null; function m() {} }
                """);
        PhpFile file = DeclarationExtractor.extract(ast, "memory:test.php");
        assertSame(ast, file.syntax());
        assertEquals("memory:test.php", file.source().sourceId());
        var section = file.namespaceSections().getFirst();
        assertEquals(file.source().sourceId(), section.source().sourceId());
        assertEquals(file.source().sourceId(), section.body().source().sourceId());
        assertEquals(file.source().sourceId(), section.imports().getFirst().source().sourceId());
        var function = (FunctionDefinition) section.declarations().getFirst();
        var original = ast.getStmts().getValue().get(1).getFunction();
        ComplexLocation location = original.getLocation();
        assertEquals(new SourceRange(location.getStartLine(), location.getStartColumn(),
                location.getEndLine(), location.getEndColumn()), function.source().range());
        var parameter = function.signature().parameters().getFirst();
        TypeReference parameterType = parameter.declaredType();
        SyntaxExpression defaultValue = parameter.defaultValue();
        TypeReference returnType = function.signature().returnType();
        assertNotNull(parameterType);
        assertNotNull(defaultValue);
        assertNotNull(returnType);
        for (SourceInfo source : List.of(function.source(), function.body().source(), parameter.source(),
                parameterType.source(), parameterType.name().source(), defaultValue.source(), returnType.source())) {
            assertEquals(file.source().sourceId(), source.sourceId());
        }
        var type = (ClassLikeDefinition) section.declarations().get(1);
        var property = (PropertyDefinition) type.members().getFirst();
        var method = (MethodDefinition) type.members().get(1);
        SyntaxExpression initialValue = property.initialValue();
        SyntaxBody methodBody = method.body();
        assertNotNull(initialValue);
        assertNotNull(methodBody);
        assertEquals(file.source().sourceId(), initialValue.source().sourceId());
        assertEquals(file.source().sourceId(), methodBody.source().sourceId());
        assertNotEquals(file.source(), DeclarationExtractor.extract(ast, "memory:other.php").source());
    }

    // 验证未知位置与真实零宽范围不同，不因空文件或空范围而提取失败。
    @Test
    void distinguishesUnknownLocationsFromKnownZeroWidthRanges() {
        PhpFile unknown = DeclarationExtractor.extract(Main.parse("<?php "));
        assertNull(unknown.source().sourceId());
        assertNull(unknown.source().range());
        assertEquals(unknown.source(), DeclarationExtractor.extract(Main.parse("<?php "), null).source());
        var location = ComplexLocation.of(3, 7, 3, 7);
        PhpFile zeroWidth = DeclarationExtractor.extract(program(List.of(), location));
        assertNull(zeroWidth.source().sourceId());
        assertEquals(new SourceRange(3, 7, 3, 7), zeroWidth.source().range());
        assertEquals(1, zeroWidth.namespaceSections().size());
    }

    // 验证声明模型直接继承现有 AST 的不完整范围，不猜测遗漏的 function 关键字或花括号。
    @Test
    void keepsTheExistingPartialDeclarationRange() {
        var ast = (NodeProgram) Main.parse("<?php function example() {}");
        var original = ast.getStmts().getValue().getFirst().getFunction();
        var declaration = DeclarationExtractor.extract(ast).namespaceSections().getFirst().declarations().getFirst();
        SourceRange range = declaration.source().range();
        assertNotNull(range);
        assertEquals(original.getLocation().getStartColumn(), range.startColumn());
        assertTrue(range.startColumn() > 7);
    }

    // 验证根节点类型错误、根字段缺失和列表损坏均报告明确的提取异常。
    @Test
    void rejectsInvalidRootsAndMissingStatementLists() {
        for (AstNode ast : new AstNode[]{null, new NodeString("x", ComplexLocation.NO_LOCATION), new NodeProgram()}) {
            var error = assertThrows(DeclarationExtractionException.class, () -> DeclarationExtractor.extract(ast));
            assertTrue(error.fieldPath().startsWith("program"));
            assertFalse(error.reason().isEmpty());
        }
        assertThrows(DeclarationExtractionException.class,
                () -> DeclarationExtractor.extract(program(null, ComplexLocation.NO_LOCATION)));
        var withNull = new ArrayList<NodeTopStatement>();
        withNull.add(null);
        var error = assertThrows(DeclarationExtractionException.class,
                () -> DeclarationExtractor.extract(program(withNull, ComplexLocation.NO_LOCATION)));
        assertEquals("program.stmts[0]", error.fieldPath());
    }

    // 验证缺失函数名称时异常携带来源、已知范围及字段路径，不返回部分成功结果。
    @Test
    void reportsMissingDeclarationFieldsWithSourceAndPath() {
        var location = ComplexLocation.of(2, 4, 2, 9);
        var broken = new NodeFunctionDeclarationStatement() {
            @Override
            public ComplexLocation getLocation() {
                return location;
            }
        };
        var top = new NodeTopStatement.FunctionDecl(broken, location);
        var error = assertThrows(DeclarationExtractionException.class,
                () -> DeclarationExtractor.extract(program(List.of(top), location), "broken.php"));
        assertEquals("function.name", error.fieldPath());
        assertEquals("broken.php", error.source().sourceId());
        assertEquals(new SourceRange(2, 4, 2, 9), error.source().range());
        assertTrue(error.getMessage().contains("broken.php"));
        assertTrue(error.getMessage().contains("2:4"));
    }

    // 可空来源与范围各自独立，异常格式仍明确标出缺失信息。
    @Test
    void formatsUnknownSourceAndRangeIndependently() {
        SourceInfo unknown = new SourceInfo(null, null);
        assertNull(unknown.sourceId());
        assertNull(unknown.range());
        var unknownError = new DeclarationExtractionException(unknown, "function.name", "缺少名称");
        assertEquals("声明提取失败 [未知来源 未知位置] function.name: 缺少名称", unknownError.getMessage());

        var sourceOnly = new DeclarationExtractionException(
                new SourceInfo("known.php", null), "program.stmts", "缺少语句列表");
        assertEquals("声明提取失败 [known.php 未知位置] program.stmts: 缺少语句列表", sourceOnly.getMessage());
        var rangeOnly = new DeclarationExtractionException(
                new SourceInfo(null, new SourceRange(2, 4, 2, 9)), "function.name", "缺少名称");
        assertEquals("声明提取失败 [未知来源 2:4-2:9] function.name: 缺少名称", rangeOnly.getMessage());
    }

    // 验证未知顶层或类型成员不会被当作可执行内容悄悄跳过。
    @Test
    void rejectsUnknownTopLevelAndMemberStructures() {
        assertThrows(DeclarationExtractionException.class, () -> DeclarationExtractor.extract(
                program(List.of(new NodeTopStatement()), ComplexLocation.NO_LOCATION)));
        var ast = (NodeProgram) Main.parse("<?php class C { function f() {} }");
        ast.getStmts().getValue().getFirst().getClazz().getMembers().getValue().set(0, new NodeClassStatement());
        var error = assertThrows(DeclarationExtractionException.class, () -> DeclarationExtractor.extract(ast));
        assertEquals("members", error.fieldPath());
    }

    // 验证解析器接受的嵌套 namespace 不会在提取过程中被错误地展平。
    @Test
    void rejectsNestedNamespaceSections() {
        var ast = Main.parse("<?php namespace Outer { namespace Inner {} }");
        var error = assertThrows(DeclarationExtractionException.class, () -> DeclarationExtractor.extract(ast));
        assertEquals("program.stmts[0].stmts[0]", error.fieldPath());
    }

    // 验证模型及查询候选集合只读，避免调用方修改共享的声明结果。
    @Test
    void exposesReadOnlyModelAndCandidateLists() {
        PhpFile file = DeclarationExtractor.extract(Main.parse("""
                <?php use Vendor\\Thing;
                function f($x) {}
                class C { public $p; function m($x) {} }
                """));
        var section = file.namespaceSections().getFirst();
        var function = (FunctionDefinition) section.declarations().getFirst();
        var type = (ClassLikeDefinition) section.declarations().get(1);
        var property = (PropertyDefinition) type.members().getFirst();
        var method = (MethodDefinition) type.members().get(1);
        List<List<?>> lists = List.of(file.namespaceSections(), section.imports(), section.declarations(),
                section.body().statements(), function.signature().parameters(), type.members(),
                property.declaredModifiers(), method.signature().parameters(),
                file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "f"),
                file.declarationIndex().findMembers(type.id(), MemberKind.METHOD, "m"));
        for (List<?> list : lists) assertThrows(UnsupportedOperationException.class, list::clear);
    }

    // 验证集合是快照而 AST 是共享引用；调用方改动 AST 列表不会改动已提取模型列表。
    @Test
    void snapshotsCollectionsWithoutCloningTheAst() {
        var parsed = (NodeProgram) Main.parse("<?php function f() {}");
        var input = new ArrayList<>(parsed.getStmts().getValue());
        var ast = program(input, parsed.getLocation());
        var file = DeclarationExtractor.extract(ast);
        var section = file.namespaceSections().getFirst();
        AstNode originalStatement = input.getFirst();
        input.clear();
        assertSame(ast, file.syntax());
        assertTrue(((NodeProgram) file.syntax()).getStmts().getValue().isEmpty());
        assertEquals(1, section.body().statements().size());
        assertSame(originalStatement, section.body().statements().getFirst());
        assertEquals(1, section.declarations().size());
        assertEquals(1, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "f").size());

        var statements = new ArrayList<AstNode>();
        statements.add(originalStatement);
        var body = new SyntaxBody(statements, section.source());
        statements.clear();
        assertEquals(1, body.statements().size());
        var params = new ArrayList<ParameterDefinition>();
        var signature = new FunctionSignature("f", params, null, false);
        params.add(new ParameterDefinition("x", null, false, false, null, section.source()));
        assertTrue(signature.parameters().isEmpty());
        assertNull(signature.returnType());
        assertNull(params.getFirst().declaredType());
        assertNull(params.getFirst().defaultValue());
    }

    // 验证多次调用不共享 ID 分配器或声明注册状态。
    @Test
    void keepsExtractionStateLocalToEachCall() {
        AstNode ast = Main.parse("<?php function f() {} class C { function m() {} }");
        var first = DeclarationExtractor.extract(ast);
        var second = DeclarationExtractor.extract(ast);
        assertEquals(first.namespaceSections(), second.namespaceSections());
        assertNotSame(first.declarationIndex(), second.declarationIndex());
    }

    private static NodeProgram program(List<NodeTopStatement> statements, ComplexLocation location) {
        return new NodeProgram() {
            @Override
            public NodeListNodeTopStatement getStmts() {
                return new NodeListNodeTopStatement(statements, location);
            }

            @Override
            public ComplexLocation getLocation() {
                return location;
            }
        };
    }
}
