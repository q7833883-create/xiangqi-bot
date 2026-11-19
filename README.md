# 象棋自动对弈助手（原生独立版）

完全独立的 Android 原生应用：**不依赖 AutoX.js、不依赖 Termux**。Pikafish 引擎 + NNUE 权重直接内置在 APK 里，装上即用。

对应 AutoX.js 脚本版（`xiangqi-bot.zip`）的原生重写：

| 能力 | 原生版实现 |
|---|---|
| 截屏 | MediaProjection（系统授权弹窗） |
| 识别 | 按校准网格逐点采样 + 归一化互相关模板匹配（Kotlin 实现） |
| 落子 | AccessibilityService 手势（点击/拖动） |
| 引擎 | 内置 Pikafish（UCI 子进程，arm64）+ NNUE；无二进制时回退内置 JS 引擎的 Kotlin 移植版 |
| 校准 | 悬浮窗捕获两个对角交叉点 |
| 停止 | 音量上键（无障碍按键过滤）/ 通知"停止"按钮 / 应用内按钮 |

## 构建（CI 自动完成）

推送到 `xiangqi-native-apk` 分支即触发 `.github/workflows/android.yml`：

1. 下载官方 NNUE 权重（official-pikafish/Networks）打进 assets；
2. Pikafish 二进制优先用仓库内置（`app/src/main/jniLibs/arm64-v8a/libpikafish.so`），缺失时用 NDK 从源码编译；
3. 跑单元测试（棋盘逻辑/内置引擎）→ 打 debug APK；
4. 上传 artifact 并创建 GitHub Release，APK 在 Release 附件里。

本地手动构建（需 JDK 17 + Android SDK）：

```bash
gradle wrapper --gradle-version 8.9
./gradlew testDebugUnitTest assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装后设置（一次性）

1. **无障碍服务**：系统设置 → 无障碍 → 已安装的应用 → 象棋自动对弈助手 → 开启（落子点击 + 音量键停止都靠它）
2. **悬浮窗权限**：设置 → 应用 → 本应用 → 允许显示在其他应用上层（校准用）
3. 首次运行会弹**截图授权**（MediaProjection），点允许

## 使用流程

1. 打开 App → **「自动识别棋盘」**→ 切到象棋 App 棋盘界面（保持完整可见），App 自动定位棋盘；识别失败才用「手动校准」
2. 「提取棋子模板」→ 10 秒内切到新对局（标准初始局面）→ 自动裁 14 枚模板并判定红黑
3. 「开始自动对弈」→ 确认是否轮到你 → 切到棋盘界面 → 挂机
4. 或选「指点模式」：屏幕角上的悬浮棋盘实时同步，点「算一招」看建议，自己落子
5. 停止：按**音量上键**
6. 换 App / 换皮肤 / 换分辨率 → 重新「自动识别棋盘」（或手动校准）

## 与 AutoX 版的差异

- 模板与校准数据存**应用私有目录**，不再写 `/sdcard`，无需存储权限
- 引擎进程内运行（UCI 子进程），不走 HTTP；`auto` 模式下检测到内置 Pikafish 自动用之，否则回退内置 JS 引擎（棋力约业余初级）
- 启动后会等待你切到棋盘界面（最长 120 秒），而不是立即识别失败
- 仅支持竖屏、正视角、圆形棋子的标准 9×10 棋盘；不支持翻翻棋/揭棋

## ⚠️ 账号风险

在真人在线对局（排位、竞技场）中使用引擎代打违反各平台用户协议，可能封号。建议只用于人机对战、残局练习、打谱复盘。
