package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.model.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证索引的候选顺序、所属范围及不同声明类别的大小写规则。 */
class DeclarationIndexTest {

    // 同名区段保留各自身份；文件级查询聚合候选时不能覆盖重复声明。
    @Test
    void preservesDuplicateTopLevelCandidatesAcrossNamespaceSections() {
        PhpFile file = extract("""
                namespace N;
                class Item { function Run() {} public $value; const K = 1; use TraitA; }
                function Run() {}
                const FLAG = 1;
                namespace N;
                interface ITEM { function RUN(); }
                function run() {}
                const FLAG = 2, Flag = 3;
                """);
        DeclarationIndex index = file.declarationIndex();
        List<NamespaceSection> sections = file.namespaceSections();
        List<TopLevelDeclaration> types = index.findTopLevel(TopLevelKind.TYPE, "n\\item");
        assertEquals(List.of("Item", "ITEM"), types.stream().map(TopLevelDeclaration::name).toList());
        assertSame(sections.getFirst().declarations().getFirst(), types.getFirst());
        assertSame(sections.get(1).declarations().getFirst(), types.get(1));
        assertEquals(types, index.findTopLevel(TopLevelKind.TYPE, "\\N\\ITEM"));
        assertNotEquals(types.getFirst().sectionId(), types.get(1).sectionId());
        assertEquals(List.of("Run", "run"), index.findTopLevel(TopLevelKind.FUNCTION, "N\\RUN")
                .stream().map(TopLevelDeclaration::name).toList());
        assertEquals(List.of(sections.getFirst().declarations().get(2), sections.get(1).declarations().get(2)),
                index.findTopLevel(TopLevelKind.CONSTANT, "n\\FLAG"));
        assertEquals(List.of(sections.get(1).declarations().get(3)),
                index.findTopLevel(TopLevelKind.CONSTANT, "N\\Flag"));
        assertTrue(index.findTopLevel(TopLevelKind.CONSTANT, "N\\flag").isEmpty());

        Set<DeclarationId> identities = new HashSet<>();
        long expectedId = 1;
        for (NamespaceSection section : sections) {
            for (TopLevelDeclaration declaration : section.declarations()) {
                assertTrue(identities.add(declaration.id()));
                assertEquals(expectedId++, declaration.id().value());
                assertSame(declaration, index.findById(declaration.id()).orElseThrow());
                if (declaration instanceof ClassLikeDefinition type) {
                    for (ClassMember member : type.members()) {
                        assertTrue(identities.add(member.id()));
                        assertEquals(expectedId++, member.id().value());
                        assertSame(member, index.findById(member.id()).orElseThrow());
                    }
                }
            }
        }
    }

    // 相同拼写可属于不同声明类别，查询时必须明确类型、函数或常量。
    @Test
    void separatesTopLevelKindsSharingTheSameName() {
        PhpFile file = extract("class Same {} function Same() {} const Same = 1;");
        List<TopLevelDeclaration> declarations = file.namespaceSections().getFirst().declarations();
        assertEquals(List.of(declarations.getFirst()), file.declarationIndex().findTopLevel(TopLevelKind.TYPE, "same"));
        assertEquals(List.of(declarations.get(1)), file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "SAME"));
        assertEquals(List.of(declarations.get(2)), file.declarationIndex().findTopLevel(TopLevelKind.CONSTANT, "Same"));
        assertTrue(file.declarationIndex().findTopLevel(TopLevelKind.CONSTANT, "same").isEmpty());
        assertTrue(file.declarationIndex().findTopLevel(TopLevelKind.TYPE, "Missing").isEmpty());
    }

    // 方法按所属类型隔离；属性和类常量保持大小写，方法不会进入顶层函数索引。
    @Test
    void isolatesMembersByOwnerAndKeepsMethodsOutOfTopLevelFunctions() {
        PhpFile file = extract("""
                class A {
                    function Work() {}
                    function wORK() {}
                    public $item, $ITEM;
                    const VALUE = 1, value = 2;
                    use TraitA;
                }
                class B { function WORK() {} public $item; const VALUE = 3; }
                function WORK() {}
                """);
        List<TopLevelDeclaration> declarations = file.namespaceSections().getFirst().declarations();
        ClassLikeDefinition first = assertInstanceOf(ClassLikeDefinition.class, declarations.getFirst());
        ClassLikeDefinition second = assertInstanceOf(ClassLikeDefinition.class, declarations.get(1));
        DeclarationIndex index = file.declarationIndex();
        assertEquals(List.of(first.members().getFirst(), first.members().get(1)),
                index.findMembers(first.id(), MemberKind.METHOD, "work"));
        assertEquals(List.of(second.members().getFirst()), index.findMembers(second.id(), MemberKind.METHOD, "work"));
        assertEquals(List.of(first.members().get(2)), index.findMembers(first.id(), MemberKind.PROPERTY, "item"));
        assertEquals(List.of(first.members().get(3)), index.findMembers(first.id(), MemberKind.PROPERTY, "ITEM"));
        assertTrue(index.findMembers(first.id(), MemberKind.PROPERTY, "Item").isEmpty());
        assertEquals(List.of(first.members().get(4)), index.findMembers(first.id(), MemberKind.CONSTANT, "VALUE"));
        assertEquals(List.of(first.members().get(5)), index.findMembers(first.id(), MemberKind.CONSTANT, "value"));
        assertTrue(index.findMembers(first.id(), MemberKind.CONSTANT, "Value").isEmpty());
        assertEquals(List.of(second.members().get(2)), index.findMembers(second.id(), MemberKind.CONSTANT, "VALUE"));
        assertEquals(List.of(declarations.get(2)), index.findTopLevel(TopLevelKind.FUNCTION, "work"));
        TraitUseDefinition use = assertInstanceOf(TraitUseDefinition.class, first.members().get(6));
        assertSame(use, index.findById(use.id()).orElseThrow());
        assertTrue(index.findMembers(first.id(), MemberKind.METHOD, "TraitA").isEmpty());
    }

    // 只折叠 ASCII 大写，避免 Unicode 小写化错误合并不同 PHP 名称。
    @Test
    void foldsOnlyAsciiForTypesFunctionsAndMethodsAndPreservesConstantNames() {
        PhpFile file = extract("""
                namespace Demo;
                class Apple {}
                class APPLE {}
                class Ä {}
                class ä {}
                function Mix() {}
                function mIX() {}
                function Ä() {}
                function ä() {}
                const Flag = 1, FLAG = 2;
                class Holder { function Ä() {} function ä() {} }
                """);
        DeclarationIndex index = file.declarationIndex();
        assertEquals(2, index.findTopLevel(TopLevelKind.TYPE, "demo\\apple").size());
        assertEquals(List.of("Ä"), index.findTopLevel(TopLevelKind.TYPE, "DEMO\\Ä")
                .stream().map(TopLevelDeclaration::name).toList());
        assertEquals(List.of("ä"), index.findTopLevel(TopLevelKind.TYPE, "Demo\\ä")
                .stream().map(TopLevelDeclaration::name).toList());
        assertEquals(2, index.findTopLevel(TopLevelKind.FUNCTION, "demo\\mix").size());
        assertEquals(List.of("Ä"), index.findTopLevel(TopLevelKind.FUNCTION, "demo\\Ä")
                .stream().map(TopLevelDeclaration::name).toList());
        assertEquals(List.of("ä"), index.findTopLevel(TopLevelKind.FUNCTION, "demo\\ä")
                .stream().map(TopLevelDeclaration::name).toList());
        assertEquals(1, index.findTopLevel(TopLevelKind.CONSTANT, "DEMO\\Flag").size());
        assertEquals(1, index.findTopLevel(TopLevelKind.CONSTANT, "demo\\FLAG").size());
        assertTrue(index.findTopLevel(TopLevelKind.CONSTANT, "Demo\\flag").isEmpty());
        ClassLikeDefinition holder = assertInstanceOf(ClassLikeDefinition.class,
                index.findTopLevel(TopLevelKind.TYPE, "Demo\\Holder").getFirst());
        assertEquals(List.of(holder.members().getFirst()), index.findMembers(holder.id(), MemberKind.METHOD, "Ä"));
        assertEquals(List.of(holder.members().get(1)), index.findMembers(holder.id(), MemberKind.METHOD, "ä"));
    }

    // 手工组装模型时，声明中的区段身份必须与容纳它的区段一致。
    @Test
    void rejectsTopLevelDeclarationsAssignedToAnotherSection() {
        var sections = extract("namespace A; function f() {} namespace B; function g() {}")
                .namespaceSections();
        var first = sections.getFirst();
        var function = (FunctionDefinition) first.declarations().getFirst();
        var misplaced = new FunctionDefinition(function.id(), sections.get(1).id(),
                function.qualifiedName(), function.signature(), function.body(), function.source());
        var inconsistent = new NamespaceSection(first.id(), first.namespaceName(), first.imports(),
                List.of(misplaced), first.body(), first.source());

        var error = assertThrows(IllegalArgumentException.class,
                () -> DeclarationIndex.from(List.of(inconsistent, sections.get(1))));
        assertTrue(error.getMessage().contains("sectionId"));
    }

    // 成员即使使用另一个已存在类型的 ID，也不能出现在错误的类型成员列表中。
    @Test
    void rejectsMembersAssignedToAnotherType() {
        var section = extract("class A { function f() {} } class B { function g() {} }")
                .namespaceSections().getFirst();
        var first = (ClassLikeDefinition) section.declarations().getFirst();
        var second = (ClassLikeDefinition) section.declarations().get(1);
        var method = (MethodDefinition) first.members().getFirst();
        var misplaced = new MethodDefinition(method.id(), second.id(), method.declaredModifiers(),
                method.signature(), method.body(), method.source());
        var inconsistentType = new ClassLikeDefinition(first.id(), first.sectionId(), first.name(),
                first.qualifiedName(), first.kind(), first.declaredModifiers(), first.parentTypes(),
                first.interfaces(), List.of(misplaced), first.source());
        var inconsistent = new NamespaceSection(section.id(), section.namespaceName(), section.imports(),
                List.of(inconsistentType, second), section.body(), section.source());

        var error = assertThrows(IllegalArgumentException.class,
                () -> DeclarationIndex.from(List.of(inconsistent)));
        assertTrue(error.getMessage().contains("ownerId"));
    }

    // 重名声明可以保留，但重复声明 ID 或重复区段 ID 都会让身份查询变得不确定。
    @Test
    void rejectsDuplicateDeclarationAndSectionIds() {
        var section = extract("function f() {} function g() {}").namespaceSections().getFirst();
        var first = (FunctionDefinition) section.declarations().getFirst();
        var second = (FunctionDefinition) section.declarations().get(1);
        var duplicateId = new FunctionDefinition(first.id(), second.sectionId(), second.qualifiedName(),
                second.signature(), second.body(), second.source());
        var inconsistent = new NamespaceSection(section.id(), section.namespaceName(), section.imports(),
                List.of(first, duplicateId), section.body(), section.source());
        var declarationError = assertThrows(IllegalArgumentException.class,
                () -> DeclarationIndex.from(List.of(inconsistent)));
        assertTrue(declarationError.getMessage().contains("重复声明 ID"));

        var sections = extract("namespace A; namespace B;").namespaceSections();
        var secondSection = sections.get(1);
        var duplicateSection = new NamespaceSection(sections.getFirst().id(), secondSection.namespaceName(),
                secondSection.imports(), secondSection.declarations(), secondSection.body(), secondSection.source());
        var sectionError = assertThrows(IllegalArgumentException.class,
                () -> DeclarationIndex.from(List.of(sections.getFirst(), duplicateSection)));
        assertTrue(sectionError.getMessage().contains("重复命名空间区段 ID"));
    }

    private static PhpFile extract(String source) {
        return DeclarationExtractor.extract(Main.parse("<?php\n" + source));
    }
}