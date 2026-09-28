package top.kmar.php.extract;

import top.kmar.php.model.SourceInfo;

import java.util.Objects;

/** 语法结构损坏或超出当前转换子集时抛出，不返回部分转换结果。 */
public final class SyntaxConversionException extends RuntimeException {
    private final SourceInfo source;
    private final String fieldPath;
    private final String reason;

    public SyntaxConversionException(SourceInfo source, String fieldPath, String reason) {
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
        return "语法转换失败 [" + (source.sourceId() == null ? "未知来源" : source.sourceId())
                + " " + position + "] " + Objects.requireNonNull(fieldPath, "fieldPath")
                + ": " + Objects.requireNonNull(reason, "reason");
    }
}
