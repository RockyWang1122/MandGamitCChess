# 橘中弈象棋 —— 开源免费的安卓版中国象棋学习工具

## 出处与修改声明

本应用基于开源项目 **迪克象棋 XQDK**（GPL-3.0）二次开发而来（上游仓库：https://github.com/Silva6c/chess-dike），
修改开始于 **2026-09**。依据 **GNU General Public License v3.0**（见 [LICENSE](LICENSE)），
本项目整体同样以 **GPL-3.0** 协议开源发布。
本软件如有任何侵权行为，请及时联系作者删除。

## 介绍

MandGamitCChess是一款开源的中国象棋学习软件，具有人机对战(引擎为Pikafish)，棋盘编辑，图片识别，拍照识别棋盘(来源于VinXiangQi)，第三方棋软识局面识别并悬浮窗提示，盲棋训练以及在线游戏大厅（支持语音与文字沟通功能），支持好友对战等功能于一身。支持XQF ，PGN棋谱解析。希望有更多的人参与进来完善。

**免责声明**：本应用仅供个人学习、研究与文化交流使用；请遵守所在地区的法律法规与相关平台的服务条款。

## 开源许可

- 本项目以 **GPL-3.0** 发布，完整协议文本见 [LICENSE](LICENSE)。
- 本项目为"象棋迪克 XQDK"的衍生作品，且其上游 DroidFish、Pikafish 等均为 GPL-3.0 项目，
  因此本项目不得闭源、不得以任何限制源码获取的方式分发。
- **分发义务**：以任何形式分发本应用的 APK 或其他二进制形式时，必须同时提供完整的对应源码
  （推荐方式：在 App"关于"页面与本 README 中注明本仓库的源码地址）。
- 对上游代码的修改已在整体层面声明（见"出处与修改声明"）；上游源文件中的版权与许可声明均予保留。

## 鸣谢

本项目站在以下开源项目的肩膀上，谨致谢意：

- **象棋迪克 XQDK** —— 本项目的直接基座（GPL-3.0）；（[GitHub]：https://github.com/Silva6c/chess-dike）
- 原项目 **"象棋鱼"**（作者 zfdang，[GitHub](https://github.com/zfdang/chinese-chess-android)）——
  象棋迪克在其基础上二次开发；
- [VinXiangQi](https://github.com/Vincentzyx/VinXiangQi) —— YOLO 棋子检测权重文件来源；
- [Pikafish](https://github.com/official-pikafish/Pikafish) 引擎团队与
  [DroidFish](https://github.com/peterosterlund2/droidfish)（Peter Österlund）—— 引擎与对弈框架基座（GPL-3.0）；
- [tinypinyin](https://github.com/promeg/tinypinyin)、[cpu_features](https://github.com/google/cpu_features)
  及 filepicker 等第三方开源库（各自的许可文件随源码保留）。

## 新增功能：连线（屏幕识别 · 悬浮窗走法指导）

首页新增 **连线** 入口，功能为：通过系统录屏授权识别其他象棋 App 的棋盘局面，
在悬浮窗中显示**已识别局面迷你棋盘**与**我方最佳走法建议**（复用内置皮卡鱼引擎分析）。

- 默认"靠近玩家的一侧（屏幕下方）＝我方"，自动识别我方执红还是执黑（含换边局），
  **只为我方给出指导**；另一方仅观察（显示其走法与预测）。
- 识别为只读：**没有自动走子、没有触摸注入、没有无障碍服务**，所有着法由玩家手动操作。
- 使用说明与原理：[docs/assist-helper.md](docs/assist-helper.md) ·
  技术报告：[docs/技术报告.md](docs/技术报告.md)
- 本功能为学习辅助工具，请求的权限为常规的屏幕录制（MediaProjection）与悬浮窗，
  与反作弊相关的敏感权限（无障碍服务等）一律不申请。

## 从源码构建

要求：Android Studio（或 Gradle 8.x + JDK 17）+ Android SDK 34 + NDK 25。

```bash
./gradlew :app:assembleArmv8-dotprod-Release     # 推荐：dotprod 引擎变体（需 ARMv8.2+ 真机）
./gradlew :app:assembleArmv8-Release             # 兼容变体（不支持 dotprod 的老机器）
./gradlew :app:testArmv8-dotprod-DebugUnitTest   # 单元测试
```

**大文件说明**（本仓库已内置/未内置）：

| 内容 | git 仓库 | 本地构建 | 说明 |
| --- | --- | --- | --- |
| Pikafish 引擎（`libpikafish-armv8.so` / `-dotprod.so`，GPL-3.0） | ✅ 分发 | ✅ 内置 | `app/src/main/pikafish/arm64-v8a/` |
| Pikafish NNUE 权重（`libpikafish.nnue.so`，GPL-3.0） | ✅ 分发 | ✅ 内置 | 同上，约 45MB |
| YOLO 棋子检测模型（`assets/yolov5n_xq_fp16.tflite`） | ✅ 分发 | ✅ 内置 | 权重来自 VinXiangQi |

**签名**：发布时请使用你自己的 keystore 进行签名，切勿沿用上游项目公开的密钥库，以免与上游 APK 签名冲突，也无法证明你的发布者身份。

## 参考

#### 基于Android的中国象棋
https://github.com/kongxiangchx/ChineseChess

#### DroidFish Android Chess App
https://github.com/peterosterlund2/droidfish

#### cchess是一个Python版的中国象棋库
https://github.com/walker8088/cchess
