package top.kmar.php.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 从有序声明列表派生的只读索引，同名候选保持出现顺序，不执行重名合法性校验。
 * 顶层名称跨命名空间区段查询；成员名称按所属声明 ID 隔离。
 */
public final class DeclarationIndex {
    private final Map<DeclarationId, Declaration> byId;
    private final Map<TopLevelKey, List<TopLevelDeclaration>> topLevel;
    private final Map<MemberKey, List<ClassMember>> members;

    private DeclarationIndex(Map<DeclarationId, Declaration> byId,
                             Map<TopLevelKey, List<TopLevelDeclaration>> topLevel,
                             Map<MemberKey, List<ClassMember>> members) {
        this.byId = Collections.unmodifiableMap(new LinkedHashMap<>(byId));
        this.topLevel = freezeCandidates(topLevel);
        this.members = freezeCandidates(members);
    }

    /**
     * 按区段、声明和成员顺序建立索引；重复 ID 和所属范围不一致是模型结构错误，
     * 重复名称仍完整保留。
     * trait 使用项可按 ID 查询，但不进入名称索引。
     */
    public static DeclarationIndex from(List<NamespaceSection> sections) {
        Map<DeclarationId, Declaration> byId = new LinkedHashMap<>();
        Map<TopLevelKey, List<TopLevelDeclaration>> topLevel = new LinkedHashMap<>();
        Map<MemberKey, List<ClassMember>> members = new LinkedHashMap<>();
        Set<NamespaceSectionId> sectionIds = new HashSet<>();
        for (NamespaceSection section : List.copyOf(sections)) {
            if (!sectionIds.add(section.id())) {
                throw new IllegalArgumentException("重复命名空间区段 ID: " + section.id().value());
            }
            for (TopLevelDeclaration declaration : section.declarations()) {
                if (!section.id().equals(declaration.sectionId())) {
                    throw new IllegalArgumentException("声明 " + declaration.id().value()
                            + " 的 sectionId 与所在区段 ID " + section.id().value() + " 不一致");
                }
                addById(byId, declaration);
                TopLevelKind kind = topLevelKind(declaration);
                TopLevelKey key = new TopLevelKey(kind, topLevelName(kind, declaration.qualifiedName()));
                topLevel.computeIfAbsent(key, ignored -> new ArrayList<>()).add(declaration);
                if (declaration instanceof ClassLikeDefinition type) {
                    for (ClassMember member : type.members()) {
                        if (!type.id().equals(member.ownerId())) {
                            throw new IllegalArgumentException("成员 " + member.id().value()
                                    + " 的 ownerId 与所属类型 ID " + type.id().value() + " 不一致");
                        }
                        addById(byId, member);
                        MemberKey memberKey = memberKey(member);
                        if (memberKey != null) {
                            members.computeIfAbsent(memberKey, ignored -> new ArrayList<>()).add(member);
                        }
                    }
                }
            }
        }
        return new DeclarationIndex(byId, topLevel, members);
    }

    /** 按当前提取结果内的 ID 查询声明，包括未进入名称索引的 trait 使用项。 */
    public Optional<Declaration> findById(DeclarationId id) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(id, "id")));
    }

    /**
     * 按完整限定名查询顶层候选；可带一个开头反斜杠，不应用 import 或全局回退规则。
     * 类型与函数忽略 ASCII 大小写；常量仅对命名空间部分忽略 ASCII 大小写。
     */
    public List<TopLevelDeclaration> findTopLevel(TopLevelKind kind, String qualifiedName) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        return topLevel.getOrDefault(new TopLevelKey(kind, topLevelName(kind, qualifiedName)), List.of());
    }

    /** 按所属类型和成员名称查询；方法忽略 ASCII 大小写，属性和类常量精确匹配。 */
    public List<ClassMember> findMembers(DeclarationId ownerId, MemberKind kind, String name) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        return members.getOrDefault(new MemberKey(ownerId, kind, memberName(kind, name)), List.of());
    }

    private static void addById(Map<DeclarationId, Declaration> byId, Declaration declaration) {
        DeclarationId id = Objects.requireNonNull(declaration.id(), "declaration.id");
        if (byId.putIfAbsent(id, declaration) != null) {
            throw new IllegalArgumentException("重复声明 ID: " + id.value());
        }
    }

    private static TopLevelKind topLevelKind(TopLevelDeclaration declaration) {
        if (declaration instanceof ClassLikeDefinition) return TopLevelKind.TYPE;
        if (declaration instanceof FunctionDefinition) return TopLevelKind.FUNCTION;
        if (declaration instanceof NamespaceConstantDefinition) return TopLevelKind.CONSTANT;
        throw new IllegalArgumentException("不支持的顶层声明类型: " + declaration.getClass().getName());
    }

    private static MemberKey memberKey(ClassMember member) {
        if (member instanceof MethodDefinition method) {
            return new MemberKey(method.ownerId(), MemberKind.METHOD, asciiLowerCase(method.name()));
        }
        if (member instanceof PropertyDefinition property) {
            return new MemberKey(property.ownerId(), MemberKind.PROPERTY, property.name());
        }
        if (member instanceof ClassConstantDefinition constant) {
            return new MemberKey(constant.ownerId(), MemberKind.CONSTANT, constant.name());
        }
        if (member instanceof TraitUseDefinition) return null;
        throw new IllegalArgumentException("不支持的类型成员: " + member.getClass().getName());
    }

    private static String topLevelName(TopLevelKind kind, String qualifiedName) {
        String name = qualifiedName.startsWith("\\") ? qualifiedName.substring(1) : qualifiedName;
        if (kind != TopLevelKind.CONSTANT) return asciiLowerCase(name);
        int separator = name.lastIndexOf('\\');
        if (separator < 0) return name;
        return asciiLowerCase(name.substring(0, separator)) + name.substring(separator);
    }

    private static String memberName(MemberKind kind, String name) {
        return kind == MemberKind.METHOD ? asciiLowerCase(name) : name;
    }

    private static String asciiLowerCase(String value) {
        char[] chars = value.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] >= 'A' && chars[i] <= 'Z') {
                chars[i] = (char) (chars[i] + ('a' - 'A'));
            }
        }
        return new String(chars);
    }

    private static <K, V> Map<K, List<V>> freezeCandidates(Map<K, List<V>> candidates) {
        Map<K, List<V>> result = new LinkedHashMap<>();
        candidates.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(result);
    }

    private record TopLevelKey(TopLevelKind kind, String name) {}
    private record MemberKey(DeclarationId ownerId, MemberKind kind, String name) {}
}
