package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SyntaxBody;
import top.kmar.php.model.SyntaxExpression;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证具名对象操作和可写访问链的结构，不解析类绑定或执行 PHP 对象操作。 */
class ObjectConversionTest {

    // 无括号与空括号构造统一为空参数列表，有参构造保留每个实参及其顺序。
    @Test
    void normalizesConstructorArgumentsWithoutEvaluatingThem() {
        for (String code : List.of("new Thing", "new Thing()")) {
            IrNew value = assertInstanceOf(IrNew.class, expression(code));
            assertNamedClass(value.classReference(), "Thing", NameForm.UNQUALIFIED);
            assertTrue(value.arguments().isEmpty());
        }
        IrNew value = assertInstanceOf(IrNew.class, expression("new Thing(first(), $x = 2, [3])"));
        assertEquals(3, value.arguments().size());
        assertCall(value.arguments().getFirst(), "first");
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, value.arguments().get(1));
        assertVariableTarget(assignment.target(), "x");
        assertInteger(assignment.value(), 2);
        IrArrayLiteral array = assertInstanceOf(IrArrayLiteral.class, value.arguments().get(2));
        assertEquals(1, array.entries().size());
        assertInteger(array.entries().getFirst().value(), 3);
    }

    // 类引用保留原始限定形式；带前缀的 self/parent 仍是普通名称，不提前解析。
    @Test
    void preservesQualifiedClassNamesAndTheirSpelling() {
        Map<String, NameForm> names = Map.of(
                "Thing", NameForm.UNQUALIFIED, "Demo\\Thing", NameForm.QUALIFIED,
                "\\Demo\\Thing", NameForm.FULLY_QUALIFIED, "namespace\\Thing", NameForm.NAMESPACE_RELATIVE,
                "\\self", NameForm.FULLY_QUALIFIED, "Demo\\parent", NameForm.QUALIFIED,
                "namespace\\self", NameForm.NAMESPACE_RELATIVE);
        names.forEach((name, form) -> {
            assertNamedClass(assertInstanceOf(IrNew.class, expression("new " + name)).classReference(), name, form);
            assertNamedClass(assertInstanceOf(IrInstanceOf.class, expression("$x instanceof " + name))
                    .classReference(), name, form);
            assertNamedClass(assertInstanceOf(IrStaticCall.class, expression(name + "::make()"))
                    .classReference(), name, form);
            assertNamedClass(assertInstanceOf(IrClassName.class, expression(name + "::class"))
                    .classReference(), name, form);
        });
    }

    // 未限定的特殊类名不区分大小写，在所有具名类操作中统一建模且不要求类上下文。
    @Test
    void representsSpecialClassReferencesWithoutBindingThem() {
        Map<String, SpecialClassKind> names = Map.of(
                "self", SpecialClassKind.SELF, "SeLf", SpecialClassKind.SELF,
                "parent", SpecialClassKind.PARENT, "PaReNt", SpecialClassKind.PARENT,
                "static", SpecialClassKind.STATIC, "StAtIc", SpecialClassKind.STATIC);
        names.forEach((name, kind) -> {
            assertSpecialClass(assertInstanceOf(IrNew.class, expression("new " + name)).classReference(), kind);
            assertSpecialClass(assertInstanceOf(IrInstanceOf.class, expression("$x instanceof " + name))
                    .classReference(), kind);
            assertSpecialClass(assertInstanceOf(IrStaticCall.class, expression(name + "::make()"))
                    .classReference(), kind);
            assertSpecialClass(assertInstanceOf(IrStaticPropertyAccess.class, expression(name + "::$value"))
                    .classReference(), kind);
            assertSpecialClass(assertInstanceOf(IrClassConstantReference.class, expression(name + "::VALUE"))
                    .classReference(), kind);
            assertSpecialClass(assertInstanceOf(IrClassName.class, expression(name + "::class"))
                    .classReference(), kind);
        });
    }

    // clone 与 instanceof 保留独立节点和原有运算分组，不能降级成普通一元或二元运算。
    @Test
    void preservesCloneAndInstanceofGrouping() {
        IrClone clone = assertInstanceOf(IrClone.class, expression("clone new Thing(1)"));
        IrNew constructed = assertInstanceOf(IrNew.class, clone.expression());
        assertNamedClass(constructed.classReference(), "Thing", NameForm.UNQUALIFIED);
        assertInteger(constructed.arguments().getFirst(), 1);

        IrUnary negation = assertInstanceOf(IrUnary.class, expression("!$value instanceof Thing"));
        assertEquals(UnaryOperator.NOT, negation.operator());
        IrInstanceOf check = assertInstanceOf(IrInstanceOf.class, negation.operand());
        assertVariable(check.expression(), "value");
        assertNamedClass(check.classReference(), "Thing", NameForm.UNQUALIFIED);
        IrInstanceOf copied = assertInstanceOf(IrInstanceOf.class, expression("clone $value instanceof Thing"));
        assertVariable(assertInstanceOf(IrClone.class, copied.expression()).expression(), "value");
    }

    // 属性、方法和下标的接收者按源码层级嵌套，$this 不需要单独的对象节点。
    @Test
    void preservesMixedInstanceReadAndCallChains() {
        IrPropertyAccess result = assertInstanceOf(IrPropertyAccess.class,
                expression("$this->service->Run(first(), 2)[next()]->Value"));
        assertEquals("Value", result.property());
        IrIndex index = assertInstanceOf(IrIndex.class, result.receiver());
        assertCall(index.index(), "next");
        IrMethodCall call = assertInstanceOf(IrMethodCall.class, index.base());
        assertEquals("Run", call.method());
        assertEquals(2, call.arguments().size());
        assertCall(call.arguments().getFirst(), "first");
        assertInteger(call.arguments().get(1), 2);
        IrPropertyAccess service = assertInstanceOf(IrPropertyAccess.class, call.receiver());
        assertEquals("service", service.property());
        assertVariable(service.receiver(), "this");

        IrMethodCall fresh = assertInstanceOf(IrMethodCall.class, expression("(new Thing)->run()"));
        assertEquals("run", fresh.method());
        assertTrue(fresh.arguments().isEmpty());
        assertInstanceOf(IrNew.class, fresh.receiver());
        assertInstanceOf(IrNew.class,
                assertInstanceOf(IrPropertyAccess.class, expression("(new Thing)->value")).receiver());
    }

    // 静态属性去掉变量前缀，静态方法参数有序，类常量仍保留名称而不是值。
    @Test
    void convertsStaticPropertiesCallsAndConstants() {
        IrStaticPropertyAccess property = assertInstanceOf(IrStaticPropertyAccess.class, expression("Box::$Items"));
        assertEquals("Items", property.property());
        assertNamedClass(property.classReference(), "Box", NameForm.UNQUALIFIED);
        IrStaticCall call = assertInstanceOf(IrStaticCall.class, expression("Box::Make(first(), second())"));
        assertEquals("Make", call.method());
        assertNamedClass(call.classReference(), "Box", NameForm.UNQUALIFIED);
        assertEquals(2, call.arguments().size());
        assertCall(call.arguments().getFirst(), "first");
        assertCall(call.arguments().get(1), "second");
        IrClassConstantReference constant = assertInstanceOf(IrClassConstantReference.class, expression("Box::Value"));
        assertEquals("Value", constant.constantName());
        assertNamedClass(constant.classReference(), "Box", NameForm.UNQUALIFIED);
        IrIndex read = assertInstanceOf(IrIndex.class, expression("Box::ITEMS[0]"));
        assertEquals("ITEMS", assertInstanceOf(IrClassConstantReference.class, read.base()).constantName());
        assertInteger(read.index(), 0);
    }

    // ::class 单独建模但 ::class() 仍是方法；半保留字成员名从类型化字段读取。
    @Test
    void distinguishesClassNamesFromMethodsAndKeepsSemiReservedMembers() {
        for (String name : List.of("class", "ClAsS")) {
            assertNamedClass(assertInstanceOf(IrClassName.class, expression("Box::" + name))
                    .classReference(), "Box", NameForm.UNQUALIFIED);
            IrStaticCall call = assertInstanceOf(IrStaticCall.class, expression("Box::" + name + "()"));
            assertEquals(name, call.method());
            assertTrue(call.arguments().isEmpty());
        }
        for (String name : List.of("public", "static", "yield", "__METHOD__")) {
            assertEquals(name, assertInstanceOf(IrStaticCall.class, expression("Box::" + name + "()")).method());
            assertEquals(name,
                    assertInstanceOf(IrClassConstantReference.class, expression("Box::" + name)).constantName());
            assertEquals(name, assertInstanceOf(IrMethodCall.class, expression("$a->" + name + "()")).method());
            assertEquals(name, assertInstanceOf(IrPropertyAccess.class, expression("$a->" + name)).property());
        }
    }

    // 读取和写入使用不同模型；属性链中的接收者是可写基底而不是读取表达式。
    @Test
    void separatesPropertyTargetsFromPropertyReads() {
        IrAssignment assignment = assignment("$this->child->value = Box::$value");
        IrPropertyTarget value = assertInstanceOf(IrPropertyTarget.class, assignment.target());
        assertEquals("value", value.property());
        IrPropertyTarget child = assertInstanceOf(IrPropertyTarget.class, value.receiver());
        assertEquals("child", child.property());
        assertVariableTarget(child.receiver(), "this");
        assertEquals("value", assertInstanceOf(IrStaticPropertyAccess.class, assignment.value()).property());

        IrAssignment staticWrite = assignment("Box::$value = $this->value");
        IrStaticPropertyTarget target = assertInstanceOf(IrStaticPropertyTarget.class, staticWrite.target());
        assertEquals("value", target.property());
        assertNamedClass(target.classReference(), "Box", NameForm.UNQUALIFIED);
        assertEquals("value", assertInstanceOf(IrPropertyAccess.class, staticWrite.value()).property());
    }

    // 属性、下标与追加在同一目标链里递归保存，括号和花括号不额外添加语义节点。
    @Test
    void preservesMixedPropertyIndexAndAppendTargets() {
        IrPropertyTarget property = assertInstanceOf(IrPropertyTarget.class, assignment("$a[]->p = 1").target());
        assertEquals("p", property.property());
        IrIndexTarget append = assertInstanceOf(IrIndexTarget.class, property.receiver());
        assertNull(append.index());
        assertVariableTarget(append.base(), "a");

        IrIndexTarget indexed = assertInstanceOf(IrIndexTarget.class, assignment("($a->items){next()}[] = 2").target());
        assertNull(indexed.index());
        IrIndexTarget middle = assertInstanceOf(IrIndexTarget.class, indexed.base());
        assertCall(middle.index(), "next");
        IrPropertyTarget items = assertInstanceOf(IrPropertyTarget.class, middle.base());
        assertEquals("items", items.property());
        assertVariableTarget(items.receiver(), "a");

        IrPropertyTarget staticChain = assertInstanceOf(IrPropertyTarget.class,
                assignment("Box::$items[]->p = 3").target());
        IrIndexTarget staticAppend = assertInstanceOf(IrIndexTarget.class, staticChain.receiver());
        assertNull(staticAppend.index());
        IrStaticPropertyTarget root = assertInstanceOf(IrStaticPropertyTarget.class, staticAppend.base());
        assertEquals("items", root.property());
        assertNamedClass(root.classReference(), "Box", NameForm.UNQUALIFIED);
    }

    // 调用结果可成为后续写访问的基底，但调用表达式本身并不是赋值目标。
    @Test
    void wrapsCallResultsOnlyAsWriteBases() {
        IrPropertyTarget property = assertInstanceOf(IrPropertyTarget.class,
                assignment("factory()->p = 1").target());
        assertEquals("p", property.property());
        assertCall(assertInstanceOf(IrExpressionWriteBase.class, property.receiver()).expression(), "factory");

        IrPropertyTarget indexed = assertInstanceOf(IrPropertyTarget.class,
                assignment("(factory())[next()]->p = 2").target());
        IrIndexTarget index = assertInstanceOf(IrIndexTarget.class, indexed.receiver());
        assertCall(index.index(), "next");
        assertCall(assertInstanceOf(IrExpressionWriteBase.class, index.base()).expression(), "factory");

        IrIndexTarget append = assertInstanceOf(IrIndexTarget.class, assignment("factory()[] = 3").target());
        assertNull(append.index());
        assertCall(assertInstanceOf(IrExpressionWriteBase.class, append.base()).expression(), "factory");
        IrPropertyTarget method = assertInstanceOf(IrPropertyTarget.class,
                assignment("$obj->make()->p = 4").target());
        IrMethodCall receiver = assertInstanceOf(IrMethodCall.class,
                assertInstanceOf(IrExpressionWriteBase.class, method.receiver()).expression());
        assertEquals("make", receiver.method());
        assertVariable(receiver.receiver(), "obj");
        IrIndexTarget staticResult = assertInstanceOf(IrIndexTarget.class,
                assignment("Box::make()[0] = 5").target());
        assertInteger(staticResult.index(), 0);
        assertEquals("make", assertInstanceOf(IrStaticCall.class,
                assertInstanceOf(IrExpressionWriteBase.class, staticResult.base()).expression()).method());
    }

    // 复合赋值和前后置更新共用目标转换，不能只在普通赋值中允许对象写链。
    @Test
    void sharesObjectTargetsAcrossEveryCompoundAndUpdateOperator() {
        Map<String, CompoundAssignmentOperator> operators = Map.ofEntries(
                Map.entry("+=", CompoundAssignmentOperator.ADD), Map.entry("-=", CompoundAssignmentOperator.SUBTRACT),
                Map.entry("*=", CompoundAssignmentOperator.MULTIPLY), Map.entry("/=", CompoundAssignmentOperator.DIVIDE),
                Map.entry("%=", CompoundAssignmentOperator.MODULO), Map.entry("**=", CompoundAssignmentOperator.POWER),
                Map.entry(".=", CompoundAssignmentOperator.CONCAT), Map.entry("<<=", CompoundAssignmentOperator.SHIFT_LEFT),
                Map.entry(">>=", CompoundAssignmentOperator.SHIFT_RIGHT), Map.entry("&=", CompoundAssignmentOperator.BITWISE_AND),
                Map.entry("|=", CompoundAssignmentOperator.BITWISE_OR), Map.entry("^=", CompoundAssignmentOperator.BITWISE_XOR));
        for (String code : List.of("$obj->p", "Box::$p", "$a[]->p", "factory()[next()]->p")) {
            IrAssignmentTarget expected = assignment(code + " = 1").target();
            IrAssignmentTarget expectedPrefix = assignment("  " + code + " = 1").target();
            operators.forEach((token, operator) -> {
                IrCompoundAssignment actual = assertInstanceOf(IrCompoundAssignment.class,
                        expression(code + " " + token + " 2"));
                assertEquals(operator, actual.operator());
                assertEquals(expected, actual.target(), code);
                assertInteger(actual.value(), 2);
            });
            for (String token : List.of("++", "--")) {
                IrUpdate prefix = assertInstanceOf(IrUpdate.class, expression(token + code));
                IrUpdate postfix = assertInstanceOf(IrUpdate.class, expression(code + token));
                assertEquals(token.equals("++") ? UpdateOperator.PRE_INCREMENT : UpdateOperator.PRE_DECREMENT,
                        prefix.operator());
                assertEquals(token.equals("++") ? UpdateOperator.POST_INCREMENT : UpdateOperator.POST_DECREMENT,
                        postfix.operator());
                assertEquals(expectedPrefix, prefix.target(), code);
                assertEquals(expected, postfix.target(), code);
            }
        }
    }

    // 目标中的调用、接收者与下标只保存一次，复合赋值不会展开成重复读写。
    @Test
    void keepsSideEffectingReceiverAndIndexSingleInObjectWrites() {
        IrCompoundAssignment assignment = assertInstanceOf(IrCompoundAssignment.class,
                expression("factory(first())->items[next()] += rhs()"));
        IrIndexTarget index = assertInstanceOf(IrIndexTarget.class, assignment.target());
        assertCall(index.index(), "next");
        IrPropertyTarget property = assertInstanceOf(IrPropertyTarget.class, index.base());
        assertEquals("items", property.property());
        IrCall factory = assertInstanceOf(IrCall.class,
                assertInstanceOf(IrExpressionWriteBase.class, property.receiver()).expression());
        assertEquals("factory", factory.name().spelling());
        assertEquals(1, factory.arguments().size());
        assertCall(factory.arguments().getFirst(), "first");
        assertCall(assignment.value(), "rhs");
    }

    // foreach 的键和值也复用对象写链，并允许调用结果及中间追加访问。
    @Test
    void convertsForeachObjectAndCallBasedTargets() {
        IrForeach loop = assertInstanceOf(IrForeach.class, body(
                "foreach ($items as Box::$keys[]->id => factory()[0]->value) ;").statements().getFirst());
        IrPropertyTarget key = assertInstanceOf(IrPropertyTarget.class, loop.keyTarget());
        assertEquals("id", key.property());
        IrIndexTarget append = assertInstanceOf(IrIndexTarget.class, key.receiver());
        assertNull(append.index());
        assertEquals("keys", assertInstanceOf(IrStaticPropertyTarget.class, append.base()).property());
        IrPropertyTarget value = assertInstanceOf(IrPropertyTarget.class, loop.valueTarget());
        assertEquals("value", value.property());
        IrIndexTarget index = assertInstanceOf(IrIndexTarget.class, value.receiver());
        assertInteger(index.index(), 0);
        assertCall(assertInstanceOf(IrExpressionWriteBase.class, index.base()).expression(), "factory");
    }

    // 写上下文仅沿访问基底传递，索引、实参和方法接收者内部始终使用普通读取。
    @Test
    void rejectsAppendAcrossReadBoundariesInsideWriteChains() {
        for (String code : List.of("$a[]->p", "$a[]->p ?? 1", "$a[]->m()->p = 1",
                "f($a[])->p = 1", "$a[$b[]]->p = 1", "$a->m($b[])->p = 1",
                "Box::make($a[])->p = 1", "new Box($a[])", "clone $a[]")) {
            assertRejected(code);
        }
    }

    // 名称值相同也不折叠动态写法，动态类/成员、匿名类和参数解包继续明确报错。
    @Test
    void rejectsDynamicNamesAnonymousClassesAndUnpackedArguments() {
        for (String code : List.of("new $class", "new $classes[0]", "$a instanceof $class",
                "$a->$name", "$a->{'value'}", "$a->$method()", "$a->{'run'}()",
                "$class::$p", "$class::run()", "$class::VALUE", "$class::class",
                "Box::$$p", "Box::${$p}", "Box::$method()", "Box::{'run'}()",
                "new class {}", "new Box(...$args)", "$a->run(...$args)", "Box::run(...$args)")) {
            assertRejected(code);
        }
    }

    // 常量、字面量、运算或临时对象不作为写根；调用虽可接后续访问但不能独立赋值。
    @Test
    void rejectsTemporaryWriteRootsAndDirectCallTargets() {
        for (String target : List.of("f()", "$a->run()", "Box::run()", "(new Box)->p",
                "(clone $a)->p", "($a + $b)->p", "ITEMS[0]->p", "Box::ITEMS[0]->p",
                "[1][0]->p", "'text'[0]->p")) {
            assertRejected(target + " = 1");
            assertRejected(target + " += 1");
            assertRejected(target + "++");
        }
    }

    private static IrAssignment assignment(String code) {
        return assertInstanceOf(IrAssignment.class, expression(code));
    }

    private static IrExpression expression(String code) {
        return SyntaxConverter.convertExpression(syntaxExpression(code));
    }

    private static SyntaxExpression syntaxExpression(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php " + code + ";");
        NodeExpr expression = parsed.getStmts().getValue().getFirst().getStmt().getExpression();
        return new SyntaxExpression(expression, new SourceInfo("objects.php", null));
    }

    private static IrBlock body(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php function f() { " + code + " }");
        var statements = parsed.getStmts().getValue().getFirst().getFunction().getStmts().getValue();
        return SyntaxConverter.convertBody(new SyntaxBody(List.copyOf(statements), new SourceInfo("objects.php", null)));
    }

    private static void assertRejected(String code) {
        SyntaxExpression syntax = assertDoesNotThrow(() -> syntaxExpression(code), code);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                () -> SyntaxConverter.convertExpression(syntax), code);
        assertEquals("objects.php", error.source().sourceId());
        assertFalse(error.fieldPath().isBlank(), code);
        assertFalse(error.reason().isBlank(), code);
    }

    private static void assertNamedClass(IrClassReference reference, String spelling, NameForm form) {
        IrNamedClassReference named = assertInstanceOf(IrNamedClassReference.class, reference);
        assertEquals(spelling, named.name().spelling());
        assertEquals(form, named.name().form());
    }

    private static void assertSpecialClass(IrClassReference reference, SpecialClassKind kind) {
        assertEquals(kind, assertInstanceOf(IrSpecialClassReference.class, reference).kind());
    }

    private static void assertCall(IrExpression expression, String name) {
        IrCall call = assertInstanceOf(IrCall.class, expression);
        assertEquals(name, call.name().spelling());
        assertTrue(call.arguments().isEmpty());
    }

    private static void assertVariable(IrExpression expression, String name) {
        assertEquals(name, assertInstanceOf(IrVariable.class, expression).name());
    }

    private static void assertVariableTarget(IrWriteBase base, String name) {
        assertEquals(name, assertInstanceOf(IrVariableTarget.class, base).name());
    }

    private static void assertInteger(IrExpression expression, long value) {
        assertEquals(value, assertInstanceOf(IrIntegerLiteral.class, expression).value());
    }
}
