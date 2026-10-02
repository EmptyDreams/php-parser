package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.*;
import top.kmar.php.model.ImportKind;
import top.kmar.php.model.SourceInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 只读取 namespace 的 use，通过工厂直接构造各阶段模型，不处理 trait 使用或闭包捕获。 */
final class ImportReader<T> {
    @FunctionalInterface
    interface Factory<T> {
        T create(ImportKind kind, String targetName, @Nullable String declaredAlias, SourceInfo source);
    }

    private final AstReaderContext context;
    private final Factory<T> factory;

    ImportReader(AstReaderContext context, Factory<T> factory) {
        this.context = Objects.requireNonNull(context, "context");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    List<T> read(NodeTopStatement statement, String path) {
        var result = new ArrayList<T>();
        if (statement instanceof NodeTopStatement.Use || statement instanceof NodeTopStatement.UseTyped) {
            ImportKind kind = statement instanceof NodeTopStatement.UseTyped
                    ? kind(context.required(statement.getType(), statement, path + ".type"), path + ".type")
                    : ImportKind.CLASS;
            var uses = context.required(statement.getUses(), statement, path + ".uses");
            var values = nonEmpty(uses.getValue(), uses, path + ".uses");
            for (int i = 0; i < values.size(); i++) {
                var use = values.get(i);
                String itemPath = path + ".uses[" + i + "]";
                if (use instanceof NodeUseDeclaration.UseDeclAbsolute) {
                    if (!context.text(use.getKw(), use, itemPath + ".kw").equals("\\")) {
                        throw context.error(use, itemPath + ".kw", "绝对导入标记必须为反斜杠");
                    }
                } else if (!(use instanceof NodeUseDeclaration.UseDecl)) {
                    throw context.error(use, itemPath, "无法识别的导入结构");
                }
                result.add(item(kind, "", context.required(use.getUse(), use, itemPath + ".use"), use,
                        itemPath + ".use"));
            }
        } else if (statement instanceof NodeTopStatement.UseGroup) {
            ImportKind kind = kind(context.required(statement.getType(), statement, path + ".type"), path + ".type");
            String groupPath = path + ".use";
            var group = context.required(statement.getUse(), statement, groupPath);
            if (!(group instanceof NodeGroupUseDeclaration.GroupUse)
                    && !(group instanceof NodeGroupUseDeclaration.GroupUseAbsolute)) {
                throw context.error(group, groupPath, "无法识别的分组导入结构");
            }
            String prefix = context.namespaceName(context.required(group.getPrefix(), group, groupPath + ".prefix"),
                    groupPath + ".prefix");
            var uses = context.required(group.getUses(), group, groupPath + ".uses");
            var values = nonEmpty(uses.getValue(), uses, groupPath + ".uses");
            for (int i = 0; i < values.size(); i++) {
                var use = values.get(i);
                result.add(item(kind, prefix, use, use, groupPath + ".uses[" + i + "]"));
            }
        } else if (statement instanceof NodeTopStatement.UseMixedGroup) {
            String groupPath = path + ".mixedUse";
            var group = context.required(statement.getMixedUse(), statement, groupPath);
            if (!(group instanceof NodeMixedGroupUseDeclaration.MixedGroupUse)
                    && !(group instanceof NodeMixedGroupUseDeclaration.MixedGroupUseAbsolute)) {
                throw context.error(group, groupPath, "无法识别的混合分组导入结构");
            }
            String prefix = context.namespaceName(context.required(group.getPrefix(), group, groupPath + ".prefix"),
                    groupPath + ".prefix");
            var uses = context.required(group.getUses(), group, groupPath + ".uses");
            var values = nonEmpty(uses.getValue(), uses, groupPath + ".uses");
            for (int i = 0; i < values.size(); i++) {
                var use = values.get(i);
                String itemPath = groupPath + ".uses[" + i + "]";
                ImportKind kind;
                if (use instanceof NodeInlineUseDeclaration.InlineUseTyped) {
                    kind = kind(context.required(use.getType(), use, itemPath + ".type"), itemPath + ".type");
                } else if (use instanceof NodeInlineUseDeclaration.InlineUse) {
                    kind = ImportKind.CLASS;
                } else {
                    throw context.error(use, itemPath, "无法识别的混合导入项");
                }
                result.add(item(kind, prefix, context.required(use.getUse(), use, itemPath + ".use"), use,
                        itemPath + ".use"));
            }
        } else {
            throw context.error(statement, path, "无法识别的导入语句");
        }
        return List.copyOf(result);
    }

    private T item(ImportKind kind, String prefix, NodeUnprefixedUseDeclaration node,
                   AstNode origin, String path) {
        String alias;
        if (node instanceof NodeUnprefixedUseDeclaration.UseElemAs) {
            alias = context.text(node.getAlias(), node, path + ".alias");
        } else if (node instanceof NodeUnprefixedUseDeclaration.UseElem) {
            alias = null;
        } else {
            throw context.error(node, path, "无法识别的导入项");
        }
        String suffix = context.namespaceName(context.required(node.getN(), node, path + ".n"), path + ".n");
        // PHP 导入目标始终是全限定名称，不受当前 namespace 影响。
        String name = prefix.isEmpty() ? suffix : prefix + "\\" + suffix;
        try {
            return factory.create(kind, name, alias, context.source(origin));
        } catch (IllegalArgumentException exception) {
            // IR 的缺省别名必须非空；损坏目标不能泄露模型构造异常或返回部分结果。
            throw context.error(node, path + ".n", exception.getMessage());
        }
    }

    private ImportKind kind(NodeUseType node, String path) {
        ImportKind kind = switch (node) {
            case NodeUseType.UseFunction ignored -> ImportKind.FUNCTION;
            case NodeUseType.UseConst ignored -> ImportKind.CONST;
            default -> throw context.error(node, path, "无法识别的导入种类");
        };
        String spelling = context.text(node.getKw(), node, path + ".kw").toLowerCase(Locale.ROOT);
        if (!spelling.equals(kind == ImportKind.FUNCTION ? "function" : "const")) {
            throw context.error(node, path + ".kw", "导入种类标记与结构不一致");
        }
        return kind;
    }

    private <E> List<E> nonEmpty(List<E> values, AstNode node, String path) {
        context.elements(values, node, path);
        if (values.isEmpty()) throw context.error(node, path, "导入列表至少需要一项");
        return values;
    }
}
