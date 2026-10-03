# PHP Parser

使用 Java 21 实现的 PHP 7.2 语法解析器，基于 JFlex 和 CUP fork，仅支持纯 PHP 源码。

## 构建

```powershell
.\gradlew.bat build
```

## 发布

通过环境变量或 `~/.jreleaser/config.toml` 配置发布凭据和签名密钥，版本号在 `build.gradle` 中修改。

```powershell
# 检查配置
.\gradlew.bat jreleaserConfig

# 清理旧产物
.\gradlew.bat clean

# 构建、测试并暂存产物
.\gradlew.bat build publish

# 发布到 Maven Central
.\gradlew.bat jreleaserDeploy
```

Linux/macOS 使用 `./gradlew`。