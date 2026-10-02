package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.ImportDeclaration;
import top.kmar.php.model.ImportKind;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SyntaxBody;
import top.kmar.php.model.TopLevelKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证导入在 IR 中只保存有效别名，不绑定名称或改变声明提取结果。 */
class ImportConversionTest {

    // 省略别名与显式同名别名具有相同业务字段，但两个导入项仍保留各自来源。
    @Test
    void normalizesImplicitAndExplicitEquivalentAliasesWithoutMergingSources() {
        List<IrImport> imports = imports(statements(parse("""
                use Vendor\\Thing, Vendor\\Thing as Thing;
                """)));
        assertEquals(2, imports.size());
        IrImport implicit = imports.getFirst();
        IrImport explicit = imports.get(1);
        assertEquals(ImportKind.CLASS, implicit.kind());
        assertEquals("Vendor\\Thing", implicit.targetName());
        assertEquals("Thing", implicit.alias());
        assertEquals(implicit.kind(), explicit.kind());
        assertEquals(implicit.targetName(), explicit.targetName());
        assertEquals(implicit.alias(), explicit.alias());
        assertEquals("imports.php", implicit.source().sourceId());
        assertEquals("imports.php", explicit.source().sourceId());
        assertNotNull(implicit.source().range());
        assertNotNull(explicit.source().range());
        assertNotEquals(implicit.source().range(), explicit.source().range());
    }

    // 普通、类型化分组和混合分组均取完整目标的末段作为缺省别名，显式别名及大小写保持原值。
    @Test
    void normalizesAliasesAcrossImportKindsAndGroupForms() {
        List<IrImport> imports = imports(statements(parse("""
                use \\Vendor\\Plain;
                use function \\Fns\\{Sub\\RuN, Other as fAlIaS};
                use const Constants\\{Sub\\VaLuE, Other as cAlIaS};
                use \\Pkg\\{Sub\\Thing, function Helpers\\RuN, const Inner\\MiXeD, Other as aLiAs};
                """)));
        assertEquals(List.of("Vendor\\Plain", "Fns\\Sub\\RuN", "Fns\\Other",
                        "Constants\\Sub\\VaLuE", "Constants\\Other", "Pkg\\Sub\\Thing",
                        "Pkg\\Helpers\\RuN", "Pkg\\Inner\\MiXeD", "Pkg\\Other"),
                imports.stream().map(IrImport::targetName).toList());
        assertEquals(List.of("Plain", "RuN", "fAlIaS", "VaLuE", "cAlIaS", "Thing", "RuN", "MiXeD", "aLiAs"),
                imports.stream().map(IrImport::alias).toList());
        assertEquals(List.of(ImportKind.CLASS, ImportKind.FUNCTION, ImportKind.FUNCTION,
                        ImportKind.CONST, ImportKind.CONST, ImportKind.CLASS,
                        ImportKind.FUNCTION, ImportKind.CONST, ImportKind.CLASS),
                imports.stream().map(IrImport::kind).toList());
    }

    // 整文件和按需正文入口使用同一有效别名规范化，来源及原位语句结构也保持一致。
    @Test
    void sharesNormalizedImportResultsAcrossFileAndBodyEntrypoints() {
        NodeProgram syntax = parse("""
                use A\\Thing, B\\Thing as Other;
                echo 1;
                use P\\{function Sub\\Run, const VALUE as Value};
                """);
        List<IrStatement> fileStatements = statements(syntax);
        IrBlock selected = SyntaxConverter.convertBody(new SyntaxBody(
                List.copyOf(syntax.getStmts().getValue()), new SourceInfo("imports.php", null)));
        assertEquals(fileStatements, selected.statements());
        assertEquals(3, fileStatements.size());
        assertInstanceOf(IrEcho.class, fileStatements.get(1));
        assertEquals(List.of("Thing", "Other", "Run", "Value"),
                imports(selected.statements()).stream().map(IrImport::alias).toList());
    }

    // IR 规范化不改写 AST、第一阶段的显式别名状态或声明索引，导入目标也不会成为声明。
    @Test
    void leavesSyntaxDeclaredAliasesAndDeclarationIndexesUnchanged() {
        NodeProgram syntax = parse("""
                namespace App;
                use Vendor\\Thing, Vendor\\Thing as Thing;
                class Local extends Thing {}
                function run() {}
                """);
        String originalTree = syntax.toTreeString(false);
        var extracted = DeclarationExtractor.extract(syntax, "imports.php");
        var section = extracted.namespaceSections().getFirst();
        List<ImportDeclaration> originalImports = section.imports();
        var local = section.declarations().getFirst();
        var index = extracted.declarationIndex();
        assertNull(originalImports.getFirst().declaredAlias());
        assertEquals("Thing", originalImports.get(1).declaredAlias());

        List<IrImport> normalized = imports(statements(syntax));
        assertEquals(List.of("Thing", "Thing"), normalized.stream().map(IrImport::alias).toList());
        assertSame(syntax, extracted.syntax());
        assertEquals(originalTree, syntax.toTreeString(false));
        assertSame(originalImports, section.imports());
        assertNull(originalImports.getFirst().declaredAlias());
        assertEquals("Thing", originalImports.get(1).declaredAlias());
        assertSame(index, extracted.declarationIndex());
        assertSame(local, index.findTopLevel(TopLevelKind.TYPE, "App\\Local").getFirst());
        assertTrue(index.findTopLevel(TopLevelKind.TYPE, "Vendor\\Thing").isEmpty());
        assertEquals(1, index.findTopLevel(TopLevelKind.FUNCTION, "App\\run").size());
        assertEquals(originalImports,
                DeclarationExtractor.extract(syntax, "imports.php").namespaceSections().getFirst().imports());
    }

    // 导入顺序、重复项和语句间交错不变，后续类名、调用名和常量名不会被替换成导入目标。
    @Test
    void preservesImportOrderDuplicatesAndUnboundReferences() {
        List<IrStatement> statements = statements(parse("""
                namespace Demo;
                use A\\Thing as Shared;
                new Shared;
                use B\\Thing as Shared, B\\Thing as Shared;
                use function Tools\\run as invoke;
                invoke();
                use const Values\\FLAG as Flag;
                echo Flag;
                """));
        assertEquals(7, statements.size());
        List<IrImport> imports = imports(statements);
        assertEquals(List.of("A\\Thing", "B\\Thing", "B\\Thing", "Tools\\run", "Values\\FLAG"),
                imports.stream().map(IrImport::targetName).toList());
        assertEquals(List.of("Shared", "Shared", "Shared", "invoke", "Flag"),
                imports.stream().map(IrImport::alias).toList());
        assertEquals(2, assertInstanceOf(IrUse.class, statements.get(2)).imports().size());
        IrNew created = assertInstanceOf(IrNew.class,
                assertInstanceOf(IrExpressionStatement.class, statements.get(1)).expression());
        IrNameReference className = assertInstanceOf(IrNamedClassReference.class, created.classReference()).name();
        assertEquals("Shared", className.value());
        assertEquals(NameForm.UNQUALIFIED, className.form());
        IrCall call = assertInstanceOf(IrCall.class,
                assertInstanceOf(IrExpressionStatement.class, statements.get(4)).expression());
        IrNameReference functionName = assertInstanceOf(IrNamedCallTarget.class, call.target()).name();
        assertEquals("invoke", functionName.value());
        assertEquals(NameForm.UNQUALIFIED, functionName.form());
        IrConstantReference constant = assertInstanceOf(IrConstantReference.class,
                assertInstanceOf(IrEcho.class, statements.get(6)).expressions().getFirst());
        assertEquals("Flag", constant.name().value());
        assertEquals(NameForm.UNQUALIFIED, constant.name().form());
    }

    private static NodeProgram parse(String code) {
        return assertInstanceOf(NodeProgram.class, Main.parse("<?php\n" + code));
    }

    private static List<IrStatement> statements(NodeProgram syntax) {
        IrFile file = SyntaxConverter.convertFile(syntax, "imports.php");
        assertEquals(1, file.namespaceSections().size());
        return file.namespaceSections().getFirst().body().statements();
    }

    private static List<IrImport> imports(List<IrStatement> statements) {
        return statements.stream().filter(IrUse.class::isInstance).map(IrUse.class::cast)
                .flatMap(use -> use.imports().stream()).toList();
    }
}
