package top.kmar.php;

import java_cup.runtime.AstNode;
import java_cup.runtime.symbol.complex.ComplexLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import top.kmar.php.extract.DeclarationExtractionException;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.*;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证整文件入口、共享区段与导入读取、来源、不可变性和 AST 隔离契约。 */
class FileConversionContractTest {
    private static final ComplexLocation FILE = ComplexLocation.of(1, 1, 20, 20);
    private static final ComplexLocation NAMESPACE = ComplexLocation.of(2, 2, 18, 18);
    private static final ComplexLocation BODY = ComplexLocation.of(3, 3, 17, 17);
    private static final ComplexLocation GROUP = ComplexLocation.of(4, 4, 16, 16);
    private static final ComplexLocation ITEM = ComplexLocation.of(5, 5, 15, 15);
    private static final ComplexLocation LEAF = ComplexLocation.of(6, 6, 7, 7);
    private static final SourceInfo SOURCE = new SourceInfo("file-contract.php", null);

    // null、错误根及损坏根列表统一报告语法转换异常，不泄漏提取异常或普通空指针异常。
    @Test
    void rejectsInvalidRootsAndMalformedProgramLists() {
        for (AstNode root : new AstNode[]{null, token("not-program"), new NodeStatement()}) {
            assertFileFailure(root, "program");
        }
        assertFileFailure(new NodeProgram(), "program.stmts");
        assertFileFailure(program(null, FILE), "program.stmts");
        assertFileFailure(program(new NodeListNodeTopStatement(null, BODY), FILE), "program.stmts");
        assertFileFailure(program(new NodeListNodeTopStatement(Arrays.asList(constants(constant()), null), BODY), FILE),
                "program.stmts[1]");
        assertFileFailure(program(List.of(new NodeTopStatement())), "program.stmts[0]");
        assertFileFailure(program(List.of(new NodeTopStatement() {
            @Override public NodeStatement getStmt() { return new NodeStatement.Return(null, ITEM); }
        })), "program.stmts[0]");
    }

    // namespace 名称、块列表及其元素均完整；显式空块与包装缺失不能混淆。
    @Test
    void rejectsMissingNamespaceFieldsAndMalformedBodies() {
        assertFileFailure(program(List.of(new NodeTopStatement.Namespace(null, NAMESPACE))), "program.stmts[0].n");
        assertFileFailure(program(List.of(new NodeTopStatement.NamespaceBlock(null, topStatements(), NAMESPACE))),
                "program.stmts[0].n");
        assertFileFailure(program(List.of(new NodeTopStatement.NamespaceBlock(namespace("App"), null, NAMESPACE))),
                "program.stmts[0].stmts");
        assertFileFailure(program(List.of(new NodeTopStatement.GlobalNamespaceBlock(null, NAMESPACE))),
                "program.stmts[0].stmts");
        for (NodeListNodeTopStatement list : List.of(new NodeListNodeTopStatement(null, BODY),
                new NodeListNodeTopStatement(Arrays.asList(constants(constant()), null), BODY))) {
            String suffix = list.getValue() == null ? ".stmts" : ".stmts[1]";
            assertFileFailure(program(List.of(new NodeTopStatement.NamespaceBlock(namespace("App"), list, NAMESPACE))),
                    "program.stmts[0]" + suffix);
            assertFileFailure(program(List.of(new NodeTopStatement.GlobalNamespaceBlock(list, NAMESPACE))),
                    "program.stmts[0]" + suffix);
        }
        assertFileFailure(program(List.of(new NodeTopStatement.Namespace(new NodeNamespaceName.Part(null, LEAF), NAMESPACE))),
                "program.stmts[0].n.name");
        assertFileFailure(program(List.of(new NodeTopStatement.Namespace(new NodeNamespaceName() {
            @Override public NodeString getName() { return token("App"); }
        }, NAMESPACE))), "program.stmts[0].n");
    }

    // 嵌套 namespace 不能被展平，错误路径精确保留根列表及块内列表的原始下标。
    @Test
    void rejectsNestedNamespacesWithOriginalPathsInBothPipelines() {
        for (NodeTopStatement nested : List.of(new NodeTopStatement.Namespace(namespace("Inner"), ITEM),
                new NodeTopStatement.NamespaceBlock(namespace("Inner"), topStatements(), ITEM),
                new NodeTopStatement.GlobalNamespaceBlock(topStatements(), ITEM))) {
            NodeListNodeTopStatement children = topStatements(constants(constant()), nested);
            for (NodeTopStatement outer : List.of(new NodeTopStatement.NamespaceBlock(namespace("Outer"), children, NAMESPACE),
                    new NodeTopStatement.GlobalNamespaceBlock(children, NAMESPACE))) {
                NodeProgram root = program(List.of(constants(constant()), outer));
                String path = "program.stmts[1].stmts[1]";
                SyntaxConversionException conversion = assertThrows(SyntaxConversionException.class, () -> convert(root));
                DeclarationExtractionException extraction = assertThrows(DeclarationExtractionException.class,
                        () -> DeclarationExtractor.extract(root, SOURCE.sourceId()));
                assertEquals(path, conversion.fieldPath());
                assertEquals(path, extraction.fieldPath());
                assertEquals(conversion.source(), extraction.source());
                assertEquals(range(ITEM), conversion.source().range());
            }
        }
    }

    // 各种 use 外壳都需要实际内容和类型包装；共享读取器使用各入口自己的异常类型。
    @Test
    void rejectsMissingImportWrappersInBothPipelines() {
        assertImportFailure(new NodeTopStatement.Use(null, GROUP), ".uses");
        assertImportFailure(new NodeTopStatement.UseTyped(null, uses(use()), GROUP), ".type");
        assertImportFailure(new NodeTopStatement.UseTyped(functionType(), null, GROUP), ".uses");
        assertImportFailure(new NodeTopStatement.UseGroup(null, group(elements(element())), GROUP), ".type");
        assertImportFailure(new NodeTopStatement.UseGroup(functionType(), null, GROUP), ".use");
        assertImportFailure(new NodeTopStatement.UseMixedGroup(null, GROUP), ".mixedUse");
        assertImportFailure(new NodeTopStatement.UseGroup(functionType(),
                new NodeGroupUseDeclaration.GroupUse(null, elements(element()), ITEM), GROUP), ".use.prefix");
        assertImportFailure(new NodeTopStatement.UseMixedGroup(new NodeMixedGroupUseDeclaration.MixedGroupUse(null,
                inlineUses(new NodeInlineUseDeclaration.InlineUse(element(), ITEM)), ITEM), GROUP), ".mixedUse.prefix");
    }

    // 普通导入列表不能空或含 null，单项名称、显式别名及绝对名标记均严格校验。
    @Test
    void rejectsMalformedOrdinaryImportListsAndItems() {
        for (NodeListNodeUseDeclaration list : List.of(new NodeListNodeUseDeclaration(null, GROUP),
                uses(), new NodeListNodeUseDeclaration(Arrays.asList(use(), null), GROUP))) {
            String suffix = list.getValue() != null && list.getValue().size() == 2 ? ".uses[1]" : ".uses";
            assertImportFailure(new NodeTopStatement.Use(list, GROUP), suffix);
            assertImportFailure(new NodeTopStatement.UseTyped(functionType(), list, GROUP), suffix);
        }
        assertImportFailure(ordinary(new NodeUseDeclaration.UseDecl(null, ITEM)), ".uses[0].use");
        assertImportFailure(ordinary(new NodeUseDeclaration() {
            @Override public NodeUnprefixedUseDeclaration getUse() { return element(); }
        }), ".uses[0]");
        assertImportFailure(ordinary(new NodeUseDeclaration.UseDecl(new NodeUnprefixedUseDeclaration() {
            @Override public NodeNamespaceName getN() { return namespace("Thing"); }
        }, ITEM)), ".uses[0].use");
        assertImportFailure(ordinary(new NodeUseDeclaration.UseDecl(new NodeUnprefixedUseDeclaration.UseElem(null, LEAF), ITEM)),
                ".uses[0].use.n");
        for (NodeString marker : new NodeString[]{null, token(null), token(""), token("/"), token("\\\\")}) {
            assertImportFailure(ordinary(new NodeUseDeclaration.UseDeclAbsolute(marker, element(), ITEM)), ".uses[0].kw");
        }
        for (NodeString alias : new NodeString[]{null, token(null), token("")}) {
            assertImportFailure(ordinary(new NodeUseDeclaration.UseDecl(new NodeUnprefixedUseDeclaration.UseElemAs(
                    namespace("Thing"), alias, LEAF), ITEM)), ".uses[0].use.alias");
        }
    }

    // 推导别名为空时仅 IR 拒绝，诊断指向导入元素的名称，不能借用外层 use 包装来源。
    @Test
    void rejectsEmptyInferredAliasesWithImportElementOrigins() {
        NodeUnprefixedUseDeclaration broken = new NodeUnprefixedUseDeclaration.UseElem(namespace("Bad\\"), LEAF);
        record InvalidImport(NodeTopStatement statement, String suffix, String targetName) {}
        for (InvalidImport invalid : List.of(
                new InvalidImport(ordinary(new NodeUseDeclaration.UseDecl(broken, ITEM)),
                        ".uses[0].use.n", "Bad\\"),
                new InvalidImport(new NodeTopStatement.UseGroup(functionType(), group(elements(broken)), GROUP),
                        ".use.uses[0].n", "Vendor\\Bad\\"))) {
            NodeProgram root = program(List.of(invalid.statement()));
            SyntaxConversionException fileError = assertThrows(SyntaxConversionException.class, () -> convert(root));
            assertEquals("program.stmts[0]" + invalid.suffix(), fileError.fieldPath());
            assertSource(LEAF, fileError.source());
            assertDiagnostic(fileError);
            SyntaxConversionException bodyError = assertThrows(SyntaxConversionException.class,
                    () -> convertBody(invalid.statement()));
            assertEquals("body.statements[0]" + invalid.suffix(), bodyError.fieldPath());
            assertSource(LEAF, bodyError.source());
            assertDiagnostic(bodyError);

            ImportDeclaration extracted = assertDoesNotThrow(() -> DeclarationExtractor.extract(root, SOURCE.sourceId()))
                    .namespaceSections().getFirst().imports().getFirst();
            assertEquals(invalid.targetName(), extracted.targetName());
            assertNull(extracted.declaredAlias());
            assertEquals("", extracted.alias());
        }
    }

    // function/const 类型不能仅凭关键字非空判定，未知变体及与类型不匹配的标记都失败。
    @Test
    void rejectsMalformedImportTypeMarkers() {
        for (NodeUseType type : List.of(new NodeUseType(), new NodeUseType() {
            @Override public NodeString getKw() { return token("function"); }
        })) {
            assertImportFailure(new NodeTopStatement.UseTyped(type, uses(use()), GROUP), ".type");
        }
        for (NodeString marker : new NodeString[]{null, token(null), token(""), token("class"), token("function ")}) {
            assertImportFailure(new NodeTopStatement.UseTyped(new NodeUseType.UseFunction(marker, ITEM), uses(use()), GROUP), ".type.kw");
        }
        for (NodeString marker : new NodeString[]{null, token(null), token(""), token("function"), token(" const")}) {
            assertImportFailure(new NodeTopStatement.UseTyped(new NodeUseType.UseConst(marker, ITEM), uses(use()), GROUP), ".type.kw");
        }
        assertImportFailure(new NodeTopStatement.UseTyped(new NodeUseType.UseFunction(token("const"), ITEM), uses(use()), GROUP), ".type.kw");
    }

    // 两种分组导入列表要求非空，混合组中的每一项分别验证类型和内部导入包装。
    @Test
    void rejectsMalformedGroupedAndMixedImports() {
        assertImportFailure(new NodeTopStatement.UseGroup(functionType(), new NodeGroupUseDeclaration() {
            @Override public NodeNamespaceName getPrefix() { return namespace("Vendor"); }
            @Override public NodeListNodeUnprefixedUseDeclaration getUses() { return elements(element()); }
        }, GROUP), ".use");
        assertImportFailure(new NodeTopStatement.UseMixedGroup(new NodeMixedGroupUseDeclaration() {
            @Override public NodeNamespaceName getPrefix() { return namespace("Vendor"); }
            @Override public NodeListNodeInlineUseDeclaration getUses() { return inlineUses(); }
        }, GROUP), ".mixedUse");
        assertImportFailure(new NodeTopStatement.UseGroup(functionType(), group(null), GROUP), ".use.uses");
        assertImportFailure(mixed(null), ".mixedUse.uses");
        for (NodeListNodeUnprefixedUseDeclaration list : List.of(new NodeListNodeUnprefixedUseDeclaration(null, GROUP),
                elements(), new NodeListNodeUnprefixedUseDeclaration(Arrays.asList(element(), null), GROUP))) {
            String suffix = list.getValue() != null && list.getValue().size() == 2 ? ".use.uses[1]" : ".use.uses";
            assertImportFailure(new NodeTopStatement.UseGroup(functionType(), group(list), GROUP), suffix);
        }
        for (NodeListNodeInlineUseDeclaration list : List.of(new NodeListNodeInlineUseDeclaration(null, GROUP),
                inlineUses(), new NodeListNodeInlineUseDeclaration(Arrays.asList(
                        new NodeInlineUseDeclaration.InlineUse(element(), ITEM), null), GROUP))) {
            String suffix = list.getValue() != null && list.getValue().size() == 2 ? ".mixedUse.uses[1]" : ".mixedUse.uses";
            assertImportFailure(mixed(list), suffix);
        }
        assertImportFailure(mixed(inlineUses(new NodeInlineUseDeclaration() {
            @Override public NodeUnprefixedUseDeclaration getUse() { return element(); }
        })), ".mixedUse.uses[0]");
        assertImportFailure(mixed(inlineUses(new NodeInlineUseDeclaration.InlineUse(null, ITEM))), ".mixedUse.uses[0].use");
        assertImportFailure(mixed(inlineUses(new NodeInlineUseDeclaration.InlineUseTyped(null, element(), ITEM))),
                ".mixedUse.uses[0].type");
        assertImportFailure(mixed(inlineUses(new NodeInlineUseDeclaration.InlineUseTyped(functionType(), null, ITEM))),
                ".mixedUse.uses[0].use");
        assertImportFailure(mixed(inlineUses(new NodeInlineUseDeclaration.InlineUseTyped(
                new NodeUseType.UseConst(token("function"), ITEM), element(), ITEM))), ".mixedUse.uses[0].type.kw");
    }

    // 顶层 const 至少有一项，不能忽略未知常量变体或缺少名字、初值的项。
    @Test
    void rejectsMalformedTopLevelConstantGroups() {
        assertTopFailure(new NodeTopStatement.Const(null, GROUP), ".consts");
        assertTopFailure(new NodeTopStatement.Const(new NodeListNodeConstDecl(null, GROUP), GROUP), ".consts");
        assertTopFailure(constants(), ".consts");
        assertTopFailure(new NodeTopStatement.Const(new NodeListNodeConstDecl(Arrays.asList(constant(), null), GROUP), GROUP), ".consts[1]");
        assertTopFailure(constants(new NodeConstDecl() {
            @Override public NodeString getName() { return token("VALUE"); }
            @Override public NodeExpr getValue() { return integer(LEAF); }
        }), ".consts[0]");
        for (NodeString name : new NodeString[]{null, token(null), token("")}) {
            assertTopFailure(constants(new NodeConstDecl.ConstDecl(name, integer(LEAF), ITEM)), ".consts[0].name");
        }
        assertTopFailure(constants(new NodeConstDecl.ConstDecl(token("VALUE"), null, ITEM)), ".consts[0].value");
    }

    // halt 必须包含完整关键字、括号和分号；只接受当前词法规则允许的空白。
    @Test
    void rejectsMalformedHaltMarkersWithoutGuessingPayload() {
        for (NodeString marker : new NodeString[]{null, token(null), token("")}) {
            assertTopFailure(new NodeTopStatement.HaltCompiler(marker, GROUP), ".halt");
        }
        for (String marker : List.of("halt_compiler();", "__halt_compiler", "__halt_compiler;", "__halt_compiler(1);",
                "__halt_compiler(;) ", "__halt_compiler()", "__halt_compiler();payload", " __halt_compiler();",
                "__halt_compiler(); ", "__halt_compiler\f();", "__halt_compiler\u00a0();", "__halt_compiler/**/();")) {
            assertTopFailure(new NodeTopStatement.HaltCompiler(token(marker), GROUP), ".halt");
        }
        for (String marker : List.of("__halt_compiler();", "__HaLt_CoMpIlEr \r\n(\t) \n;")) {
            IrStatement result = convertBody(new NodeTopStatement.HaltCompiler(token(marker), GROUP)).statements().getFirst();
            assertInstanceOf(IrHaltCompiler.class, result);
        }
    }

    // const 展开和区段分割不改变异常的原始 AST 下标，局部 body 使用自身路径前缀。
    @Test
    void preservesOriginalItemPathsAfterConstantExpansion() {
        NodeTopStatement good = constants(constant(), new NodeConstDecl.ConstDecl(token("SECOND"), integer(LEAF), ITEM));
        NodeTopStatement broken = constants(constant(), new NodeConstDecl.ConstDecl(token("BROKEN"), null, ITEM));
        NodeTopStatement namespace = new NodeTopStatement.NamespaceBlock(namespace("App"), topStatements(good, ordinary(use()), broken), NAMESPACE);
        assertFileFailure(program(List.of(good, namespace)), "program.stmts[1].stmts[2].consts[1].value");
        SyntaxBody body = new SyntaxBody(List.of(good, ordinary(use()), broken), SOURCE);
        var error = assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertBody(body));
        assertEquals("body.statements[2].consts[1].value", error.fieldPath());
        assertEquals(range(ITEM), error.source().range());
    }

    // 文件、区段、区段体、use 及单项声明各保留自己的来源，展开不会套用父节点范围。
    @Test
    void preservesFileNamespaceImportConstantAndHaltOrigins() {
        NodeUseDeclaration ordinaryItem = new NodeUseDeclaration.UseDecl(element(), ITEM);
        NodeTopStatement use = ordinary(ordinaryItem);
        NodeTopStatement constants = constants(constant());
        NodeTopStatement halt = new NodeTopStatement.HaltCompiler(token("__halt_compiler();"), GROUP);
        NodeTopStatement namespace = new NodeTopStatement.NamespaceBlock(namespace("App"),
                topStatements(use, constants, halt), NAMESPACE);
        NodeProgram root = program(List.of(namespace));
        IrFile result = convert(root);
        IrNamespaceSection section = result.namespaceSections().getFirst();
        assertSource(FILE, result.source());
        assertSource(NAMESPACE, section.source());
        assertSource(BODY, section.body().source());
        IrUse convertedUse = assertInstanceOf(IrUse.class, section.body().statements().getFirst());
        assertSource(GROUP, convertedUse.source());
        assertSource(ITEM, convertedUse.imports().getFirst().source());
        IrConstantDeclaration constant = assertInstanceOf(IrConstantDeclaration.class, section.body().statements().get(1));
        assertSource(ITEM, constant.source());
        assertSource(LEAF, constant.value().source());
        assertSource(GROUP, assertInstanceOf(IrHaltCompiler.class, section.body().statements().getLast()).source());

        var typedGroup = new NodeTopStatement.UseGroup(functionType(), group(elements(element())), GROUP);
        var mixedGroup = mixed(inlineUses(new NodeInlineUseDeclaration.InlineUse(element(), ITEM)));
        assertSource(LEAF, assertInstanceOf(IrUse.class, convertBody(typedGroup).statements().getFirst()).imports().getFirst().source());
        assertSource(ITEM, assertInstanceOf(IrUse.class, convertBody(mixedGroup).statements().getFirst()).imports().getFirst().source());
    }

    // 合并区段范围与第一阶段一致，空文件、未知范围和真实零宽范围不混为一谈。
    @Test
    void preservesUnknownAndZeroWidthSourcesAcrossBothPipelines() throws ReflectiveOperationException {
        for (ComplexLocation location : List.of(ComplexLocation.NO_LOCATION, ComplexLocation.of(8, 2, 8, 2))) {
            NodeString marker = new NodeString("__halt_compiler();", location);
            NodeConstDecl constant = new NodeConstDecl.ConstDecl(new NodeString("VALUE", location), integer(location), location);
            NodeUnprefixedUseDeclaration element = new NodeUnprefixedUseDeclaration.UseElem(
                    new NodeNamespaceName.Part(new NodeString("Thing", location), location), location);
            NodeTopStatement use = new NodeTopStatement.Use(new NodeListNodeUseDeclaration(List.of(
                    new NodeUseDeclaration.UseDecl(element, location)), location), location);
            NodeTopStatement namespace = new NodeTopStatement.NamespaceBlock(
                    new NodeNamespaceName.Part(new NodeString("App", location), location),
                    new NodeListNodeTopStatement(List.of(use, new NodeTopStatement.Const(
                            new NodeListNodeConstDecl(List.of(constant), location), location),
                            new NodeTopStatement.HaltCompiler(marker, location)), location), location);
            NodeProgram root = program(new NodeListNodeTopStatement(List.of(namespace), location), location);
            IrFile result = convert(root);
            assertAllSources(result, SOURCE.sourceId(), location.isNoLocation() ? null : range(location));
            assertSectionSourcesMatchExtraction(root, result);
        }
        NodeProgram empty = program(new NodeListNodeTopStatement(List.of(), ComplexLocation.NO_LOCATION), ComplexLocation.NO_LOCATION);
        IrFile unknown = SyntaxConverter.convertFile(empty);
        assertNull(unknown.source().sourceId());
        assertNull(unknown.source().range());
        assertEquals(unknown, SyntaxConverter.convertFile(empty, null));
        assertEquals(1, unknown.namespaceSections().size());
        assertNull(unknown.namespaceSections().getFirst().source().range());
    }

    // 空文件的隐式全局没有可借用的范围；显式空区段分别保留标记和空主体列表的来源。
    @Test
    void preservesEmptySectionSourcesWithoutFallingBackToFileRanges() {
        ComplexLocation zeroWidth = ComplexLocation.of(8, 2, 8, 2);
        NodeProgram empty = program(new NodeListNodeTopStatement(List.of(), zeroWidth), zeroWidth);
        IrFile emptyResult = convert(empty);
        assertSource(zeroWidth, emptyResult.source());
        assertEquals(1, emptyResult.namespaceSections().size());
        assertNull(emptyResult.namespaceSections().getFirst().source().range());
        assertNull(emptyResult.namespaceSections().getFirst().body().source().range());
        assertSectionSourcesMatchExtraction(empty, emptyResult);

        NodeProgram root = program(List.of(new NodeTopStatement.Namespace(namespace("Semicolon"), NAMESPACE),
                new NodeTopStatement.NamespaceBlock(namespace("Block"), topStatements(), NAMESPACE),
                new NodeTopStatement.GlobalNamespaceBlock(new NodeListNodeTopStatement(List.of(), zeroWidth), zeroWidth)));
        IrFile result = convert(root);
        assertEquals(3, result.namespaceSections().size());
        assertSource(NAMESPACE, result.namespaceSections().getFirst().source());
        assertNull(result.namespaceSections().getFirst().body().source().range());
        assertSource(NAMESPACE, result.namespaceSections().get(1).source());
        assertSource(BODY, result.namespaceSections().get(1).body().source());
        assertSource(zeroWidth, result.namespaceSections().get(2).source());
        assertSource(zeroWidth, result.namespaceSections().get(2).body().source());
        assertSectionSourcesMatchExtraction(root, result);
    }

    // 具名、隐式全局、重复及空区段使用同一分段规则，来源也不能在两个入口间漂移。
    @Test
    void matchesExtractedSectionBoundariesSourcesAndImportItems() {
        NodeProgram root = (NodeProgram) Main.parse("""
                <?php declare(strict_types=1);
                namespace Same;
                use A\\One as Shared;
                namespace Same;
                use function F\\{run, other as Other,};
                namespace Block { use P\\{C, function f, const K}; const VALUE = 1; }
                echo 1;
                namespace {}
                namespace EmptySection;
                """);
        IrFile result = convert(root);
        assertSectionSourcesMatchExtraction(root, result);
        var extracted = DeclarationExtractor.extract(root, SOURCE.sourceId());
        assertEquals(List.of("", "Same", "Same", "Block", "", "", "EmptySection"),
                result.namespaceSections().stream().map(IrNamespaceSection::namespaceName).toList());
        for (int i = 0; i < extracted.namespaceSections().size(); i++) {
            List<IrImport> imports = result.namespaceSections().get(i).body().statements().stream()
                    .filter(IrUse.class::isInstance).map(IrUse.class::cast).flatMap(use -> use.imports().stream()).toList();
            var expectedImports = extracted.namespaceSections().get(i).imports().stream()
                    .map(item -> new IrImport(item.kind(), item.targetName(), item.alias(), item.source())).toList();
            assertEquals(expectedImports, imports);
        }
    }

    // 新模型列表非空约束仅施加在文件区段和 use 项，空全局名称及空区段体合法。
    @Test
    void validatesRequiredFileModelFieldsAndNonemptyLists() {
        IrBlock body = new IrBlock(List.of(), SOURCE);
        IrNamespaceSection section = new IrNamespaceSection("", body, SOURCE);
        IrImport item = new IrImport(ImportKind.CLASS, "Thing", "Thing", SOURCE);
        IrExpression value = new IrIntegerLiteral(1, SOURCE);
        for (Executable constructor : List.<Executable>of(
                () -> new IrFile(null, SOURCE), () -> new IrFile(List.of(section), null),
                () -> new IrNamespaceSection(null, body, SOURCE), () -> new IrNamespaceSection("", null, SOURCE),
                () -> new IrNamespaceSection("", body, null), () -> new IrUse(null, SOURCE),
                () -> new IrUse(List.of(item), null), () -> new IrConstantDeclaration(null, value, SOURCE),
                () -> new IrConstantDeclaration("VALUE", null, SOURCE), () -> new IrConstantDeclaration("VALUE", value, null),
                () -> new IrHaltCompiler(null), () -> new IrFile(Arrays.asList(section, null), SOURCE),
                () -> new IrUse(Arrays.asList(item, null), SOURCE),
                () -> new IrImport(null, "Thing", "Thing", SOURCE),
                () -> new IrImport(ImportKind.CLASS, null, "Thing", SOURCE),
                () -> new IrImport(ImportKind.CLASS, "Thing", null, SOURCE),
                () -> new IrImport(ImportKind.CLASS, "Thing", "Thing", null))) {
            assertThrows(NullPointerException.class, constructor);
        }
        assertThrows(IllegalArgumentException.class, () -> new IrFile(List.of(), SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrUse(List.of(), SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrConstantDeclaration("", value, SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrImport(ImportKind.CLASS, "", "Alias", SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrImport(ImportKind.CLASS, "Thing", "", SOURCE));
        assertEquals(List.of("kind", "targetName", "alias", "source"),
                Arrays.stream(IrImport.class.getRecordComponents()).map(RecordComponent::getName).toList());
        assertEquals(List.of(ImportKind.class, String.class, String.class, SourceInfo.class),
                Arrays.stream(IrImport.class.getRecordComponents()).map(RecordComponent::getType).toList());
        assertEquals("", section.namespaceName());
        assertTrue(section.body().statements().isEmpty());
    }

    // 新列表防御性复制，区段和导入重复项保留，结果中的正文列表也保持只读。
    @Test
    void snapshotsAndFreezesFileAndImportCollections() {
        IrImport item = new IrImport(ImportKind.CLASS, "Thing", "Alias", SOURCE);
        var imports = new ArrayList<>(List.of(item, item));
        IrUse use = new IrUse(imports, SOURCE);
        var statements = new ArrayList<IrStatement>(List.of(use));
        IrNamespaceSection section = new IrNamespaceSection("Same", new IrBlock(statements, SOURCE), SOURCE);
        var sections = new ArrayList<>(List.of(section, section));
        IrFile result = new IrFile(sections, SOURCE);
        imports.clear(); statements.clear(); sections.clear();
        assertEquals(List.of(item, item), use.imports());
        assertEquals(List.of(section, section), result.namespaceSections());
        assertEquals(List.of(use), section.body().statements());
        assertThrows(UnsupportedOperationException.class, use.imports()::clear);
        assertThrows(UnsupportedOperationException.class, section.body().statements()::clear);
        assertThrows(UnsupportedOperationException.class, result.namespaceSections()::clear);
        IrFile converted = convert(Main.parse("<?php use Thing, Thing; namespace Same; namespace Same;"));
        assertThrows(UnsupportedOperationException.class, converted.namespaceSections()::clear);
        assertThrows(UnsupportedOperationException.class, converted.namespaceSections().getFirst().body().statements()::clear);
        assertThrows(UnsupportedOperationException.class,
                assertInstanceOf(IrUse.class, converted.namespaceSections().getFirst().body().statements().getFirst()).imports()::clear);
    }

    // 全文件遍历声明默认值与方法体，后续不支持子树不能被跳过或交付部分成功结果。
    @Test
    void propagatesUnsupportedContentsFromEveryFileBoundary() {
        for (String code : List.of("echo 1; ($invalid[]);", "const GOOD = 1, BAD = ($invalid[]);",
                "namespace App; function f($value = ($invalid[])) {}", "namespace App { class C { public $value = ($invalid[]); } }",
                "namespace App { function f() { ($invalid[]); } }", "trait T { function run() { ($invalid[]); } }",
                "interface I { const VALUE = ($invalid[]); }", "namespace First; echo 1; namespace Last; ($invalid[]);")) {
            AstNode root = assertDoesNotThrow(() -> Main.parse("<?php " + code), code);
            var error = assertThrows(SyntaxConversionException.class, () -> convert(root), code);
            assertTrue(error.fieldPath().startsWith("program.stmts["), error.fieldPath());
            assertEquals(SOURCE.sourceId(), error.source().sourceId());
            assertFalse(error.reason().isBlank());
        }
    }

    // AST 与第一阶段 ID/索引保持不变，重复调用及失败后的调用没有状态或来源泄漏。
    @Test
    void convertsRepeatedlyWithoutMutatingAstOrDeclarationIndexes() throws ReflectiveOperationException {
        NodeProgram root = (NodeProgram) Main.parse("""
                <?php
                namespace Demo;
                use Vendor\\{Thing, function run as execute, const VALUE};
                const TEXT = "ready", COUNT = 2;
                function outer() { function nested() { return "nested"; } return new Thing(); }
                class Owner { public $value = 1; function read() { return $this->value; } }
                namespace Demo;
                use Other\\Thing;
                echo "done";
                __halt_compiler(); arbitrary payload <?php const MISSING = 3;
                """);
        PhpFile extracted = DeclarationExtractor.extract(root, SOURCE.sourceId());
        var originalSections = List.copyOf(extracted.namespaceSections());
        var originalStatements = List.copyOf(root.getStmts().getValue());
        String before = root.toTreeString(false);
        AstNode broken = assertDoesNotThrow(() -> Main.parse("<?php namespace Broken; const FAIL = ($invalid[]);"));
        assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertFile(broken, "failed.php"));
        IrFile result = convert(root);
        assertEquals(result, convert(root));
        assertEquals(before, root.toTreeString(false));
        assertEquals(originalSections, extracted.namespaceSections());
        for (int i = 0; i < originalStatements.size(); i++) assertSame(originalStatements.get(i), root.getStmts().getValue().get(i));
        for (NamespaceSection section : originalSections) {
            for (TopLevelDeclaration declaration : section.declarations()) {
                assertSame(declaration, extracted.declarationIndex().findById(declaration.id()).orElseThrow());
                if (declaration instanceof ClassLikeDefinition type) {
                    for (ClassMember member : type.members()) {
                        assertSame(member, extracted.declarationIndex().findById(member.id()).orElseThrow());
                    }
                }
            }
        }
        assertTrue(extracted.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "Demo\\nested").isEmpty());
        assertTrue(extracted.declarationIndex().findTopLevel(TopLevelKind.CONSTANT, "Demo\\MISSING").isEmpty());
        assertEquals(originalSections, DeclarationExtractor.extract(root, SOURCE.sourceId()).namespaceSections());
        assertNoAst(result, Collections.newSetFromMap(new IdentityHashMap<>()));
        assertAllSourceIds(result, SOURCE.sourceId());
        IrFile other = SyntaxConverter.convertFile(root, "other.php");
        assertAllSourceIds(other, "other.php");
        assertAllSourceIds(SyntaxConverter.convertFile(root), null);
        assertEquals(result, convert(root));
    }

    private static IrFile convert(AstNode root) { return SyntaxConverter.convertFile(root, SOURCE.sourceId()); }

    private static IrBlock convertBody(NodeTopStatement statement) {
        return SyntaxConverter.convertBody(new SyntaxBody(List.of(statement), SOURCE));
    }

    private static void assertFileFailure(AstNode root, String expectedPath) {
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class, () -> convert(root));
        assertEquals(expectedPath, error.fieldPath());
        assertDiagnostic(error);
    }

    private static void assertTopFailure(NodeTopStatement statement, String suffix) {
        assertFileFailure(program(List.of(statement)), "program.stmts[0]" + suffix);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class, () -> convertBody(statement));
        assertEquals("body.statements[0]" + suffix, error.fieldPath());
        assertDiagnostic(error);
    }

    private static void assertImportFailure(NodeTopStatement statement, String suffix) {
        assertTopFailure(statement, suffix);
        NodeProgram root = program(List.of(statement));
        DeclarationExtractionException error = assertThrows(DeclarationExtractionException.class,
                () -> DeclarationExtractor.extract(root, SOURCE.sourceId()));
        assertEquals("program.stmts[0]" + suffix, error.fieldPath());
        assertEquals(SOURCE.sourceId(), error.source().sourceId());
        assertFalse(error.reason().isBlank());
    }

    private static void assertDiagnostic(SyntaxConversionException error) {
        assertEquals(SOURCE.sourceId(), error.source().sourceId());
        assertFalse(error.reason().isBlank());
        assertTrue(error.getMessage().contains(SOURCE.sourceId()));
        assertTrue(error.getMessage().contains(error.fieldPath()));
    }

    private static NodeProgram program(List<NodeTopStatement> statements) { return program(topStatements(statements), FILE); }

    private static NodeProgram program(NodeListNodeTopStatement statements, ComplexLocation location) {
        return new NodeProgram() {
            @Override public NodeListNodeTopStatement getStmts() { return statements; }
            @Override public ComplexLocation getLocation() { return location; }
        };
    }

    private static NodeListNodeTopStatement topStatements(NodeTopStatement... statements) { return topStatements(List.of(statements)); }

    private static NodeListNodeTopStatement topStatements(List<NodeTopStatement> statements) {
        return new NodeListNodeTopStatement(statements, BODY);
    }

    private static NodeString token(String value) { return new NodeString(value, LEAF); }

    private static NodeNamespaceName namespace(String value) { return new NodeNamespaceName.Part(token(value), LEAF); }

    private static NodeExpr integer(ComplexLocation location) {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(new NodeString("1", location), location), location), location);
    }

    private static NodeConstDecl constant() { return new NodeConstDecl.ConstDecl(token("VALUE"), integer(LEAF), ITEM); }

    private static NodeTopStatement constants(NodeConstDecl... constants) {
        return new NodeTopStatement.Const(new NodeListNodeConstDecl(List.of(constants), GROUP), GROUP);
    }

    private static NodeUnprefixedUseDeclaration element() { return new NodeUnprefixedUseDeclaration.UseElem(namespace("Thing"), LEAF); }

    private static NodeUseDeclaration use() { return new NodeUseDeclaration.UseDecl(element(), ITEM); }

    private static NodeListNodeUseDeclaration uses(NodeUseDeclaration... uses) { return new NodeListNodeUseDeclaration(List.of(uses), GROUP); }

    private static NodeTopStatement ordinary(NodeUseDeclaration use) { return new NodeTopStatement.Use(uses(use), GROUP); }

    private static NodeUseType functionType() { return new NodeUseType.UseFunction(token("function"), ITEM); }

    private static NodeListNodeUnprefixedUseDeclaration elements(NodeUnprefixedUseDeclaration... elements) {
        return new NodeListNodeUnprefixedUseDeclaration(List.of(elements), GROUP);
    }

    private static NodeGroupUseDeclaration group(NodeListNodeUnprefixedUseDeclaration uses) {
        return new NodeGroupUseDeclaration.GroupUse(namespace("Vendor"), uses, ITEM);
    }

    private static NodeListNodeInlineUseDeclaration inlineUses(NodeInlineUseDeclaration... uses) {
        return new NodeListNodeInlineUseDeclaration(List.of(uses), GROUP);
    }

    private static NodeTopStatement mixed(NodeListNodeInlineUseDeclaration uses) {
        return new NodeTopStatement.UseMixedGroup(new NodeMixedGroupUseDeclaration.MixedGroupUse(namespace("Vendor"), uses, ITEM), GROUP);
    }

    private static void assertSource(ComplexLocation expected, SourceInfo actual) {
        assertEquals(SOURCE.sourceId(), actual.sourceId());
        assertEquals(expected.isNoLocation() ? null : range(expected), actual.range());
    }

    private static SourceRange range(ComplexLocation location) {
        return new SourceRange(location.getStartLine(), location.getStartColumn(), location.getEndLine(), location.getEndColumn());
    }

    private static void assertSectionSourcesMatchExtraction(NodeProgram root, IrFile result) {
        PhpFile extracted = DeclarationExtractor.extract(root, SOURCE.sourceId());
        assertEquals(extracted.source(), result.source());
        assertEquals(extracted.namespaceSections().size(), result.namespaceSections().size());
        for (int i = 0; i < extracted.namespaceSections().size(); i++) {
            NamespaceSection expected = extracted.namespaceSections().get(i);
            IrNamespaceSection actual = result.namespaceSections().get(i);
            assertEquals(expected.namespaceName(), actual.namespaceName());
            assertEquals(expected.source(), actual.source());
            assertEquals(expected.body().source(), actual.body().source());
        }
    }

    private static void assertAllSources(Object value, String sourceId, SourceRange expected) throws ReflectiveOperationException {
        if (value == null) return;
        if (value instanceof SourceInfo(String id, SourceRange range)) {
            assertEquals(sourceId, id);
            assertEquals(expected, range);
        } else if (value instanceof List<?> list) {
            for (Object child : list) assertAllSources(child, sourceId, expected);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertAllSources(component.getAccessor().invoke(value), sourceId, expected);
            }
        }
    }

    private static void assertAllSourceIds(Object value, String sourceId) throws ReflectiveOperationException {
        if (value == null) return;
        if (value instanceof SourceInfo source) {
            assertEquals(sourceId, source.sourceId());
        } else if (value instanceof List<?> list) {
            for (Object child : list) assertAllSourceIds(child, sourceId);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertAllSourceIds(component.getAccessor().invoke(value), sourceId);
            }
        }
    }

    private static void assertNoAst(Object value, Set<Object> visited) throws ReflectiveOperationException {
        if (value == null || !visited.add(value)) return;
        assertFalse(value instanceof AstNode, "整文件 IR 不应保留 CUP AST");
        if (value instanceof ByteString bytes) {
            assertEquals(bytes.size(), bytes.toByteArray().length);
        } else if (value instanceof List<?> list) {
            for (Object child : list) assertNoAst(child, visited);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertNoAst(component.getAccessor().invoke(value), visited);
            }
        } else {
            assertTrue(value instanceof String || value instanceof Enum<?> || value instanceof Number
                    || value instanceof Boolean, "未预期的结果字段类型：" + value.getClass());
        }
    }
}
