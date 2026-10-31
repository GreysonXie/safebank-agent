# SafeBank Android 客户端 · 验收记录

日期：2026-10-27
工程：`C:\Users\15094\Desktop\shenzhen\safebank-app`
后端参考：`C:\Users\15094\Desktop\shenzhen\safebank-agent-main`（只读，未做任何修改）

## 1. 验收标准（用户确认）

- 功能范围：**全量对齐 Web 端**（对话助手、账单分析、代扣管理、储蓄任务、常用工具/转账、实验与审计 + 会话/CSRF + 预览→授权→提交→查单 + 联合任务/定时储蓄 + 真实后端 API）。
- 验证目标：**编译通过 + 产出 debug APK**（不依赖真机/模拟器）。
- 联网：允许 Gradle 构建时下载依赖（google()/mavenCentral()）；未下载模拟器系统镜像。

## 2. 交付物

| 交付物 | 路径 |
|---|---|
| Android 工程 | `safebank-app/`（Kotlin + Compose，55 个 Kotlin 文件 ≈ 12,000 行） |
| Debug APK | [`app/build/outputs/apk/debug/app-debug.apk`](../app/build/outputs/apk/debug/app-debug.apk)（14.3 MB） |
| 构建脚本 | [`build.ps1`](../build.ps1)（沙箱内 Gradle home / TEMP 重定向） |
| 使用说明 | [`README.md`](../README.md) |
| API 契约 | [`docs/api-contract.md`](api-contract.md)（35 个端点、字段命名差异、状态机、错误码） |
| UI 规范 | [`docs/ui-design-spec.md`](ui-design-spec.md)（设计令牌 / 组件 / 307 条文案 / Compose 映射） |
| 架构与分工 | [`docs/android-architecture.md`](android-architecture.md) |
| 数据层契约 | [`docs/repository-contract.md`](repository-contract.md) |

## 3. 模块覆盖

| 模块 | 实现的端点 / 能力 | 状态 |
|---|---|---|
| 会话与传输 | `GET /api/session`；内存 CookieJar；非 GET 自动 `X-CSRF-Token`；`401 SESSION_REQUIRED` 自动重建会话并重放一次；baseUrl 每请求现读 | ✅ |
| 账单分析 | `GET /accounts`、`GET /statement?month=` | ✅ |
| 代扣管理 | `POST /query-intents` → `authorize` → `submit`（读 `data`）；`POST /mandates/preview` → 授权/提交；**不调用恒 409 的 `GET /mandates`** | ✅ |
| 转账工具 | `GET /recipients?query=`；`POST /transfers/preview`；风险刷新 `GET/POST …/risk`；挑战 `verification-challenges` / `verify`；`authorize`；`submit`；查单 `GET /actions/{id}`；`cancel` | ✅ |
| 储蓄任务 | `POST /schedules/preview`、`GET /schedules`；`POST /workflows`、`GET /workflows`、`GET /workflows/{id}`、`GET /{id}/plan`、`resume`、`revise`、`cancel`（全部带 `version`） | ✅ |
| 对话助手 | `POST /conversations`、`GET /conversations/{id}`、`POST /{id}/context/reset`、`POST /api/chat`（conversationId + expectedContextVersion + requestId 幂等） | ✅ |
| 待确认操作/回执 | `GET /audit` → 最近 12 个 actionId → 逐条 `GET /actions/{id}`；取消/提交/查单 | ✅ |
| 实验与审计 | `GET /audit`、`GET /risk/summary`、`POST /simulation/run-due`、`POST /simulation/faults`（仅 `faultsEnabled` 显示） | ✅ |
| 设置 | baseUrl 持久化（SharedPreferences）、连通性自检、会话信息展示 | ✅ |
| 导航 | 底部 5 tab（对话/账单/代扣/储蓄/更多）+ 5 个二级页；手写返回栈 + 系统返回键 | ✅ |

授权安全语义（与后端契约逐条对齐）：
- 授权与执行严格两步；`payloadHash` / `decisionId` 原样回传，`userConfirmed=true` 显式证据；不发送已废弃的 `demoVerificationAccepted`。
- `needsRefresh` / 空 `decisionId` → 先 `risk/preview` 取权威判定。
- RED → `createChallenge` → 6 位验证码 → `verifyChallenge` → `verificationProofId`；默认 profile 的 503 `VERIFICATION_UNAVAILABLE` 有明确中文提示。
- `PENDING` / `UNKNOWN` 只允许按原 `actionId` 查单；提交类失败不自动重试，改为读回同一动作状态。

## 4. 验证证据（本机实测）

构建环境：Gradle 9.6.0（workspace 内 `.gradle-home`）、AGP 9.4.1、Kotlin 2.2.10、JDK 17.0.18、Android SDK platform 37 / build-tools 36.0.0。

```text
> .\build.ps1 assembleDebug
BUILD SUCCESSFUL

> .\build.ps1 test
app/build/test-results/testDebugUnitTest:
  ErrorParserTest      tests=9   failures=0
  FormattersTest       tests=8   failures=0
  ModelsJsonTest       tests=11  failures=0
  NetworkPrimitivesTest tests=6  failures=0
  ExampleUnitTest      tests=1   failures=0
  合计 35 tests / 0 failures

> .\build.ps1 lint
0 errors, 35 warnings, 6 hints （BUILD SUCCESSFUL）
```

APK 校验（`aapt2 dump badging`）：

```text
package: name='cn.safebank.app' versionCode='1' versionName='1.0'
compileSdkVersion='37'
minSdkVersion:'24'
targetSdkVersion:'37'
uses-permission: android.permission.INTERNET
uses-permission: android.permission.ACCESS_NETWORK_STATE
application-label:'SafeBank'
launchable-activity: cn.safebank.app.MainActivity
```

单元测试覆盖：响应字段 snake_case/camelCase 映射（ActionVO / 执行回执 / workflow）、`pendingAction: {}` 空对象、OFFLINE_DEMO 聊天响应缺 `results/answerSource/factReferences`、`clarification` 三种形态、错误体三形态（ErrorVO / RiskErrorVO / Spring 默认体）、Cookie 隔离与 CSRF 头、金额解析与格式化边界。

## 5. 构建环境适配（重要，供复现）

本机 DSH 沙箱对本仓库有额外限制，构建脚本已处理，**直接用 `build.ps1` 即可**：

1. `~/.gradle` 不可写 → `GRADLE_USER_HOME` 指向工作区 `<workspace>\.gradle-home`（并复用了本机已有 Gradle 缓存）。
2. 系统 `%TEMP%` 对子 JVM（Gradle/Kotlin daemon）写入被拒（`AccessDeniedException: …kotlin-compiler-*.alive`）→ 将 `TEMP`/`TMP` 指向工作区 `<workspace>\.tmp`。
3. 默认 debug keystore 位于不可写的 `~/.android` → 工程内置 `keystore/debug.keystore` 并配置 `signingConfigs.localDebug`（`app/build.gradle.kts`）。在正常桌面环境用 Android Studio 直接构建也可（Studio 会使用它自己的 debug keystore）。
4. `minSdk 24` 上使用 `java.time` → 已启用 core library desugaring（`desugar_jdk_libs 2.1.5`）。
5. 若 Gradle 守护进程在权限变更后仍报 `.alive` 相关错误：`.\gradlew.bat --stop` 后重跑 `build.ps1`。

## 6. 协作与任务板

5 条共享任务全部完成（均为独占写作用域，未发生公共文件冲突）：

| 任务 | 负责人 | 作用域 | 结果 |
|---|---|---|---|
| 数据层 OkHttp/CSRF/仓库/单测 | data-layer | `data/**` | 34 个端点实现 + 35 单测 |
| 设计系统 + 公共组件 + 账单/账户 | ui-shell | `ui/theme`、`ui/components`、`screens/accounts`、`screens/statement` | 主题/14 组件/账单页 |
| 对话 + 逐动作授权 | chat-actions | `screens/chat`、`screens/actions` | 对话/上下文/授权卡/回执 |
| 储蓄 + 联合任务 | savings-workflow | `screens/savings` | 定时储蓄 + workflow 全流程 |
| 工具与审计 | tools-audit | `screens/more/tools/mandates/audit/settings` | 5 个页面 |

Lead 负责：架构与公共模型/仓库接口/导航外壳、依赖与构建配置、集成构建、跨模块错误修复、lint 修复、文档与验收。

## 7. 已知边界（未验证部分，明确标注）

1. ~~未在真机/模拟器上运行~~ → **已于 2026-10-28 在 Android 37 模拟器上实测通过，见 §9。**
2. ~~未与真实后端做端到端联调~~ → **已于 2026-10-28 与本机后端完成端到端联调（账单、对话），见 §9。**
3. 默认 profile 下 RED 动作返回 503（需 `risk-demo` profile）；模拟/故障端点同理，UI 已给出对应提示。
4. 有意取舍：对话页未做网关模式自动长轮询；重启 App 不恢复历史气泡；对话页与「待确认操作」页不共享卡片状态（服务端为唯一事实源）；联合任务列表无创建/更新时间（模型无该字段）。
5. 视觉一致性：各页面并行开发，部分页面使用包内私有小组件，未全部收敛到 `ui/components`；设计令牌同源（`docs/ui-design-spec.md`）。

## 8. 复现步骤

```powershell
# 1) 构建 + 单测 + lint
cd C:\Users\15094\Desktop\shenzhen\safebank-app
.\build.ps1 assembleDebug
.\build.ps1 test
.\build.ps1 lint

# 2) 启动后端（另一个终端，在 safebank-agent-main 目录）
docker compose up --build -d --wait --wait-timeout 300

# 3) 安装（模拟器）
adb install -r app\build\outputs\apk\debug\app-debug.apk
#    真机 USB：先 adb reverse tcp:8080 tcp:8080，再到 App「设置」把地址改为 http://127.0.0.1:8080

# 4) 建议联调顺序
#    更多→设置→连通性自检 → 账单分析 → 定时储蓄（YELLOW，可完整授权提交）
#    → 联合任务（先「查询本人代扣」）→ 转账/代扣撤销 → 对话助手
```

## 9. Android 37 模拟器实测（2026-10-28 补充验收）

### 环境搭建

| 步骤 | 做法 | 结果 |
|---|---|---|
| 系统镜像 | 用户已下载 `x86_64-37.0_r06.zip` 并解压到 `Desktop\shenzhen\x86_64-37.0_r06`；安装到 `%LOCALAPPDATA%\Android\Sdk\system-images\android-37.0\default\x86_64\` | ✅ 245,126 个文件/2.4 GB 的 `system.img` 等齐全 |
| AVD | 无 `cmdline-tools/avdmanager`，改为手工创建 `~/.android/avd/SafeBank_API37.avd/config.ini` + `SafeBank_API37.ini` | ✅ `emulator -list-avds` 可识别 |
| 硬件加速 | Win10 家庭版无 Hyper-V/WHPX；从 Google SDK 仓库（`addon2-3.xml`）下载 `aehd-windows_v2.2.zip`（175,928 B，SHA1 `b3f53739…`，已校验），`silent_install.bat` 提权安装 | ✅ `sc query aehd` = RUNNING；`emulator -accel-check` = `accel: 0, AEHD (version 2.2) is installed and usable` |
| 启动 | `emulator -avd SafeBank_API37 -no-snapshot -no-boot-anim -no-audio -gpu auto` | ✅ 约 1 分钟完成启动（`sys.boot_completed=1`） |
| 后端 | 用 Studio 自带 JBR 25 + 缓存 Maven 依赖直接 `java -cp target/classes;cp.txt cn.safebank.Application`，profile `risk-demo,context-demo`（H2 内存库，含演示验证与故障注入） | ✅ `http://127.0.0.1:8080/api/session` = 200，OFFLINE_DEMO |
| 端口连通 | 后端只绑定 `127.0.0.1`，模拟器直连 `10.0.2.2:8080` 超时 → 改用 `adb reverse tcp:8080 tcp:8080`，并把 App baseUrl 预置为 `http://127.0.0.1:8080`（`run-as` 写 SharedPreferences） | ✅ 请求可达 |
| 安装/启动 | `adb install -r app-debug.apk` + `am start -n cn.safebank.app/.MainActivity` | ✅ `Success`，无 AndroidRuntime 崩溃 |

### 交互验证

| 项 | 结果 | 截图 |
|---|---|---|
| 账单分析 tab | ✅ 收入 ¥11,700.00 / 支出 ¥5,369.33 / 净收支 ¥6,330.67 / 已排除内部转账 ¥1,000.00；支出分类条形图（居住 2,500 / 维修 2,000 / 日用品 363.50 / 餐饮 184.24 / 订阅 95 / 通信 89）与后端种子数据一致 | `emulator-shot-3.png` |
| 对话助手 tab | ✅ 显示离线演示模式、已知会话 `合成用户 / u1`、上下文卡片；点击「分析账单」chip 后一轮真实 `/api/chat` 往返：意图变为“账单分析”、记忆“账单 2026-09”、回答含分类金额、内部转账排除说明、2 笔大额支出候选、“查看本轮查询依据（2 条轨迹）” | `emulator-shot-4.png`、`emulator-shot-5.png` |
| 底部导航 | ✅ 对话助手 / 账单分析 / 代扣管理 / 储蓄任务 / 更多 五个 tab 均可切换渲染 | 截图 1–5 |

### 复现命令

```powershell
# 后端（safebank-agent-main 目录）
$env:SPRING_PROFILES_ACTIVE='risk-demo,context-demo'
$env:BANK_DEMO_PEPPER='safebank-demo-pepper-0123456789abcdef'
$env:BANK_DEMO_OPERATOR_TOKEN='safebank-demo-operator-token-0123456789'
$env:BANK_DEBUG_BUDGET_PATH='<workspace>\safebank-agent-main\.demo\debug-budget.json'
& "<jbr>\bin\java.exe" -cp "target\classes;<cp.txt 内容>" cn.safebank.Application

# 模拟器 + App
emulator -avd SafeBank_API37 -no-snapshot -no-boot-anim -no-audio -gpu auto
adb install -r safebank-app\app\build\outputs\apk\debug\app-debug.apk
adb reverse tcp:8080 tcp:8080
adb shell am start -n cn.safebank.app/.MainActivity
```

> 注意：AEHD 为内核驱动（服务名 `aehd`），不需要时可 `silent_install.bat -u` 卸载；模拟器内 App 的 baseUrl 已持久化为 `http://127.0.0.1:8080`，若改用真机请改为电脑局域网 IP 或同样使用 `adb reverse`。
