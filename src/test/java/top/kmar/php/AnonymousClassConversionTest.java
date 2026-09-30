package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.Modifier;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.NameReference;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SyntaxBody;
import top.kmar.php.model.SyntaxExpression;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证匿名类及其成员的独立 IR，不实例化对象、合并 trait 或执行 PHP 声明校验。 */
class AnonymousClassConversionTest {

    // 匿名类定义与构造实参分别保存，省略括号和空括号均表示零个实参。
    @Test
    void normalizesEmptyAnonymousConstructorsWithoutInventingAClassName() {
        for (String code : List.of("new class {}", "new class() {}")) {
            IrNewAnonymous instance = anonymous(code);
            assertTrue(instance.arguments().isEmpty(), code);
            assertNull(instance.definition().parentType(), code);
            assertTrue(instance.definition().interfaces().isEmpty(), code);
            assertTrue(instance.definition().members().isEmpty(), code);
        }
        assertInstanceOf(IrNew.class, expression("new ExistingClass"));
    }

    // 构造实参保留普通项、解包和副作用的顺序，不复制调用或提前执行构造方法。
    @Test
    void preservesConstructorArgumentOrderUnpackingAndSideEffects() {
        IrNewAnonymous instance = anonymous("new class(nextValue(), ...$args, $saved = 2, ...$more, $i++) {}");
        assertEquals(5, instance.arguments().size());
        assertEquals(List.of(false, true, false, true, false),
                instance.arguments().stream().map(IrArgument::unpack).toList());
        assertCall(instance.arguments().get(0).expression(), "nextValue");
        assertVariable(instance.arguments().get(1).expression(), "args");
        IrAssignment saved = assertInstanceOf(IrAssignment.class, instance.arguments().get(2).expression());
        assertVariableTarget(saved.target(), "saved");
        assertInteger(saved.value(), 2);
        assertVariable(instance.arguments().get(3).expression(), "more");
        assertInstanceOf(IrUpdate.class, instance.arguments().get(4).expression());
    }

    // 父类与接口只保存名称引用，保留四种名称形式、重复接口和源码顺序。
    @Test
    void preservesInheritanceReferencesWithoutResolvingNames() {
        IrAnonymousClass definition = definition("""
                new class extends \\Base implements Local, Rel\\Contract, \\Root\\Contract,
                        namespace\\Contract, Local {}
                """);
        assertName(definition.parentType(), "\\Base", NameForm.FULLY_QUALIFIED);
        assertEquals(5, definition.interfaces().size());
        assertName(definition.interfaces().get(0), "Local", NameForm.UNQUALIFIED);
        assertName(definition.interfaces().get(1), "Rel\\Contract", NameForm.QUALIFIED);
        assertName(definition.interfaces().get(2), "\\Root\\Contract", NameForm.FULLY_QUALIFIED);
        assertName(definition.interfaces().get(3), "namespace\\Contract", NameForm.NAMESPACE_RELATIVE);
        assertName(definition.interfaces().get(4), "Local", NameForm.UNQUALIFIED);
        for (String parent : List.of("Base", "Rel\\Base", "namespace\\Base")) {
            assertEquals(parent, definition("new class extends " + parent + " {}").parentType().spelling());
        }
    }

    // 同一属性或常量声明展开为多个有序成员，其间的方法和 trait 使用项不重排。
    @Test
    void flattensGroupedPropertiesAndConstantsInMemberOrder() {
        List<IrClassMember> members = definition("""
                new class {
                    public $first, $second = null;
                    function run() {}
                    const ONE = 1, TWO = 2;
                    use Feature;
                    var $last;
                }
                """).members();
        assertEquals(7, members.size());
        assertEquals("first", assertInstanceOf(IrProperty.class, members.get(0)).name());
        assertEquals("second", assertInstanceOf(IrProperty.class, members.get(1)).name());
        assertEquals("run", assertInstanceOf(IrMethod.class, members.get(2)).name());
        assertEquals("ONE", assertInstanceOf(IrClassConstant.class, members.get(3)).name());
        assertEquals("TWO", assertInstanceOf(IrClassConstant.class, members.get(4)).name());
        assertInstanceOf(IrTraitUse.class, members.get(5));
        assertEquals("last", assertInstanceOf(IrProperty.class, members.get(6)).name());
    }

    // 只保存显式修饰符，不注入 public、不合并重复项，也不把 var 改写为 public。
    @Test
    void preservesDeclaredModifierOrderWithoutSemanticNormalization() {
        List<IrClassMember> members = definition("""
                new class {
                    public public static $value;
                    var $legacy;
                    private protected const VALUE = 1;
                    static final public function run() {}
                    function plain() {}
                    const PLAIN = 2;
                }
                """).members();
        assertEquals(List.of(Modifier.PUBLIC, Modifier.PUBLIC, Modifier.STATIC),
                assertInstanceOf(IrProperty.class, members.get(0)).declaredModifiers());
        assertEquals(List.of(Modifier.VAR),
                assertInstanceOf(IrProperty.class, members.get(1)).declaredModifiers());
        assertEquals(List.of(Modifier.PRIVATE, Modifier.PROTECTED),
                assertInstanceOf(IrClassConstant.class, members.get(2)).declaredModifiers());
        assertEquals(List.of(Modifier.STATIC, Modifier.FINAL, Modifier.PUBLIC),
                assertInstanceOf(IrMethod.class, members.get(3)).declaredModifiers());
        assertTrue(assertInstanceOf(IrMethod.class, members.get(4)).declaredModifiers().isEmpty());
        assertTrue(assertInstanceOf(IrClassConstant.class, members.get(5)).declaredModifiers().isEmpty());
    }

    // 属性省略初值与显式 null 不合并，重复成员名称及大小写不在本层判错。
    @Test
    void distinguishesAbsentPropertyInitializersAndPreservesDuplicateNames() {
        List<IrClassMember> members = definition("""
                new class { public $value, $value = NuLl, $Value = 3; const X=1, X=2; }
                """).members();
        assertEquals(5, members.size());
        assertNull(assertInstanceOf(IrProperty.class, members.get(0)).initialValue());
        IrProperty explicit = assertInstanceOf(IrProperty.class, members.get(1));
        assertEquals("value", explicit.name());
        IrLiteral literal = assertInstanceOf(IrLiteral.class, explicit.initialValue());
        assertEquals(LiteralKind.NULL, literal.kind());
        assertEquals("NuLl", literal.lexeme());
        assertEquals("Value", assertInstanceOf(IrProperty.class, members.get(2)).name());
        assertEquals("X", assertInstanceOf(IrClassConstant.class, members.get(3)).name());
        assertEquals("X", assertInstanceOf(IrClassConstant.class, members.get(4)).name());
    }

    // 属性和常量值使用普通表达式转换，不求值或额外执行 PHP 常量表达式限制。
    @Test
    void convertsGeneralExpressionsInPropertyAndConstantValues() {
        List<IrClassMember> members = definition("""
                new class {
                    public $sum = 1 + 2, $call = nextValue(), $saved = $items[] = nextValue();
                    const list = [true, null], echo = $outer, class = new class {};
                }
                """).members();
        assertInstanceOf(IrBinary.class, assertInstanceOf(IrProperty.class, members.get(0)).initialValue());
        assertCall(assertInstanceOf(IrProperty.class, members.get(1)).initialValue(), "nextValue");
        assertAppendAssignment(assertInstanceOf(IrProperty.class, members.get(2)).initialValue(), "items");
        IrClassConstant array = assertInstanceOf(IrClassConstant.class, members.get(3));
        assertEquals("list", array.name());
        assertEquals(2, assertInstanceOf(IrArrayLiteral.class, array.value()).entries().size());
        IrClassConstant read = assertInstanceOf(IrClassConstant.class, members.get(4));
        assertEquals("echo", read.name());
        assertVariable(read.value(), "outer");
        IrClassConstant nested = assertInstanceOf(IrClassConstant.class, members.get(5));
        assertEquals("class", nested.name());
        assertInstanceOf(IrNewAnonymous.class, nested.value());
    }

    // 方法与闭包共享参数和类型规则，引用返回、引用参数与可变参数分别保存。
    @Test
    void preservesMethodSignaturesTypesAndReferenceFlags() {
        IrMethod method = method("""
                public static function &list(?\\Pkg\\Value &$value = null, array ...$items): ?namespace\\Result {
                    return $value;
                }
                """);
        assertEquals("list", method.name());
        assertEquals(List.of(Modifier.PUBLIC, Modifier.STATIC), method.declaredModifiers());
        assertTrue(method.returnsReference());
        assertEquals(2, method.parameters().size());
        IrParameter first = method.parameters().getFirst();
        assertEquals("value", first.name());
        assertTrue(first.byReference());
        assertFalse(first.variadic());
        assertNotNull(first.declaredType());
        assertTrue(first.declaredType().nullable());
        assertName(first.declaredType().name(), "\\Pkg\\Value", NameForm.FULLY_QUALIFIED);
        assertEquals(LiteralKind.NULL, assertInstanceOf(IrLiteral.class, first.defaultValue()).kind());
        IrParameter second = method.parameters().get(1);
        assertEquals("items", second.name());
        assertFalse(second.byReference());
        assertTrue(second.variadic());
        assertNull(second.defaultValue());
        assertNotNull(second.declaredType());
        assertName(second.declaredType().name(), "array", NameForm.UNQUALIFIED);
        assertNotNull(method.returnType());
        assertTrue(method.returnType().nullable());
        assertName(method.returnType().name(), "namespace\\Result", NameForm.NAMESPACE_RELATIVE);
        assertNotNull(method.body());
        assertVariable(assertInstanceOf(IrReturn.class, method.body().statements().getFirst()).value(), "value");
    }

    // 分号方法体、省略默认值和返回类型均用 null，显式空块及 null 默认值仍为节点。
    @Test
    void distinguishesSemicolonMethodsEmptyBodiesAndMissingSignatureParts() {
        List<IrClassMember> members = definition("""
                new class {
                    abstract function missing($first, $second = null);
                    function emptyBody() {}
                    function emptyStatement() {;}
                }
                """).members();
        IrMethod missing = assertInstanceOf(IrMethod.class, members.getFirst());
        assertNull(missing.body());
        assertNull(missing.returnType());
        assertFalse(missing.returnsReference());
        assertNull(missing.parameters().get(0).declaredType());
        assertNull(missing.parameters().get(0).defaultValue());
        assertEquals(LiteralKind.NULL,
                assertInstanceOf(IrLiteral.class, missing.parameters().get(1).defaultValue()).kind());
        IrMethod empty = assertInstanceOf(IrMethod.class, members.get(1));
        assertNotNull(empty.body());
        assertTrue(empty.body().statements().isEmpty());
        IrMethod semicolon = assertInstanceOf(IrMethod.class, members.get(2));
        assertNotNull(semicolon.body());
        assertEquals(1, semicolon.body().statements().size());
        assertInstanceOf(IrEmpty.class, semicolon.body().statements().getFirst());
    }

    // 方法默认值、方法体及闭包里都可以递归包含匿名类，不构造伪造的具名声明。
    @Test
    void recursivelyConvertsAnonymousClassesAcrossMemberExpressionPositions() {
        IrNewAnonymous instance = anonymous("""
                new class(new class {}) {
                    public $property = new class {};
                    const VALUE = new class {};
                    function build($default = new class {}) {
                        return function() { return new class {}; };
                    }
                }
                """);
        assertInstanceOf(IrNewAnonymous.class, instance.arguments().getFirst().expression());
        List<IrClassMember> members = instance.definition().members();
        assertInstanceOf(IrNewAnonymous.class, assertInstanceOf(IrProperty.class, members.get(0)).initialValue());
        assertInstanceOf(IrNewAnonymous.class, assertInstanceOf(IrClassConstant.class, members.get(1)).value());
        IrMethod method = assertInstanceOf(IrMethod.class, members.get(2));
        assertInstanceOf(IrNewAnonymous.class, method.parameters().getFirst().defaultValue());
        assertNotNull(method.body());
        IrClosure closure = assertInstanceOf(IrClosure.class,
                assertInstanceOf(IrReturn.class, method.body().statements().getFirst()).value());
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrReturn.class, closure.body().statements().getFirst()).value());
    }

    // 方法中的魔术常量、yield 和作用域语句按原模型保留，不推导类名或生成器标志。
    @Test
    void preservesExistingStatementAndGeneratorStructuresInsideMethods() {
        IrMethod method = method("""
                function &generate() {
                    global $shared;
                    static $count = 0;
                    declare(ticks=1);
                    yield __CLASS__ => new class {};
                    yield from $shared;
                    return __METHOD__;
                }
                """);
        assertTrue(method.returnsReference());
        assertNotNull(method.body());
        List<IrStatement> statements = method.body().statements();
        assertEquals(6, statements.size());
        assertInstanceOf(IrGlobal.class, statements.get(0));
        assertInstanceOf(IrStaticVariables.class, statements.get(1));
        assertInstanceOf(IrDeclare.class, statements.get(2));
        IrYield yielded = assertInstanceOf(IrYield.class, statementExpression(method.body(), 3));
        assertEquals(MagicConstantKind.CLASS, assertInstanceOf(IrMagicConstant.class, yielded.key()).kind());
        assertInstanceOf(IrNewAnonymous.class, yielded.value());
        assertInstanceOf(IrYieldFrom.class, statementExpression(method.body(), 4));
        assertEquals(MagicConstantKind.METHOD, assertInstanceOf(IrMagicConstant.class,
                assertInstanceOf(IrReturn.class, statements.get(5)).value()).kind());
    }

    // trait 使用项的分号式和空花括号都归为空规则列表，不要求 trait 实际存在。
    @Test
    void normalizesEmptyTraitAdaptationForms() {
        List<IrClassMember> members = definition("new class { use First; use Second {} use First, First; }").members();
        assertEquals(3, members.size());
        for (IrClassMember member : members) {
            assertTrue(assertInstanceOf(IrTraitUse.class, member).adaptations().isEmpty());
        }
        IrTraitUse duplicated = assertInstanceOf(IrTraitUse.class, members.get(2));
        assertEquals(List.of("First", "First"), duplicated.traits().stream().map(NameReference::spelling).toList());
    }

    // trait 别名覆盖四种 AST 变体，区分无来源 trait、无修饰符、无新名与关键字新名。
    @Test
    void convertsEveryTraitAliasFormWithoutConflatingNamesAndModifiers() {
        IrTraitUse use = traitUse("""
                use A {
                    run as renamed;
                    run as list;
                    A::run as protected replacement;
                    A::run as private;
                    run as var;
                    run as protected private;
                }
                """);
        assertEquals(6, use.adaptations().size());
        IrTraitAlias plain = assertInstanceOf(IrTraitAlias.class, use.adaptations().getFirst());
        assertNull(plain.method().trait());
        assertEquals("run", plain.method().method());
        assertNull(plain.modifier());
        assertEquals("renamed", plain.newName());
        assertEquals("list", assertInstanceOf(IrTraitAlias.class, use.adaptations().get(1)).newName());
        IrTraitAlias modified = assertInstanceOf(IrTraitAlias.class, use.adaptations().get(2));
        assertName(modified.method().trait(), "A", NameForm.UNQUALIFIED);
        assertEquals(Modifier.PROTECTED, modified.modifier());
        assertEquals("replacement", modified.newName());
        IrTraitAlias visibilityOnly = assertInstanceOf(IrTraitAlias.class, use.adaptations().get(3));
        assertEquals(Modifier.PRIVATE, visibilityOnly.modifier());
        assertNull(visibilityOnly.newName());
        IrTraitAlias keyword = assertInstanceOf(IrTraitAlias.class, use.adaptations().get(4));
        assertNull(keyword.modifier());
        assertEquals("var", keyword.newName());
        IrTraitAlias keywordAfterModifier = assertInstanceOf(IrTraitAlias.class, use.adaptations().get(5));
        assertEquals(Modifier.PROTECTED, keywordAfterModifier.modifier());
        assertEquals("private", keywordAfterModifier.newName());
    }

    // precedence 中所谓 absolute 引用仅要求 Trait::，名称仍可相对命名空间并保留重复排除项。
    @Test
    void preservesTraitPrecedenceAndMixedAdaptationOrder() {
        IrTraitUse use = traitUse("""
                use \\Traits\\A, namespace\\B, Rel\\C {
                    namespace\\B::list insteadof \\Traits\\A, Rel\\C, Rel\\C;
                    Rel\\C::echo as Alias;
                    \\Traits\\A::run insteadof namespace\\B;
                }
                """);
        assertEquals(3, use.traits().size());
        assertEquals(3, use.adaptations().size());
        IrTraitPrecedence first = assertInstanceOf(IrTraitPrecedence.class, use.adaptations().getFirst());
        assertName(first.method().trait(), "namespace\\B", NameForm.NAMESPACE_RELATIVE);
        assertEquals("list", first.method().method());
        assertEquals(List.of("\\Traits\\A", "Rel\\C", "Rel\\C"),
                first.insteadOf().stream().map(NameReference::spelling).toList());
        IrTraitAlias alias = assertInstanceOf(IrTraitAlias.class, use.adaptations().get(1));
        assertName(alias.method().trait(), "Rel\\C", NameForm.QUALIFIED);
        assertEquals("echo", alias.method().method());
        assertEquals("Alias", alias.newName());
        IrTraitPrecedence last = assertInstanceOf(IrTraitPrecedence.class, use.adaptations().get(2));
        assertName(last.method().trait(), "\\Traits\\A", NameForm.FULLY_QUALIFIED);
        assertName(last.insteadOf().getFirst(), "namespace\\B", NameForm.NAMESPACE_RELATIVE);
    }

    // 语法允许的 static/abstract/final alias 修饰符照常保留，不在 IR 转换时补 PHP 编译检查。
    @Test
    void preservesSyntacticTraitModifiersAndUnresolvedConflicts() {
        IrTraitUse use = traitUse("""
                use A {
                    Missing::run as static;
                    run as abstract renamed;
                    run as final;
                    Missing::run insteadof Other, Other;
                    run as renamed;
                    run as renamed;
                }
                """);
        assertEquals(6, use.adaptations().size());
        assertEquals(Modifier.STATIC, assertInstanceOf(IrTraitAlias.class, use.adaptations().get(0)).modifier());
        assertEquals(Modifier.ABSTRACT, assertInstanceOf(IrTraitAlias.class, use.adaptations().get(1)).modifier());
        assertEquals(Modifier.FINAL, assertInstanceOf(IrTraitAlias.class, use.adaptations().get(2)).modifier());
        assertEquals(2, assertInstanceOf(IrTraitPrecedence.class, use.adaptations().get(3)).insteadOf().size());
        assertEquals("renamed", assertInstanceOf(IrTraitAlias.class, use.adaptations().get(4)).newName());
        assertEquals("renamed", assertInstanceOf(IrTraitAlias.class, use.adaptations().get(5)).newName());
    }

    // 匿名类值可进入已有读取链、动态调用和静态类引用，不改变这些模型的形状。
    @Test
    void supportsAnonymousClassesAsReadReceiversAndDynamicCallTargets() {
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrPropertyAccess.class, expression("(new class {})->value")).receiver());
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrIndex.class, expression("(new class {})[0]")).base());
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrMethodCall.class, expression("(new class {})->run()")).receiver());
        IrCall call = assertInstanceOf(IrCall.class, expression("(new class {})()"));
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrExpressionCallTarget.class, call.target()).expression());
        IrStaticCall staticCall = assertInstanceOf(IrStaticCall.class, expression("(new class {})::run()"));
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrDynamicClassReference.class, staticCall.classReference()).expression());
        IrStaticPropertyAccess property = assertInstanceOf(IrStaticPropertyAccess.class,
                expression("(new class {})::$value"));
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrDynamicClassReference.class, property.classReference()).expression());
        IrClassConstantReference constant = assertInstanceOf(IrClassConstantReference.class,
                expression("(new class {})::VALUE"));
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrDynamicClassReference.class, constant.classReference()).expression());
    }

    // 已有数组、赋值、调用、类型操作和引用选择器中的匿名类从拒绝哨兵变为正常子树。
    @Test
    void convertsAnonymousClassesInsideExistingExpressionContainers() {
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression("$value = new class {}"));
        assertInstanceOf(IrNewAnonymous.class, assignment.value());
        IrArrayLiteral array = assertInstanceOf(IrArrayLiteral.class, expression("[new class {} => new class {}]"));
        IrValueArrayEntry entry = assertInstanceOf(IrValueArrayEntry.class, array.entries().getFirst());
        assertInstanceOf(IrNewAnonymous.class, entry.key());
        assertInstanceOf(IrNewAnonymous.class, entry.value());
        assertInstanceOf(IrNewAnonymous.class,
                assertCall(expression("consume(new class {})"), "consume").arguments().getFirst().expression());
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrClone.class, expression("clone (new class {})")).expression());
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrInstanceOf.class, expression("(new class {}) instanceof Box")).expression());
        IrReferenceAssignment reference = assertInstanceOf(IrReferenceAssignment.class,
                expression("$left =& $items[(new class {})]"));
        assertInstanceOf(IrNewAnonymous.class, assertInstanceOf(IrIndexTarget.class, reference.reference()).index());
        IrDestructuringAssignment destructuring = assertInstanceOf(IrDestructuringAssignment.class,
                expression("[(new class {}) => $value] = $items"));
        assertInstanceOf(IrNewAnonymous.class, destructuring.pattern().slots().getFirst().key());
    }

    // 动态名称、动态类引用与解包参数中的匿名类继续按表达式保存，不预判运行时类型。
    @Test
    void convertsAnonymousClassesInsideDynamicNamesClassReferencesAndUnpackedArguments() {
        IrVariable variable = assertInstanceOf(IrVariable.class, expression("${(new class {})}"));
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrComputedName.class, variable.name()).expression());
        IrPropertyAccess property = assertInstanceOf(IrPropertyAccess.class,
                expression("$object->{new class {}}"));
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrComputedName.class, property.property()).expression());
        IrNew instance = assertInstanceOf(IrNew.class, expression("new $classes[(new class {})]"));
        IrIndex className = assertInstanceOf(IrIndex.class,
                assertInstanceOf(IrDynamicClassReference.class, instance.classReference()).expression());
        assertInstanceOf(IrNewAnonymous.class, className.index());
        IrInstanceOf check = assertInstanceOf(IrInstanceOf.class,
                expression("$value instanceof $classes[(new class {})]"));
        IrIndex checkedClass = assertInstanceOf(IrIndex.class,
                assertInstanceOf(IrDynamicClassReference.class, check.classReference()).expression());
        assertInstanceOf(IrNewAnonymous.class, checkedClass.index());
        IrArgument argument = assertCall(expression("consume(...(new class {}))"), "consume")
                .arguments().getFirst();
        assertTrue(argument.unpack());
        assertInstanceOf(IrNewAnonymous.class, argument.expression());
    }

    // 内建结构仍有各自节点，新增匿名类只填充原有操作数，不触发执行或类型检查。
    @Test
    void convertsAnonymousClassesInsideBuiltinExpressionOperands() {
        IrIsset isset = assertInstanceOf(IrIsset.class, expression("isset($items[(new class {})])"));
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrIndex.class, isset.expressions().getFirst()).index());
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrEmptyCheck.class, expression("empty(new class {})")).expression());
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrCast.class, expression("(string)(new class {})")).expression());
        for (String keyword : List.of("include", "include_once", "require", "require_once")) {
            IrInclude include = assertInstanceOf(IrInclude.class, expression(keyword + " (new class {})"));
            assertInstanceOf(IrNewAnonymous.class, include.expression());
        }
        for (String keyword : List.of("exit", "die")) {
            IrExit exit = assertInstanceOf(IrExit.class, expression(keyword + "(new class {})"));
            assertInstanceOf(IrNewAnonymous.class, exit.expression());
        }
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrEval.class, expression("eval(new class {})")).expression());
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrErrorSuppress.class, expression("@(new class {})")).expression());
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrPrint.class, expression("print (new class {})")).expression());
    }

    // 作用域与控制流容器中的匿名类不再充当拒绝哨兵，各操作数仍留在原有位置。
    @Test
    void convertsAnonymousClassesInsideScopeAndControlFlowStatementContainers() {
        IrBlock body = SyntaxConverter.convertBody(syntaxBody("""
                global ${(new class {})};
                static $value = new class {};
                declare(custom=new class {}) echo new class {};
                switch (new class {}) { case new class {}: echo new class {}; }
                try { throw new class {}; }
                catch (Exception $error) { return new class {}; }
                finally { echo new class {}; }
                """));
        assertEquals(5, body.statements().size());
        IrGlobal global = assertInstanceOf(IrGlobal.class, body.statements().get(0));
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrComputedName.class, global.variables().getFirst().name()).expression());
        IrStaticVariables statics = assertInstanceOf(IrStaticVariables.class, body.statements().get(1));
        assertInstanceOf(IrNewAnonymous.class, statics.variables().getFirst().initializer());
        IrDeclare declaration = assertInstanceOf(IrDeclare.class, body.statements().get(2));
        assertInstanceOf(IrNewAnonymous.class, declaration.directives().getFirst().value());
        assertNotNull(declaration.body());
        assertInstanceOf(IrNewAnonymous.class, assertInstanceOf(IrEcho.class,
                declaration.body().statements().getFirst()).expressions().getFirst());
        IrSwitch selection = assertInstanceOf(IrSwitch.class, body.statements().get(3));
        assertInstanceOf(IrNewAnonymous.class, selection.condition());
        assertInstanceOf(IrNewAnonymous.class, selection.cases().getFirst().condition());
        assertInstanceOf(IrNewAnonymous.class, assertInstanceOf(IrEcho.class,
                selection.cases().getFirst().body().statements().getFirst()).expressions().getFirst());
        IrTry attempt = assertInstanceOf(IrTry.class, body.statements().get(4));
        assertInstanceOf(IrNewAnonymous.class, assertInstanceOf(IrThrow.class,
                attempt.body().statements().getFirst()).expression());
        assertInstanceOf(IrNewAnonymous.class, assertInstanceOf(IrReturn.class,
                attempt.catches().getFirst().body().statements().getFirst()).value());
        assertNotNull(attempt.finallyBlock());
        assertInstanceOf(IrNewAnonymous.class, assertInstanceOf(IrEcho.class,
                attempt.finallyBlock().statements().getFirst()).expressions().getFirst());
    }

    // 构造实参、成员初值和参数默认值中的独立赋值保留自己的追加写入上下文。
    @Test
    void preservesIndependentAppendAssignmentsInsideAnonymousClassContents() {
        IrNewAnonymous instance = anonymous("""
                new class($arguments[] = nextValue()) {
                    public $value = $properties[] = nextValue();
                    const VALUE = $constants[] = nextValue();
                    function run($value = $defaults[] = nextValue()) { $body[] = nextValue(); }
                }
                """);
        assertAppendAssignment(instance.arguments().getFirst().expression(), "arguments");
        assertAppendAssignment(assertInstanceOf(IrProperty.class,
                instance.definition().members().get(0)).initialValue(), "properties");
        assertAppendAssignment(assertInstanceOf(IrClassConstant.class,
                instance.definition().members().get(1)).value(), "constants");
        IrMethod method = assertInstanceOf(IrMethod.class, instance.definition().members().get(2));
        assertAppendAssignment(method.parameters().getFirst().defaultValue(), "defaults");
        assertNotNull(method.body());
        assertAppendAssignment(statementExpression(method.body(), 0), "body");
    }

    // new 读取能力不放宽直接临时值写链，引用、更新、解构及删除继续遵循既有目标规则。
    @Test
    void rejectsAnonymousClassRootedWriteAndReferenceTargets() {
        for (String code : List.of("(new class {})->value = 1", "(new class {})[0] = 1",
                "(new class {})->value++", "$target =& (new class {})->value",
                "[&(new class {})->value]", "[(new class {})->value] = $items")) {
            assertRejectedExpression(code);
        }
        assertRejectedBody("unset((new class {})->value);");
        assertRejectedBody("foreach ($items as (new class {})->value) {}");
    }

    // 调用返回值和动态静态属性仍沿原规则可写，不能因接收者含匿名类而一概拒绝。
    @Test
    void preservesWritableCallResultsAndDynamicStaticProperties() {
        IrAssignment assignment = assertInstanceOf(IrAssignment.class,
                expression("(new class {})->factory()->value = 1"));
        IrPropertyTarget target = assertInstanceOf(IrPropertyTarget.class, assignment.target());
        IrMethodCall call = assertInstanceOf(IrMethodCall.class,
                assertInstanceOf(IrExpressionWriteBase.class, target.receiver()).expression());
        assertInstanceOf(IrNewAnonymous.class, call.receiver());
        IrReferenceAssignment reference = assertInstanceOf(IrReferenceAssignment.class,
                expression("$target =& (new class {})->factory()"));
        assertInstanceOf(IrMethodCall.class,
                assertInstanceOf(IrExpressionWriteBase.class, reference.reference()).expression());
        IrAssignment staticWrite = assertInstanceOf(IrAssignment.class,
                expression("(new class {})::$value = 1"));
        IrStaticPropertyTarget staticTarget = assertInstanceOf(IrStaticPropertyTarget.class, staticWrite.target());
        assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrDynamicClassReference.class, staticTarget.classReference()).expression());
    }

    // 引用 foreach 仍先分类来源；匿名类值及其直接读取链不误标为可写来源。
    @Test
    void keepsReferenceForeachClassificationForAnonymousClassRootsAndCallResults() {
        for (String iterable : List.of("new class {}", "(new class {})->items", "(new class {})[0]",
                "(new class {})->factory()")) {
            IrForeach loop = foreach("foreach (" + iterable + " as &$value) {}");
            assertTrue(loop.byReference(), iterable);
            assertInstanceOf(IrExpressionIterable.class, loop.iterable(), iterable);
        }
        for (String iterable : List.of("(new class {})->factory()->items", "(new class {})->factory()[0]",
                "(new class {})::$items")) {
            IrForeach loop = foreach("foreach (" + iterable + " as &$value) {}");
            assertInstanceOf(IrWritableIterable.class, loop.iterable(), iterable);
        }
        IrForeach ordinary = foreach("foreach ((new class {})->factory()->items as $value) {}");
        assertInstanceOf(IrExpressionIterable.class, ordinary.iterable());
    }

    // 匿名类里的读取位置不会继承外围赋值或引用上下文，裸追加读取仍明确报错。
    @Test
    void rejectsAppendReadsInsideArgumentsInitializersDefaultsAndBodies() {
        for (String code : List.of("new class($items[]) {}", "new class(...$items[]) {}",
                "new class { public $value = $items[]; }", "new class { const VALUE = $items[]; }",
                "new class { function run($value = $items[]) {} }",
                "new class { function run() { return $items[]; } }",
                "$target =& $items[(new class($arguments[]) {})]",
                "(new class($arguments[]) {})::$value = 1")) {
            assertRejectedExpression(code);
        }
    }

    // 方法默认值、主体及其嵌套具名声明继续递归拒绝空下标读取，不用原 AST 占位。
    @Test
    void propagatesUnsupportedSubtreesFromEveryAnonymousClassContainer() {
        String invalidRead = "($invalid[])";
        for (String code : List.of("new class(" + invalidRead + ") {}",
                "new class { public $value = " + invalidRead + "; }",
                "new class { const VALUE = " + invalidRead + "; }",
                "new class { function run($value = " + invalidRead + ") {} }",
                "new class { function run() { " + invalidRead + "; } }",
                "new class { function run() { function nested() { ($invalid[]); } } }",
                "new class { function run() { class Nested { function method() { ($invalid[]); } } } }",
                "new class { function run() { trait Nested { function method() { ($invalid[]); } } } }",
                "new class { function run() { interface Nested { const VALUE = ($invalid[]); } } }",
                "new class { public $value = function() { function nested() { ($invalid[]); } }; }")) {
            assertRejectedExpression(code);
        }
    }

    // 外层字符串发布文本快照后才递归匿名类，成员和实参解码不能覆盖已生成的字节。
    @Test
    void isolatesSharedStringBuffersAcrossAnonymousClassInterpolation() {
        String large = "member".repeat(1024);
        IrStringTemplate template = assertInstanceOf(IrStringTemplate.class, expression(
                "\"before{$items[(new class('argument') { public $text = '" + large
                        + "'; const VALUE = 'constant'; function run($value = 'default') { return 'body'; } })]}after\""));
        assertEquals(3, template.parts().size());
        assertText(template.parts().get(0), "before");
        IrIndex index = assertInstanceOf(IrIndex.class,
                assertInstanceOf(IrStringInterpolation.class, template.parts().get(1)).expression());
        IrNewAnonymous instance = assertInstanceOf(IrNewAnonymous.class, index.index());
        assertBytes(instance.arguments().getFirst().expression(), "argument");
        assertBytes(assertInstanceOf(IrProperty.class,
                instance.definition().members().get(0)).initialValue(), large);
        assertBytes(assertInstanceOf(IrClassConstant.class,
                instance.definition().members().get(1)).value(), "constant");
        IrMethod method = assertInstanceOf(IrMethod.class, instance.definition().members().get(2));
        assertBytes(method.parameters().getFirst().defaultValue(), "default");
        assertNotNull(method.body());
        assertBytes(assertInstanceOf(IrReturn.class, method.body().statements().getFirst()).value(), "body");
        assertText(template.parts().get(2), "after");
        assertText(template.parts().get(0), "before");
    }

    // 非空匿名类定义及每种成员、适配和方法参数均保留各自来源，不绑定外部声明 ID。
    @Test
    void preservesSourcesAcrossDefinitionsMembersAndTraitRules() {
        IrNewAnonymous instance = anonymous("""
                new class(1) extends Base implements Contract {
                    public $value = 2;
                    const VALUE = 3;
                    function run($arg = 4) { return $arg; }
                    use A, B { A::run insteadof B; run as alias; }
                }
                """);
        assertKnownSource(instance.source());
        assertKnownSource(instance.definition().source());
        assertKnownSource(instance.arguments().getFirst().source());
        assertNotNull(instance.definition().parentType());
        assertKnownSource(instance.definition().parentType().source());
        for (IrClassMember member : instance.definition().members()) assertKnownSource(member.source());
        IrMethod method = assertInstanceOf(IrMethod.class, instance.definition().members().get(2));
        assertKnownSource(method.parameters().getFirst().source());
        assertNotNull(method.body());
        assertKnownSource(method.body().source());
        IrTraitUse use = assertInstanceOf(IrTraitUse.class, instance.definition().members().get(3));
        IrTraitPrecedence precedence = assertInstanceOf(IrTraitPrecedence.class, use.adaptations().getFirst());
        assertKnownSource(precedence.source());
        assertKnownSource(precedence.method().source());
        IrTraitAlias alias = assertInstanceOf(IrTraitAlias.class, use.adaptations().get(1));
        assertKnownSource(alias.source());
        assertKnownSource(alias.method().source());
    }

    private static IrNewAnonymous anonymous(String code) {
        return assertInstanceOf(IrNewAnonymous.class, expression(code));
    }

    private static IrAnonymousClass definition(String code) {
        return anonymous(code).definition();
    }

    private static IrMethod method(String code) {
        List<IrClassMember> members = definition("new class { " + code + " }").members();
        assertEquals(1, members.size());
        return assertInstanceOf(IrMethod.class, members.getFirst());
    }

    private static IrTraitUse traitUse(String code) {
        List<IrClassMember> members = definition("new class { " + code + " }").members();
        assertEquals(1, members.size());
        return assertInstanceOf(IrTraitUse.class, members.getFirst());
    }

    private static IrForeach foreach(String code) {
        IrBlock body = SyntaxConverter.convertBody(syntaxBody(code));
        assertEquals(1, body.statements().size());
        return assertInstanceOf(IrForeach.class, body.statements().getFirst());
    }

    private static IrExpression expression(String code) {
        return SyntaxConverter.convertExpression(syntaxExpression(code));
    }

    private static SyntaxExpression syntaxExpression(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php " + code + ";");
        return new SyntaxExpression(parsed.getStmts().getValue().getFirst().getStmt().getExpression(),
                new SourceInfo("anonymous.php", null));
    }

    private static SyntaxBody syntaxBody(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php function testBody() { " + code + " }");
        return new SyntaxBody(List.copyOf(parsed.getStmts().getValue().getFirst().getFunction().getStmts().getValue()),
                new SourceInfo("anonymous.php", null));
    }

    private static IrExpression statementExpression(IrBlock body, int index) {
        return assertInstanceOf(IrExpressionStatement.class, body.statements().get(index)).expression();
    }

    private static void assertName(NameReference name, String spelling, NameForm form) {
        assertNotNull(name);
        assertEquals(spelling, name.spelling());
        assertEquals(form, name.form());
    }

    private static void assertVariableTarget(Object target, String name) {
        assertEquals(name, assertInstanceOf(IrFixedName.class,
                assertInstanceOf(IrVariableTarget.class, target).name()).value());
    }

    private static void assertVariable(IrExpression expression, String name) {
        assertEquals(name, assertInstanceOf(IrFixedName.class,
                assertInstanceOf(IrVariable.class, expression).name()).value());
    }

    private static void assertInteger(IrExpression expression, long value) {
        assertEquals(value, assertInstanceOf(IrIntegerLiteral.class, expression).value());
    }

    private static IrCall assertCall(IrExpression expression, String name) {
        IrCall call = assertInstanceOf(IrCall.class, expression);
        assertEquals(name, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().spelling());
        return call;
    }

    private static void assertAppendAssignment(IrExpression expression, String target) {
        IrAssignment assignment = assertInstanceOf(IrAssignment.class, expression);
        IrIndexTarget index = assertInstanceOf(IrIndexTarget.class, assignment.target());
        assertNull(index.index());
        assertVariableTarget(index.base(), target);
        assertTrue(assertCall(assignment.value(), "nextValue").arguments().isEmpty());
    }

    private static void assertBytes(IrExpression expression, String value) {
        assertArrayEquals(value.getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringLiteral.class, expression).value().toByteArray());
    }

    private static void assertText(IrStringPart part, String value) {
        assertArrayEquals(value.getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringText.class, part).value().toByteArray());
    }

    private static void assertKnownSource(SourceInfo source) {
        assertEquals("anonymous.php", source.sourceId());
        assertNotNull(source.range());
    }

    private static void assertRejectedExpression(String code) {
        SyntaxExpression syntax = assertDoesNotThrow(() -> syntaxExpression(code), code);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                () -> SyntaxConverter.convertExpression(syntax), code);
        assertFailure(error, code);
    }

    private static void assertRejectedBody(String code) {
        SyntaxBody syntax = assertDoesNotThrow(() -> syntaxBody(code), code);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                () -> SyntaxConverter.convertBody(syntax), code);
        assertFailure(error, code);
    }

    private static void assertFailure(SyntaxConversionException error, String code) {
        assertEquals("anonymous.php", error.source().sourceId(), code);
        assertFalse(error.fieldPath().isBlank(), code);
        assertFalse(error.reason().isBlank(), code);
    }
}
