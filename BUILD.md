# 橘中弈象棋 (MandGamitCChess) 手动编译指导

## 一、环境要求

| 项                    | 版本             | 说明                                                                    |
| ----------------------| -----------------|-------------------------------------------------------------------------|
| JDK                   | 17               | 源码与目标兼容级别均为 17                                               |
| Gradle                | 8.10.2           | 工程自带 wrapper，无需单独安装                                          |
| Android Gradle Plugin | 8.8.2            | 由 wrapper 拉取                                                         |
| Kotlin                | 1.9.24           | 由 AGP 拉取                                                             |
| Android SDK           | compileSdk 34    | minSdk 26 / targetSdk 33                                                |
| Android NDK           | **25.1.8937393** | `app/build.gradle.kts` 第 118 行硬编码指定（优先级高于 toml 中的 26.x） |

> 安卓 SDK 安装后，在 `local.properties` 里指向 SDK 目录即可：
> ```
> sdk.dir=C\:/Users/yourname/AppData/Local/Android/Sdk
> ```
> NDK 通过 SDK Manager 安装 `25.1.8937393` 版本即可（项目已配置好）。

## 二、工程结构（仅编译相关）

```
MandGamitCChess/
├── settings.gradle.kts        # 包含 :app / :filepicker / :tinypinyin 三个模块
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat      # Gradle Wrapper 入口
├── local.properties           # SDK 路径（本机生成）
├── gradle/                    # wrapper 与版本目录
├── app/                       # 主应用模块（含 cpp/ndkBuild 与 pikafish 原生库）
├── filepicker/                # 文件选择器模块
└── tinypinyin/                # 拼音库模块
```

> 应用包名：`com.mandgamit.chess`（`namespace` 与 `applicationId` 一致）；
> 主源码路径 `app/src/main/java/com/mandgamit/chess/`，单元测试路径 `app/src/test/java/com/mandgamit/chess/`。

## 三、编译命令

在工程根目录执行。Windows 用 `gradlew.bat`，类 Unix 用 `./gradlew`。

**1. 构建 Release APK（推荐 dotprod 变体，需 ARMv8.2+ 真机）**
```powershell
.\gradlew.bat :app:assembleArmv8-dotprod-Release
```

**2. 构建兼容变体（不支持 dotprod 的老机器）**
```powershell
.\gradlew.bat :app:assembleArmv8-Release
```

**3. 构建 Debug 版**
```powershell
.\gradlew.bat :app:assembleArmv8-dotprod-Debug
```

**4. 运行单元测试**
```powershell
.\gradlew.bat :app:testArmv8-dotprod-DebugUnitTest
```

**5. 清理后重新全量构建**
```powershell
.\gradlew.bat clean :app:assembleArmv8-dotprod-Release
```

## 四、产物位置

```
app/build/outputs/apk/armv8-dotprod-/release/MandGamit_<日期>_<版本号>_armv8-dotprod-Release.apk
```
- 文件名格式：`MandGamit_yyyyMMdd_<versionCode>_<flavor>-<buildType>.apk`
- `versionCode` 来自 `git rev-list HEAD --count`；无 git 仓库时回退为 `1`。

## 五、签名说明

Release 构建使用工程内置的公开 keystore（来自上游开源项目，非私密凭据），定义在 `app/build.gradle.kts`：

| 项            | 值                       |
| ------------- | ------------------------ |
| keystore 文件 | `app/cchess.release.jks` |
| keyAlias      | `cchess`                 |
| storePassword | `cchess-store-pwd`       |
| keyPassword   | `cchess-key-pwd`         |

> 如需以自己身份发布，替换 `cchess.release.jks` 并修改对应配置即可。

## 六、两个变体的区别

工程通过 `productFlavors` 生成两个变体（`app/build.gradle.kts`）：

- `armv8-`：加载 `libpikafish-armv8.so`，兼容所有 ARM64 设备
- `armv8-dotprod-`：加载 `libpikafish-armv8-dotprod.so`，启用 dotprod 指令加速（需 ARMv8.2+）

两者 ABI 均为 `arm64-v8a` 与 `x86_64`。

## 七、常见编译提示（非致命，可忽略）

- `llvm-strip ... not recognized as a valid object file`：原生库中 `libpikafish.ini.so` / `libpikafish.nnue.so` 是数据文件而非 ELF，AGP 会原样打包，不影响产物。
- Kotlin 弃用警告（`Deprecated in Java` 等）：仅提示，不影响构建。
- 配置缓存提示 `external process started 'git rev-list HEAD --count'`：`versionCode` 调用了 git，不影响构建。

## 八、首次编译耗时

首次编译会下载 AGP/Kotlin/依赖与原生库，耗时较长（约数分钟）；后续增量编译会快很多。构建成功末尾会显示 `BUILD SUCCESSFUL`。
