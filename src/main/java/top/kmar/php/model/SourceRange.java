package top.kmar.php.model;

/** 已知源码范围：行列从 1 开始，起点包含，终点不包含；允许真实的零宽范围。 */
public record SourceRange(int startLine, int startColumn, int endLine, int endColumn) {
    public SourceRange {
        if (startLine < 1 || startColumn < 1 || endLine < 1 || endColumn < 1) {
            throw new IllegalArgumentException("源码行列必须从 1 开始");
        }
        if (startLine > endLine || (startLine == endLine && startColumn > endColumn)) {
            throw new IllegalArgumentException("源码范围的起点不能晚于终点");
        }
    }
}
