# SafeBank Android 客户端 · 架构与分工

> 本文件是团队协作基线。实现代码以仓库文件为准；本文件描述目录归属、公共契约与并行规则。

## 1. 目标与现状

- 后端：`C:\Users\15094\Desktop\shenzhen\safebank-agent-main`（Java 21 + Spring Boot + MyBatis + Python LangGraph）。
- Web 端：`src/main/resources/static/`（6 个模块 + 对话 + 逐动作授权）。
- Android：`C:\Users\15094\Desktop\shenzhen\safebank-app`，Kotlin + Jetpack Compose，**不引入额外 DI / 序列化 / 导航三方库**：
  - JSON：Gson（响应 snake_case 用 `@SerializedName`，请求全 camelCase）。
  - 网络：OkHttp 4.12 + 手写内存 CookieJar + CSRF 拦截器。
  - 导航：手写返回栈（`AppNavigator`），不用 navigation-compose。
  - 状态：`remember { XxxStateHolder(...) }` + Compose `mutableStateOf`，不用 ViewModel 库。
  - 中文文案**内联在 Kotlin 代码**里（单语言 demo，避免 strings.xml 多方冲突）。

## 2. 构建

```powershell
cd C:\Users\15094\Desktop\shenzhen\safebank-app
.\build.ps1 assembleDebug     # 或 .\gradlew.bat assembleDebug
.\build.ps1 test
```

- 必带环境：`GRADLE_USER_HOME=<workspace>\.gradle-home`、`JAVA_HOME=E:\Develop\java17`（`build.ps1` 已处理）。
- debug 签名用仓库内 `keystore/debug.keystore`（避免写 `~/.android`）。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`。
- SDK：compileSdk 37 / minSdk 24；模拟器访问宿主机后端用 `http://10.0.2.2:8080`，真机用局域网 IP，App 内「设置」可改。

## 3. 包结构（写作用域）

```
cn.safebank.app
├─ SafeBankApplication.kt        (Lead) 全局容器
├─ AppContainer.kt               (数据层) 设置 + 网络 + 仓库装配
├─ MainActivity.kt               (Lead) 入口，挂 SafeBankApp()
├─ data/
│   ├─ model/Models.kt           (Lead) 全部 DTO/Gson 模型
│   ├─ SafeBankRepository.kt     (Lead) 仓库接口（页面唯一数据入口）
│   ├─ AndroidBankRepository.kt  (数据层) 实现 + 错误映射
│   ├─ network/                  (数据层) OkHttp 客户端、Cookie/CSRF、ApiException、SettingsStore
│   └─ util/Formatters.kt        (Lead) 金额/日期格式化
├─ ui/
│   ├─ theme/                    (UI 基础) Color/Type/Shape/Theme
│   ├─ components/               (UI 基础) 通用组件
│   ├─ navigation/               (Lead) Destinations + AppNavigator + SafeBankApp 外壳
│   ├─ screens/chat/             (对话与授权) 对话页 + 上下文 + 待确认操作卡 + 回执
│   ├─ screens/actions/          (对话与授权) 待确认操作独立页 / 动作历史
│   ├─ screens/accounts/         (UI 基础) 账户与余额
│   ├─ screens/statement/        (UI 基础) 账单分析
│   ├─ screens/mandates/         (工具与审计) 代扣管理（确认查询 → 授权 → 提交）
│   ├─ screens/savings/          (储蓄任务) 定时储蓄 + 联合任务列表/详情/计划
│   ├─ screens/tools/            (工具与审计) 转账预览与确认
│   ├─ screens/audit/            (工具与审计) 审计 + 模拟
│   └─ screens/settings/         (工具与审计) 服务器地址、会话状态
```

规则：**只改自己作用域内的文件**。公共文件（`Models.kt`、`SafeBankRepository.kt`、`AppContainer.kt`、`ui/navigation/**`、`ui/theme/**`）改动必须先告知 Lead。

## 4. 数据层契约

- `AppContainer.settings: SettingsStore`：`baseUrl`（默认 `BuildConfig.DEFAULT_BASE_URL`）、`session`（`SessionVo?`）、`csrf`。
- `AppContainer.repository: SafeBankRepository`：所有端点，`suspend` + `Result<T>`（`ApiException` 带 `code/message/httpStatus`）。
- 会话：首次调用任意接口前自动 `ensureSession()`（GET `/api/session`，保存 Cookie + `csrf`）；`401 SESSION_REQUIRED` 时自动重建会话并重放一次。
- 所有非 GET 请求由 `CsrfInterceptor` 自动加 `X-CSRF-Token`。
- 金额一律 `Long` 分（`*_minor`），UI 用 `Formatters.money(minor)` 显示 `¥1,234.00`。

## 5. 页面与路由

底部 5 个 tab（设计文档推荐）：**对话 / 账单 / 代扣 / 储蓄 / 更多**。

| 路由 | 内容 | 负责人 |
|---|---|---|
| `Tab.Chat` | 对话、上下文记忆、建议、模式徽章、待确认操作卡内联 | 对话与授权 |
| `Tab.Statement` | 账户卡片 + 月份切换 + 收支汇总 + 分类 + 交易列表 + 异常候选 | UI 基础 |
| `Tab.Mandates` | 「确认查询范围」→ 生成 QUERY_MANDATES → 授权 → 提交 → 代扣列表；撤销预览 | 工具与审计 |
| `Tab.Savings` | 定时储蓄表单 → 预览 → 授权 → 提交；联合任务创建/列表/详情/计划/恢复/取消 | 储蓄任务 |
| `Tab.More` | 入口页：转账、审计与实验、设置 | 工具与审计 |
| `Tools`（二级） | 转账预览 → 待确认操作 | 工具与审计 |
| `Audit`（二级） | 审计事件 + 模拟执行 + 故障注入（debug） | 工具与审计 |
| `Settings`（二级） | 服务器地址、会话/模型模式、连通性自检 | 工具与审计 |
| `WorkflowDetail(id)`（二级） | 任务详情、步骤、计划节点、恢复/改参/取消 | 储蓄任务 |
| `Actions`（二级） | 待确认操作与回执列表（跨页常驻） | 对话与授权 |

## 6. 公共 UI 组件（`ui/components/`）

`PageHeader(title, subtitle, action)`、`SectionCard(title, subtitle, content)`、`StatusBadge(text, tone)`、
`EmptyState(title, hint)`、`PrimaryButton(text, onClick, enabled, loading)`、`SecondaryButton`、
`MoneyText(minor, signed)`、`LabeledField(label, content)`、`InfoBanner(text, tone)`、
`LoadingRow(text)`、`KeyValueRow(label, value)`、`Divider`。

页面内专属组件放在各自 `screens/**` 目录，不放入公共组件。

## 7. 并行与验证

- 每个负责人在自己的作用域内写代码；**不要跑 `gradlew`**（Lead 串行统一构建，避免 daemon/文件锁冲突）。
- 完成后自检：`grep` 自己的 import 是否都指向已存在文件；不要引用别人尚未提交的类。
- Lead 负责集成构建、修复跨界编译错误、最终 APK。
