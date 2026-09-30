package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SyntaxBody;
import top.kmar.php.model.SyntaxExpression;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证动态访问、动态调用和实参解包的结构，不执行名称求值或运行时合法性检查。 */
class DynamicConversionTest {

    // 普通变量使用固定名称，变量变量的每一层都保留一次独立读取。
    @Test
    void preservesEveryLayerOfIndirectVariables() {
        assertVariable(expression("$name"), "name");
        IrVariable twice = assertInstanceOf(IrVariable.class, expression("$$name"));
        assertVariable(computed(twice.name()), "name");
        IrVariable deepest = assertInstanceOf(IrVariable.class, expression("$$$$name"));
        for (int level = 0; level < 3; level++) {
            deepest = assertInstanceOf(IrVariable.class, computed(deepest.name()));
        }
        assertFixed(deepest.name(), "name");

        IrVariable indirect = assertInstanceOf(IrVariable.class, expression("${choose()}"));
        assertCall(computed(indirect.name()), "choose");
        IrVariable literal = assertInstanceOf(IrVariable.class, expression("${'name'}"));
        assertString(computed(literal.name()), "name");

        // 花括号决定下标参与名称计算，还是读取计算名称对应的变量后再取下标。
        IrVariable indexedName = assertInstanceOf(IrVariable.class, expression("${$names[0]}"));
        IrIndex nameIndex = assertInstanceOf(IrIndex.class, computed(indexedName.name()));
        assertVariable(nameIndex.base(), "names");
        assertInteger(nameIndex.index(), 0);
        IrIndex indexedValue = assertInstanceOf(IrIndex.class, expression("${$names}[0]"));
        assertVariable(computed(assertInstanceOf(IrVariable.class, indexedValue.base()).name()), "names");
        assertInteger(indexedValue.index(), 0);
    }

    // 实例属性和方法中的 $p 都表示读取变量作为名称，而不是固定名称 p。
    @Test
    void distinguishesFixedAndComputedInstanceSelectors() {
        assertFixed(assertInstanceOf(IrPropertyAccess.class, expression("$obj->p")).property(), "p");
        IrPropertyAccess property = assertInstanceOf(IrPropertyAccess.class, expression("$obj->$p"));
        assertVariable(property.receiver(), "obj");
        assertVariable(computed(property.property()), "p");
        assertCall(computed(assertInstanceOf(IrPropertyAccess.class,
                expression("$obj->{propertyName()}")).property()), "propertyName");
        IrMethodCall method = assertInstanceOf(IrMethodCall.class, expression("$obj->$p()"));
        assertVariable(method.receiver(), "obj");
        assertVariable(computed(method.method()), "p");
        assertTrue(method.arguments().isEmpty());
        assertString(computed(assertInstanceOf(IrMethodCall.class,
                expression("$obj->{'run'}()")).method()), "run");
        IrVariable nested = assertInstanceOf(IrVariable.class, computed(assertInstanceOf(IrPropertyAccess.class,
                expression("$obj->$$p")).property()));
        assertVariable(computed(nested.name()), "p");
    }

    // 静态属性 C::$p 的名称固定，但 C::$p() 的方法名来自读取 $p。
    @Test
    void distinguishesStaticPropertyNamesFromMethodNames() {
        IrStaticPropertyAccess fixed = assertInstanceOf(IrStaticPropertyAccess.class, expression("Box::$p"));
        assertFixed(fixed.property(), "p");
        assertNamedClass(fixed.classReference(), "Box");
        for (String code : List.of("Box::$$p", "Box::${$p}")) {
            IrStaticPropertyAccess dynamic = assertInstanceOf(IrStaticPropertyAccess.class, expression(code));
            assertVariable(computed(dynamic.property()), "p");
        }
        IrStaticCall method = assertInstanceOf(IrStaticCall.class, expression("Box::$p()"));
        assertVariable(computed(method.method()), "p");
        assertNamedClass(method.classReference(), "Box");
        assertCall(computed(assertInstanceOf(IrStaticCall.class,
                expression("Box::{methodName()}()")).method()), "methodName");
        assertString(computed(assertInstanceOf(IrStaticPropertyAccess.class,
                expression("Box::${'p'}")).property()), "p");
    }

    // 属性值作为 callable 与对象方法调用不同，不能因名称相同而合并节点。
    @Test
    void distinguishesCallingAPropertyValueFromCallingAMethod() {
        IrCall propertyCall = assertInstanceOf(IrCall.class, expression("($obj->$field)()"));
        IrPropertyAccess property = assertInstanceOf(IrPropertyAccess.class, callable(propertyCall));
        assertVariable(property.receiver(), "obj");
        assertVariable(computed(property.property()), "field");
        assertTrue(propertyCall.arguments().isEmpty());
        IrMethodCall method = assertInstanceOf(IrMethodCall.class, expression("$obj->$field()"));
        assertVariable(computed(method.method()), "field");

        IrCall staticPropertyCall = assertInstanceOf(IrCall.class, expression("(Box::$field)()"));
        assertFixed(assertInstanceOf(IrStaticPropertyAccess.class,
                callable(staticPropertyCall)).property(), "field");
        assertVariable(computed(assertInstanceOf(IrStaticCall.class,
                expression("Box::$field()")).method()), "field");
    }

    // new_variable 的六种文法分支均作为动态类引用中的读取表达式保存。
    @Test
    void convertsEveryDynamicConstructionPath() {
        assertVariable(dynamicClass(constructed("new $type").classReference()), "type");
        for (String code : List.of("new $types[0]", "new $types{0}")) {
            IrIndex index = assertInstanceOf(IrIndex.class, dynamicClass(constructed(code).classReference()));
            assertVariable(index.base(), "types");
            assertInteger(index.index(), 0);
        }
        IrPropertyAccess property = assertInstanceOf(IrPropertyAccess.class,
                dynamicClass(constructed("new $box->$field").classReference()));
        assertVariable(property.receiver(), "box");
        assertVariable(computed(property.property()), "field");
        IrStaticPropertyAccess namedStatic = assertInstanceOf(IrStaticPropertyAccess.class,
                dynamicClass(constructed("new Box::$type").classReference()));
        assertNamedClass(namedStatic.classReference(), "Box");
        assertFixed(namedStatic.property(), "type");
        IrStaticPropertyAccess dynamicStatic = assertInstanceOf(IrStaticPropertyAccess.class,
                dynamicClass(constructed("new $box::$type").classReference()));
        assertVariable(dynamicClass(dynamicStatic.classReference()), "box");
        assertFixed(dynamicStatic.property(), "type");
    }

    // 动态类路径可以混合下标、属性和静态属性，构造实参仍附着于最外层 new。
    @Test
    void preservesNestedDynamicClassReadChains() {
        IrNew creation = assertInstanceOf(IrNew.class, expression("new $classes[next()]->$holder::$$type(first())"));
        IrStaticPropertyAccess staticProperty = assertInstanceOf(IrStaticPropertyAccess.class,
                dynamicClass(creation.classReference()));
        assertVariable(computed(staticProperty.property()), "type");
        IrPropertyAccess property = assertInstanceOf(IrPropertyAccess.class,
                dynamicClass(staticProperty.classReference()));
        assertVariable(computed(property.property()), "holder");
        IrIndex classes = assertInstanceOf(IrIndex.class, property.receiver());
        assertVariable(classes.base(), "classes");
        assertCall(classes.index(), "next");
        assertEquals(1, creation.arguments().size());
        assertFalse(creation.arguments().getFirst().unpack());
        assertCall(creation.arguments().getFirst().expression(), "first");
    }

    // 所有允许类引用的位置共用动态类模型，类常量与 ::class 保留原有专用节点。
    @Test
    void sharesDynamicClassReferencesAcrossObjectOperations() {
        IrInstanceOf check = assertInstanceOf(IrInstanceOf.class, expression("$value instanceof $type"));
        assertVariable(check.expression(), "value");
        assertVariable(dynamicClass(check.classReference()), "type");
        IrStaticPropertyAccess property = assertInstanceOf(IrStaticPropertyAccess.class, expression("$type::$p"));
        assertVariable(dynamicClass(property.classReference()), "type");
        assertFixed(property.property(), "p");
        IrStaticCall call = assertInstanceOf(IrStaticCall.class, expression("$type::$method()"));
        assertVariable(dynamicClass(call.classReference()), "type");
        assertVariable(computed(call.method()), "method");
        IrClassConstantReference constant = assertInstanceOf(IrClassConstantReference.class,
                expression("$type::VALUE"));
        assertVariable(dynamicClass(constant.classReference()), "type");
        assertEquals("VALUE", constant.constantName());
        for (String spelling : List.of("class", "ClAsS")) {
            assertVariable(dynamicClass(assertInstanceOf(IrClassName.class,
                    expression("$type::" + spelling)).classReference()), "type");
        }
    }

    // 计算式类名即使写着 self/static，也不转换为特殊类引用或进行名称绑定。
    @Test
    void keepsComputedClassNamesDistinctFromNamedReferences() {
        assertEquals(SpecialClassKind.SELF, assertInstanceOf(IrSpecialClassReference.class,
                constructed("new self").classReference()).kind());
        assertString(dynamicClass(assertInstanceOf(IrClassName.class,
                expression("'self'::class")).classReference()), "self");
        assertString(dynamicClass(assertInstanceOf(IrStaticCall.class,
                expression("('static')::run()")).classReference()), "static");
        assertCall(dynamicClass(assertInstanceOf(IrStaticPropertyAccess.class,
                expression("(chooseClass())::$p")).classReference()), "chooseClass");
        IrArrayLiteral array = assertInstanceOf(IrArrayLiteral.class,
                dynamicClass(assertInstanceOf(IrClassName.class, expression("[]::class")).classReference()));
        assertTrue(array.entries().isEmpty());
    }

    // 具名函数保留名称形式，变量、括号、字符串和数组 callable 则保留其表达式。
    @Test
    void distinguishesNamedAndExpressionCallTargets() {
        for (String name : List.of("run", "Tools\\run", "\\Tools\\run", "namespace\\run")) {
            IrCall call = assertInstanceOf(IrCall.class, expression(name + "()"));
            assertEquals(name, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
            assertTrue(call.arguments().isEmpty());
        }
        assertVariable(callable(assertInstanceOf(IrCall.class, expression("$callback()"))), "callback");
        assertVariable(callable(assertInstanceOf(IrCall.class, expression("($callback)()"))), "callback");
        assertString(callable(assertInstanceOf(IrCall.class, expression("'run'()"))), "run");
        for (String code : List.of("[$obj, 'run']()", "array($obj, 'run')()")) {
            IrArrayLiteral array = assertInstanceOf(IrArrayLiteral.class,
                    callable(assertInstanceOf(IrCall.class, expression(code))));
            assertEquals(2, array.entries().size());
            assertVariable(assertInstanceOf(IrValueArrayEntry.class, array.entries().getFirst()).value(), "obj");
            assertString(assertInstanceOf(IrValueArrayEntry.class, array.entries().get(1)).value(), "run");
        }
        assertInteger(callable(assertInstanceOf(IrCall.class, expression("(1)()"))), 1);
    }

    // 下标 callable、方法返回值和连续调用保留各自层级，不提前推断其可调用性。
    @Test
    void preservesIndexedAndChainedCallables() {
        for (String code : List.of("$callbacks[next()]()", "$callbacks{next()}()")) {
            IrIndex index = assertInstanceOf(IrIndex.class,
                    callable(assertInstanceOf(IrCall.class, expression(code))));
            assertVariable(index.base(), "callbacks");
            assertCall(index.index(), "next");
        }
        IrIndex constantIndex = assertInstanceOf(IrIndex.class,
                callable(assertInstanceOf(IrCall.class, expression("CALLBACKS[0]()"))));
        assertInstanceOf(IrConstantReference.class, constantIndex.base());
        assertInteger(constantIndex.index(), 0);
        IrCall outer = assertInstanceOf(IrCall.class, expression("factory()()(last())"));
        IrCall middle = assertInstanceOf(IrCall.class, callable(outer));
        assertCall(callable(middle), "factory");
        assertTrue(middle.arguments().isEmpty());
        assertEquals(1, outer.arguments().size());
        assertCall(outer.arguments().getFirst().expression(), "last");
        assertInstanceOf(IrMethodCall.class,
                callable(assertInstanceOf(IrCall.class, expression("$obj->$method()()"))));
    }

    // 每种调用的普通参数和多个解包参数都按原顺序保存，解包后普通参数也不做运行时校验。
    @Test
    void preservesMixedArgumentsAcrossEveryCallFamily() {
        for (String callee : List.of("run", "$callback", "$obj->run", "$obj->$method",
                "Box::run", "Box::$method", "$type::run", "$type::$method", "new Box", "new $type")) {
            List<IrArgument> arguments = arguments(expression(callee + "(first(), ...$one, ...second(), last())"));
            assertEquals(4, arguments.size(), callee);
            assertEquals(List.of(false, true, true, false), arguments.stream().map(IrArgument::unpack).toList());
            assertCall(arguments.getFirst().expression(), "first");
            assertVariable(arguments.get(1).expression(), "one");
            assertCall(arguments.get(2).expression(), "second");
            assertCall(arguments.get(3).expression(), "last");
            assertTrue(arguments(expression(callee + "()")).isEmpty(), callee);
            List<IrArgument> ordinary = arguments(expression(callee + "(1, 2)"));
            assertEquals(List.of(false, false), ordinary.stream().map(IrArgument::unpack).toList());
            assertInteger(ordinary.getFirst().expression(), 1);
            assertInteger(ordinary.get(1).expression(), 2);
        }
    }

    // 动态变量、实例属性和静态属性均进入现有写目标层次，而不是先转为读取再赋值。
    @Test
    void convertsComputedAssignmentTargets() {
        IrVariableTarget variable = assertInstanceOf(IrVariableTarget.class, assignment("$$name = 1").target());
        assertVariable(computed(variable.name()), "name");
        IrVariableTarget indirect = assertInstanceOf(IrVariableTarget.class, assignment("${next()} = 2").target());
        assertCall(computed(indirect.name()), "next");
        IrIndexTarget indexed = assertInstanceOf(IrIndexTarget.class, assignment("$$items[0] = 2").target());
        assertVariable(computed(assertInstanceOf(IrVariableTarget.class, indexed.base()).name()), "items");
        assertInteger(indexed.index(), 0);
        IrPropertyTarget property = assertInstanceOf(IrPropertyTarget.class,
                assignment("$obj->$field = 3").target());
        assertFixed(assertInstanceOf(IrVariableTarget.class, property.receiver()).name(), "obj");
        assertVariable(computed(property.property()), "field");
        IrStaticPropertyTarget staticProperty = assertInstanceOf(IrStaticPropertyTarget.class,
                assignment("$type::$$field = 4").target());
        assertVariable(dynamicClass(staticProperty.classReference()), "type");
        assertVariable(computed(staticProperty.property()), "field");
    }

    // 复合赋值和前后置更新共享动态目标转换，不丢失名称的求值结构。
    @Test
    void convertsComputedCompoundAssignmentsAndUpdates() {
        for (String target : List.of("$$name", "$obj->$field", "$type::$$field", "$callbacks()[0]->$field")) {
            IrAssignmentTarget expected = assignment(target + " = 1").target();
            IrAssignmentTarget prefixExpected = assignment("  " + target + " = 1").target();
            IrCompoundAssignment compound = assertInstanceOf(IrCompoundAssignment.class,
                    expression(target + " += 1"));
            assertEquals(CompoundAssignmentOperator.ADD, compound.operator());
            assertEquals(expected, compound.target(), target);
            for (String token : List.of("++", "--")) {
                IrUpdate prefix = assertInstanceOf(IrUpdate.class, expression(token + target));
                IrUpdate postfix = assertInstanceOf(IrUpdate.class, expression(target + token));
                assertEquals(prefixExpected, prefix.target(), target);
                assertEquals(expected, postfix.target(), target);
                assertEquals(token.equals("++") ? UpdateOperator.PRE_INCREMENT : UpdateOperator.PRE_DECREMENT,
                        prefix.operator());
                assertEquals(token.equals("++") ? UpdateOperator.POST_INCREMENT : UpdateOperator.POST_DECREMENT,
                        postfix.operator());
            }
        }
    }

    // foreach 与 unset 也接收动态目标，且不把名称表达式预先求值成字符串。
    @Test
    void convertsComputedForeachAndUnsetTargets() {
        IrForeach loop = assertInstanceOf(IrForeach.class,
                body("foreach ($items as $$key => $obj->{$field}) ;").statements().getFirst());
        assertVariable(computed(assertInstanceOf(IrVariableTarget.class, loop.keyTarget()).name()), "key");
        assertVariable(computed(assertInstanceOf(IrPropertyTarget.class, loop.valueTarget()).property()), "field");
        IrUnset unset = assertInstanceOf(IrUnset.class,
                body("unset($$name, $obj->$field, $classes[0]::$$field, $callback()[0]->$field);")
                        .statements().getFirst());
        assertEquals(4, unset.targets().size());
        assertVariable(computed(assertInstanceOf(IrVariableTarget.class, unset.targets().getFirst()).name()), "name");
        assertInstanceOf(IrPropertyTarget.class, unset.targets().get(1));
        IrStaticPropertyTarget staticProperty = assertInstanceOf(IrStaticPropertyTarget.class, unset.targets().get(2));
        IrIndex classes = assertInstanceOf(IrIndex.class, dynamicClass(staticProperty.classReference()));
        assertVariable(classes.base(), "classes");
        assertInteger(classes.index(), 0);
        IrPropertyTarget callProperty = assertInstanceOf(IrPropertyTarget.class, unset.targets().get(3));
        IrIndexTarget index = assertInstanceOf(IrIndexTarget.class, callProperty.receiver());
        IrCall call = assertInstanceOf(IrCall.class,
                assertInstanceOf(IrExpressionWriteBase.class, index.base()).expression());
        assertVariable(callable(call), "callback");
    }

    // 动态函数、方法和静态方法结果只能作为后续属性或下标的写基底。
    @Test
    void wrapsDynamicCallResultsAsWriteBases() {
        for (String callee : List.of("$callback", "$obj->$method", "$type::$method", "Box::{methodName()}")) {
            IrPropertyTarget target = assertInstanceOf(IrPropertyTarget.class,
                    assignment(callee + "()->$field = 1").target());
            assertVariable(computed(target.property()), "field");
            IrExpression result = assertInstanceOf(IrExpressionWriteBase.class, target.receiver()).expression();
            assertTrue(result instanceof IrCall || result instanceof IrMethodCall || result instanceof IrStaticCall);
            IrIndexTarget index = assertInstanceOf(IrIndexTarget.class, assignment(callee + "()[0] = 2").target());
            assertInstanceOf(IrExpressionWriteBase.class, index.base());
            assertInteger(index.index(), 0);
            assertRejected(callee + "() = 1");
            assertRejected(callee + "() += 1");
            assertRejected(callee + "()++");
            assertRejectedBody("unset(" + callee + "());");
        }
    }

    // 普通写链允许追加，但 unset 禁止任意一层追加，包括动态成员之前的基底。
    @Test
    void retainsAppendRulesForComputedWriteChains() {
        for (String target : List.of("$a[]->$field", "$$name[]", "$type::$$field[]", "$callback()[]->$field")) {
            assertInstanceOf(IrAssignment.class, expression(target + " = 1"));
            assertInstanceOf(IrCompoundAssignment.class, expression(target + " += 1"));
            assertInstanceOf(IrUpdate.class, expression(target + "++"));
            assertInstanceOf(IrForeach.class,
                    body("foreach ($items as " + target + ") ;").statements().getFirst());
            assertRejectedBody("unset(" + target + ");");
        }
    }

    // 动态名称、类名、callable 和实参始终按读取转换，外层写模式不允许其内部空下标。
    @Test
    void rejectsAppendInsideComputedReadContexts() {
        for (String code : List.of("${$names[]}", "$obj->{$names[]}", "$obj->{$names[]} = 1",
                "Box::${$names[]} = 1", "$classes[]::$p = 1", "$classes[]::$$p = 1",
                "$classes[]::run()", "new $classes[]", "$value instanceof $classes[]",
                "$callbacks[]()", "($callbacks[])()", "$callback($items[])", "$callback(...$items[])",
                "$obj->{$names[]}()", "Box::{$names[]}()", "$a[]->$method()->$field = 1")) {
            assertRejected(code);
        }
        for (String code : List.of("unset($obj->{$names[]});", "unset($classes[]::$p);",
                "unset($callback($items[])[0]);")) {
            assertRejectedBody(code);
        }
    }

    // 名称中的独立赋值开启自己的写上下文，不受外层 unset 的禁止追加模式影响。
    @Test
    void preservesIndependentAppendAssignmentsInsideComputedNames() {
        IrVariableTarget variable = assertInstanceOf(IrVariableTarget.class,
                assignment("${$names[] = next()} = 1").target());
        assertAppendAssignment(computed(variable.name()), "names", "next");
        IrPropertyTarget property = assertInstanceOf(IrPropertyTarget.class,
                assignment("$obj->{$names[] = next()} = 2").target());
        assertAppendAssignment(computed(property.property()), "names", "next");
        IrStaticPropertyTarget staticProperty = assertInstanceOf(IrStaticPropertyTarget.class,
                assignment("($classes[] = choose())::$value = 3").target());
        assertAppendAssignment(dynamicClass(staticProperty.classReference()), "classes", "choose");
        IrUnset unset = assertInstanceOf(IrUnset.class,
                body("unset($obj->{$names[] = next()}, ${$vars[] = name()}, ($classes[] = choose())::$value);")
                        .statements().getFirst());
        assertAppendAssignment(computed(assertInstanceOf(IrPropertyTarget.class,
                unset.targets().getFirst()).property()), "names", "next");
        assertAppendAssignment(computed(assertInstanceOf(IrVariableTarget.class,
                unset.targets().get(1)).name()), "vars", "name");
        assertAppendAssignment(dynamicClass(assertInstanceOf(IrStaticPropertyTarget.class,
                unset.targets().get(2)).classReference()), "classes", "choose");
    }

    // 复杂目标中的接收者、名称、下标和参数各保存一次，复合赋值不会复制任何副作用子树。
    @Test
    void preservesSingleOccurrencesOfComputedSideEffects() {
        IrCompoundAssignment assignment = assertInstanceOf(IrCompoundAssignment.class,
                expression("(chooseClass())::{chooseMethod()}(...args())->{chooseField()}[next()] += rhs()"));
        IrIndexTarget index = assertInstanceOf(IrIndexTarget.class, assignment.target());
        assertCall(index.index(), "next");
        IrPropertyTarget property = assertInstanceOf(IrPropertyTarget.class, index.base());
        assertCall(computed(property.property()), "chooseField");
        IrStaticCall call = assertInstanceOf(IrStaticCall.class,
                assertInstanceOf(IrExpressionWriteBase.class, property.receiver()).expression());
        assertCall(dynamicClass(call.classReference()), "chooseClass");
        assertCall(computed(call.method()), "chooseMethod");
        assertEquals(1, call.arguments().size());
        assertTrue(call.arguments().getFirst().unpack());
        assertCall(call.arguments().getFirst().expression(), "args");
        assertCall(assignment.value(), "rhs");
    }

    // 新结构不会掩盖非法子树；空下标读取在任意名称、类引用或实参中都明确失败。
    @Test
    void rejectsUnsupportedSubtreesInEveryNewPosition() {
        for (String code : List.of("${($invalid[])}", "$obj->{($invalid[])}", "$obj->{($invalid[])}()",
                "Box::${($invalid[])}", "Box::{($invalid[])}()", "(($invalid[]))()", "(($invalid[]))::run()",
                "(($invalid[]))::$p", "(($invalid[]))::VALUE", "(($invalid[]))::class", "new ${($invalid[])}",
                "$value instanceof ${($invalid[])}", "$callback(($invalid[]))", "$callback(...($invalid[]))",
                "$obj->$method(...($invalid[]))", "$type::$method(...($invalid[]))", "new $type(...($invalid[]))")) {
            assertRejected(code);
        }
    }

    private static IrExpression expression(String code) {
        return SyntaxConverter.convertExpression(syntaxExpression(code));
    }

    private static SyntaxExpression syntaxExpression(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php " + code + ";");
        NodeExpr expression = parsed.getStmts().getValue().getFirst().getStmt().getExpression();
        return new SyntaxExpression(expression, new SourceInfo("dynamic.php", null));
    }

    private static SyntaxBody syntaxBody(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php function f() { " + code + " }");
        var statements = parsed.getStmts().getValue().getFirst().getFunction().getStmts().getValue();
        return new SyntaxBody(List.copyOf(statements), new SourceInfo("dynamic.php", null));
    }

    private static IrBlock body(String code) {
        return SyntaxConverter.convertBody(syntaxBody(code));
    }

    private static IrAssignment assignment(String code) {
        return assertInstanceOf(IrAssignment.class, expression(code));
    }

    private static IrNew constructed(String code) {
        IrNew result = assertInstanceOf(IrNew.class, expression(code));
        assertTrue(result.arguments().isEmpty(), code);
        return result;
    }

    private static IrExpression computed(IrAccessName name) {
        return assertInstanceOf(IrComputedName.class, name).expression();
    }

    private static IrExpression dynamicClass(IrClassReference reference) {
        return assertInstanceOf(IrDynamicClassReference.class, reference).expression();
    }

    private static IrExpression callable(IrCall call) {
        return assertInstanceOf(IrExpressionCallTarget.class, call.target()).expression();
    }

    private static List<IrArgument> arguments(IrExpression expression) {
        if (expression instanceof IrCall call) return call.arguments();
        if (expression instanceof IrMethodCall call) return call.arguments();
        if (expression instanceof IrStaticCall call) return call.arguments();
        return assertInstanceOf(IrNew.class, expression).arguments();
    }

    private static void assertNamedClass(IrClassReference reference, String spelling) {
        IrNamedClassReference named = assertInstanceOf(IrNamedClassReference.class, reference);
        assertEquals(spelling, named.name().spelling());
        assertEquals(NameForm.UNQUALIFIED, named.name().form());
    }

    private static void assertFixed(IrAccessName name, String expected) {
        assertEquals(expected, assertInstanceOf(IrFixedName.class, name).value());
    }

    private static void assertVariable(IrExpression expression, String expected) {
        assertFixed(assertInstanceOf(IrVariable.class, expression).name(), expected);
    }

    private static void assertCall(IrExpression expression, String expected) {
        IrCall call = assertInstanceOf(IrCall.class, expression);
        assertEquals(expected, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
        assertTrue(call.arguments().isEmpty());
    }

    private static void assertInteger(IrExpression expression, long expected) {
        assertEquals(expected, assertInstanceOf(IrIntegerLiteral.class, expression).value());
    }

    private static void assertString(IrExpression expression, String value) {
        assertArrayEquals(value.getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringLiteral.class, expression).value().toByteArray());
    }

    private static void assertAppendAssignment(IrExpression expression, String variable, String valueCall) {
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression);
        IrIndexTarget target = assertInstanceOf(IrIndexTarget.class, assignment.target());
        assertNull(target.index());
        assertFixed(assertInstanceOf(IrVariableTarget.class, target.base()).name(), variable);
        assertCall(assignment.value(), valueCall);
    }

    private static void assertRejected(String code) {
        SyntaxExpression syntax = assertDoesNotThrow(() -> syntaxExpression(code), code);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                () -> SyntaxConverter.convertExpression(syntax), code);
        assertEquals("dynamic.php", error.source().sourceId());
        assertFalse(error.fieldPath().isBlank(), code);
        assertFalse(error.reason().isBlank(), code);
    }

    private static void assertRejectedBody(String code) {
        SyntaxBody syntax = assertDoesNotThrow(() -> syntaxBody(code), code);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                () -> SyntaxConverter.convertBody(syntax), code);
        assertEquals("dynamic.php", error.source().sourceId());
        assertFalse(error.fieldPath().isBlank(), code);
        assertFalse(error.reason().isBlank(), code);
    }
}
