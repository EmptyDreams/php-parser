package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.*;
import top.kmar.php.ir.Visibility;
import top.kmar.php.model.Modifier;

import java.util.Objects;

/** 直接读取修饰符并补有效默认值；只检查修饰符，不校验方法体或其它声明结构。 */
final class ModifierConverter {
    private final ConversionContext context;

    ModifierConverter(ConversionContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    ClassFlags classModifiers(NodeListNodeClassModifier list, String path) {
        var values = context.elements(list.getValue(), list, path);
        if (values.isEmpty()) throw context.error(list, path, "显式类修饰符列表不能为空");
        boolean isAbstract = false;
        boolean isFinal = false;
        for (int i = 0; i < values.size(); i++) {
            var node = values.get(i);
            String p = path + "[" + i + "]";
            Modifier modifier = switch (node) {
                case NodeClassModifier.Abstract ignored -> Modifier.ABSTRACT;
                case NodeClassModifier.Final ignored -> Modifier.FINAL;
                default -> throw context.error(node, p, "无法识别的类修饰符结构");
            };
            keyword(node.getKw(), modifier.name(), node, p + ".kw");
            if (modifier == Modifier.ABSTRACT) {
                if (isAbstract) throw context.error(node, p, "重复的 abstract 修饰符");
                isAbstract = true;
            } else {
                if (isFinal) throw context.error(node, p, "重复的 final 修饰符");
                isFinal = true;
            }
            if (isAbstract && isFinal) throw context.error(node, p, "类不能同时为 abstract 和 final");
        }
        return new ClassFlags(isAbstract, isFinal);
    }

    MemberFlags methodModifiers(NodeListNodeMemberModifier list, boolean inInterface, String path) {
        return members(list, MemberKind.METHOD, inInterface, list, path);
    }

    MemberFlags propertyModifiers(NodeListNodeMemberModifier list, AstNode origin, String path) {
        return members(list, MemberKind.PROPERTY, false, origin, path);
    }

    MemberFlags varProperty(@Nullable NodeString marker, AstNode origin, String path) {
        keyword(marker, "var", origin, path);
        return new MemberFlags(Visibility.PUBLIC, false, false, false);
    }

    Visibility constantVisibility(NodeListNodeMemberModifier list, boolean inInterface, String path) {
        return members(list, MemberKind.CONSTANT, inInterface, list, path).visibility();
    }

    Visibility traitVisibility(NodeMemberModifier node, String path) {
        Modifier modifier = member(node, path);
        return switch (modifier) {
            case PUBLIC -> Visibility.PUBLIC;
            case PROTECTED -> Visibility.PROTECTED;
            case PRIVATE -> Visibility.PRIVATE;
            default -> throw context.error(node, path, "trait 别名只能调整可见性");
        };
    }

    private MemberFlags members(NodeListNodeMemberModifier list, MemberKind kind, boolean inInterface,
                                AstNode origin, String path) {
        var values = context.elements(list.getValue(), list, path);
        if (kind == MemberKind.PROPERTY && values.isEmpty()) {
            throw context.error(origin, path, "属性修饰符列表不能为空");
        }
        Visibility visibility = null;
        boolean isStatic = false;
        boolean isAbstract = false;
        boolean isFinal = false;
        for (int i = 0; i < values.size(); i++) {
            var node = values.get(i);
            String p = path + "[" + i + "]";
            Modifier modifier = member(node, p);
            if (kind == MemberKind.PROPERTY && (modifier == Modifier.ABSTRACT || modifier == Modifier.FINAL)) {
                throw context.error(node, p, "属性不能使用 abstract 或 final 修饰符");
            }
            if (kind == MemberKind.CONSTANT && (modifier == Modifier.STATIC || modifier == Modifier.ABSTRACT
                    || modifier == Modifier.FINAL)) {
                throw context.error(node, p, "类常量只能使用可见性修饰符");
            }
            if (inInterface && kind == MemberKind.METHOD && modifier != Modifier.PUBLIC && modifier != Modifier.STATIC) {
                throw context.error(node, p, "接口方法只允许显式 public 和 static 修饰符");
            }
            if (inInterface && kind == MemberKind.CONSTANT && modifier != Modifier.PUBLIC) {
                throw context.error(node, p, "接口常量只能为 public");
            }
            switch (modifier) {
                case PUBLIC, PROTECTED, PRIVATE -> {
                    if (visibility != null) throw context.error(node, p, "不能重复或同时指定多个可见性修饰符");
                    visibility = switch (modifier) {
                        case PUBLIC -> Visibility.PUBLIC;
                        case PROTECTED -> Visibility.PROTECTED;
                        case PRIVATE -> Visibility.PRIVATE;
                        default -> throw new AssertionError(modifier);
                    };
                }
                case STATIC -> {
                    if (isStatic) throw context.error(node, p, "重复的 static 修饰符");
                    isStatic = true;
                }
                case ABSTRACT -> {
                    if (isAbstract) throw context.error(node, p, "重复的 abstract 修饰符");
                    isAbstract = true;
                }
                case FINAL -> {
                    if (isFinal) throw context.error(node, p, "重复的 final 修饰符");
                    isFinal = true;
                }
                // var 有独立的属性产生式，不可能来自已识别的成员修饰符节点。
                case VAR -> throw new AssertionError(modifier);
            }
            if (isAbstract && isFinal) throw context.error(node, p, "方法不能同时为 abstract 和 final");
            if (isAbstract && visibility == Visibility.PRIVATE) {
                throw context.error(node, p, "abstract 方法不能为 private");
            }
        }
        return new MemberFlags(visibility == null ? Visibility.PUBLIC : visibility, isStatic,
                isAbstract || (inInterface && kind == MemberKind.METHOD), isFinal);
    }

    private Modifier member(NodeMemberModifier node, String path) {
        Modifier modifier = switch (node) {
            case NodeMemberModifier.Public ignored -> Modifier.PUBLIC;
            case NodeMemberModifier.Protected ignored -> Modifier.PROTECTED;
            case NodeMemberModifier.Private ignored -> Modifier.PRIVATE;
            case NodeMemberModifier.Static ignored -> Modifier.STATIC;
            case NodeMemberModifier.Abstract ignored -> Modifier.ABSTRACT;
            case NodeMemberModifier.Final ignored -> Modifier.FINAL;
            default -> throw context.error(node, path, "无法识别的成员修饰符结构");
        };
        keyword(node.getKw(), modifier.name(), node, path + ".kw");
        return modifier;
    }

    /** 避免 Java Unicode 大小写折叠把近似字符当作 PHP 关键字。 */
    private void keyword(@Nullable NodeString marker, String expected, AstNode origin, String path) {
        String value = context.text(marker, origin, path);
        if (value.length() == expected.length()) {
            boolean matches = true;
            for (int i = 0; i < value.length(); i++) {
                char actual = value.charAt(i);
                char wanted = expected.charAt(i);
                if (actual >= 'a' && actual <= 'z') actual -= 'a' - 'A';
                if (wanted >= 'a' && wanted <= 'z') wanted -= 'a' - 'A';
                if (actual != wanted) {
                    matches = false;
                    break;
                }
            }
            if (matches) return;
        }
        throw context.error(origin, path, "修饰符标记与结构不一致，预期 " + expected);
    }

    private enum MemberKind { METHOD, PROPERTY, CONSTANT }

    record ClassFlags(boolean isAbstract, boolean isFinal) {}

    record MemberFlags(Visibility visibility, boolean isStatic, boolean isAbstract, boolean isFinal) {}
}
