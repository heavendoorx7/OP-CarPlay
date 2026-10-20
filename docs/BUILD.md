# 构建 1349

## 工具链

构建环境：

- JDK 25（Gradle daemon toolchain 配置在 `gradle/gradle-daemon-jvm.properties`）
- Android SDK Platform 37（本地验证使用 `platforms/android-37.0`）
- Android Build Tools 36.0.0
- Android NDK 28.2.13676358
- 仓库 Gradle Wrapper；AGP、Kotlin、AndroidX 等见 `gradle/libs.versions.toml`

设置 `JAVA_HOME`、`ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）。首次需要下载 SDK/依赖，缓存完整后才能 `--offline`。

## Debug 与测试

```bash
bash scripts/build-p7.sh \
  :common:testDebugUnitTest :shared:testDebugUnitTest \
  :common:lintRelease :mobile:lintRelease :mobile:assembleDebug
```

构建入口固定加上 `-Pp7Profile=true`，得到 `com.shilapi.xcertplay.p7` / v0.7 / 1349。默认不启用动态视区实验。Windows 可用同一任务：

```powershell
.\gradlew.bat -Pp7Profile=true :common:testDebugUnitTest :shared:testDebugUnitTest :mobile:assembleDebug
```

Debug 默认使用本机调试签名，与维护者 Release 签名不同，不能直接覆盖维护者版本。

## Release

准备你自己的 Android 签名密钥，通过环境变量提供：

```bash
export ANDROID_KEYSTORE_PATH=/absolute/path/to/your-release.jks
export ANDROID_KEYSTORE_PASSWORD='your-store-password'
export ANDROID_KEY_ALIAS='your-alias'
export ANDROID_KEY_PASSWORD='your-key-password'

# 公开构建不注入认证材料
unset OP_MFI_ASSETS_DIR
bash scripts/build-p7.sh \
  :common:testDebugUnitTest :shared:testDebugUnitTest \
  :common:lintRelease :mobile:lintRelease :mobile:assembleRelease
```

产物：`mobile/build/outputs/apk/release/mobile-release.apk`。
密码请通过你自己的安全环境管理，不要写进仓库、日志或提交历史。维护者签名私钥不随源码提供。

## 可选认证资源

设置 `OP_MFI_ASSETS_DIR` 可从外部目录注入认证文件：

```text
OP_MFI_ASSETS_DIR/
└── op_mfi/
    ├── certificate.p7b
    └── identity.pk8
```

注入的文件可以从 APK 中提取，不适合作为保密存储。发行 APK 未注入这两份文件。

## 发布前检查

```bash
python3 scripts/check-public-tree.py
git diff --check
```

检查是发布材料筛查，不是完整安全审计。不要提交本机日志、配对数据库、私有密钥、系统 APK 或仪表固件。

## 本地验证

1349 的公开构建：279 项 JVM 用例中 278 项通过，1 项依赖可选认证资源的测试跳过；Release Lint 无错误，Release 构建通过。这些检查不替代实车兼容性与安全验证。
