package top.kmar.php.extract;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 声明结构无法可靠提取时抛出；不会伴随一个不完整的成功模型。 */
public final class DeclarationExtractionException extends RuntimeException {
    private final SourceInfo source;
    private final String fieldPath;
    private final String reason;

    public DeclarationExtractionException(SourceInfo source, String fieldPath, String reason) {
        super(format(source, fieldPath, reason));
        this.source = Objects.requireNonNull(source, "source");
        this.fieldPath = Objects.requireNonNull(fieldPath, "fieldPath");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public SourceInfo source() {
        return source;
    }

    public String fieldPath() {
        return fieldPath;
    }

    public String reason() {
        return reason;
    }

    private static String format(SourceInfo source, String fieldPath, String reason) {
        Objects.requireNonNull(source, "source");
        var range = source.range();
        String position = range == null ? "未知位置" : range.startLine() + ":" + range.startColumn()
                + "-" + range.endLine() + ":" + range.endColumn();
        String sourceId = source.sourceId();
        return "声明提取失败 [" + (sourceId == null ? "未知来源" : sourceId) + " " + position
                + "] " + Objects.requireNonNull(fieldPath, "fieldPath") + ": "
                + Objects.requireNonNull(reason, "reason");
    }
}
