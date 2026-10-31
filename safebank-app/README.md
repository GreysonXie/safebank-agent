# SafeBank Android 客户端

基于后端 [`safebank-agent-t020-integration`](../safebank-agent-t020-integration/safebank-agent-t020-integration)（Java 21 / Spring Boot 3.5 / MyBatis + Python LangGraph）实现的 Android 客户端，用 **Kotlin + Jetpack Compose** 复刻 Web 工作台并完整实现「**预览 → 逐动作授权 → 提交银行 → 查单/回执**」的两步授权流程。

- 包名：`cn.safebank.app`（应用名 **SafeBank**），单 Activity + Compose
- compileSdk **37** / targetSdk **37** / minSdk **24**（已启用 core library desugaring 以支持 `java.time`）
- 全部为**合成演示**：资金、身份、银行、模型均为本地合成数据；App 内不内置任何密钥
- **模型由服务端决定**：App 只展示服务端返回的 `session.mode / model`（模式徽章、会话信息），不提供模型选择/密钥配置入口

---

## 1. 项目简介

SafeBank 是一个「可信银行智能体」演示应用：用户可以用自然语言让助手分析账单、查询代扣、生成转账/储蓄操作；所有资金类操作都必须经过 **预览 → 用户逐项确认 → 授权 → 提交 → 回执/查单**，聊天中的“确认”不会直接执行银行操作。

App 覆盖 5 个底部 tab 与 9 个二级页，数据全部来自后端 HTTP API（会话 Cookie + CSRF 保护），失败统一转成中文可读提示。

## 2. 功能一览

底部 5 个 tab：

| 入口 | 内容 |
|---|---|
| **对话助手** | `/api/chat` 对话；模式徽章（OFFLINE_DEMO / MODEL_CONFIGURED / CONNECTION_VERIFIED / FAKE_AGENT / LANGGRAPH_*）；上下文记忆条（intent / state / slots、清空草案）；建议 chips；查询依据折叠（trace / results / answerSource / modelUsage）；澄清候选回填；错误横幅 +「核对原请求」（同 requestId 仅手动重试一次，绝不自动重放）；内联**待确认操作卡**（勾选确认 → 刷新风险 → RED 验证码 → 授权 → 提交 → 回执 → 查单/取消） |
| **账单分析** | 账户卡片（余额、展开/刷新）；月份切换；收入/支出/本人内部转账汇总（内部转账不计收支）；支出分类条形图；交易明细；异常候选（含后端 anomaly_rule 原文） |
| **代扣管理** | 不直接调恒 409 的 `GET /api/mandates`；「确认查询范围」→ 生成 `QUERY_MANDATES` 动作 → 授权/提交 → 从回执 `data` 渲染代扣列表；逐条撤销预览 → 授权/提交 → 回执 |
| **储蓄任务** | ① 定时储蓄：表单校验 → `previewSchedule` → 授权/提交 → 已登记计划列表；② 联合任务：查询代扣多选 → `createWorkflow` → 列表/详情（步骤时间线、计划节点懒加载、待确认卡、查单推进、取消、改参，写操作带 version，409 自动刷新） |
| **更多** | 会话/服务器摘要 + 以下二级页入口 + 版本与合成数据说明 |

二级页（「更多」进入，部分从对话/任务详情跳转）：

| 入口 | 内容 |
|---|---|
| 准备一笔转账 | 账户/收款人精确检索（重名必须手选）、金额校验 → 预览 → 确认 → 授权/提交 → 回执/查单 |
| 金融推荐 | 风险偏好/用途/流动性/金额（元）/期限/本金偏好 → 后端筛选示例理财方向；展示候选（起投、风险、赎回 T+n、收益/回撤/年费、流动性说明、风险提示）与未匹配方向、筛选规则；只读、不产生申购 |
| 卡片管理 | 卡片列表（卡号掩码、余额、单笔/日限额、在线/境外开关）；申请新卡、调整限额、锁定/解锁、调整支付开关、挂失（均走预览 → 确认）；操作历史可确认/取消 |
| 账单扩展 | 年度账单（每月一行 + 合计 + 覆盖率）；单月「类别 + 收支方向 + 单笔金额下限」组合筛选（合计由服务端重算） |
| 用户与联系人 | 个人资料查看/保存；联系人列表；新增/编辑联系人（姓名、手机、性别、备注、归属账户、初始余额、储蓄目标） |
| 待确认操作与回执 | `GET /api/audit` 取最近动作逐条查单回填；待确认/历史分栏；取消、确认提交、查单 |
| 实验与审计 | 审计事件、当日风险额度、模拟执行（`run-due`）、故障注入面板（仅 `faultsEnabled` 时显示） |
| 设置 | 服务器地址编辑/恢复默认、连通性自检、会话信息（模式/模型/认证/CSRF 脱敏） |
| 联合任务详情 | 状态/预计节省/步骤时间线/计划节点/待确认卡/查单推进/取消/改参 |

授权规则（与后端契约一致）：

- 预览返回 `AWAITING_AUTH`，`authorize` 与 `submit` 严格两步、不可合并；
- `needsRefresh=true` 或 `decisionId` 为空时，点击确认会先 `POST /risk/preview` 取权威判定；
- `riskLevel=RED` 需要 `verification-challenges → verify → verificationProofId` 才能授权；默认 profile 未启用演示验证，会返回 503 `VERIFICATION_UNAVAILABLE`，需要以 `risk-demo` profile 启动后端；
- `PENDING` / `UNKNOWN` 只允许按原 `actionId` 查单，不允许新建相同动作；提交类失败后读回同一动作最新状态。

## 3. 技术栈与结构

| 项 | 选择 | 说明 |
|---|---|---|
| 语言/UI | Kotlin 2.2.10 + Compose（Material 3） | 单 Activity + Compose |
| 构建 | AGP 9.4.1 / Gradle 9.6 / JDK 17 | 使用 AGP 9 内置 Kotlin 支持 |
| 网络 | OkHttp 4.12 | 内存 CookieJar（JSESSIONID）+ 自动 CSRF 头 + 401 重建会话并重放一次；只读 GET 遇网络错误会丢弃旧连接重试一次，写操作不自动重试；普通 20s / chat 130s 超时 |
| JSON | Gson 2.11 | 旧模块响应 snake_case 用 `@SerializedName`；t020 新模块（卡片/金融/用户等）为 camelCase；`InstanceCreator` 保留 Kotlin 默认值 |
| 导航 | 手写返回栈 `AppNavigator` | 不引入 navigation-compose |
| 状态 | `remember { XxxStateHolder(scope, repo) }` + Compose State | 不引入 ViewModel 库 |
| 依赖注入 | 手写 `AppContainer` | 不引入 Hilt/Koin |
| 签名 | 仓库内 `keystore/debug.keystore` | 避免依赖 `~/.android` |

```
app/src/main/java/cn/safebank/app
├─ SafeBankApplication.kt / AppContainer.kt / MainActivity.kt
├─ data/
│  ├─ model/Models.kt            后端 VO/DTO 的 Gson 映射（含 t020 卡片/金融/用户/账单扩展）
│  ├─ SafeBankRepository.kt      仓库接口（50+ 方法）
│  ├─ AndroidBankRepository.kt   真实实现
│  ├─ network/                   会话、Cookie、CSRF、错误解析、Gson 配置
│  └─ util/Formatters.kt         金额/日期/状态文案
└─ ui/
   ├─ theme/                     设计令牌（蓝红主题，强制浅色）
   ├─ components/                公共组件（卡片/徽章/按钮/表单/金额…）
   ├─ navigation/                外壳 + 底部导航 + 返回栈路由
   └─ screens/                   chat / actions / statement / accounts / mandates /
                                  savings / tools / audit / settings / more /
                                  cards / finance / statement_extras / users
```

数据与 UI 之间的唯一契约是 `SafeBankRepository`（`suspend` + `Result<T>`，失败统一为 `ApiException`，含业务 `code`、中文 `message`、`httpStatus`）。

---

## 4. 环境依赖

### 4.1 构建/开发环境（只运行 APK 不需要）

| 依赖 | 要求 | 本项目实测版本 |
|---|---|---|
| 操作系统 | Windows 10/11 64 位（macOS / Linux 亦可） | Windows 10 Home 25H2 |
| JDK | **17 及以上**（设置 `JAVA_HOME`） | JDK 17.0.18 |
| Android Studio | 2026.2 或更新（需识别 AGP 9.4.1） | 2026.2.1 |
| Gradle | 9.6（项目自带 wrapper，首次自动下载） | Gradle 9.6.0 |
| Android Gradle Plugin | 9.4.1（`gradle/libs.versions.toml`） | 9.4.1 |
| Kotlin | 2.2.10（AGP 9 内置 Kotlin 支持） | 2.2.10 |
| Android SDK Platform | **android-37**（compileSdk/targetSdk = 37） | `platforms/android-37.0` |
| Build Tools | 36.0.0 | 36.0.0 |
| Platform Tools（adb） | 任意近期版本 | 37.0.1 |
| 网络 | **首次构建需联网**访问 `google()` / `mavenCentral()` 下载 Compose、OkHttp、Gson 等依赖 | 依赖体积约 100–300 MB |
| 磁盘 | ≥ 5 GB 空闲（Gradle 缓存 + 构建产物） | — |
| 签名 | 不需要自备，项目内含 `keystore/debug.keystore`（alias `androiddebugkey`，密码 `android`） | — |
| NDK | **不需要**（无原生代码） | — |

### 4.2 运行环境（在本机跑 App）

**方式 A：Android 模拟器**

| 依赖 | 要求 |
|---|---|
| CPU 虚拟化 | BIOS 中开启 Intel VT-x / AMD-V（**必需**） |
| 虚拟化加速 | Windows 家庭版：安装 **AEHD**（Android Emulator Hypervisor Driver，需管理员/UAC，Hyper-V 需关闭）；Windows 专业版/企业版：启用 **WHPX**（Windows Hypervisor Platform） |
| 内存 | ≥ 8 GB（推荐 16 GB；AVD 分配 2 GB RAM） |
| 磁盘 | ≥ 10 GB 空闲（系统镜像解压约 2.4 GB + AVD 数据约 6 GB） |
| Android SDK 组件 | `platform-tools`、`emulator`（37.2.x）、`system-images;android-37.0;default;x86_64`（AOSP，下载 866 MB / 解压 2.4 GB；也可用 `google_apis` 约 2.0 GB） |
| 虚拟设备 | AVD 指向该镜像，例如 `SafeBank_API37`（x86_64 / 2 GB RAM / 1080×2340 / data 6 GB） |
| 宿主 CPU 架构 | x86_64 镜像（Apple Silicon / ARM 电脑用 `arm64-v8a` 镜像） |

检查加速是否可用：

```powershell
emulator -accel-check
# 期望：accel: 0  AEHD (version 2.2) is installed and usable.
```

**方式 B：Android 真机（不需要模拟器与加速）**

| 依赖 | 要求 |
|---|---|
| 手机 | Android 7.0+（APK minSdk 24） |
| 调试 | USB 数据线 + 开发者选项「USB 调试」（仅调试安装需要） |
| 安装 | `adb install -r app-debug.apk`；或直接把 APK 传到手机安装 |

### 4.3 后端（可选，不启后端也能运行 UI）

App 默认服务器地址 `http://10.0.2.2:8080`（模拟器指宿主机 127.0.0.1）。不启动后端时：

- ✅ 可正常启动、浏览 5 个 tab 与所有二级页、表单校验、错误提示；
- ⚠️ 所有数据页会显示连接失败（账单/对话/代扣/储蓄/卡片/金融/用户/账单扩展），这是预期行为。

---

## 5. 构建

前置：JDK 17+、Android SDK（platform android-37、build-tools 36.0.0）、首次构建可联网。

```powershell
cd C:\Users\15094\Desktop\shenzhen\safebank-app

# 命令行构建（推荐）：脚本会把 Gradle 用户目录与临时目录重定向到工作区
.\build.ps1 assembleDebug
.\build.ps1 test
.\build.ps1 lint

# 或直接用 wrapper（需自行保证 ~/.gradle 与 %TEMP% 可写）
.\gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 14 MB）。

用 Android Studio 打开本目录即可；`local.properties` 已指向本机 SDK。若在当前 DSH 沙箱内构建，必须用 `build.ps1`（沙箱禁止写 `~/.gradle` 与系统 `%TEMP%`，见 `docs/acceptance-report.md`）。

---

## 6. 运行与联调

1. **启动模拟器**（已有 AVD 时）：

   ```powershell
   emulator -avd SafeBank_API37 -no-snapshot -no-boot-anim -no-audio -gpu auto
   ```

   无 AVD 时用 Android Studio 的 Device Manager 下载 API 37 镜像并创建（或手工把镜像解压到 `%LOCALAPPDATA%\Android\Sdk\system-images\android-37.0\default\x86_64` 后创建 `~/.android/avd/<name>.avd/config.ini`）。

2. **安装并启动 App**：

   ```powershell
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   adb shell am start -n cn.safebank.app/.MainActivity
   ```

3. **启动后端**（在 `safebank-agent-t020-integration\safebank-agent-t020-integration` 目录，可选）：

   ```powershell
   # 方式一：Docker（含 MySQL / Java / Python）
   docker compose up --build -d --wait --wait-timeout 300

   # 方式二：本机脚本（需 Java 21 / Python 3.10+ / Maven）
   .\run.ps1 start
   ```

   后端默认监听 `http://127.0.0.1:8080`。演示建议用 `risk-demo,context-demo` profile（H2 内存库，含演示验证与故障注入）。

4. **模拟器连宿主后端**：App 默认 `http://10.0.2.2:8080`，通常无需修改。

5. **真机（USB）**：先做端口反向映射，再把 App「设置」里的地址改为 `http://127.0.0.1:8080`：

   ```powershell
   adb reverse tcp:8080 tcp:8080
   ```

6. **真机（同一 Wi-Fi）**：后端默认只绑定 127.0.0.1，需要让它监听局域网（如 `server.address=0.0.0.0`），然后在 App「设置」里填 `http://<电脑局域网IP>:8080`。

7. **自检**：打开「更多 → 设置 → 连通性自检」，确认会话建立、模式徽章、账户数量正常。

> 默认（无模型密钥）profile 为 `OFFLINE_DEMO`：对话由规则匹配 + 程序化回答，仍可演示账单分析、代扣查询/撤销、转账/定时储蓄预览与授权、联合任务、卡片管理、金融推荐。需要 RED 强验证或故障注入时用 `risk-demo` profile 启动后端。

---

## 7. 验证状态（本次交付）

| 项 | 结果 |
|---|---|
| `gradlew assembleDebug` | ✅ 通过 |
| 单元测试 `gradlew test` | ✅ 35 个用例（模型解析 / 错误体 / Cookie / CSRF / 金额格式化）全部通过 |
| `gradlew lintDebug` | ✅ 0 error（35 warning，均为依赖版本提示、未使用资源、KTX 建议等） |
| APK | ✅ `app-debug.apk`，package `cn.safebank.app`，minSdk 24 / targetSdk 37，INTERNET 权限，启动 Activity `MainActivity` |
| 模拟器运行 | ✅ Android 37 模拟器（AVD `SafeBank_API37`，AEHD v2.2 加速）实测：启动、账单数据、对话问答、代扣查询/授权/提交、转账授权/提交（回执 `08eac682…`） |
| 卡片管理 | ✅ 实测：申卡 → 确认生成卡片（余额 ¥51,544.80、`DEMO •••• 1019`）；锁定/解锁；调整限额（¥8,000/¥30,000）；在线/境外双开关；挂失（二次确认 → 已挂失） |
| 金融推荐 | ✅ 实测：起投 ¥1.00、赎回 T+0、收益/回撤/年费「暂无数据」、目录/数据日/生成时间、排除方向产品名完整 |
| 账单扩展 / 用户 | ✅ 实测：年度账单 2026 合计（支出 ¥29,315.20 / 收入 ¥54,200.00 / 113 条）；单月筛选（2026-09 支出 15 笔 ¥5,369.33）；新建联系人成功 |
| 后端联调 | ✅ 后端以 `risk-demo,context-demo` profile 启动（H2 内存库），App 经 `adb reverse` 连接 `127.0.0.1:8080` |

详细验收记录见 [`docs/t020-app-test-report.md`](docs/t020-app-test-report.md)、[`docs/acceptance-report.md`](docs/acceptance-report.md)。

## 8. 已知限制与后续可选项

1. **模型选择/密钥不在 App 端**：按架构决策，模型由服务端环境配置决定（`BANK_MODEL_*` / `bank.model.*`），App 只展示当前模式与模型名；后端 `/api/models/*` 目前无管理员校验，建议后续加权限与 `baseUrl` 白名单，再考虑作为运维工具暴露。
2. 默认 profile 下 RED 动作无法完成强验证（后端返回 503），需要 `risk-demo` profile；UI 已给出对应中文提示。
3. 对话页未做网关模式自动长轮询（`CONTEXT_PROCESSING` 等只提供手动「核对原请求」）；重启 App 后不恢复历史气泡（上下文仍可从服务端读取显示）。
4. 动作/任务状态以服务端为唯一事实源；对话页与「待确认操作」页各自持有卡片状态，跨页不会自动联动。
5. 后端新增能力尚未接入 App：`/api/statement/query`（通用区间查询）、`/api/actions`（动作列表）、`ACTIVATE_MANDATE` / `MANAGE_SCHEDULE` 两类新动作、`/api/model-budget`（仅 langgraph-agent profile）。
6. `docs/api-contract.md` 仍是旧版 35 端点，t020 的 66 端点请参考 `docs/t020-backend-changes.md`。
7. 各页面为并行开发，部分页面使用包内私有小组件（`ui/screens/*/`），未全部收敛到 `ui/components`；视觉令牌一致。
8. 未接入真实生物识别/MFA；验证码流程需要后端 `risk-demo` 演示验证服务。

## 9. 文档

- [`docs/api-contract.md`](docs/api-contract.md)：旧版 35 个端点契约（会话/CSRF、字段命名、状态机、错误码、时序）
- [`docs/t020-backend-changes.md`](docs/t020-backend-changes.md)：t020 后端 66 端点变更清单、启动方式、字段/语义变化
- [`docs/t020-app-changes.md`](docs/t020-app-changes.md)：App 本批更新分析与待测清单
- [`docs/t020-app-test-report.md`](docs/t020-app-test-report.md)：t020 功能验收、P0/P1 缺陷、修复复验与新功能接入记录
- [`docs/ui-design-spec.md`](docs/ui-design-spec.md)：Web → Compose 设计令牌、组件、文案清单
- [`docs/android-architecture.md`](docs/android-architecture.md)：包结构、分工、构建约定
- [`docs/repository-contract.md`](docs/repository-contract.md)：数据层用法与错误码处理建议
- [`docs/acceptance-report.md`](docs/acceptance-report.md)：基础验收记录（构建/单测/APK/环境约束）
