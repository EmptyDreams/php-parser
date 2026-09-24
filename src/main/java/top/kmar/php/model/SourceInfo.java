package top.kmar.php.model;

import org.jetbrains.annotations.Nullable;

/** 来源标识由调用方提供；sourceId 为 null 表示未提供，range 为 null 表示位置未知而非零宽范围。 */
public record SourceInfo(@Nullable String sourceId, @Nullable SourceRange range) {}
