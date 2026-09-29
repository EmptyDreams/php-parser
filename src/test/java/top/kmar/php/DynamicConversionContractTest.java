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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** 验证动态名称、调用目标与实参包装的诊断、来源和不可变结构契约。 */
class DynamicConversionContractTest {
    private static final ComplexLocation LOCATION = ComplexLocation.of(4, 3, 4, 29);
    private static final ComplexLocation INNER = ComplexLocation.of(5, 2, 5, 17);
    private static final ComplexLocation LEAF = ComplexLocation.of(6, 7, 6, 12);
    private static final SourceInfo SOURCE = new SourceInfo("dynamic.php", null);

    // 动态变量的名称表达式必须完整，读取与写入不能采用不同的损坏 AST 容错策略。
    @Test
    void rejectsMissingDynamicVariableFieldsInReadAndWriteContexts() {
        for (NodeSimpleVariable variable : List.of(
                new NodeSimpleVariable.IndirectVar(null, LOCATION),
                new NodeSimpleVariable.NestedVar(null, LOCATION),
                new NodeSimpleVariable.NamedVar(new NodeString("", LOCATION), LOCATION),
                new NodeSimpleVariable.NamedVar(new NodeString(null, LOCATION), LOCATION))) {
            String path = variable instanceof NodeSimpleVariable.IndirectVar ? ".e"
                    : variable instanceof NodeSimpleVariable.NestedVar ? ".nested" : ".var";
            assertFailure(variableExpression(simple(variable)), path);
            assertFailure(assignment(simple(variable)), path);
        }
    }

    // 计算属性名与计算方法名分别验证表达式或变量包装，不能静默退化为固定空名称。
    @Test
    void rejectsMissingDynamicMemberFields() {
        for (NodePropertyName property : List.of(
                new NodePropertyName.Expression(null, LOCATION),
                new NodePropertyName.Variable(null, LOCATION))) {
            String path = property instanceof NodePropertyName.Expression ? ".e" : ".var";
            NodeVariable access = new NodeVariable.PropertyAccess(receiver(), property, LOCATION);
            assertFailure(variableExpression(access), path);
            assertFailure(assignment(access), path);
            assertFailure(method(property, emptyArguments()), path);
        }
        for (NodeMemberName member : List.of(
                new NodeMemberName.ExpressionName(null, LOCATION),
                new NodeMemberName.VariableName(null, LOCATION))) {
            String path = member instanceof NodeMemberName.ExpressionName ? ".e" : ".var";
            assertFailure(call(new NodeFunctionCall.StaticCall(className(), member, emptyArguments(), LOCATION)), path);
            assertFailure(call(new NodeFunctionCall.StaticCallDynamic(receiver(), member, emptyArguments(), LOCATION)), path);
        }
    }

    // 动态静态属性的类接收者和名称缺失时，读写路径都产生带字段路径的转换异常。
    @Test
    void rejectsMissingDynamicStaticAccessFields() {
        for (NodeStaticMember member : List.of(
                new NodeStaticMember.DynamicStaticProperty(null, namedVariable(), LOCATION),
                new NodeStaticMember.DynamicStaticProperty(receiver(), null, LOCATION),
                new NodeStaticMember.DynamicStaticProperty(receiver(),
                        new NodeSimpleVariable.IndirectVar(null, LOCATION), LOCATION))) {
            NodeVariable variable = new NodeVariable.StaticMember(member, LOCATION);
            assertFailure(variableExpression(variable), "expression.v.sm");
            assertFailure(assignment(variable), ".target.sm");
        }
        assertFailure(call(new NodeFunctionCall.StaticCallDynamic(null, memberName(), emptyArguments(), LOCATION)), ".d");
        assertFailure(call(new NodeFunctionCall.StaticCallDynamic(receiver(), null, emptyArguments(), LOCATION)), ".member");
        assertFailure(call(new NodeFunctionCall.StaticCallDynamic(receiver(), memberName(), null, LOCATION)), ".args");
        assertFailure(constant(new NodeConstant.DynamicClassConstant(null, identifier(), LOCATION)), ".d");
        assertFailure(constant(new NodeConstant.DynamicClassConstant(receiver(), null, LOCATION)), ".member");
    }

    // new_variable 的六个分支均必须检查必需字段，类名称读取也不允许追加空下标。
    @Test
    void rejectsMissingFieldsInEveryDynamicClassVariableBranch() {
        NodeNewVariable nested = new NodeNewVariable.SimpleVar(namedVariable(), LOCATION);
        List<NewVariableFailure> failures = List.of(
                new NewVariableFailure(new NodeNewVariable.SimpleVar(null, LOCATION), ".sv"),
                new NewVariableFailure(new NodeNewVariable.Index(null, integer(1, LOCATION), LOCATION), ".nested"),
                new NewVariableFailure(new NodeNewVariable.Index(nested, null, LOCATION), ".offset"),
                new NewVariableFailure(new NodeNewVariable.CurlyIndex(null, integer(1, LOCATION), LOCATION), ".nested"),
                new NewVariableFailure(new NodeNewVariable.CurlyIndex(nested, null, LOCATION), ".e"),
                new NewVariableFailure(new NodeNewVariable.PropertyAccess(null, propertyName(), LOCATION), ".nested"),
                new NewVariableFailure(new NodeNewVariable.PropertyAccess(nested, null, LOCATION), ".prop"),
                new NewVariableFailure(new NodeNewVariable.StaticProperty(null, namedVariable(), LOCATION), ".clazz"),
                new NewVariableFailure(new NodeNewVariable.StaticProperty(className(), null, LOCATION), ".var"),
                new NewVariableFailure(new NodeNewVariable.DynamicStaticProperty(null, namedVariable(), LOCATION), ".nested"),
                new NewVariableFailure(new NodeNewVariable.DynamicStaticProperty(nested, null, LOCATION), ".var"));
        for (NewVariableFailure failure : failures) {
            var reference = new NodeClassNameReference.NewVariable(failure.variable(), LOCATION);
            assertFailure(creation(reference, null), failure.path());
            assertFailure(nonVariable(new NodeExprWithoutVariable.Instanceof(
                    integer(1, LOCATION), token("instanceof"), reference, LOCATION)), failure.path());
        }
        assertFailure(creation(new NodeClassNameReference.NewVariable(null, LOCATION), null), ".var");
    }

    // 动态调用的 callable 三个分支均需要实际子树，缺少参数包装不等于零实参。
    @Test
    void rejectsMissingDynamicCallAndCallableFields() {
        assertFailure(call(new NodeFunctionCall.CallDynamic(null, emptyArguments(), LOCATION)), ".callee");
        assertFailure(call(new NodeFunctionCall.CallDynamic(callable(), null, LOCATION)), ".args");
        for (NodeCallableExpr callee : List.of(
                new NodeCallableExpr.CallableVariable(null, LOCATION),
                new NodeCallableExpr.Paren(null, LOCATION),
                new NodeCallableExpr.Scalar(null, LOCATION))) {
            String path = callee instanceof NodeCallableExpr.CallableVariable ? ".cv"
                    : callee instanceof NodeCallableExpr.Paren ? ".e" : ".ds";
            assertFailure(call(new NodeFunctionCall.CallDynamic(callee, emptyArguments(), LOCATION)), path);
        }
    }

    // 未知变体即使伪装成提供合法字段，也不能凭 getter 猜测成已支持的动态语法。
    @Test
    void rejectsUnknownDynamicWrappers() {
        NodeSimpleVariable unknownVariable = new NodeSimpleVariable() {
            @Override public NodeExpr getE() { return integer(1, LOCATION); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodePropertyName unknownProperty = new NodePropertyName() {
            @Override public NodeExpr getE() { return integer(1, LOCATION); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeMemberName unknownMember = new NodeMemberName() {
            @Override public NodeSimpleVariable getVar() { return namedVariable(); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeNewVariable unknownNewVariable = new NodeNewVariable() {
            @Override public NodeSimpleVariable getSv() { return namedVariable(); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeCallableExpr unknownCallable = new NodeCallableExpr() {
            @Override public NodeExpr getE() { return integer(1, LOCATION); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeFunctionCall unknownCall = new NodeFunctionCall() {
            @Override public NodeCallableExpr getCallee() { return callable(); }
            @Override public NodeArgumentList getArgs() { return emptyArguments(); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        assertFailure(variableExpression(simple(unknownVariable)), ".sv");
        assertFailure(assignment(simple(unknownVariable)), ".sv");
        assertFailure(method(unknownProperty, emptyArguments()), ".prop");
        assertFailure(call(new NodeFunctionCall.StaticCallDynamic(receiver(), unknownMember, emptyArguments(), LOCATION)), ".member");
        assertFailure(creation(new NodeClassNameReference.NewVariable(unknownNewVariable, LOCATION), null), ".var");
        assertFailure(call(new NodeFunctionCall.CallDynamic(unknownCallable, emptyArguments(), LOCATION)), ".callee");
        assertFailure(call(unknownCall), ".call");
    }

    // 解包标记必须确实是三个点；损坏标记和缺失表达式不能被布尔标识掩盖。
    @Test
    void rejectsMalformedUnpackMarkersAndArguments() {
        for (NodeString marker : List.of(token(".."), token("...."), token(""), new NodeString(null, LOCATION))) {
            assertArgumentFailure(new NodeArgument.UnpackArg(marker, integer(1, LOCATION), LOCATION), ".op");
        }
        assertArgumentFailure(new NodeArgument.UnpackArg(null, integer(1, LOCATION), LOCATION), ".op");
        assertArgumentFailure(new NodeArgument.UnpackArg(token("..."), null, LOCATION), ".arg");
        assertArgumentFailure(new NodeArgument.Arg(null, LOCATION), ".arg");
        NodeArgument unknown = new NodeArgument() {
            @Override public NodeExpr getArg() { return integer(1, LOCATION); }
            @Override public NodeString getOp() { return token("..."); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        assertArgumentFailure(unknown, ".args[0]");
    }

    // 普通、动态、实例、静态及构造调用共享参数列表校验，不能漏掉新增调用入口。
    @Test
    void rejectsMalformedArgumentListsForEveryCallShape() {
        var nullEntry = new ArrayList<NodeArgument>();
        nullEntry.add(null);
        NodeArgumentList unknown = new NodeArgumentList() {
            @Override public NodeListNodeArgument getArgs() { return new NodeListNodeArgument(List.of(), LOCATION); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        for (NodeArgumentList arguments : List.of(unknown,
                new NodeArgumentList.Args(null, LOCATION),
                new NodeArgumentList.Args(new NodeListNodeArgument(null, LOCATION), LOCATION),
                new NodeArgumentList.Args(new NodeListNodeArgument(nullEntry, LOCATION), LOCATION))) {
            for (Function<NodeArgumentList, NodeExpr> factory : callFactories()) {
                assertFailure(factory.apply(arguments), "expression");
            }
        }
    }

    // 新增公开模型不接受缺失的必需字段，固定名称为空也应在模型边界立即失败。
    @Test
    void validatesRequiredDynamicModelFields() {
        IrExpression value = new IrIntegerLiteral(1, SOURCE);
        IrAccessName name = new IrFixedName("value", SOURCE);
        IrClassReference clazz = new IrDynamicClassReference(value, SOURCE);
        NameReference named = new NameReference("f", NameForm.UNQUALIFIED, SOURCE);
        IrCallTarget target = new IrNamedCallTarget(named, SOURCE);
        for (Executable invalid : List.<Executable>of(
                () -> new IrFixedName(null, SOURCE), () -> new IrFixedName("value", null),
                () -> new IrComputedName(null, SOURCE), () -> new IrComputedName(value, null),
                () -> new IrNamedCallTarget(null, SOURCE), () -> new IrNamedCallTarget(named, null),
                () -> new IrExpressionCallTarget(null, SOURCE), () -> new IrExpressionCallTarget(value, null),
                () -> new IrDynamicClassReference(null, SOURCE), () -> new IrDynamicClassReference(value, null),
                () -> new IrArgument(null, false, SOURCE), () -> new IrArgument(value, true, null),
                () -> new IrVariable(null, SOURCE), () -> new IrVariable(name, null),
                () -> new IrVariableTarget(null, SOURCE), () -> new IrVariableTarget(name, null),
                () -> new IrPropertyAccess(value, null, SOURCE),
                () -> new IrPropertyTarget(new IrVariableTarget(name, SOURCE), null, SOURCE),
                () -> new IrStaticPropertyAccess(clazz, null, SOURCE),
                () -> new IrStaticPropertyTarget(clazz, null, SOURCE),
                () -> new IrMethodCall(value, null, List.of(), SOURCE),
                () -> new IrStaticCall(clazz, null, List.of(), SOURCE),
                () -> new IrCall(null, List.of(), SOURCE), () -> new IrCall(target, null, SOURCE),
                () -> new IrCall(target, List.of(), null),
                () -> new IrCall(target, Collections.singletonList(null), SOURCE),
                () -> new IrMethodCall(value, name, Collections.singletonList(null), SOURCE),
                () -> new IrStaticCall(clazz, name, Collections.singletonList(null), SOURCE),
                () -> new IrNew(clazz, Collections.singletonList(null), SOURCE))) {
            assertThrows(NullPointerException.class, invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> new IrFixedName("", SOURCE));
    }

    // 每种调用都复制实参列表，普通与解包实参保持顺序且不会暴露可修改集合。
    @Test
    void snapshotsAndFreezesArgumentsForEveryCallModel() {
        IrExpression value = new IrIntegerLiteral(1, SOURCE);
        IrAccessName name = new IrFixedName("run", SOURCE);
        IrClassReference clazz = new IrDynamicClassReference(value, SOURCE);
        var arguments = new ArrayList<>(List.of(new IrArgument(value, false, SOURCE), new IrArgument(value, true, SOURCE)));
        List<IrExpression> calls = List.of(
                new IrCall(new IrNamedCallTarget(new NameReference("f", NameForm.UNQUALIFIED, SOURCE), SOURCE), arguments, SOURCE),
                new IrCall(new IrExpressionCallTarget(value, SOURCE), arguments, SOURCE),
                new IrMethodCall(value, name, arguments, SOURCE),
                new IrStaticCall(clazz, name, arguments, SOURCE),
                new IrNew(clazz, arguments, SOURCE));
        arguments.clear();
        for (IrExpression expression : calls) {
            List<IrArgument> snapshot = switch (expression) {
                case IrCall call -> call.arguments();
                case IrMethodCall call -> call.arguments();
                case IrStaticCall call -> call.arguments();
                case IrNew call -> call.arguments();
                default -> throw new AssertionError("预期调用或构造表达式");
            };
            assertEquals(2, snapshot.size());
            assertFalse(snapshot.getFirst().unpack());
            assertTrue(snapshot.get(1).unpack());
            assertSame(value, snapshot.getFirst().expression());
            assertThrows(UnsupportedOperationException.class, snapshot::clear);
        }
    }

    // 名称外壳、嵌套变量和最内层固定名称分别使用自己的 AST 来源，不相互覆盖。
    @Test
    void preservesDistinctDynamicNameOrigins() {
        NodeSimpleVariable leaf = new NodeSimpleVariable.NamedVar(new NodeString("name", LEAF), LEAF);
        NodeSimpleVariable nested = new NodeSimpleVariable.NestedVar(leaf, INNER);
        NodeSimpleVariable indirect = new NodeSimpleVariable.IndirectVar(variableExpression(simple(nested)), LOCATION);
        IrVariable variable = assertInstanceOf(IrVariable.class, convert(variableExpression(simple(indirect))));
        IrComputedName outerName = assertInstanceOf(IrComputedName.class, variable.name());
        IrVariable innerVariable = assertInstanceOf(IrVariable.class, outerName.expression());
        IrComputedName innerName = assertInstanceOf(IrComputedName.class, innerVariable.name());
        IrVariable leafVariable = assertInstanceOf(IrVariable.class, innerName.expression());
        assertSource(indirect, variable.source());
        assertSource(indirect, outerName.source());
        assertSource(nested, innerVariable.source());
        assertSource(nested, innerName.source());
        assertSource(leaf, leafVariable.source());
        assertSource(leaf, leafVariable.name().source());

        NodePropertyName property = new NodePropertyName.Expression(integer(1, LEAF), INNER);
        NodeVariable access = new NodeVariable.PropertyAccess(receiver(), property, LOCATION);
        IrPropertyAccess result = assertInstanceOf(IrPropertyAccess.class, convert(variableExpression(access)));
        assertSource(access, result.source());
        assertSource(property, result.property().source());
        assertSource(property.getE().getEv().getScalar(),
                assertInstanceOf(IrComputedName.class, result.property()).expression().source());
    }

    // 调用目标与实参都保留独立包装来源，解包标记范围不能覆盖参数值自身的范围。
    @Test
    void preservesDistinctCallTargetAndArgumentOrigins() {
        NodeCallableExpr callee = new NodeCallableExpr.Paren(integer(1, LEAF), INNER);
        NodeArgument argument = new NodeArgument.UnpackArg(token("..."), integer(2, LEAF), INNER);
        NodeFunctionCall syntax = new NodeFunctionCall.CallDynamic(callee, arguments(argument), LOCATION);
        IrCall result = assertInstanceOf(IrCall.class, convert(call(syntax)));
        IrExpressionCallTarget target = assertInstanceOf(IrExpressionCallTarget.class, result.target());
        assertSource(syntax, result.source());
        assertSource(callee, target.source());
        assertSource(callee.getE().getEv().getScalar(), target.expression().source());
        assertSource(argument, result.arguments().getFirst().source());
        assertSource(argument.getArg().getEv().getScalar(), result.arguments().getFirst().expression().source());
        assertTrue(result.arguments().getFirst().unpack());

        NodeName name = new NodeName.Unqualified(new NodeNamespaceName.Part(new NodeString("f", LEAF), LEAF), INNER);
        IrCall named = assertInstanceOf(IrCall.class, convert(call(new NodeFunctionCall.Call(name, arguments(
                new NodeArgument.Arg(integer(1, LEAF), INNER)), LOCATION))));
        assertSource(name, named.target().source());
        assertSource(name, assertInstanceOf(IrNamedCallTarget.class, named.target()).name().source());
        assertEquals(range(INNER), named.arguments().getFirst().source().range());
        assertEquals(range(LEAF), named.arguments().getFirst().expression().source().range());
    }

    // new/instanceof 使用类引用包装来源，静态访问使用接收者包装来源，内部读取保持独立。
    @Test
    void preservesDistinctDynamicClassOrigins() {
        NodeNewVariable variable = new NodeNewVariable.SimpleVar(
                new NodeSimpleVariable.NamedVar(new NodeString("clazz", LEAF), LEAF), INNER);
        NodeClassNameReference reference = new NodeClassNameReference.NewVariable(variable, LOCATION);
        IrNew created = assertInstanceOf(IrNew.class, convert(creation(reference, null)));
        IrDynamicClassReference newClass = assertInstanceOf(IrDynamicClassReference.class, created.classReference());
        assertSource(reference, newClass.source());
        assertEquals(range(LEAF), newClass.expression().source().range());
        IrInstanceOf check = assertInstanceOf(IrInstanceOf.class, convert(nonVariable(new NodeExprWithoutVariable.Instanceof(
                integer(1, LEAF), token("instanceof"), reference, LOCATION))));
        assertSource(reference, check.classReference().source());

        NodeDereferencable receiver = new NodeDereferencable.Paren(integer(1, LEAF), INNER);
        IrStaticCall call = assertInstanceOf(IrStaticCall.class, convert(call(
                new NodeFunctionCall.StaticCallDynamic(receiver, memberName(), emptyArguments(), LOCATION))));
        assertSource(receiver, call.classReference().source());
        assertEquals(range(LEAF), assertInstanceOf(IrDynamicClassReference.class, call.classReference()).expression().source().range());

        NodeNewVariable chain = new NodeNewVariable.DynamicStaticProperty(variable, namedVariable(), LOCATION);
        IrNew chained = assertInstanceOf(IrNew.class, convert(creation(
                new NodeClassNameReference.NewVariable(chain, LOCATION), null)));
        IrStaticPropertyAccess property = assertInstanceOf(IrStaticPropertyAccess.class,
                assertInstanceOf(IrDynamicClassReference.class, chained.classReference()).expression());
        assertSource(variable, property.classReference().source());
        assertEquals(range(LEAF), assertInstanceOf(IrDynamicClassReference.class, property.classReference()).expression().source().range());
    }

    // 位置未知和合法零宽位置均原样保留，入口提供的备用范围不能填充内部节点。
    @Test
    void preservesUnknownAndZeroWidthDynamicLocations() throws ReflectiveOperationException {
        for (ComplexLocation location : List.of(ComplexLocation.NO_LOCATION, ComplexLocation.of(8, 2, 8, 2))) {
            NodeExpr value = integer(1, location);
            NodeCallableExpr callee = new NodeCallableExpr.Paren(value, location);
            NodeArgument arg = new NodeArgument.UnpackArg(new NodeString("...", location), value, location);
            NodeArgumentList args = new NodeArgumentList.Args(new NodeListNodeArgument(List.of(arg), location), location);
            IrCall result = assertInstanceOf(IrCall.class, SyntaxConverter.convertExpression(new SyntaxExpression(
                    call(new NodeFunctionCall.CallDynamic(callee, args, location)),
                    new SourceInfo("dynamic.php", new SourceRange(99, 1, 99, 9)))));
            SourceRange expected = location.isNoLocation() ? null : range(location);
            assertEquals(expected, result.source().range());
            assertEquals(expected, result.target().source().range());
            assertEquals(expected, assertInstanceOf(IrExpressionCallTarget.class, result.target()).expression().source().range());
            assertEquals(expected, result.arguments().getFirst().source().range());
            assertEquals(expected, result.arguments().getFirst().expression().source().range());
            assertAllSourceIds(result, "dynamic.php");

            NodeSimpleVariable named = new NodeSimpleVariable.NamedVar(new NodeString("name", location), location);
            NodeSimpleVariable nested = new NodeSimpleVariable.NestedVar(named, location);
            IrVariable variable = assertInstanceOf(IrVariable.class, convert(variableExpression(simple(nested))));
            assertEquals(expected, variable.source().range());
            assertEquals(expected, variable.name().source().range());
            IrVariable nameValue = assertInstanceOf(IrVariable.class,
                    assertInstanceOf(IrComputedName.class, variable.name()).expression());
            assertEquals(expected, nameValue.source().range());
            assertEquals(expected, nameValue.name().source().range());

            NodeClassNameReference reference = new NodeClassNameReference.NewVariable(
                    new NodeNewVariable.SimpleVar(named, location), location);
            IrNew creation = assertInstanceOf(IrNew.class, convert(creation(reference, null)));
            assertEquals(expected, creation.classReference().source().range());
            assertEquals(expected, assertInstanceOf(IrDynamicClassReference.class,
                    creation.classReference()).expression().source().range());
            assertAllSourceIds(variable, "dynamic.php");
            assertAllSourceIds(creation, "dynamic.php");
        }
    }

    // 新模型不能泄露 CUP 节点，转换动态函数体也不能改变原始声明、索引和 AST。
    @Test
    void convertsDynamicBodiesWithoutMutatingDeclarationsOrLeakingAst() throws ReflectiveOperationException {
        NodeProgram parsed = (NodeProgram) Main.parse("""
                <?php
                namespace Demo;
                function useDynamic($class, $object, $method, $args) {
                    $$method = $object->{$method}(...$args);
                    $class::${$method} = new $class(...$args);
                    return ($object->{$method})(...$args);
                }
                """);
        String before = parsed.toTreeString(false);
        PhpFile file = DeclarationExtractor.extract(parsed, "dynamic.php");
        FunctionDefinition function = assertInstanceOf(FunctionDefinition.class,
                file.namespaceSections().getFirst().declarations().getFirst());
        SyntaxBody originalBody = function.body();
        IrBlock result = SyntaxConverter.convertBody(originalBody);
        assertEquals(3, result.statements().size());
        assertSame(originalBody, function.body());
        assertSame(function, file.declarationIndex().findById(function.id()).orElseThrow());
        assertEquals(before, parsed.toTreeString(false));
        assertAllSourceIds(result, "dynamic.php");
        assertNoAst(result, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static List<Function<NodeArgumentList, NodeExpr>> callFactories() {
        return List.of(
                args -> call(new NodeFunctionCall.Call(className().getN(), args, LOCATION)),
                args -> call(new NodeFunctionCall.CallDynamic(callable(), args, LOCATION)),
                args -> method(propertyName(), args),
                args -> call(new NodeFunctionCall.StaticCall(className(), memberName(), args, LOCATION)),
                args -> call(new NodeFunctionCall.StaticCallDynamic(receiver(), memberName(), args, LOCATION)),
                args -> creation(new NodeClassNameReference.NewVariable(
                        new NodeNewVariable.SimpleVar(namedVariable(), LOCATION), LOCATION), args));
    }

    private static void assertArgumentFailure(NodeArgument argument, String path) {
        for (Function<NodeArgumentList, NodeExpr> factory : callFactories()) {
            assertFailure(factory.apply(arguments(argument)), path);
        }
    }

    private static void assertFailure(NodeExpr syntax, String path) {
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class, () -> convert(syntax));
        assertEquals("dynamic.php", error.source().sourceId());
        assertEquals(range(LOCATION), error.source().range());
        assertTrue(error.fieldPath().contains(path), error.fieldPath());
        assertFalse(error.reason().isBlank());
        assertTrue(error.getMessage().contains("dynamic.php"));
        assertTrue(error.getMessage().contains(error.fieldPath()));
    }

    private static IrExpression convert(NodeExpr expression) {
        return SyntaxConverter.convertExpression(new SyntaxExpression(expression, SOURCE));
    }

    private static NodeExpr integer(int value, ComplexLocation location) {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(new NodeString(Integer.toString(value), location), location), location), location);
    }

    private static NodeExpr nonVariable(NodeExprWithoutVariable expression) {
        return new NodeExpr.ExprWithoutVariable(expression, LOCATION);
    }

    private static NodeExpr variableExpression(NodeVariable variable) {
        return new NodeExpr.VariableExpr(variable, LOCATION);
    }

    private static NodeExpr assignment(NodeVariable variable) {
        return nonVariable(new NodeExprWithoutVariable.Assign(variable, integer(1, LOCATION), LOCATION));
    }

    private static NodeVariable simple(NodeSimpleVariable variable) {
        return new NodeVariable.CallableVariable(new NodeCallableVariable.SimpleVar(variable, LOCATION), LOCATION);
    }

    private static NodeSimpleVariable namedVariable() {
        return new NodeSimpleVariable.NamedVar(token("name"), LOCATION);
    }

    private static NodeDereferencable receiver() {
        return new NodeDereferencable.Var(simple(namedVariable()), LOCATION);
    }

    private static NodeCallableExpr callable() {
        return new NodeCallableExpr.CallableVariable(new NodeCallableVariable.SimpleVar(namedVariable(), LOCATION), LOCATION);
    }

    private static NodePropertyName propertyName() {
        return new NodePropertyName.Name(token("value"), LOCATION);
    }

    private static NodeMemberName memberName() {
        return new NodeMemberName.IdentifierName(identifier(), LOCATION);
    }

    private static NodeIdentifier identifier() {
        return new NodeIdentifier.Identifier(token("run"), LOCATION);
    }

    private static NodeClassName className() {
        return new NodeClassName.NamedClass(new NodeName.Unqualified(
                new NodeNamespaceName.Part(token("Box"), LOCATION), LOCATION), LOCATION);
    }

    private static NodeExpr method(NodePropertyName property, NodeArgumentList arguments) {
        return variableExpression(new NodeVariable.CallableVariable(
                new NodeCallableVariable.MethodCall(receiver(), property, arguments, LOCATION), LOCATION));
    }

    private static NodeExpr call(NodeFunctionCall call) {
        return variableExpression(new NodeVariable.CallableVariable(new NodeCallableVariable.FunctionCall(call, LOCATION), LOCATION));
    }

    private static NodeExpr creation(NodeClassNameReference clazz, NodeArgumentList arguments) {
        return nonVariable(new NodeExprWithoutVariable.New(new NodeNewExpr.New(clazz, arguments, LOCATION), LOCATION));
    }

    private static NodeExpr constant(NodeConstant constant) {
        return nonVariable(new NodeExprWithoutVariable.Scalar(new NodeScalar.Constant(constant, LOCATION), LOCATION));
    }

    private static NodeArgumentList arguments(NodeArgument argument) {
        return new NodeArgumentList.Args(new NodeListNodeArgument(List.of(argument), LOCATION), LOCATION);
    }

    private static NodeArgumentList emptyArguments() {
        return new NodeArgumentList.Args(new NodeListNodeArgument(List.of(), LOCATION), LOCATION);
    }

    private static NodeString token(String text) {
        return new NodeString(text, LOCATION);
    }

    private static void assertSource(AstNode expected, SourceInfo actual) {
        assertEquals("dynamic.php", actual.sourceId());
        ComplexLocation location = assertInstanceOf(ComplexLocation.class, expected.getLocation());
        assertEquals(location.isNoLocation() ? null : range(location), actual.range());
    }

    private static SourceRange range(ComplexLocation location) {
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

    private static void assertNoAst(Object value, Set<Object> visited) throws ReflectiveOperationException {
        if (value == null || !visited.add(value)) return;
        if (value instanceof ByteString bytes) {
            byte[] snapshot = bytes.toByteArray();
            assertEquals(snapshot.length, bytes.size());
            if (snapshot.length != 0) {
                byte first = snapshot[0];
                snapshot[0] ^= 1;
                assertEquals(first, bytes.byteAt(0));
            }
            return;
        }
        assertFalse(value instanceof AstNode, "动态 IR 不应保留 CUP AST");
        if (value instanceof List<?> list) {
            for (Object child : list) assertNoAst(child, visited);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertNoAst(component.getAccessor().invoke(value), visited);
            }
        } else {
            assertTrue(value instanceof String || value instanceof Enum<?> || value instanceof Number || value instanceof Boolean,
                    "未预期的结果字段类型：" + value.getClass());
        }
    }

    private record NewVariableFailure(NodeNewVariable variable, String path) {}
}
