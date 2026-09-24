package top.kmar.php.model;

/** 当前提取结果内的声明标识，不承诺跨提取运行稳定。 */
public record DeclarationId(long value) {
    public DeclarationId {
        if (value < 1) throw new IllegalArgumentException("声明 ID 必须从 1 开始");
    }
}
