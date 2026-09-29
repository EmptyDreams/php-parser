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

/** 验证对象 IR 的来源、损坏 AST 诊断、只读集合及声明模型隔离契约。 */
class ObjectConversionContractTest {
    private static final ComplexLocation LOCATION = ComplexLocation.of(4, 3, 4, 19);
    private static final SourceInfo SOURCE = new SourceInfo("objects.php", null);

    // 构造、克隆和类型判断缺少必需字段时明确失败；无构造括号不等于损坏参数列表。
    @Test
    void rejectsMissingObjectCreationAndTypeCheckFields() {
        NodeClassNameReference classReference = classReference(LOCATION);
        NodeExpr value = expression("$value");
        for (FailureCase test : List.of(
                failure(new NodeExprWithoutVariable.New(null, LOCATION), ".newExpr"),
                failure(new NodeExprWithoutVariable.New(new NodeNewExpr.New(null, null, LOCATION), LOCATION), ".clazz"),
                failure(new NodeExprWithoutVariable.New(new NodeNewExpr.New(
                        new NodeClassNameReference.ClassName(null, LOCATION), null, LOCATION), LOCATION), ".clazz"),
                failure(new NodeExprWithoutVariable.Clone(null, LOCATION), ".expr"),
                failure(new NodeExprWithoutVariable.Instanceof(null, token("instanceof"), classReference, LOCATION), ".left"),
                failure(new NodeExprWithoutVariable.Instanceof(value, token("instanceof"), null, LOCATION), ".classRef"),
                failure(new NodeExprWithoutVariable.Instanceof(value, null, classReference, LOCATION), ".op"),
                failure(new NodeExprWithoutVariable.Instanceof(value, token("is"), classReference, LOCATION), ".op"))) {
            //noinspection ThrowableNotThrown
            assertFailure(test.expression(), test.pathPart());
        }
        IrNew withoutParentheses = assertInstanceOf(IrNew.class, convert(expression("new C")));
        IrNew emptyParentheses = assertInstanceOf(IrNew.class, convert(expression("new C()")));
        assertTrue(withoutParentheses.arguments().isEmpty());
        assertTrue(emptyParentheses.arguments().isEmpty());
    }

    // 属性读写使用相同的字段检查，不能让缺失名称或接收者进入结果对象。
    @Test
    void rejectsMissingPropertyFieldsInReadAndWriteContexts() {
        NodeDereferencable receiver = receiver();
        NodePropertyName property = property();
        for (NodeVariable variable : List.of(
                new NodeVariable.PropertyAccess(null, property, LOCATION),
                new NodeVariable.PropertyAccess(receiver, null, LOCATION),
                new NodeVariable.PropertyAccess(receiver, new NodePropertyName.Name(null, LOCATION), LOCATION),
                new NodeVariable.StaticMember(null, LOCATION),
                new NodeVariable.StaticMember(new NodeStaticMember.StaticProperty(null, simpleVariable(), LOCATION), LOCATION),
                new NodeVariable.StaticMember(new NodeStaticMember.StaticProperty(className(LOCATION), null, LOCATION), LOCATION),
                new NodeVariable.StaticMember(new NodeStaticMember.StaticProperty(className(LOCATION),
                        new NodeSimpleVariable.NamedVar(null, LOCATION), LOCATION), LOCATION))) {
            SyntaxConversionException readError = assertFailure(variableExpression(variable), "expression.v");
            SyntaxConversionException writeError = assertFailure(nonVariable(
                    new NodeExprWithoutVariable.Assign(variable, expression("1"), LOCATION)), ".target");
            assertEquals(readError.reason(), writeError.reason());
        }
    }

    // 方法和静态调用的参数包装不能缺省，成员标识符包装也必须完整。
    @Test
    void rejectsMissingMethodAndStaticCallFields() {
        NodeArgumentList arguments = emptyArguments();
        NodeMemberName member = member();
        for (FailureCase test : List.of(
                new FailureCase(methodExpression(null, property(), arguments), ".d"),
                new FailureCase(methodExpression(receiver(), null, arguments), ".prop"),
                new FailureCase(methodExpression(receiver(), property(), null), ".args"),
                new FailureCase(staticCallExpression(null, member, arguments), ".clazz"),
                new FailureCase(staticCallExpression(className(LOCATION), null, arguments), ".member"),
                new FailureCase(staticCallExpression(className(LOCATION), member, null), ".args"),
                new FailureCase(staticCallExpression(className(LOCATION),
                        new NodeMemberName.IdentifierName(null, LOCATION), arguments), ".ident"),
                new FailureCase(staticCallExpression(className(LOCATION), new NodeMemberName.IdentifierName(
                        new NodeIdentifier.Identifier(null, LOCATION), LOCATION), arguments), ".name"),
                new FailureCase(staticCallExpression(className(LOCATION), new NodeMemberName.IdentifierName(
                        new NodeIdentifier.SemiReservedIdentifier(null, LOCATION), LOCATION), arguments), ".kw"),
                new FailureCase(staticCallExpression(className(LOCATION), new NodeMemberName.IdentifierName(
                        new NodeIdentifier.SemiReservedIdentifier(new NodeSemiReserved.Reserved(null, LOCATION), LOCATION),
                        LOCATION), arguments), ".keyword"))) {
            //noinspection ThrowableNotThrown
            assertFailure(test.expression(), test.pathPart());
        }
    }

    // 类引用和类常量的必需字段独立诊断，不能把无名称的节点当成 self 或空常量。
    @Test
    void rejectsMalformedClassReferencesAndClassConstants() {
        for (NodeClassName clazz : List.of(
                new NodeClassName.NamedClass(null, LOCATION),
                new NodeClassName.StaticClass(null, LOCATION),
                new NodeClassName.StaticClass(token("not-static"), LOCATION))) {
            //noinspection ThrowableNotThrown
            assertFailure(newExpression(new NodeClassNameReference.ClassName(clazz, LOCATION), null), ".clazz");
            //noinspection ThrowableNotThrown
            assertFailure(staticCallExpression(clazz, member(), emptyArguments()), ".clazz");
        }
        //noinspection ThrowableNotThrown
        assertFailure(constantExpression(new NodeConstant.ClassConstant(null,
                new NodeIdentifier.Identifier(token("VALUE"), LOCATION), LOCATION)), ".clazz");
        //noinspection ThrowableNotThrown
        assertFailure(constantExpression(new NodeConstant.ClassConstant(className(LOCATION), null, LOCATION)), ".member");
        //noinspection ThrowableNotThrown
        assertFailure(constantExpression(new NodeConstant.ClassConstant(className(LOCATION),
                new NodeIdentifier.Identifier(null, LOCATION), LOCATION)), ".name");
    }

    // 未知变体即使提供看似正确的字段也不应被当成已知对象语法。
    @Test
    void rejectsUnknownObjectStructuresWithoutGuessingTheirMeaning() {
        NodeNewExpr unknownNew = new NodeNewExpr() {
            @Override public NodeClassNameReference getClazz() { return classReference(LOCATION); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeClassNameReference unknownClassReference = new NodeClassNameReference() {
            @Override public NodeClassName getClazz() { return className(LOCATION); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeClassName unknownClassName = new NodeClassName() {
            @Override public NodeName getN() { return className(LOCATION).getN(); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodePropertyName unknownProperty = new NodePropertyName() {
            @Override public NodeString getName() { return token("value"); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeMemberName unknownMember = new NodeMemberName() {
            @Override public NodeIdentifier getIdent() { return member().getIdent(); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeIdentifier unknownIdentifier = new NodeIdentifier() {
            @Override public NodeString getName() { return token("VALUE"); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        NodeStaticMember unknownStaticMember = new NodeStaticMember() {
            @Override public NodeClassName getClazz() { return className(LOCATION); }
            @Override public NodeSimpleVariable getVar() { return simpleVariable(); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        for (NodeExpr invalid : List.of(
                nonVariable(new NodeExprWithoutVariable.New(unknownNew, LOCATION)),
                newExpression(unknownClassReference, null),
                newExpression(new NodeClassNameReference.ClassName(unknownClassName, LOCATION), null),
                variableExpression(new NodeVariable.PropertyAccess(receiver(), unknownProperty, LOCATION)),
                methodExpression(receiver(), unknownProperty, emptyArguments()),
                staticCallExpression(className(LOCATION), unknownMember, emptyArguments()),
                constantExpression(new NodeConstant.ClassConstant(className(LOCATION), unknownIdentifier, LOCATION)),
                variableExpression(new NodeVariable.StaticMember(unknownStaticMember, LOCATION)))) {
            //noinspection ThrowableNotThrown
            assertFailure(invalid, "expression");
        }
    }

    // 共用的实参转换必须在构造、实例方法和静态方法三条路径上拒绝损坏列表。
    @Test
    void rejectsMalformedArgumentListsForEveryObjectCallKind() {
        var nullEntry = new ArrayList<NodeArgument>();
        nullEntry.add(null);
        NodeArgumentList unknown = new NodeArgumentList() {
            @Override public NodeListNodeArgument getArgs() { return emptyArguments().getArgs(); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        };
        for (NodeArgumentList arguments : List.of(
                unknown,
                new NodeArgumentList.Args(null, LOCATION),
                new NodeArgumentList.Args(new NodeListNodeArgument(null, LOCATION), LOCATION),
                new NodeArgumentList.Args(new NodeListNodeArgument(nullEntry, LOCATION), LOCATION),
                new NodeArgumentList.Args(new NodeListNodeArgument(
                        List.of(new NodeArgument.Arg(null, LOCATION)), LOCATION), LOCATION))) {
            //noinspection ThrowableNotThrown
            assertFailure(newExpression(classReference(LOCATION), arguments), ".ctorArgs");
            //noinspection ThrowableNotThrown
            assertFailure(methodExpression(receiver(), property(), arguments), ".args");
            //noinspection ThrowableNotThrown
            assertFailure(staticCallExpression(className(LOCATION), member(), arguments), ".args");
        }
    }

    // 对象节点及写链各自继承原始 AST 范围，不用外层来源范围覆盖内部节点。
    @Test
    void preservesOriginalObjectAndWriteChainRanges() throws ReflectiveOperationException {
        NodeExpr original = expression("factory(1)[2]->value += new Vendor\\Box(3)");
        NodeExprWithoutVariable assignment = original.getEv();
        NodeVariable property = assignment.getTarget();
        NodeCallableVariable index = property.getD().getV().getCv();
        NodeFunctionCall call = index.getD().getV().getCv().getCall();
        NodeNewExpr constructor = assignment.getValue().getEv().getNewExpr();
        IrCompoundAssignment result = assertInstanceOf(IrCompoundAssignment.class, convert(original));
        IrPropertyTarget target = assertInstanceOf(IrPropertyTarget.class, result.target());
        IrIndexTarget indexTarget = assertInstanceOf(IrIndexTarget.class, target.receiver());
        IrExpressionWriteBase callBase = assertInstanceOf(IrExpressionWriteBase.class, indexTarget.base());
        IrCall callResult = assertInstanceOf(IrCall.class, callBase.expression());
        IrNew creation = assertInstanceOf(IrNew.class, result.value());

        assertEquals(sourceRange(assignment), result.source().range());
        assertEquals(sourceRange(property), target.source().range());
        assertEquals(sourceRange(index), indexTarget.source().range());
        assertEquals(sourceRange(index.getOffset()), indexTarget.index().source().range());
        assertEquals(sourceRange(call), callResult.source().range());
        assertEquals(callResult.source(), callBase.source());
        assertEquals(sourceRange(call.getArgs().getArgs().getValue().getFirst().getArg()),
                callResult.arguments().getFirst().source().range());
        assertEquals(sourceRange(constructor), creation.source().range());
        assertEquals(sourceRange(constructor.getClazz()), creation.classReference().source().range());
        assertEquals(sourceRange(constructor.getCtorArgs().getArgs().getValue().getFirst().getArg()),
                creation.arguments().getFirst().source().range());
        assertAllSourceIds(result, "objects.php");

        NodeExpr originalMethod = expression("$box->run(4)");
        NodeCallableVariable method = originalMethod.getV().getCv();
        IrMethodCall convertedMethod = assertInstanceOf(IrMethodCall.class, convert(originalMethod));
        assertEquals(sourceRange(method), convertedMethod.source().range());
        assertEquals(sourceRange(method.getD()), convertedMethod.receiver().source().range());
        assertAllSourceIds(convertedMethod, "objects.php");

        NodeExpr originalStatic = expression("Vendor\\Box::run(5)");
        NodeFunctionCall staticCall = originalStatic.getV().getCv().getCall();
        IrStaticCall convertedStatic = assertInstanceOf(IrStaticCall.class, convert(originalStatic));
        assertEquals(sourceRange(staticCall), convertedStatic.source().range());
        assertEquals(sourceRange(staticCall.getClazz()), convertedStatic.classReference().source().range());
        assertAllSourceIds(convertedStatic, "objects.php");
    }

    // 未知位置保持 null，合法零宽范围不丢弃；入口的备用范围不代替 AST 的真实位置。
    @Test
    void preservesUnknownAndZeroWidthObjectLocations() throws ReflectiveOperationException {
        for (ComplexLocation location : List.of(ComplexLocation.NO_LOCATION, ComplexLocation.of(8, 2, 8, 2))) {
            NodeNewExpr constructor = new NodeNewExpr.New(classReference(location), null, location);
            NodeExpr syntax = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.New(constructor, location), location);
            IrNew result = assertInstanceOf(IrNew.class, SyntaxConverter.convertExpression(new SyntaxExpression(syntax,
                    new SourceInfo("unknown-object.php", new SourceRange(99, 1, 99, 9)))));
            SourceRange expected = location.isNoLocation() ? null : new SourceRange(8, 2, 8, 2);
            assertEquals(expected, result.source().range());
            assertEquals(expected, result.classReference().source().range());
            assertEquals(expected, assertInstanceOf(IrNamedClassReference.class, result.classReference()).name().source().range());
            assertAllSourceIds(result, "unknown-object.php");
        }
    }

    // 三类对象调用都防御性复制参数，调用方修改原始列表或结果列表不会改变 IR。
    @Test
    void snapshotsAndFreezesObjectArguments() {
        SourceInfo source = new SourceInfo(null, null);
        IrClassReference clazz = new IrNamedClassReference(new NameReference("C", NameForm.UNQUALIFIED, source), source);
        var arguments = new ArrayList<IrExpression>(List.of(new IrIntegerLiteral(1, source)));
        IrNew constructor = new IrNew(clazz, arguments, source);
        IrMethodCall method = new IrMethodCall(new IrVariable("object", source), "run", arguments, source);
        IrStaticCall staticCall = new IrStaticCall(clazz, "run", arguments, source);
        arguments.clear();
        for (List<IrExpression> snapshot : List.of(constructor.arguments(), method.arguments(), staticCall.arguments())) {
            assertEquals(1, assertInstanceOf(IrIntegerLiteral.class, snapshot.getFirst()).value());
            assertThrows(UnsupportedOperationException.class, snapshot::clear);
        }
        for (String code : List.of("new C(1)", "$object->run(1)", "C::run(1)")) {
            IrExpression result = convert(expression(code));
            List<IrExpression> convertedArguments = switch (result) {
                case IrNew call -> call.arguments();
                case IrMethodCall call -> call.arguments();
                case IrStaticCall call -> call.arguments();
                default -> throw new AssertionError("预期对象调用，实际为 " + result);
            };
            assertThrows(UnsupportedOperationException.class, convertedArguments::clear);
        }
    }

    // 具名类的属性、静态成员、方法体与外部函数独立转换，不能改变原始声明和索引。
    @Test
    void convertsClassAndFunctionBodiesWithoutMutatingDeclarations() throws ReflectiveOperationException {
        NodeProgram parsed = (NodeProgram) Main.parse("""
                <?php
                namespace Demo;
                class Box {
                    public $value = 0;
                    public static $created = 0;
                    public const TYPE = self::class;
                    public function __construct($value) {
                        $this->value = $value;
                        self::$created++;
                    }
                    public function copy() {
                        $copy = clone $this;
                        $copy->value += 1;
                        return $copy;
                    }
                    public static function make($value) {
                        return new self($value);
                    }
                }
                function useBox($value) {
                    $box = Box::make($value);
                    $box->value += 2;
                    $copies[] = $box->copy();
                    return $box instanceof Box ? Box::TYPE : Box::class;
                }
                """);
        String before = parsed.toTreeString(false);
        PhpFile file = DeclarationExtractor.extract(parsed, "class-bodies.php");
        ClassLikeDefinition clazz = assertInstanceOf(ClassLikeDefinition.class,
                file.declarationIndex().findTopLevel(TopLevelKind.TYPE, "Demo\\Box").getFirst());
        FunctionDefinition function = assertInstanceOf(FunctionDefinition.class,
                file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "Demo\\useBox").getFirst());
        List<ClassMember> membersBefore = List.copyOf(clazz.members());
        MethodDefinition constructor = method(file, clazz, "__construct");
        MethodDefinition copy = method(file, clazz, "copy");
        MethodDefinition make = method(file, clazz, "make");
        IrBlock constructorBody = convertUnchanged(constructor.body());
        IrBlock copyBody = convertUnchanged(copy.body());
        IrBlock makeBody = convertUnchanged(make.body());
        IrBlock functionBody = convertUnchanged(function.body());

        assertEquals(2, constructorBody.statements().size());
        IrAssignment initialize = assertInstanceOf(IrAssignment.class, statementExpression(constructorBody, 0));
        IrPropertyTarget property = assertInstanceOf(IrPropertyTarget.class, initialize.target());
        assertEquals("this", assertInstanceOf(IrVariableTarget.class, property.receiver()).name());
        assertEquals("value", property.property());
        IrUpdate increment = assertInstanceOf(IrUpdate.class, statementExpression(constructorBody, 1));
        assertEquals(UpdateOperator.POST_INCREMENT, increment.operator());
        IrStaticPropertyTarget staticProperty = assertInstanceOf(IrStaticPropertyTarget.class, increment.target());
        assertEquals("created", staticProperty.property());
        assertEquals(SpecialClassKind.SELF,
                assertInstanceOf(IrSpecialClassReference.class, staticProperty.classReference()).kind());
        assertEquals(3, copyBody.statements().size());
        IrClone clone = assertInstanceOf(IrClone.class,
                assertInstanceOf(IrAssignment.class, statementExpression(copyBody, 0)).value());
        assertEquals("this", assertInstanceOf(IrVariable.class, clone.expression()).name());
        assertEquals(CompoundAssignmentOperator.ADD,
                assertInstanceOf(IrCompoundAssignment.class, statementExpression(copyBody, 1)).operator());
        assertEquals("copy", assertInstanceOf(IrVariable.class,
                assertInstanceOf(IrReturn.class, copyBody.statements().get(2)).value()).name());
        IrNew newSelf = assertInstanceOf(IrNew.class,
                assertInstanceOf(IrReturn.class, makeBody.statements().getFirst()).value());
        assertEquals(SpecialClassKind.SELF,
                assertInstanceOf(IrSpecialClassReference.class, newSelf.classReference()).kind());
        assertEquals("value", assertInstanceOf(IrVariable.class, newSelf.arguments().getFirst()).name());

        assertEquals(4, functionBody.statements().size());
        IrStaticCall call = assertInstanceOf(IrStaticCall.class,
                assertInstanceOf(IrAssignment.class, statementExpression(functionBody, 0)).value());
        assertEquals("Box", assertInstanceOf(IrNamedClassReference.class, call.classReference()).name().spelling());
        assertEquals("make", call.method());
        IrAssignment append = assertInstanceOf(IrAssignment.class, statementExpression(functionBody, 2));
        assertNull(assertInstanceOf(IrIndexTarget.class, append.target()).index());
        assertEquals("copy", assertInstanceOf(IrMethodCall.class, append.value()).method());
        IrConditional conditional = assertInstanceOf(IrConditional.class,
                assertInstanceOf(IrReturn.class, functionBody.statements().get(3)).value());
        assertInstanceOf(IrInstanceOf.class, conditional.condition());
        assertEquals("TYPE", assertInstanceOf(IrClassConstantReference.class, conditional.thenExpression()).constantName());
        assertInstanceOf(IrClassName.class, conditional.elseExpression());

        ClassConstantDefinition constant = assertInstanceOf(ClassConstantDefinition.class,
                file.declarationIndex().findMembers(clazz.id(), MemberKind.CONSTANT, "TYPE").getFirst());
        IrClassName typeValue = assertInstanceOf(IrClassName.class, SyntaxConverter.convertExpression(constant.value()));
        assertEquals(SpecialClassKind.SELF,
                assertInstanceOf(IrSpecialClassReference.class, typeValue.classReference()).kind());
        for (ClassMember member : membersBefore) {
            assertSame(member, file.declarationIndex().findById(member.id()).orElseThrow());
            if (member instanceof PropertyDefinition declaredProperty) {
                IrIntegerLiteral initial = assertInstanceOf(IrIntegerLiteral.class,
                        SyntaxConverter.convertExpression(declaredProperty.initialValue()));
                assertEquals(0, initial.value());
            }
        }
        for (Object result : List.of(constructorBody, copyBody, makeBody, functionBody, typeValue)) {
            assertAllSourceIds(result, "class-bodies.php");
            assertNoAst(result, Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        assertEquals(before, parsed.toTreeString(false));
        assertEquals(membersBefore, clazz.members());
        for (int i = 0; i < membersBefore.size(); i++) assertSame(membersBefore.get(i), clazz.members().get(i));
        assertSame(clazz, file.declarationIndex().findTopLevel(TopLevelKind.TYPE, "Demo\\Box").getFirst());
        assertSame(function, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "Demo\\useBox").getFirst());
    }

    private static IrBlock convertUnchanged(SyntaxBody syntax) {
        assertNotNull(syntax);
        List<AstNode> statements = List.copyOf(syntax.statements());
        IrBlock result = SyntaxConverter.convertBody(syntax);
        assertEquals(syntax.source(), result.source());
        assertEquals(statements, syntax.statements());
        for (int i = 0; i < statements.size(); i++) assertSame(statements.get(i), syntax.statements().get(i));
        assertEquals(result, SyntaxConverter.convertBody(syntax));
        return result;
    }

    private static MethodDefinition method(PhpFile file, ClassLikeDefinition clazz, String name) {
        return assertInstanceOf(MethodDefinition.class,
                file.declarationIndex().findMembers(clazz.id(), MemberKind.METHOD, name).getFirst());
    }

    private static IrExpression statementExpression(IrBlock body, int index) {
        return assertInstanceOf(IrExpressionStatement.class, body.statements().get(index)).expression();
    }

    private static NodeExpr expression(String code) {
        return ((NodeProgram) Main.parse("<?php " + code + ";"))
                .getStmts().getValue().getFirst().getStmt().getExpression();
    }

    private static IrExpression convert(NodeExpr syntax) {
        return SyntaxConverter.convertExpression(new SyntaxExpression(syntax, SOURCE));
    }

    private static SyntaxConversionException assertFailure(NodeExpr syntax, String pathPart) {
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class, () -> convert(syntax));
        assertEquals("objects.php", error.source().sourceId());
        assertEquals(new SourceRange(4, 3, 4, 19), error.source().range());
        assertTrue(error.fieldPath().contains(pathPart), error.fieldPath());
        assertFalse(error.reason().isBlank());
        assertTrue(error.getMessage().contains("objects.php"));
        assertTrue(error.getMessage().contains(error.fieldPath()));
        return error;
    }

    private static NodeExpr nonVariable(NodeExprWithoutVariable syntax) {
        return new NodeExpr.ExprWithoutVariable(syntax, LOCATION);
    }

    private static NodeExpr variableExpression(NodeVariable syntax) {
        return new NodeExpr.VariableExpr(syntax, LOCATION);
    }

    private static NodeExpr methodExpression(NodeDereferencable receiver, NodePropertyName property, NodeArgumentList arguments) {
        return variableExpression(new NodeVariable.CallableVariable(
                new NodeCallableVariable.MethodCall(receiver, property, arguments, LOCATION), LOCATION));
    }

    private static NodeExpr staticCallExpression(NodeClassName clazz, NodeMemberName member, NodeArgumentList arguments) {
        return variableExpression(new NodeVariable.CallableVariable(new NodeCallableVariable.FunctionCall(
                new NodeFunctionCall.StaticCall(clazz, member, arguments, LOCATION), LOCATION), LOCATION));
    }

    private static NodeExpr newExpression(NodeClassNameReference clazz, NodeArgumentList arguments) {
        return nonVariable(new NodeExprWithoutVariable.New(new NodeNewExpr.New(clazz, arguments, LOCATION), LOCATION));
    }

    private static NodeExpr constantExpression(NodeConstant constant) {
        return nonVariable(new NodeExprWithoutVariable.Scalar(new NodeScalar.Constant(constant, LOCATION), LOCATION));
    }

    private static NodeClassName className(ComplexLocation location) {
        return new NodeClassName.NamedClass(new NodeName.Unqualified(
                new NodeNamespaceName.Part(new NodeString("C", location), location), location), location);
    }

    private static NodeClassNameReference classReference(ComplexLocation location) {
        return new NodeClassNameReference.ClassName(className(location), location);
    }

    private static NodeString token(String value) {
        return new NodeString(value, LOCATION);
    }

    private static NodeSimpleVariable simpleVariable() {
        return new NodeSimpleVariable.NamedVar(token("object"), LOCATION);
    }

    private static NodeDereferencable receiver() {
        return new NodeDereferencable.Var(new NodeVariable.CallableVariable(
                new NodeCallableVariable.SimpleVar(simpleVariable(), LOCATION), LOCATION), LOCATION);
    }

    private static NodePropertyName property() {
        return new NodePropertyName.Name(token("value"), LOCATION);
    }

    private static NodeMemberName member() {
        return new NodeMemberName.IdentifierName(new NodeIdentifier.Identifier(token("run"), LOCATION), LOCATION);
    }

    private static NodeArgumentList emptyArguments() {
        return new NodeArgumentList.Args(new NodeListNodeArgument(List.of(), LOCATION), LOCATION);
    }

    private static FailureCase failure(NodeExprWithoutVariable expression, String pathPart) {
        return new FailureCase(nonVariable(expression), pathPart);
    }

    private record FailureCase(NodeExpr expression, String pathPart) {}

    private static SourceRange sourceRange(AstNode syntax) {
        var location = assertInstanceOf(ComplexLocation.class, syntax.getLocation());
        return new SourceRange(location.getStartLine(), location.getStartColumn(),
                location.getEndLine(), location.getEndColumn());
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
        assertFalse(value instanceof AstNode, "对象 IR 不应保留 CUP AST");
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
