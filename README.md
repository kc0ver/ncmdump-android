# ncmdump-android

把 [taurusxin/ncmdump](https://github.com/taurusxin/ncmdump) 这个 C++ 命令行工具搬到 Android 上的图形前端。

选一个或多个本地 `.ncm` 文件（网易云音乐的加密缓存格式），应用会调用随 APK 一起分发的
**原生 ncmdump 命令行程序**把它们转换成 `.flac` / `.mp3`，并写回歌曲的标题、艺术家、专辑和封面。

> 转换内核是上游 ncmdump 的原始 C++ 源码，一行未改，用 Android NDK 交叉编译成可执行文件。
> 本项目只负责「找文件 → 调命令行 → 报结果」这一层前端逻辑。

**本项目的开发灵感来自 [lilyco-42/ncmdump-android](https://github.com/lilyco-42/ncmdump-android)** ——
它同样选择用 Android NDK 复用上游 C++ 代码，而不是用 Kotlin 重写解密逻辑。原生二进制的打包方式、
批量转换、自动定位音乐目录等思路都受它启发，特此致谢。两者的区别见文末「与 lilyco-42 版本的差异」。

---

## 功能

- **批量转换**：一次选多个 `.ncm`，串行调用 `ncmdump -o <目录> <文件>`，单个失败自动跳过并继续
- **顶部实时计数**：待转换（默认色）/ 成功（绿）/ 失败（红），转换过程中逐个更新
- **保存位置**：没设置默认目录时，每次点「开始转换」都会弹出目录选择器；设置过默认目录就直接用
- **自动识别网易云目录**：内置已知路径清单 + 目录名启发式搜索（`cloudmusic` / `netease` / `com.netease.cloudmusic`）
- **扫描本地 ncm**：通过 SAF 选择任意文件夹递归扫描，或开启「所有文件访问权限」后全盘深度扫描
- **转换后删除源文件**：可选开关
- **随时取消**：转换过程中点悬浮按钮即可中断当前进程
- **一键清空列表**：顶部栏垃圾桶图标（或「清空列表」chip），带二次确认，清空后三个计数归零，方便换一批 ncm 继续转
- **内置目录选择器**：拿到「所有文件访问权限」后选目录不再启动系统的 DocumentsUI，
  瞬间打开、没有系统授权确认框，而且能选系统 SAF 不允许授权的 `Download` 根目录

## 截图

> 竖屏、Jetpack Compose + Material 3、Android 12 及以上跟随系统动态取色，
> 以下回退到网易云红 `#C20C0C`。

## 技术方案

### 为什么是「原生可执行文件」而不是 Kotlin 重写

`ncmdump` 是 C++17 项目（CMake + TagLib + zlib），不是 Rust，所以不存在「cargo 交叉编译」这条路。
可选的方案有三种：

| 方案 | 说明 |
| --- | --- |
| **A. NDK 交叉编译上游源码为可执行文件**（本项目采用） | 真正的「调用 ncmdump 命令行」，与上游行为零偏差；只需一份 `native/build.sh` |
| B. Kotlin 重写解密逻辑 | 不需要 NDK、APK 更小，但 NCM 解密 + FLAC/MP3 标签写入全要自己实现，容易出细节 bug |
| C. NDK 编译成 JNI 共享库 | 最稳，但要把 CLI 改造成库接口，不再是「命令行」 |

选 A。

### 怎么在 Android 10+ 上执行自带二进制

Android 10 起禁止应用 `exec()` 自己私有目录里的文件（W^X 策略）。
但 **`applicationInfo.nativeLibraryDir` 是豁免的**，于是：

1. 编译产物命名为 `libncmdump.so`（必须以 `lib` 开头、`.so` 结尾，Android 才会当作 native library）；
2. 放进 `app/src/main/jniLibs/<abi>/`；
3. `packaging { jniLibs { useLegacyPackaging = true } }` 强制把库解压到磁盘（而不是留在 APK 里 mmap）；
4. 运行时用 `ProcessBuilder("$nativeLibraryDir/libncmdump.so", …)` 直接执行。

### 输入输出路径怎么来

原生程序只认真实路径，而 Android 的存储模型有两套，所以应用同时支持：

- **有「所有文件访问权限」时**：直接用真实路径，输入输出都零拷贝；
- **只有 SAF 时**：把选中的 `content://` 文档复制到应用缓存目录再转换，产物再通过
  `DocumentsContract.createDocument` 写回用户选定的目录树。

SAF 拿到的目录树 URI 会尽力反解成真实路径（`primary:Music/ncm` → `/storage/emulated/0/Music/ncm`），
反解成功且可写时自动走第一种模式。

## 构建

### 依赖

- JDK 17
- Android SDK：platform 36、build-tools 36、NDK r28+、CMake 3.22+
- Python 不需要，但构建原生部分需要 `git`（拉 submodule）

### 步骤

```bash
git clone --recursive https://github.com/kc0ver/ncmdump-android.git
cd ncmdump-android

# 1) 交叉编译原生 ncmdump（会先编译 TagLib 静态库，再编译 ncmdump 可执行文件）
./native/build.sh                  # 全部 ABI；也可以 ./native/build.sh arm64-v8a x86_64

# 2) 打包 APK
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

`native/build.sh` 支持这些环境变量：`ANDROID_HOME` / `ANDROID_NDK_HOME`、`ANDROID_API`（默认 26）、
`CMAKE_BIN`、`NINJA_BIN`。

### release 签名

release 构建的签名信息从**仓库根目录之外**的 `keystore.properties` 读取，该文件与 `.jks`
都在 `.gitignore` 里，私钥不会进版本库：

```properties
# keystore.properties（gitignore 掉，不要提交）
storeFile=/absolute/path/to/ncmdump-release.jks
storePassword=********
keyAlias=ncmdump
keyPassword=********
```

生成自己的 keystore：

```bash
keytool -genkeypair \
  -keystore ~/keystores/ncmdump-release.jks \
  -storetype PKCS12 -alias ncmdump \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=你的名字, O=你的组织, C=CN"
```

然后 `./gradlew :app:assembleRelease`。R8 混淆 + 资源压缩后 APK 约 **2.4 MB**
（debug 版是 12.6 MB），原生 ncmdump 二进制在 `jniLibs` 里不受 R8 影响。

构建脚本里显式打开了 **v1 + v2 + v3 三种签名方案**。AGP 在 `minSdk >= 24` 时默认
只签 v2，产物里没有 `META-INF/*.SF`，`jarsigner -verify` 会报 `no manifest`，
不少「APK 签名检测」工具因此把它判成「未签名」。对 minSdk 26 来说 v1 技术上不是必需，
但加上它兼容性最好，也省得被误判。校验：

```bash
apksigner verify --min-sdk-version 21 -v app-release.apk   # v1/v2/v3 都应为 true
apksigner verify --print-certs app-release.apk             # 确认 DN 是自己的，不是 Android Debug
jarsigner -verify app-release.apk                          # 应输出 jar verified.
```

> 注意别把 `app-debug.apk` 当成发布包：它由 `~/.android/debug.keystore` 签名，
> 证书是 `C=US, O=Android, CN=Android Debug`。

> ⚠️ **keystore 必须备份**（密码管理器 / 网盘）。同一个包名的应用只能用同一把密钥签名，
> 密钥丢了就再也无法给已安装的用户推送更新 —— 除非你使用 Google Play App Signing 托管。

没有 `keystore.properties` 时 release 仍可构建，只是产物未签名。

## 目录结构

```
app/src/main/java/com/kc0ver/ncmdump/
├── MainActivity.kt               # Compose 入口
├── NcmViewModel.kt               # 全部状态与转换调度
├── core/
│   ├── Converter.kt              # 单个文件的转换流程（输入准备→调 CLI→校验产物→发布）
│   ├── NcmdumpRunner.kt          # 执行原生命令行、解析输出
│   └── Storage.kt                # SAF ↔ 真实路径、全文件访问权限
├── data/
│   ├── AppPrefs.kt               # 设置持久化
│   ├── NcmScanner.kt             # 真实目录 / SAF 目录树 / 全盘扫描
│   └── NeteaseLocator.kt         # 网易云音乐目录识别
├── model/Models.kt               # NcmSource / NcmItem / OutputTarget
└── ui/                           # Compose 界面（ConverterScreen、SettingsSheet、theme）

native/
├── CMakeLists.txt                # 把上游 main.cpp 编成 libncmdump.so
└── build.sh                      # TagLib + ncmdump 的全 ABI 交叉编译脚本

third_party/
├── ncmdump/                      # git submodule -> taurusxin/ncmdump
└── taglib/                       # git submodule -> taglib/taglib v2.0.2
```

## 已知限制

- 网易云音乐 3.0 之后部分版本的 ncm 文件不再内置封面图，此时转换出的文件没有封面（上游行为一致）。
- 「自动识别网易云目录」依赖目录名启发式匹配；如果网易云改了目录结构，请用「扫描文件夹」手动指定。
- 全盘「深度扫描」受 20 秒 / 2000 个文件 / 8 层深度的预算限制，超大存储卡可能扫不全。
- 「所有文件访问权限」会导致应用无法上架 Google Play（需要额外提交用途声明）。
- **系统目录选择器的打开速度不受本应用控制**：`DocumentsUI` 的 `PickActivity` 在
  Android 16 x86_64 模拟器上实测 START → 首帧冷启动约 **1.41 s**、热启动约 **0.68 s**，
  而应用从点击到 `startActivity` 只花不到 0.1 s。既然快不了，就①尽量不打开它
  （有权限时走内置选择器）②打开时加 `EXTRA_LOCAL_ONLY` 跳过云端 provider 的查询
  ③带上 `EXTRA_INITIAL_URI` 直接落在上次用过的目录。

## 许可证

本项目基于 **MIT** 发布，见 [LICENSE](LICENSE)。

第三方组件：

- [taurusxin/ncmdump](https://github.com/taurusxin/ncmdump) — MIT
- [taglib/taglib](https://github.com/taglib/taglib) — LGPL-2.1 / MPL-1.1 双许可，本项目按 **MPL-1.1** 静态链接
- zlib — 随 Android NDK sysroot 提供

## 致谢

- [taurusxin/ncmdump](https://github.com/taurusxin/ncmdump) —— 转换内核，全部解密工作由它完成（MIT）。
- [lilyco-42/ncmdump-android](https://github.com/lilyco-42/ncmdump-android) —— **本项目的灵感来源**。
  它把「用 Android NDK 直接复用上游 C++ 代码」这条路走通了，本项目沿着同样的思路重做了一遍，
  在此致谢。
- [taglib/taglib](https://github.com/taglib/taglib) —— 写入 FLAC/MP3 标签与封面。

## 与 lilyco-42 版本的差异

两者都把上游 C++ 编成原生二进制，区别主要在前端与存储策略：

| | 本项目 | lilyco-42/ncmdump-android |
| --- | --- | --- |
| 输入方式 | 系统文件选择器 / SAF 目录树 / 网易云目录自动识别 / 全盘深度扫描 | 批量选择文件 |
| 保存位置 | 用户指定；未设默认目录时每次询问 | 固定 `Music/ncmdump/` |
| 存储权限 | SAF 与「所有文件访问权限」两条路都支持，无权限也能用 | 见其文档 |
| 封面图 | 由 TagLib 内嵌进音频文件 | 另存为独立图片文件 |
| 界面语言 | 简体中文 | 中 / 英可切换，支持自定义翻译包 |

## 免责声明

本工具仅用于格式转换。请勿用于传播未获授权的版权内容，转换后的文件请自行承担合规责任。
