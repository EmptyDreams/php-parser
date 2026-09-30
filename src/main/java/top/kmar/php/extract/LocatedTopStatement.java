package top.kmar.php.extract;

import top.kmar.php.NodeTopStatement;

import java.util.Objects;

/** 保留分段前的 AST 节点及字段路径，区段重排视图不改变诊断索引。 */
record LocatedTopStatement(NodeTopStatement node, String path) {
    LocatedTopStatement {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(path, "path");
    }
}
