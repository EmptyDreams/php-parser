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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 switch 与异常处理 IR 的损坏 AST 诊断、来源和只读模型契约。 */
class ControlFlowConversionContractTest {
    private static final ComplexLocation LOCATION = ComplexLocation.of(4, 3, 4, 19);
    private static final SourceInfo SOURCE = new SourceInfo("control-flow.php", null);

    // switch 外壳和普通／默认分支的必需字段均不可通过空值退化成合法分支。
    @Test
    void rejectsMissingSwitchAndCaseFields() {
        assertFailure(new NodeStatement.Switch(null, switchCases(new NodeCaseList()), LOCATION), ".cond");
        assertFailure(new NodeStatement.Switch(literal(LOCATION), null, LOCATION), ".cases");
        for (NodeSwitchCaseList wrapper : List.of(
                new NodeSwitchCaseList.Cases(null, LOCATION),
                new NodeSwitchCaseList.CasesWithSemi(null, LOCATION),
                new NodeSwitchCaseList.AltCases(null, LOCATION),
                new NodeSwitchCaseList.AltCasesWithSemi(null, LOCATION))) {
            assertFailure(new NodeStatement.Switch(literal(LOCATION), wrapper, LOCATION), ".cases");
        }
        for (CaseFailure test : List.of(
                new CaseFailure(new NodeCaseList.Case(null, literal(LOCATION), separator(), statements(LOCATION), LOCATION), ".cases"),
                new CaseFailure(new NodeCaseList.Case(new NodeCaseList(), null, separator(), statements(LOCATION), LOCATION), ".cond"),
                new CaseFailure(new NodeCaseList.Case(new NodeCaseList(), literal(LOCATION), null, statements(LOCATION), LOCATION), ".sep"),
                new CaseFailure(new NodeCaseList.Case(new NodeCaseList(), literal(LOCATION), separator(), null, LOCATION), ".stmts"),
                new CaseFailure(new NodeCaseList.DefaultCase(null, token("default"), separator(), statements(LOCATION), LOCATION), ".cases"),
                new CaseFailure(new NodeCaseList.DefaultCase(new NodeCaseList(), null, separator(), statements(LOCATION), LOCATION), ".kw"),
                new CaseFailure(new NodeCaseList.DefaultCase(new NodeCaseList(), token("default"), null, statements(LOCATION), LOCATION), ".sep"),
                new CaseFailure(new NodeCaseList.DefaultCase(new NodeCaseList(), token("default"), separator(), null, LOCATION), ".stmts"))) {
            assertFailure(switchStatement(test.branch()), test.pathPart());
        }
    }

    // 已知的分隔符与 default 变体仍须校验标记值，不能接受缺失或伪造的字面量。
    @Test
    void rejectsMalformedCaseMarkersAndSeparators() {
        for (NodeCaseSeparator separator : List.of(
                new NodeCaseSeparator.Colon(null, LOCATION),
                new NodeCaseSeparator.Colon(token(null), LOCATION),
                new NodeCaseSeparator.Colon(token(""), LOCATION),
                new NodeCaseSeparator.Colon(token(";"), LOCATION),
                new NodeCaseSeparator.Semi(null, LOCATION),
                new NodeCaseSeparator.Semi(token(null), LOCATION),
                new NodeCaseSeparator.Semi(token(""), LOCATION),
                new NodeCaseSeparator.Semi(token(":"), LOCATION))) {
            assertFailure(switchStatement(new NodeCaseList.Case(new NodeCaseList(), literal(LOCATION),
                    separator, statements(LOCATION), LOCATION)), ".sep");
            assertFailure(switchStatement(new NodeCaseList.DefaultCase(new NodeCaseList(), token("default"),
                    separator, statements(LOCATION), LOCATION)), ".sep");
        }
        for (NodeString marker : List.of(token(null), token(""), token("case"))) {
            assertFailure(switchStatement(new NodeCaseList.DefaultCase(new NodeCaseList(), marker,
                    separator(), statements(LOCATION), LOCATION)), ".kw");
        }
    }

    // try、catch、finally 和 throw 的包装字段必须存在，缺省 finally 应由精确空产生式表示。
    @Test
    void rejectsMissingTryCatchFinallyAndThrowFields() {
        assertFailure(new NodeStatement.Try(null, new NodeCatchList(), new NodeFinallyStatement(), LOCATION), ".stmts");
        assertFailure(new NodeStatement.Try(statements(LOCATION), null, new NodeFinallyStatement(), LOCATION), ".catches");
        assertFailure(new NodeStatement.Try(statements(LOCATION), new NodeCatchList(), null, LOCATION), ".finallyBlock");
        assertFailure(new NodeStatement.Try(statements(LOCATION), new NodeCatchList(),
                new NodeFinallyStatement.Finally(null, LOCATION), LOCATION), ".stmts");
        assertFailure(new NodeStatement.Throw(null, LOCATION), ".expr");
        for (CatchFailure test : List.of(
                new CatchFailure(new NodeCatchList.CatchItem(null, exceptionTypes(), token("error"), statements(LOCATION), LOCATION), ".catches"),
                new CatchFailure(new NodeCatchList.CatchItem(new NodeCatchList(), null, token("error"), statements(LOCATION), LOCATION), ".exceptions"),
                new CatchFailure(new NodeCatchList.CatchItem(new NodeCatchList(), exceptionTypes(), null, statements(LOCATION), LOCATION), ".var"),
                new CatchFailure(new NodeCatchList.CatchItem(new NodeCatchList(), exceptionTypes(), token(null), statements(LOCATION), LOCATION), ".var"),
                new CatchFailure(new NodeCatchList.CatchItem(new NodeCatchList(), exceptionTypes(), token(""), statements(LOCATION), LOCATION), ".var"),
                new CatchFailure(new NodeCatchList.CatchItem(new NodeCatchList(), exceptionTypes(), token("error"), null, LOCATION), ".stmts"))) {
            assertFailure(tryStatement(test.branch()), test.pathPart());
        }
    }

    // 多异常类型列表至少一项，包装、列表值、元素及名称内部结构都应给出上下文错误。
    @Test
    void rejectsMalformedCatchTypeLists() {
        var nullEntry = new ArrayList<NodeName>();
        nullEntry.add(null);
        for (NodeListNodeName types : List.of(
                new NodeListNodeName(null, LOCATION),
                new NodeListNodeName(List.of(), LOCATION),
                new NodeListNodeName(nullEntry, LOCATION),
                new NodeListNodeName(List.of(new NodeName.Unqualified(null, LOCATION)), LOCATION),
                new NodeListNodeName(List.of(new NodeName.Unqualified(
                        new NodeNamespaceName.Part(token(null), LOCATION), LOCATION)), LOCATION))) {
            assertFailure(tryStatement(new NodeCatchList.CatchItem(new NodeCatchList(), types,
                    token("error"), statements(LOCATION), LOCATION)), ".exceptions");
        }
    }

    // 未知节点即使暴露已知字段，也不能被误认为 switch 外壳或链尾空产生式。
    @Test
    void rejectsUnknownControlFlowWrappersAndChainNodes() {
        NodeSwitchCaseList unknownWrapper = new NodeSwitchCaseList() {
            @Override public NodeCaseList getCases() { return new NodeCaseList(); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeCaseList unknownCase = new NodeCaseList() {
            @Override public NodeCaseList getCases() { return new NodeCaseList(); }
            @Override public NodeExpr getCond() { return literal(LOCATION); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeCaseSeparator unknownSeparator = new NodeCaseSeparator() {
            @Override public NodeString getColon() { return token(":"); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeCatchList unknownCatch = new NodeCatchList() {
            @Override public NodeCatchList getCatches() { return new NodeCatchList(); }
            @Override public NodeListNodeName getExceptions() { return exceptionTypes(); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeFinallyStatement unknownFinally = new NodeFinallyStatement() {
            @Override public NodeListNodeInnerStatement getStmts() { return statements(LOCATION); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        assertFailure(new NodeStatement.Switch(literal(LOCATION), unknownWrapper, LOCATION), ".cases");
        assertFailure(switchStatement(unknownCase), ".cases");
        assertFailure(switchStatement(new NodeCaseList.Case(unknownCase, literal(LOCATION), separator(),
                statements(LOCATION), LOCATION)), ".cases");
        assertFailure(switchStatement(new NodeCaseList.Case(new NodeCaseList(), literal(LOCATION),
                unknownSeparator, statements(LOCATION), LOCATION)), ".sep");
        assertFailure(tryStatement(unknownCatch), ".catches");
        assertFailure(tryStatement(new NodeCatchList.CatchItem(unknownCatch, exceptionTypes(), token("error"),
                statements(LOCATION), LOCATION)), ".catches");
        assertFailure(new NodeStatement.Try(statements(LOCATION), new NodeCatchList(), unknownFinally, LOCATION), ".finallyBlock");
    }

    // 所有新语句块都拒绝损坏列表值及 null 元素，而不是跳过元素后交付部分 IR。
    @Test
    void rejectsMalformedStatementListsInEveryControlFlowBody() {
        var nullEntry = new ArrayList<NodeInnerStatement>();
        nullEntry.add(null);
        for (NodeListNodeInnerStatement list : List.of(
                new NodeListNodeInnerStatement(null, LOCATION),
                new NodeListNodeInnerStatement(nullEntry, LOCATION))) {
            assertFailure(switchStatement(new NodeCaseList.Case(new NodeCaseList(), literal(LOCATION),
                    separator(), list, LOCATION)), ".stmts");
            assertFailure(switchStatement(new NodeCaseList.DefaultCase(new NodeCaseList(), token("default"),
                    separator(), list, LOCATION)), ".stmts");
            assertFailure(new NodeStatement.Try(list, new NodeCatchList(), new NodeFinallyStatement(), LOCATION), ".stmts");
            assertFailure(tryStatement(new NodeCatchList.CatchItem(new NodeCatchList(), exceptionTypes(),
                    token("error"), list, LOCATION)), ".stmts");
            assertFailure(new NodeStatement.Try(statements(LOCATION), new NodeCatchList(),
                    new NodeFinallyStatement.Finally(list, LOCATION), LOCATION), ".stmts");
        }
    }

    // case/catch 链的累计来源照实保留；语句块使用列表来源，finally 使用自身节点来源。
    @Test
    void preservesOriginalCaseCatchAndBodyRanges() throws ReflectiveOperationException {
        NodeProgram parsed = (NodeProgram) Main.parse("""
                <?php
                switch ($value) {
                    case 1: echo 1;
                    default: echo 2;
                    case 3: break;
                }
                try {
                    throw new FirstError();
                } catch (FirstError | Vendor\\SecondError $first) {
                    echo 3;
                } catch (\\OtherError $second) {
                    throw $second;
                } finally {
                    echo 4;
                }
                """);
        NodeStatement switchSyntax = parsed.getStmts().getValue().getFirst().getStmt();
        NodeStatement trySyntax = parsed.getStmts().getValue().get(1).getStmt();
        IrSwitch selection = assertInstanceOf(IrSwitch.class, convert(switchSyntax));
        assertEquals(sourceRange(switchSyntax), selection.source().range());
        assertEquals(sourceRange(switchSyntax.getCond()), selection.condition().source().range());
        NodeCaseList branchSyntax = switchSyntax.getCases().getCases();
        for (int i = selection.cases().size() - 1; i >= 0; i--) {
            IrSwitchCase branch = selection.cases().get(i);
            assertEquals(sourceRange(branchSyntax), branch.source().range());
            assertEquals(sourceRange(branchSyntax.getStmts()), branch.body().source().range());
            if (branchSyntax instanceof NodeCaseList.Case) {
                assertEquals(sourceRange(branchSyntax.getCond()), branch.condition().source().range());
            } else {
                assertNull(branch.condition());
            }
            branchSyntax = branchSyntax.getCases();
        }
        IrTry guarded = assertInstanceOf(IrTry.class, convert(trySyntax));
        assertEquals(sourceRange(trySyntax), guarded.source().range());
        assertEquals(sourceRange(trySyntax.getStmts()), guarded.body().source().range());
        NodeStatement throwSyntax = trySyntax.getStmts().getValue().getFirst().getStmt();
        IrThrow thrown = assertInstanceOf(IrThrow.class, guarded.body().statements().getFirst());
        assertEquals(sourceRange(throwSyntax), thrown.source().range());
        assertEquals(sourceRange(throwSyntax.getExpr().getEv().getNewExpr()), thrown.expression().source().range());
        NodeCatchList catchSyntax = trySyntax.getCatches();
        for (int i = guarded.catches().size() - 1; i >= 0; i--) {
            IrCatch branch = guarded.catches().get(i);
            assertEquals(sourceRange(catchSyntax), branch.source().range());
            assertEquals(sourceRange(catchSyntax.getStmts()), branch.body().source().range());
            for (int j = 0; j < branch.exceptionTypes().size(); j++) {
                assertEquals(sourceRange(catchSyntax.getExceptions().getValue().get(j)),
                        branch.exceptionTypes().get(j).source().range());
            }
            catchSyntax = catchSyntax.getCatches();
        }
        assertEquals(sourceRange(trySyntax.getFinallyBlock()), guarded.finallyBlock().source().range());
        assertAllSourceIds(selection, "control-flow.php");
        assertAllSourceIds(guarded, "control-flow.php");
    }

    // 合成不同范围排除父节点覆盖子块的实现，并明确 finally 的来源不是它的列表来源。
    @Test
    void keepsDistinctBodyAndFinallyOrigins() {
        ComplexLocation bodyLocation = ComplexLocation.of(5, 2, 5, 2);
        ComplexLocation finallyLocation = ComplexLocation.of(6, 1, 6, 20);
        NodeListNodeInnerStatement body = statements(bodyLocation);
        NodeCaseList branch = new NodeCaseList.Case(new NodeCaseList(), literal(LOCATION), separator(), body, LOCATION);
        IrSwitch selection = assertInstanceOf(IrSwitch.class, convert(switchStatement(branch)));
        assertEquals(new SourceRange(4, 3, 4, 19), selection.cases().getFirst().source().range());
        assertEquals(new SourceRange(5, 2, 5, 2), selection.cases().getFirst().body().source().range());
        NodeCatchList catcher = new NodeCatchList.CatchItem(new NodeCatchList(), exceptionTypes(), token("error"), body, LOCATION);
        IrTry guarded = assertInstanceOf(IrTry.class, convert(new NodeStatement.Try(body, catcher,
                new NodeFinallyStatement.Finally(body, finallyLocation), LOCATION)));
        assertEquals(new SourceRange(5, 2, 5, 2), guarded.body().source().range());
        assertEquals(new SourceRange(4, 3, 4, 19), guarded.catches().getFirst().source().range());
        assertEquals(new SourceRange(5, 2, 5, 2), guarded.catches().getFirst().body().source().range());
        assertEquals(new SourceRange(6, 1, 6, 20), guarded.finallyBlock().source().range());
    }

    // 未知位置与零宽位置均原样保留，入口备用范围不能下发给任何子节点。
    @Test
    void preservesUnknownAndZeroWidthControlFlowLocations() throws ReflectiveOperationException {
        SourceInfo fallback = new SourceInfo("unknown-flow.php", new SourceRange(99, 1, 99, 9));
        for (ComplexLocation location : List.of(ComplexLocation.NO_LOCATION, ComplexLocation.of(8, 2, 8, 2))) {
            NodeListNodeInnerStatement body = statements(location);
            NodeCaseList branch = new NodeCaseList.Case(new NodeCaseList(), literal(location),
                    new NodeCaseSeparator.Colon(new NodeString(":", location), location), body, location);
            NodeStatement selection = new NodeStatement.Switch(literal(location), new NodeSwitchCaseList.Cases(branch, location), location);
            NodeListNodeName types = new NodeListNodeName(List.of(name(location)), location);
            NodeCatchList catcher = new NodeCatchList.CatchItem(new NodeCatchList(), types,
                    new NodeString("error", location), body, location);
            NodeStatement guarded = new NodeStatement.Try(body, catcher, new NodeFinallyStatement.Finally(body, location), location);
            IrBlock result = SyntaxConverter.convertBody(new SyntaxBody(List.of(selection, guarded,
                    new NodeStatement.Throw(literal(location), location)), fallback));
            SourceRange expected = location.isNoLocation() ? null : new SourceRange(8, 2, 8, 2);
            assertEquals(fallback, result.source());
            for (IrStatement statement : result.statements()) assertAllRanges(statement, expected);
            assertAllSourceIds(result, "unknown-flow.php");
        }
    }

    // 新模型列表防御性复制并只读，空 catch 类型列表不能构造为有效记录。
    @Test
    void snapshotsAndFreezesControlFlowCollections() {
        IrBlock body = new IrBlock(List.of(), SOURCE);
        var types = new ArrayList<>(List.of(new NameReference("Error", NameForm.UNQUALIFIED, SOURCE)));
        IrCatch catcher = new IrCatch(types, "error", body, SOURCE);
        var catches = new ArrayList<>(List.of(catcher));
        IrTry guarded = new IrTry(body, catches, null, SOURCE);
        var cases = new ArrayList<>(List.of(new IrSwitchCase(null, body, SOURCE)));
        IrSwitch selection = new IrSwitch(new IrIntegerLiteral(1, SOURCE), cases, SOURCE);
        types.clear();
        catches.clear();
        cases.clear();
        assertEquals("Error", catcher.exceptionTypes().getFirst().spelling());
        assertSame(catcher, guarded.catches().getFirst());
        assertEquals(1, selection.cases().size());
        assertThrows(UnsupportedOperationException.class, catcher.exceptionTypes()::clear);
        assertThrows(UnsupportedOperationException.class, guarded.catches()::clear);
        assertThrows(UnsupportedOperationException.class, selection.cases()::clear);
        assertThrows(IllegalArgumentException.class, () -> new IrCatch(List.of(), "error", body, SOURCE));

        IrSwitch convertedSwitch = assertInstanceOf(IrSwitch.class, convert(parsedStatement("switch ($value) { default: }")));
        IrTry convertedTry = assertInstanceOf(IrTry.class, convert(parsedStatement("try {} catch (Error $error) {}")));
        assertThrows(UnsupportedOperationException.class, convertedSwitch.cases()::clear);
        assertThrows(UnsupportedOperationException.class, convertedTry.catches()::clear);
        assertThrows(UnsupportedOperationException.class, convertedTry.catches().getFirst().exceptionTypes()::clear);
    }

    // 类方法与函数的控制流转换不改变声明、索引或 AST，重复结果相等且递归不包含 CUP 节点。
    @Test
    void convertsControlFlowBodiesWithoutMutatingDeclarations() throws ReflectiveOperationException {
        NodeProgram parsed = (NodeProgram) Main.parse("""
                <?php
                namespace Demo;
                class Handler {
                    public function handle($value) {
                        try {
                            switch ($value) {
                                case 1: return new Result();
                                default: throw new Failure();
                            }
                        } catch (Failure | \\RuntimeException $error) {
                            throw $error;
                        } finally {
                            $this->closed = true;
                        }
                    }
                }
                function run($value) {
                    try { return (new Handler())->handle($value); }
                    catch (Failure $error) { return null; }
                }
                """);
        String before = parsed.toTreeString(false);
        PhpFile file = DeclarationExtractor.extract(parsed, "control-flow-declarations.php");
        ClassLikeDefinition clazz = assertInstanceOf(ClassLikeDefinition.class,
                file.declarationIndex().findTopLevel(TopLevelKind.TYPE, "Demo\\Handler").getFirst());
        MethodDefinition method = assertInstanceOf(MethodDefinition.class,
                file.declarationIndex().findMembers(clazz.id(), MemberKind.METHOD, "handle").getFirst());
        FunctionDefinition function = assertInstanceOf(FunctionDefinition.class,
                file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "Demo\\run").getFirst());
        List<ClassMember> membersBefore = List.copyOf(clazz.members());
        for (SyntaxBody syntax : List.of(method.body(), function.body())) {
            List<AstNode> statementsBefore = List.copyOf(syntax.statements());
            IrBlock body = SyntaxConverter.convertBody(syntax);
            assertInstanceOf(IrTry.class, body.statements().getFirst());
            assertEquals(syntax.source(), body.source());
            assertEquals(body, SyntaxConverter.convertBody(syntax));
            assertAllSourceIds(body, "control-flow-declarations.php");
            assertNoAst(body, Collections.newSetFromMap(new IdentityHashMap<>()));
            assertEquals(statementsBefore, syntax.statements());
            for (int i = 0; i < statementsBefore.size(); i++) assertSame(statementsBefore.get(i), syntax.statements().get(i));
        }
        assertEquals(before, parsed.toTreeString(false));
        assertEquals(membersBefore, clazz.members());
        for (ClassMember member : membersBefore) assertSame(member, file.declarationIndex().findById(member.id()).orElseThrow());
        assertSame(clazz, file.declarationIndex().findTopLevel(TopLevelKind.TYPE, "Demo\\Handler").getFirst());
        assertSame(function, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "Demo\\run").getFirst());
    }

    private static IrStatement convert(NodeStatement syntax) {
        return SyntaxConverter.convertBody(new SyntaxBody(List.of(syntax), SOURCE)).statements().getFirst();
    }

    private static void assertFailure(NodeStatement syntax, String pathPart) {
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class, () -> convert(syntax));
        assertEquals("control-flow.php", error.source().sourceId());
        assertEquals(new SourceRange(4, 3, 4, 19), error.source().range());
        assertTrue(error.fieldPath().contains(pathPart), error.fieldPath());
        assertFalse(error.reason().isBlank());
        assertTrue(error.getMessage().contains("control-flow.php"));
        assertTrue(error.getMessage().contains(error.fieldPath()));
    }

    private static NodeStatement parsedStatement(String code) {
        return ((NodeProgram) Main.parse("<?php " + code)).getStmts().getValue().getFirst().getStmt();
    }

    private static NodeStatement switchStatement(NodeCaseList cases) {
        return new NodeStatement.Switch(literal(LOCATION), switchCases(cases), LOCATION);
    }

    private static NodeSwitchCaseList switchCases(NodeCaseList cases) {
        return new NodeSwitchCaseList.Cases(cases, LOCATION);
    }

    private static NodeStatement tryStatement(NodeCatchList catches) {
        return new NodeStatement.Try(statements(LOCATION), catches, new NodeFinallyStatement(), LOCATION);
    }

    private static NodeCaseSeparator separator() {
        return new NodeCaseSeparator.Colon(token(":"), LOCATION);
    }

    private static NodeListNodeInnerStatement statements(ComplexLocation location) {
        return new NodeListNodeInnerStatement(List.of(), location);
    }

    private static NodeListNodeName exceptionTypes() {
        return new NodeListNodeName(List.of(name(LOCATION)), LOCATION);
    }

    private static NodeName name(ComplexLocation location) {
        return new NodeName.Unqualified(new NodeNamespaceName.Part(new NodeString("Error", location), location), location);
    }

    private static NodeString token(String value) {
        return new NodeString(value, LOCATION);
    }

    private static NodeExpr literal(ComplexLocation location) {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(new NodeString("1", location), location), location), location);
    }

    private record CaseFailure(NodeCaseList branch, String pathPart) {}
    private record CatchFailure(NodeCatchList branch, String pathPart) {}

    private static SourceRange sourceRange(AstNode syntax) {
        var location = assertInstanceOf(ComplexLocation.class, syntax.getLocation());
        return new SourceRange(location.getStartLine(), location.getStartColumn(), location.getEndLine(), location.getEndColumn());
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

    private static void assertAllRanges(Object value, SourceRange expected) throws ReflectiveOperationException {
        if (value == null) return;
        if (value instanceof SourceInfo source) {
            assertEquals(expected, source.range());
        } else if (value instanceof List<?> list) {
            for (Object child : list) assertAllRanges(child, expected);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertAllRanges(component.getAccessor().invoke(value), expected);
            }
        }
    }

    private static void assertNoAst(Object value, Set<Object> visited) throws ReflectiveOperationException {
        if (value == null || !visited.add(value)) return;
        assertFalse(value instanceof AstNode, "控制流 IR 不应保留 CUP AST");
        if (value instanceof List<?> list) {
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
