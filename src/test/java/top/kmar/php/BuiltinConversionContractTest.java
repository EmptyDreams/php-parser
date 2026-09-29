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
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证内置语言结构的损坏 AST 诊断、来源、只读模型和声明隔离契约。 */
class BuiltinConversionContractTest {
    private static final ComplexLocation LOCATION = ComplexLocation.of(4, 3, 4, 19);
    private static final SourceInfo SOURCE = new SourceInfo("builtins.php", null);

    // 必需的操作数、操作符及包装缺失时明确失败，不能误当作省略参数。
    @Test
    void rejectsMissingBuiltinFields() {
        for (ExpressionFailure test : List.of(
                failure(new NodeExprWithoutVariable.InternalFunction(null, LOCATION), ".func"),
                failure(new NodeExprWithoutVariable.Print(null, LOCATION), ".expr"),
                failure(new NodeExprWithoutVariable.Cast(null, literal(LOCATION), LOCATION), ".op"),
                failure(new NodeExprWithoutVariable.Cast(token("(int)"), null, LOCATION), ".expr"),
                failure(new NodeExprWithoutVariable.Unary(token("@"), null, LOCATION), ".expr"),
                failure(new NodeExprWithoutVariable.Unary(null, literal(LOCATION), LOCATION), ".op"),
                failure(new NodeExprWithoutVariable.Exit(null, LOCATION), ".arg"))) {
            assertFailure(test.expression(), test.pathPart());
        }
        for (String operator : List.of("empty", "include", "include_once", "require", "require_once", "eval")) {
            assertFailure(internal(new NodeInternalFunctionsInYacc.IncludeOrEval(token(operator), null, LOCATION)), ".expr");
        }
    }

    // isset 至少需要一个操作数，损坏的包装、列表值及元素不能产生部分结果。
    @Test
    void rejectsMalformedIssetLists() {
        assertFailure(internal(new NodeInternalFunctionsInYacc.Isset(null, LOCATION)), ".vars");
        var nullEntry = new ArrayList<NodeExpr>();
        nullEntry.add(literal(LOCATION));
        nullEntry.add(null);
        for (NodeListNodeExpr list : List.of(
                new NodeListNodeExpr(null, LOCATION),
                new NodeListNodeExpr(List.of(), LOCATION),
                new NodeListNodeExpr(nullEntry, LOCATION))) {
            assertFailure(internal(new NodeInternalFunctionsInYacc.Isset(list, LOCATION)), ".vars");
        }
        NodeExpr unknown = new NodeExpr() {
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        assertFailure(internal(new NodeInternalFunctionsInYacc.Isset(
                new NodeListNodeExpr(List.of(literal(LOCATION), unknown), LOCATION), LOCATION)), ".vars[1]");
    }

    // unset 的非空列表与目标包装均需要完整，不能把损坏目标当成空语句。
    @Test
    void rejectsMalformedUnsetListsAndTargets() {
        assertFailure(new NodeStatement.Unset(null, LOCATION), ".unsetVars");
        var nullEntry = new ArrayList<NodeVariable>();
        nullEntry.add(variable(LOCATION));
        nullEntry.add(null);
        for (NodeListNodeVariable list : List.of(
                new NodeListNodeVariable(null, LOCATION),
                new NodeListNodeVariable(List.of(), LOCATION),
                new NodeListNodeVariable(nullEntry, LOCATION))) {
            assertFailure(new NodeStatement.Unset(list, LOCATION), ".unsetVars");
        }
        NodeVariable unknown = new NodeVariable() {
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        for (NodeVariable target : List.of(unknown,
                new NodeVariable.CallableVariable(null, LOCATION),
                new NodeVariable.CallableVariable(new NodeCallableVariable.SimpleVar(null, LOCATION), LOCATION),
                new NodeVariable.CallableVariable(new NodeCallableVariable.SimpleVar(
                        new NodeSimpleVariable.NamedVar(null, LOCATION), LOCATION), LOCATION),
                new NodeVariable.PropertyAccess(null, new NodePropertyName.Name(token("value"), LOCATION), LOCATION))) {
            assertFailure(new NodeStatement.Unset(new NodeListNodeVariable(
                    List.of(variable(LOCATION), target), LOCATION), LOCATION), ".unsetVars[1]");
        }
    }

    // 共用 AST 变体必须按已知标记区分语言结构，不能把未知标记归为普通函数。
    @Test
    void rejectsMissingEmptyAndUnknownBuiltinMarkers() {
        assertFailure(internal(new NodeInternalFunctionsInYacc.IncludeOrEval(null, literal(LOCATION), LOCATION)), ".op");
        assertFailure(internal(new NodeInternalFunctionsInYacc.IncludeOrEval(token(null), literal(LOCATION), LOCATION)), ".op");
        for (String marker : List.of("", "isset", "print", "include_extra", " empty", "eval ")) {
            assertFailure(internal(new NodeInternalFunctionsInYacc.IncludeOrEval(token(marker), literal(LOCATION), LOCATION)), ".op");
        }
        assertFailure(nonVariable(new NodeExprWithoutVariable.Unary(token("@@"), literal(LOCATION), LOCATION)), ".op");
    }

    // 类型转换只接受词法定义的括号、ASCII 空格/tab 与别名，不能通过任意 trim 放宽输入。
    @Test
    void rejectsMalformedCastSpellings() {
        assertFailure(nonVariable(new NodeExprWithoutVariable.Cast(token(null), literal(LOCATION), LOCATION)), ".op");
        for (String spelling : List.of("", "int", "(int", "int)", "()", "((int))", "(i nt)",
                "(unknown)", "(decimal)", " (int)", "(int) ", "(\nint)", "(int\r)", "(\u00a0int)", "(int\f)")) {
            assertFailure(nonVariable(new NodeExprWithoutVariable.Cast(token(spelling), literal(LOCATION), LOCATION)), ".op");
        }
    }

    // 未知内置结构及 exit 包装即使提供看似有效的字段也不能被猜测为已知语法。
    @Test
    void rejectsUnknownBuiltinAndExitWrappers() {
        NodeInternalFunctionsInYacc unknown = new NodeInternalFunctionsInYacc() {
            @Override public NodeString getOp() { return token("empty"); }
            @Override public NodeExpr getExpr() { return literal(LOCATION); }
            @Override public NodeListNodeExpr getVars() { return new NodeListNodeExpr(List.of(literal(LOCATION)), LOCATION); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        assertFailure(internal(unknown), ".func");
        NodeExitExpr unknownEmpty = new NodeExitExpr() {
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeExitExpr unknownValue = new NodeExitExpr() {
            @Override public NodeExpr getExpr() { return literal(LOCATION); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        assertFailure(nonVariable(new NodeExprWithoutVariable.Exit(unknownEmpty, LOCATION)), ".arg");
        assertFailure(nonVariable(new NodeExprWithoutVariable.Exit(unknownValue, LOCATION)), ".arg");
    }

    // exit 的已知空包装合法，显式 PHP null 则必须保留为一个实际的表达式节点。
    @Test
    void distinguishesOmittedExitOperandsFromExplicitNull() {
        IrExit omitted = assertInstanceOf(IrExit.class, convert(nonVariable(
                new NodeExprWithoutVariable.Exit(new NodeExitExpr(), LOCATION))));
        IrExit parentheses = assertInstanceOf(IrExit.class, convert(nonVariable(
                new NodeExprWithoutVariable.Exit(new NodeExitExpr.ExitArgs(null, LOCATION), LOCATION))));
        assertNull(omitted.expression());
        assertNull(parentheses.expression());
        IrExit explicitNull = assertInstanceOf(IrExit.class, convert(parsedExpression("exit(null)")));
        assertEquals(LiteralKind.NULL, assertInstanceOf(IrLiteral.class, explicitNull.expression()).kind());
    }

    // 每种新节点使用对应 AST 的范围，内置函数包装和参数包装不得覆盖真实来源。
    @Test
    void preservesDistinctBuiltinAndOperandOrigins() {
        ComplexLocation innerLocation = ComplexLocation.of(5, 4, 5, 12);
        ComplexLocation operandLocation = ComplexLocation.of(6, 2, 6, 3);
        NodeExpr operand = literal(operandLocation);
        SourceRange innerRange = new SourceRange(5, 4, 5, 12);
        SourceRange operandRange = new SourceRange(6, 2, 6, 3);
        IrIsset check = assertInstanceOf(IrIsset.class, convert(internal(new NodeInternalFunctionsInYacc.Isset(
                new NodeListNodeExpr(List.of(operand), operandLocation), innerLocation))));
        assertEquals(innerRange, check.source().range());
        assertEquals(operandRange, check.expressions().getFirst().source().range());
        for (String operator : List.of("empty", "include", "include_once", "require", "require_once", "eval")) {
            IrExpression result = convert(internal(new NodeInternalFunctionsInYacc.IncludeOrEval(
                    new NodeString(operator, LOCATION), operand, innerLocation)));
            assertEquals(innerRange, result.source().range());
            IrExpression child = switch (result) {
                case IrEmptyCheck value -> value.expression();
                case IrInclude value -> value.expression();
                case IrEval value -> value.expression();
                default -> throw new AssertionError(result);
            };
            assertEquals(operandRange, child.source().range());
        }
        for (NodeExprWithoutVariable syntax : List.of(
                new NodeExprWithoutVariable.Cast(token("(int)"), operand, innerLocation),
                new NodeExprWithoutVariable.Unary(token("@"), operand, innerLocation),
                new NodeExprWithoutVariable.Print(operand, innerLocation),
                new NodeExprWithoutVariable.Exit(new NodeExitExpr.ExitArgs(operand, LOCATION), innerLocation))) {
            IrExpression result = convert(nonVariable(syntax));
            assertEquals(innerRange, result.source().range());
            IrExpression child = switch (result) {
                case IrCast value -> value.expression();
                case IrErrorSuppress value -> value.expression();
                case IrPrint value -> value.expression();
                case IrExit value -> value.expression();
                default -> throw new AssertionError(result);
            };
            assertEquals(operandRange, child.source().range());
        }
        IrUnset unset = assertInstanceOf(IrUnset.class, convert(new NodeStatement.Unset(
                new NodeListNodeVariable(List.of(variable(operandLocation)), LOCATION), innerLocation)));
        assertEquals(innerRange, unset.source().range());
        assertEquals(operandRange, unset.targets().getFirst().source().range());
    }

    // 未知位置与零宽位置必须区分，入口的备用范围不得下发给内部节点。
    @Test
    void preservesUnknownAndZeroWidthBuiltinLocations() throws ReflectiveOperationException {
        SourceInfo fallback = new SourceInfo("unknown-builtins.php", new SourceRange(99, 1, 99, 9));
        for (ComplexLocation location : List.of(ComplexLocation.NO_LOCATION, ComplexLocation.of(8, 2, 8, 2))) {
            NodeExpr operand = literal(location);
            var expressions = new ArrayList<NodeExprWithoutVariable>();
            expressions.add(new NodeExprWithoutVariable.InternalFunction(new NodeInternalFunctionsInYacc.Isset(
                    new NodeListNodeExpr(List.of(operand), location), location), location));
            for (String operator : List.of("empty", "include", "include_once", "require", "require_once", "eval")) {
                expressions.add(new NodeExprWithoutVariable.InternalFunction(new NodeInternalFunctionsInYacc.IncludeOrEval(
                        new NodeString(operator, location), operand, location), location));
            }
            expressions.addAll(List.of(
                    new NodeExprWithoutVariable.Cast(new NodeString("(int)", location), operand, location),
                    new NodeExprWithoutVariable.Unary(new NodeString("@", location), operand, location),
                    new NodeExprWithoutVariable.Print(operand, location),
                    new NodeExprWithoutVariable.Exit(new NodeExitExpr.ExitArgs(operand, location), location)));
            SourceRange expected = location.isNoLocation() ? null : new SourceRange(8, 2, 8, 2);
            for (NodeExprWithoutVariable syntax : expressions) {
                IrExpression result = SyntaxConverter.convertExpression(new SyntaxExpression(
                        new NodeExpr.ExprWithoutVariable(syntax, location), fallback));
                assertAllRanges(result, expected);
                assertAllSourceIds(result, "unknown-builtins.php");
            }
            IrBlock body = SyntaxConverter.convertBody(new SyntaxBody(List.of(new NodeStatement.Unset(
                    new NodeListNodeVariable(List.of(variable(location)), location), location)), fallback));
            assertEquals(fallback, body.source());
            assertAllRanges(body.statements().getFirst(), expected);
            assertAllSourceIds(body, "unknown-builtins.php");
        }
    }

    // 公开模型不接受损坏必需字段；只有 exit 操作数可为空，非空列表约束在模型层同样有效。
    @Test
    void validatesRequiredBuiltinModelFields() {
        IrExpression expression = new IrIntegerLiteral(1, SOURCE);
        for (Executable construction : List.<Executable>of(
                () -> new IrIsset(null, SOURCE),
                () -> new IrIsset(List.of(expression), null),
                () -> new IrUnset(null, SOURCE),
                () -> new IrUnset(List.of(new IrVariableTarget("value", SOURCE)), null),
                () -> new IrEmptyCheck(null, SOURCE),
                () -> new IrEmptyCheck(expression, null),
                () -> new IrCast(null, expression, SOURCE),
                () -> new IrCast(CastKind.INTEGER, null, SOURCE),
                () -> new IrCast(CastKind.INTEGER, expression, null),
                () -> new IrErrorSuppress(null, SOURCE),
                () -> new IrErrorSuppress(expression, null),
                () -> new IrPrint(null, SOURCE),
                () -> new IrPrint(expression, null),
                () -> new IrInclude(null, expression, SOURCE),
                () -> new IrInclude(IncludeKind.INCLUDE, null, SOURCE),
                () -> new IrInclude(IncludeKind.INCLUDE, expression, null),
                () -> new IrEval(null, SOURCE),
                () -> new IrEval(expression, null),
                () -> new IrExit(null, null))) {
            assertThrows(NullPointerException.class, construction);
        }
        assertThrows(IllegalArgumentException.class, () -> new IrIsset(List.of(), SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrUnset(List.of(), SOURCE));
        assertNull(new IrExit(null, SOURCE).expression());
    }

    // 新列表保留重复项并防御性复制，模型和转换结果的列表均不允许外部修改。
    @Test
    void snapshotsAndFreezesBuiltinCollections() {
        IrExpression expression = new IrIntegerLiteral(1, SOURCE);
        var expressions = new ArrayList<>(List.of(expression, expression));
        IrIsset check = new IrIsset(expressions, SOURCE);
        IrVariableTarget target = new IrVariableTarget("value", SOURCE);
        var targets = new ArrayList<IrAssignmentTarget>(List.of(target, target));
        IrUnset deletion = new IrUnset(targets, SOURCE);
        expressions.clear();
        targets.clear();
        assertEquals(List.of(expression, expression), check.expressions());
        assertEquals(List.of(target, target), deletion.targets());
        assertThrows(UnsupportedOperationException.class, check.expressions()::clear);
        assertThrows(UnsupportedOperationException.class, deletion.targets()::clear);
        expressions.add(null);
        targets.add(null);
        assertThrows(NullPointerException.class, () -> new IrIsset(expressions, SOURCE));
        assertThrows(NullPointerException.class, () -> new IrUnset(targets, SOURCE));
        IrIsset convertedCheck = assertInstanceOf(IrIsset.class, convert(parsedExpression("isset($a, $a)")));
        IrUnset convertedDeletion = assertInstanceOf(IrUnset.class, convert(parsedStatement("unset($a, $a);")));
        assertEquals(2, convertedCheck.expressions().size());
        assertEquals(2, convertedDeletion.targets().size());
        assertThrows(UnsupportedOperationException.class, convertedCheck.expressions()::clear);
        assertThrows(UnsupportedOperationException.class, convertedDeletion.targets()::clear);
    }

    // 按需转换类方法与函数不影响声明或 AST，内置结构在结果中不夹带 CUP 节点。
    @Test
    void convertsBuiltinBodiesWithoutMutatingDeclarations() throws ReflectiveOperationException {
        NodeProgram syntax = (NodeProgram) Main.parse("""
                <?php
                namespace Demo;
                class Handler {
                    public function run($value) {
                        unset($this->cache[$value]);
                        if (isset($value, $this->cache)) { print (string) $value; }
                        return empty($value) ? @include 'part.php' : eval('return 1;');
                    }
                }
                function run($value) {
                    require_once 'bootstrap.php';
                    if (empty($value)) { die(); }
                    return (bool) $value;
                }
                """);
        String before = syntax.toTreeString(false);
        PhpFile file = DeclarationExtractor.extract(syntax, "builtin-declarations.php");
        ClassLikeDefinition clazz = assertInstanceOf(ClassLikeDefinition.class,
                file.declarationIndex().findTopLevel(TopLevelKind.TYPE, "Demo\\Handler").getFirst());
        MethodDefinition method = assertInstanceOf(MethodDefinition.class,
                file.declarationIndex().findMembers(clazz.id(), MemberKind.METHOD, "run").getFirst());
        FunctionDefinition function = assertInstanceOf(FunctionDefinition.class,
                file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "Demo\\run").getFirst());
        List<ClassMember> membersBefore = List.copyOf(clazz.members());
        for (SyntaxBody body : List.of(method.body(), function.body())) {
            List<AstNode> statementsBefore = List.copyOf(body.statements());
            IrBlock result = SyntaxConverter.convertBody(body);
            assertEquals(body.source(), result.source());
            assertEquals(result, SyntaxConverter.convertBody(body));
            assertAllSourceIds(result, "builtin-declarations.php");
            assertNoAst(result, Collections.newSetFromMap(new IdentityHashMap<>()));
            assertEquals(statementsBefore, body.statements());
            for (int i = 0; i < statementsBefore.size(); i++) assertSame(statementsBefore.get(i), body.statements().get(i));
        }
        assertEquals(before, syntax.toTreeString(false));
        assertEquals(membersBefore, clazz.members());
        for (ClassMember member : membersBefore) assertSame(member, file.declarationIndex().findById(member.id()).orElseThrow());
        assertSame(clazz, file.declarationIndex().findTopLevel(TopLevelKind.TYPE, "Demo\\Handler").getFirst());
        assertSame(function, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "Demo\\run").getFirst());
    }

    // unset 的禁追加模式属于当前目标转换，失败或成功后都不能泄漏到独立赋值。
    @Test
    void keepsUnsetTargetModeLocalToEachConversion() {
        NodeStatement bad = parsedStatement("unset($value[]);");
        String before = bad.toTreeString(false);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class, () -> convert(bad));
        assertTrue(error.fieldPath().contains(".unsetVars[0]"), error.fieldPath());
        assertTrue(error.fieldPath().contains(".offset"), error.fieldPath());
        assertEquals("builtins.php", error.source().sourceId());
        assertFalse(error.reason().isBlank());
        assertEquals(before, bad.toTreeString(false));
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, convert(parsedExpression("$value[] = 1")));
        assertNull(assertInstanceOf(IrIndexTarget.class, assignment.target()).index());
        IrBlock body = SyntaxConverter.convertBody(new SyntaxBody(List.of(
                parsedStatement("unset($value[0]);"), parsedStatement("$value[] = 1;")), SOURCE));
        assertInstanceOf(IrUnset.class, body.statements().getFirst());
        IrAssignment next = assertInstanceOf(IrAssignment.class,
                assertInstanceOf(IrExpressionStatement.class, body.statements().get(1)).expression());
        assertNull(assertInstanceOf(IrIndexTarget.class, next.target()).index());
    }

    private static IrExpression convert(NodeExpr expression) {
        return SyntaxConverter.convertExpression(new SyntaxExpression(expression, SOURCE));
    }

    private static IrStatement convert(NodeStatement statement) {
        return SyntaxConverter.convertBody(new SyntaxBody(List.of(statement), SOURCE)).statements().getFirst();
    }

    private static void assertFailure(NodeExpr expression, String pathPart) {
        assertDiagnostic(assertThrows(SyntaxConversionException.class, () -> convert(expression)), pathPart);
    }

    private static void assertFailure(NodeStatement statement, String pathPart) {
        assertDiagnostic(assertThrows(SyntaxConversionException.class, () -> convert(statement)), pathPart);
    }

    private static void assertDiagnostic(SyntaxConversionException error, String pathPart) {
        assertEquals("builtins.php", error.source().sourceId());
        assertEquals(new SourceRange(4, 3, 4, 19), error.source().range());
        assertTrue(error.fieldPath().contains(pathPart), error.fieldPath());
        assertFalse(error.reason().isBlank());
        assertTrue(error.getMessage().contains("builtins.php"));
        assertTrue(error.getMessage().contains(error.fieldPath()));
    }

    private static ExpressionFailure failure(NodeExprWithoutVariable expression, String pathPart) {
        return new ExpressionFailure(nonVariable(expression), pathPart);
    }

    private record ExpressionFailure(NodeExpr expression, String pathPart) {}

    private static NodeExpr nonVariable(NodeExprWithoutVariable expression) {
        return new NodeExpr.ExprWithoutVariable(expression, LOCATION);
    }

    private static NodeExpr internal(NodeInternalFunctionsInYacc expression) {
        return nonVariable(new NodeExprWithoutVariable.InternalFunction(expression, LOCATION));
    }

    private static NodeString token(String value) {
        return new NodeString(value, LOCATION);
    }

    private static NodeExpr literal(ComplexLocation location) {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(new NodeString("1", location), location), location), location);
    }

    private static NodeVariable variable(ComplexLocation location) {
        return new NodeVariable.CallableVariable(new NodeCallableVariable.SimpleVar(
                new NodeSimpleVariable.NamedVar(new NodeString("value", location), location), location), location);
    }

    private static NodeExpr parsedExpression(String code) {
        return assertInstanceOf(NodeStatement.ExpressionStatement.class, parsedStatement(code + ";"))
                .getExpression();
    }

    private static NodeStatement parsedStatement(String code) {
        return ((NodeProgram) Main.parse("<?php " + code)).getStmts().getValue().getFirst().getStmt();
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
        assertFalse(value instanceof AstNode, "内置语言结构 IR 不应保留 CUP AST");
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
