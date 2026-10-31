# SafeBank Android App｜T020 更新分析报告

> 角色：更新分析负责人（app-diff）｜产出时间：2026-10-31 21:2x
> 分析对象：`safebank-app`（Kotlin + Jetpack Compose，Android SDK 37 / minSdk 24）
> 对照后端：工作区 `safebank-agent-t020-integration`（源码整套快照时间戳 2026-10-31 18:52:31）
> 约束：**只读分析、未改一行代码、未运行 gradlew/gradle**（构建由 Lead 统一执行）。

---

## 0. 结论摘要（先读这 6 条）

1. **本批更新 = 新增两个二级页面**：「金融推荐」(Finance) 与「卡片管理」(Cards)，加上支撑它们的 6 个数据层方法 / 5 条新端点 / 8 个新响应模型。**底部 tab 没变，仍是 5 个**（对话/账单/代扣/储蓄/更多）。
2. **两个新页面对当前 t020 后端基本不可用**，且不是后端改字段导致的：App 端把这 8 个新模型全写成了 `snake_case`，而 t020 的卡片/金融 VO 是**裸 camelCase record**（全仓无全局命名策略、无 `@JsonProperty`）。后果是**静默空值**（不是崩溃）：余额 `¥0.00`、开关 `✗`、起投 `¥0.00`、目录版本空白。
3. **卡片管理还有第二处硬伤**：App 等 `AWAITING_AUTH` 才显示「确认变更/取消」，而 t020 的卡片操作状态只可能是 `AWAITING_CONFIRMATION / COMPLETED / CANCELLED`（`schema.sql` 有 CHECK 约束）→ **确认按钮永远不会出现，卡片变更流程走不完**。
4. **第三处**：t020 `UPDATE_CONTROLS` 要求 `onlineEnabled` 与 `overseasEnabled` **同时非 null**，App 每次只传一个 → 四个开关按钮（开启/关闭在线、开启/关闭境外）**必 400 VALIDATION_ERROR**。
5. **干净数据库里没有任何卡片种子数据**（`data.sql` / `risk-demo-data.sql` 都没有 `bank_cards` 插入，全仓唯一写入路径是 APPLY 的 confirm）→ 卡片页默认是「暂无卡片 / 暂无历史」空态，而 App **没有"申请新卡"入口**。要验收必须先造卡（本文 §3.2 给了 curl 步骤）。
6. **编译面无阻断**：19:13 有 `CardsScreenKt` / `FinanceScreenKt` 的编译产物，19:14 出了 APK（14.4 MB，晚于所有源码时间戳），21:13 落盘的单测结果 35/35 通过。静态检查只发现未使用 import 等 warning 级问题。

---

## 1. 本批更新总览

### 1.1 时间线（取自文件系统时间戳）

| 时间 | 事件 |
| --- | --- |
| 10-27 22:34–23:13 | 首版工程 + 文档 + 首个 debug APK（此时无卡片/金融模块） |
| **10-31 18:52** | t020 后端源码落地（整套同一快照，含 `CardManagementController` / `FinanceRecommendationController`） |
| **10-31 18:59–19:11** | 本批 9 个文件更新（见 1.2）；`cards/`、`finance/` 两个新包在 19:02 创建 |
| **10-31 19:12–19:14** | 重新编译，19:14:12 产出 `app-debug.apk`（14,412,225 B） |
| 10-31 21:13 | `app/build/test-results` 有一次单测落盘（5 个类 / 35 用例 / 0 失败） |

### 1.2 变更清单

| # | 文件 | 状态 | 本次变化（相对旧版） |
| --- | --- | --- | --- |
| 1 | `app/src/main/java/cn/safebank/app/data/model/Models.kt` | 改 | 追加两段模型：**金融推荐**（`FinanceProductVo` 459-476、`FinancePreferenceVo` 478-485、`FinanceCandidateVo` 487-490、`FinanceExclusionVo` 492-496、`FinanceRecommendationVo` 498-513、`FinanceRecommendationRequest` 515-522）；**卡片管理**（`BankCardVo` 526-540、`CardChangeVo` 542-552、`CardOperationVo` 554-566、`CardChangeRequest` 568-578、`CardConfirmRequest` 580-583）。原有银行侧模型未变。 |
| 2 | `app/src/main/java/cn/safebank/app/data/network/SafeBankJson.kt` | 改 | `responseModels` 注册表从 25 类扩到 33 类，新增 8 个（金融 5 + 卡片 3，见 89-98 行）。**注意副作用**：这些模型缺省字段会走 Kotlin 默认值 → 字段名对不上时得到 `""` / `0L` / `false` 而不是 `null`，即"静默错数据"。 |
| 3 | `app/src/main/java/cn/safebank/app/data/SafeBankRepository.kt` | 改 | 接口新增 6 个方法：`financeRecommend`(145-147)、`cards` / `cardOperations` / `previewCardChange` / `confirmCardOperation` / `cancelCardOperation`(149-159)。`previewCardChange` 的入参用了全限定名 `cn.safebank.app.data.model.CardChangeRequest`（多带了一个无用 import）。 |
| 4 | `app/src/main/java/cn/safebank/app/data/AndroidBankRepository.kt` | 改 | 实现上述 6 个方法（330-360），对应 5 条新路径，全部走既有 `resultOf` + CSRF 自动头机制，无需额外改动。 |
| 5 | `app/src/main/java/cn/safebank/app/ui/navigation/Destinations.kt` | 改 | 新增 2 个二级目的地：`Finance`（62-65，"金融推荐"）、`Cards`（67-70，"卡片管理"）。`tabs` 仍是 `Chat/Statement/Mandates/Savings/More`（72-75）。 |
| 6 | `app/src/main/java/cn/safebank/app/ui/navigation/SafeBankApp.kt` | 改 | `ScreenHost`（100-116）新增 `AppDestination.Finance -> FinanceRoute`、`AppDestination.Cards -> CardsRoute` 两个分支 + 2 行 import。**外层 Scaffold / 底栏 / 返回栈逻辑一字未改。** |
| 7 | `app/src/main/java/cn/safebank/app/ui/screens/more/MoreScreen.kt` | 改 | 「工具与实验」入口从 4 个变 6 个：在「准备一笔转账」与「待确认操作与回执」之间插入 **金融推荐**（101-104）、**卡片管理**（105-108）。会话摘要卡、版本号、`modeDescription` 文案未变。 |
| 8 | `app/src/main/java/cn/safebank/app/ui/screens/cards/CardsScreen.kt` | **新**（222 行） | 卡片列表 + 每卡 3 个操作按钮（锁定/解锁、在线开关、境外开关）+ 最近操作历史 + 待确认项的确认/取消。 |
| 9 | `app/src/main/java/cn/safebank/app/ui/screens/finance/FinanceScreen.kt` | **新**（261 行） | 推荐偏好表单（风险/用途/流动性/金额/期限/本金保障）+ 生成推荐 + 候选卡片 + 未匹配方向 + 筛选规则 + 免责声明。 |

> 本批更新**没有**新增/修改：`MainActivity`、`AppContainer`、`AppSettings`、`SafeBankHttp`、`CSRF/Cookie`、`ErrorParser`、`Formatters`、任何已有页面的业务代码。所以 5 个 tab 的行为理论上只受"后端换版本"影响，不受这次 App 改动影响。

### 1.3 没有同步更新的文档（本轮遗留）

| 文档 | 现状 | 缺口 |
| --- | --- | --- |
| `docs/api-contract.md` | 仍是 10-27 版（1445 行 / 35 端点） | **全文 0 次提到 cards / finance / 卡片 / 理财**；§5.1 的"响应体命名规则"也未覆盖新模块（见 §4.1） |
| `docs/repository-contract.md` | 10-27 版 | 方法表缺新增 6 个方法，页面负责人照此写页面会漏 API |
| `docs/android-architecture.md` / `docs/ui-design-spec.md` | 10-27 版 | 页面清单、组件选择规则未包含两个新页 |
| `docs/acceptance-report.md` | 10-28 版 | 仍写"5 tab + 5 个二级页"，实际已是 5 tab + 7 个二级页 |
| `MoreScreen.kt` 注释 | 第 37 行仍写"**4 个**二级入口" | 实际 6 个 |

---

## 2. 当前 App 功能地图

### 2.1 导航结构

- **底部 tab（5 个，未变）**：对话助手 `Chat` / 账单分析 `Statement` / 代扣管理 `Mandates` / 储蓄任务 `Savings` / 更多 `More`。
- **二级页面（push 进手写返回栈，显示自带标题栏 + 返回）**：准备一笔转账 `Transfer`(→`ToolsRoute`)、**金融推荐 `Finance`（新）**、**卡片管理 `Cards`（新）**、待确认操作与回执 `Actions`、实验与审计 `Audit`、设置 `Settings`、联合任务 `WorkflowDetail(workflowId)`。
- **入口现实**：两个新页**只能从「更多」页进入**；没有独立 tab，也没有从账单/储蓄页的深链。

### 2.2 页面 × repository 方法 × HTTP 路径 × 关键交互

| 分组 | 页面 | 作用 | repository 方法 | HTTP 端点 | 关键交互 / 校验 |
| --- | --- | --- | --- | --- | --- |
| tab | 对话助手 `ChatScreen` | 自然语言问数 + 生成操作卡 | `ensureSession` / `chat` / `createConversation` / `conversation` / `resetConversation`（`ChatState.kt`）；卡内动作走 `ActionCard` 的 `authorize`/`submit`/`cancel`/`refreshRiskPreview`/`createChallenge`/`verifyChallenge` | `POST /api/chat`、`POST/GET /api/conversations`、`…/context/reset`、`/api/actions/*` | **两步授权**：勾选确认→authorize→submit；RED 需 6 位验证码（挑战→校验→凭据）。输入校验：非空、`expectedContextVersion` 冲突处理 |
| tab | 账单分析 `StatementScreen` | 月度账单、收支汇总、类别、交易列表、异常候选 | `ensureSession` / `accounts` / `statement(month)` | `GET /api/accounts`、`GET /api/statement?month=` | 只读；月份切换 / 回到本月；`month` 空串走后端默认 2026-09 |
| tab | 代扣管理 `MandatesScreen` | 读银行侧代扣 → 逐项撤销 | `createQueryIntent` → 共享动作卡（authorize/submit）拿 `data`；`previewMandateRevoke` → 同一两步流程 | `POST /api/query-intents`、`POST /api/mandates/preview`、`/api/actions/*` | **故意不调 `GET /api/mandates`（恒 409）**；页内文案明确"确认查询范围"→授权→提交；撤销前必须预览 |
| tab | 储蓄任务 `SavingsScreen` / `WorkflowDetailScreen` | 定时转账预览 + 联合任务（撤销代扣→省出钱→建计划） | `accounts` / `recipients` / `previewSchedule` / `schedules` / `createQueryIntent` / `workflows` / `createWorkflow` / `workflow` / `workflowPlan` / `resumeWorkflow` / `reviseWorkflow` / `cancelWorkflow` | `GET /api/accounts`、`/api/schedules`、`/api/workflows*`、`POST /api/schedules/preview`、`POST /api/workflows/*` | **表单校验最完整**（`SavingsUi.kt` 500-527）：金额 `parseAmountToMinor`、`mandateIds` 非空、账户/收款人必选、日期与 `dayOfMonth` 校验；所有 workflow 写操作带 `version` |
| tab | 更多 `MoreScreen` | 会话摘要 + 6 个二级入口 + 版本 | `ensureSession` | `GET /api/session` | 展示 user/mode/model/authentication/baseUrl；失败可"重新建立会话"；**新入口：金融推荐、卡片管理** |
| 二级 | 准备一笔转账 `ToolsScreen` | 收款人检索 + 转账预览 | `accounts` / `recipients(query)` / `previewTransfer` | `GET /api/accounts`、`GET /api/recipients?query=`、`POST /api/transfers/preview` | 校验（95-141）：收款人必填、金额 `parseAmountToMinor`、来源账户必选；重名联系人**必须手选**；预览后进两步授权 |
| 二级 | **金融推荐 `FinanceScreen`（新）** | 按偏好筛选示例理财产品（**不申购、不动钱**） | `financeRecommend` | `POST /api/finance/recommendations` | 表单 6 项（3 组 Chip + 金额 + 期限 + 本金保障）；校验：金额 `≥100` 分、期限 `1–120` 月；单按钮，`enabled=!loading` 防重复；**无授权/提交两步**（纯读） |
| 二级 | **卡片管理 `CardsScreen`（新）** | 列卡 → 预览变更 → 确认/取消；看历史 | `cards` / `cardOperations` / `previewCardChange` / `confirmCardOperation` / `cancelCardOperation` | `GET /api/cards`、`GET /api/cards/operations`、`POST /api/cards/preview`、`POST /api/cards/operations/{id}/confirm`、`POST …/cancel` | **两步但不完整**：预览仅落一条待确认操作，"确认变更"从"最近操作"里点；**无输入校验**（没有金额/限额输入框）；开关逐项切换 |
| 二级 | 待确认操作与回执 `ActionsScreen` | 从 `/api/audit` 反推最近动作卡 + 历史 | `audit` / `action(id)` + 共享 `ActionCard` | `GET /api/audit`、`GET /api/actions/{id}`、`/api/actions/*` | 与对话页共用动作卡（两步授权/查单/取消/验证码） |
| 二级 | 实验与审计 `AuditScreen` | 审计事件、当日额度、到期任务模拟、故障注入 | `audit` / `riskSummary` / `schedules` / `runDue` / `injectFault` | `GET /api/audit`、`GET /api/risk/summary`、`GET /api/schedules`、`POST /api/simulation/run-due`、`POST /api/simulation/faults` | 日期 / actionId 输入校验；故障面板仅在会话 `faultsEnabled=true` 显示 |
| 二级 | 设置 `SettingsScreen` | 服务器地址、连通性自检、会话信息 | `ensureSession(force)` / `accounts` / `currentSession` | `GET /api/session`、`GET /api/accounts` | baseUrl 规范化 + 持久化 + 恢复默认；地址非法有中文提示 |

### 2.3 新增数据层能力（本批唯一新增的 HTTP 面）

| repository 方法 | HTTP | 说明 |
| --- | --- | --- |
| `financeRecommend(request)` | `POST /api/finance/recommendations` | 请求体 6 字段全 camelCase，与 t020 `FinanceRecommendationRequestDTO` 一致 ✅ |
| `cards()` | `GET /api/cards` | 无参数 |
| `cardOperations()` | `GET /api/cards/operations` | 操作历史（最新在前，UI 只取前 10） |
| `previewCardChange(request)` | `POST /api/cards/preview` | 请求体 `CardChangeRequest` camelCase，与 t020 `CardChangeDTO` 一致 ✅ |
| `confirmCardOperation(id, payloadHash)` | `POST /api/cards/operations/{id}/confirm` | body `{payloadHash, confirmed:true}`，与 `CardConfirmDTO` 一致 ✅ |
| `cancelCardOperation(id)` | `POST /api/cards/operations/{id}/cancel` | 空 body `{}` |

> **路径全部对得上 t020**（我已逐个核对 `CardManagementController` / `FinanceRecommendationController` 的 `@RequestMapping`）。**请求体也全部对得上。问题只出在"响应体如何解析"和"状态枚举语义"上。**

---

## 3. 待测清单（按 tab / 页面分组，含预期结果）

> 编号规则：`A~E` = 5 个底部 tab，`F` = 卡片管理，`G` = 金融推荐，`H` = 其它二级页，`X` = 横切项。
> 「预期」给的是**正确行为**；若实测不符，括号里指向 §6 的风险编号（R*）。
> 默认 profile（无 profile 启动）种子数据参照：`checking` 余额 1,243,000 分（¥12,430.00）、`travel` 5,820,000 分（¥58,200.00）、默认账单月 `2026-09`、代扣 `music`/`video`。

### A. 对话助手（tab）

| 编号 | 检查项 | 操作 | 预期结果 |
| --- | --- | --- | --- |
| A1 | 首屏可用 | 切到「对话助手」 | 无崩溃；顶部显示当前用户（"合成用户 / u1"）与模式徽标；输入框可输入 |
| A2 | 账单问答 | 输入「分析 2026-09 账单」→ 发送 | 返回中文文本 + 工具轨迹（可折叠）；`2026-09` 支出合计 ¥3,403.00（默认 profile） |
| A3 | 生成操作卡的两步授权 | 输入「给张三转 500 元」→ 生成卡 → 勾选确认 → 提交 | 卡上 riskLevel 应为 YELLOW（¥500 ≤ ¥1,000 阈值）；**先 authorize 再 submit，两步都成功**；回执状态 `VERIFIED_SUCCESS` 且回执号非空（R8：后端换版后此链路未回归） |

### B. 账单分析（tab）

| 编号 | 检查项 | 操作 | 预期结果 |
| --- | --- | --- | --- |
| B1 | 汇总卡 | 进入页面 | 收支汇总、净收支、内部转账排除说明均显示金额；`¥` 千分位格式正确 |
| B2 | 类别与交易 | 下滑 | 类别色块/列表、交易行（商户/类别/日期/金额正负）齐全；异常候选区按后端 `anomaly_candidates` 显示（默认库可能为空） |
| B3 | 月份切换 | 切到 `2026-08` 再「回到本月」 | 每次切换有加载态；切回后回到 `2026-09`；非法月份后端 400 时有中文错误条（R8） |

### C. 代扣管理（tab）

| 编号 | 检查项 | 操作 | 预期结果 |
| --- | --- | --- | --- |
| C1 | 拒绝直读 | 进入页面 | **不应**发出 `GET /api/mandates`；页面只显示"确认查询范围"按钮 + 说明（"恒 409"文案） |
| C2 | 查询范围→授权→提交 | 点「确认查询范围」→ 勾选 → 确认 → 提交 | 依次 `POST /api/query-intents` → `authorize` → `submit`；`submit` 返回 `data` 数组后渲染代扣列表（music ¥15.00/月、video ¥68.00/月）；**提交后必须携带 `data`**，再点"重新查询"要重新走一遍（R8） |
| C3 | 撤销预览 | 点某个代扣的「预览撤销」 | 生成 REVOKE 动作卡：商户、扣费金额、周期、`estimated_monthly_saving_minor`（仅 MONTHLY 有）、"仅撤销银行侧授权"文案 |
| C4 | 撤销完成 | 在卡上 授权→提交 | 回执 `VERIFIED_SUCCESS` 后该代扣显示"已撤销，不可重复撤销"；再点预览应 400 CONFLICT（R8） |

### D. 储蓄任务（tab）

| 编号 | 检查项 | 操作 | 预期结果 |
| --- | --- | --- | --- |
| D1 | 定时转账预览 | 选账户/收款人/金额 83.00/首期 2026-11-20/结束 2027-10-20 | 生成 SCHEDULE 卡（`first_date`/`day_of_month=20`/`timezone=Asia/Shanghai`/`insufficient_funds`）；**创建计划不立即扣款** |
| D2 | 表单校验 | 金额留空、金额填 3 位小数、日期留空、不选代扣 | 分别提示："请输入转账金额" / "金额无效：请输入最多两位小数的数字" / "请明确选定至少一项月度代扣" 等（`SavingsUi.kt` 500-527） |
| D3 | 联合任务创建 | 月度账单 → 勾选代扣 → 选择储蓄目标 → 创建 | `POST /api/workflows` 后进入详情；首个 step `revoke:music` 为 `AWAITING_AUTH`；`pendingAction` 渲染成可操作卡（R8） |
| D4 | 版本与推进 | 详情页「查单并推进」/「改参」/「取消」 | 均带当前 `version`；版本冲突时提示"任务版本已改变，请刷新后再操作"而不是重发；改参后旧待确认项失效 |

### E. 更多（tab）

| 编号 | 检查项 | 操作 | 预期结果 |
| --- | --- | --- | --- |
| E1 | 会话摘要 | 进入页面 | 五行：用户 / 会话模式 / 模型 / 认证方式 / 服务器（应为 `http://10.0.2.2:8080`）；模式说明条随 mode 变化；**不显示业务日期 `businessDate`（后端已返回，App 没用，见 §4.3）** |
| E2 | 入口数量与跳转 | 数入口 + 逐个点 | **6 个入口**（转账、金融推荐、卡片管理、待确认操作、实验与审计、设置）；每个都能进、返回键能回更多页 |
| E3 | 会话失败兜底 | 停掉后端 → 进入更多页 | 显示错误条 + 「重新建立会话」按钮；恢复后端后点它应成功（R8：`NETWORK_ERROR` 文案） |
| E4 | 版本与免责 | 滑到底 | "版本 1.0 · 合成银行数据 · 仅用于本地演示"（R13：文案写死） |

### F. 卡片管理（新增二级页，本轮重点）

> **前置条件（必须）**：干净库里没有卡片，且 App 无"申请新卡"入口。先用 Web 页面或 curl 造一张卡，否则该页永远空态、无法验收确认流程：
>
> ```bash
> # 1) 建会话拿 cookie + csrf
> curl -c c.txt http://127.0.0.1:8080/api/session
> # 2) 预览 APPLY（注意 limits 与两个开关都是后端必填）
> curl -b c.txt -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
>   -d '{"kind":"APPLY","accountId":"checking","label":"日常卡","cardType":"DEBIT",
>        "perTransactionLimitMinor":500000,"dailyLimitMinor":2000000,
>        "onlineEnabled":true,"overseasEnabled":false}' \
>   http://127.0.0.1:8080/api/cards/preview
> # 3) 确认（payloadHash 取上一步返回值）
> curl -b c.txt -H "X-CSRF-Token: <csrf>" -H "Content-Type: application/json" \
>   -d '{"payloadHash":"<payloadHash>","confirmed":true}' \
>   http://127.0.0.1:8080/api/cards/operations/<opId>/confirm
> ```

| 编号 | 检查项 | 操作 | 预期结果 |
| --- | --- | --- | --- |
| F1 | 页面可达 | 更多 → 卡片管理 | 能进入；顶部出现**两次标题**"卡片管理"（`DetailTopBar` + `PageHeader`，R12 观感问题） |
| F2 | 卡片列表真实字段 | 观察卡片行 | 应显示：`DEMO ××××1234`（`maskedNumber`）、账户标签（日常账户）、`DEBIT`、`v1`、`余额 ¥12,430.00 · 日限 ¥20,000.00 · 单笔 ¥5,000.00`、`在线✓ · 境外✗`。**若显示"余额 ¥0.00 / 日限 ¥0.00 / 单笔 ¥0.00 / 在线✗ 境外✗ / 标题退化成 label"，即命中 R1** |
| F3 | 锁定/解锁预览 | 点「锁定」 | 成功后刷新，"最近操作"出现一条新记录（kind=LOCK，状态应是"待确认"）。**记录的状态徽章文案会显示英文原文 `AWAITING_CONFIRMATION`（未中文化，R4）** |
| F4 | **确认变更（关键）** | 在"最近操作"里找刚生成的待确认项 | **应出现「确认变更」+「取消」两个按钮**；点确认后卡片状态变 `LOCKED`、可再「解锁」。**若只有徽章、没有任何按钮 → 命中 R2（P0）** |
| F5 | 在线开关（关键） | 点「开启在线」/「关闭在线」 | 预览成功后同上确认；确认后卡片 `onlineEnabled` 取反。**若直接弹错误条（多半是 `VALIDATION_ERROR 请明确选择线上与境外交易开关`）→ 命中 R3（P0）** |
| F6 | 境外开关（关键） | 点「开启境外」/「关闭境外」 | 同 F5；**同样大概率命中 R3** |
| F7 | 历史读取失败静默 | 停后端 → 刷新页面 | `cards()` 失败有错误条；`cardOperations()` 失败**无任何提示**（`CardsScreen.kt:73` 吞掉，R11） |
| F8 | LOST 卡处理 | 造一张 `LOST` 卡（REPORT_LOST 后） | **App 会显示「锁定」按钮，点了必 400 INVALID_STATE**（App 未处理 LOST 状态，R10） |

### G. 金融推荐（新增二级页，本轮重点）

> 参照 t020 `DemoFinanceDataProvider`：目录 8 个示例产品、`version=demo-finance-v1`、`source=SafeBank 本地合成产品库`、`asOf=2026-09-30`。
> 用 App 默认表单（稳健 / 备用金 / 随时支取 / 100000 分 / 6 个月 / 不勾本金保障）提交时，后端**确定性**返回：`candidates = 1`（`DEMO-CASH` 灵活备用金）、`exclusions = 7`、`evaluatedCount = 8`、`rules = 4` 条。

| 编号 | 检查项 | 操作 | 预期结果 |
| --- | --- | --- | --- |
| G1 | 页面可达与默认值 | 更多 → 金融推荐 | 能进入；三个 Chip 组默认 稳健/备用金/随时支取；金额框显示 `100000`、期限 `6`；**金额单位是"整数分"，与用户"元"心智冲突（R13）** |
| G2 | 校验（前端） | 金额填 `99`、期限填 `0` 或 `121` | 期望提示"金额至少为 ¥1.00（整数分）" / "持有期限须为 1–120 个月"，且**不发请求** |
| G3 | 返回结构与摘要 | 用默认值点「生成推荐」 | 应出现"推荐结果"卡：`summary` 文本（"在 8 个示例方向中找到 1 个匹配方向…"）、第 3 行元信息。**若元信息显示"目录  · SafeBank 本地合成产品库 · 生成 "（版本与时间空白）→ 命中 R5** |
| G4 | 候选产品卡片 | 看 `DEMO-CASH` 卡 | 应显示：`✓ 灵活备用金 · 示例活期存款` / 类别"活期存款" / `风险 较低风险` / `起投 ¥1.00` / `随时可取` / `本金保障` / `预期区间收益 —`（该产品无收益率）。**若"起投 ¥0.00"、且对货币基金/短债等出现"随时可取"（实际有赎回 T+n / 存期）→ 命中 R5** |
| G5 | 未匹配方向 | 下滑看"未匹配方向" | 应显示 7 条 `• 产品名：原因1；原因2…`。**若显示"• ：原因…"（产品名空白）→ 命中 R5** |
| G6 | 披露与规则 | 最下方"筛选规则" | 4 条规则 + 红色免责声明（`disclosure`，非空）。**缺 `riskNote` / `liquidityNote` / `annualFeeBps` / `redemptionBusinessDays` 的展示（R6 合规缺口）** |

### H. 其它二级页回归

| 编号 | 检查项 | 操作 | 预期结果 |
| --- | --- | --- | --- |
| H1 | 转账预览→两步授权 | 更多 → 准备一笔转账 → 选张三 → 500.00 → 生成预览 → 勾选 → 确认 → 提交 | 预览 200 得 ActionVO（¥500 为 YELLOW）；authorize→AUTHORIZED；submit→`VERIFIED_SUCCESS`；余额减少（R8） |
| H2 | 限额与余额校验 | 金额填 `10001.00` | 提示 `LIMIT_EXCEEDED`（单笔上限 ¥10,000）；余额不足场景提示 `INSUFFICIENT_FUNDS` |
| H3 | 待确认操作与回执 | 更多 → 待确认操作与回执 | 从 `/api/audit` 恢复最近动作卡；待确认/历史分段正确；卡上"刷新风险条件""查询最新状态""取消操作"可用（R8） |
| H4 | 实验与审计 | 更多 → 实验与审计 | 审计事件（≤50，倒序）、当日额度（`businessDate/committed/reserved/unverified`）、已登记计划；`run-due` 用今天日期；故障注入面板仅 `faultsEnabled=true` 时可见 |
| H5 | 设置页自检 | 更多 → 设置 → 改地址为 `127.0.0.1:8080` → 保存 → 连通性自检 | 地址规范化（补 `http://`、去尾斜杠）；自检返回会话 + 账户；非法地址有中文提示 |
| H6 | 联合任务详情 | 储蓄任务 → 任务列表点进去 | 摘要（版本/状态/预计节省）、待确认操作、步骤记录、计划节点（懒加载）齐全；节点依赖与状态文案正确（R8） |

### X. 横切项

| 编号 | 检查项 | 操作 | 预期结果 |
| --- | --- | --- | --- |
| X1 | 会话/CSRF | 冷启动后连续做一次 GET 和一次 POST | GET 不带 CSRF、POST 带 `X-CSRF-Token`；30 分钟空闲后 401 自动重建会话并重放一次，用户无感 |
| X2 | server 地址切换 | 设置页改错地址 | 全局 `NETWORK_ERROR` 有中文提示（"无法连接服务器"），不崩溃 |
| X3 | 底部导航 | 5 个 tab 来回切 | 切 tab 清空返回栈；二级页返回键（系统返回 + 标题栏返回）都正常 |
| X4 | 崩溃面 | 两个新页在后端返回异常/空数据时 | 空数组 → 空态；字段缺失 →（见 R1）不崩溃，但会显示空值 |

---

## 4. 与旧契约 `docs/api-contract.md` 的差异 & 待核对清单

### 4.1 为什么新模块会踩坑：旧契约的"记忆规则"在新模块失效

旧契约 §5.1 给的记忆规则是：

> 2. **响应体只要属于"银行动作/账户/账单/代扣/计划/审计"就是 snake_case**；属于"workflow / 风险 / 对话 / 计划节点 / 模型用量"的基本是 camelCase。

卡片与理财在业务上当然属于"银行"，App 端就按这条规则给 8 个新模型全打了 `@SerializedName("xxx_yyy")`。但 t020 里这两个新模块的 VO 都是**裸 record**（无 `@JsonProperty`），且后端**没有全局命名策略**：

- `application.properties` 只有 `write-dates-as-timestamps=false` 和 `accept-float-as-int=false`；
- 全仓源码 grep `PropertyNamingStrateg|SNAKE_CASE|JsonNaming` → **0 命中**；
- 对照：`ActionVO` / `StatementVO` / `MandateVO` / `AccountVO` 等**旧模块**确实带了 `@JsonProperty("payload_hash")` 一类注解。

**结论：旧契约的规则在 t020 只对"旧模块"成立；卡片/金融是 camelCase。** 这不是后端这一小时改的（t020 全套源码时间戳都是 18:52:31，同一快照），而是 App 端照着旧规则推错了。修法二选一：App 侧去掉这 8 个模型上的 `@SerializedName`（推荐，改动小且不动后端），或后端补 `@JsonProperty`（会动契约）。

### 4.2 已静态确认的不一致（App ↔ t020 源码，逐条可复核）

| # | 模块 | App 侧（`Models.kt` 行号） | t020 实际 | 后果 |
| --- | --- | --- | --- | --- |
| D1 | `BankCardVo` (526-540) | `account_id/account_label/balance_minor/card_type/masked_number/per_transaction_limit_minor/daily_limit_minor/online_enabled/overseas_enabled` | `BankCardVO` record：`accountId/accountLabel/balanceMinor/cardType/maskedNumber/perTransactionLimitMinor/dailyLimitMinor/onlineEnabled/overseasEnabled` | 除 `id/label/status/version` 外**全部取不到值** → 余额 ¥0.00、限额 0、开关全 ✗、卡号空白 |
| D2 | `CardOperationVo` (554-566) | `payload_hash/expected_version/created_at/expires_at/executed_at/receipt_id` | `CardOperationVO` record：`payloadHash/expectedVersion/createdAt/expiresAt/executedAt/receiptId` | **`payloadHash` 恒为空** → 即便点确认也 409 CONFLICT；时间列恒 "—" |
| D3 | `CardChangeVo` (542-552，响应里 `CardOperationVO.request`) | `card_id/account_id/card_type/per_transaction_limit_minor/daily_limit_minor/online_enabled/overseas_enabled` | `CardChangeDTO`（camelCase） | "最近操作"标题的 `${op.request?.cardId ?: "—"}` 恒为 "—" |
| D4 | 卡片操作状态枚举 | `CardsScreen.kt:209` 只认 `AWAITING_AUTH`；徽章映射（188-193）也只认 `AWAITING_AUTH/VERIFIED_SUCCESS/VERIFIED_FAILED/CANCELLED` | `CardManagementService` 写入 `AWAITING_CONFIRMATION / COMPLETED / CANCELLED`；`schema.sql:41` 有 `CHECK(status IN ('AWAITING_CONFIRMATION','COMPLETED','CANCELLED'))` | **确认/取消按钮永不出现 → 卡片变更无法完成**；徽章色与文案也不对 |
| D5 | 卡片开关语义 | `CardsScreen.kt:168-181` 每次只传 `onlineEnabled` 或 `overseasEnabled` 之一 | `CardManagementService.controls()` 要求两个开关**同时非 null**，否则 400 VALIDATION_ERROR | 四个开关按钮**必失败** |
| D6 | `FinanceProductVo` (459-476) | `minimum_amount_minor/minimum_horizon_months/lock_months/redemption_business_days/capital_protected/period_return_bps/max_drawdown_bps/annual_fee_bps/liquidity_note/risk_note` | `FinanceProduct` record 同名 camelCase（`minimumAmountMinor/...`） | 起投 ¥0.00、`lock_months=0` → 误显示"随时可取"、无收益/回撤行、风险提示缺失 |
| D7 | `FinanceExclusionVo` (492-496) | `product_id/product_name` | `Exclusion(productId, productName)` | "未匹配方向"产品名空白 → `• ：原因` |
| D8 | `FinanceRecommendationVo` (498-513) | `catalog_version/as_of/period_start/period_end/generated_at/evaluated_count` | `FinanceRecommendationVO` 对应 camelCase | 页脚"目录  · … · 生成 " 空白 |
| D9 | `FinancePreferenceVo` (478-485) | `amount_minor/horizon_months/capital_protection` | `preferences` 回显的是 `FinanceRecommendationRequestDTO`（camelCase） | `vo.preferences` 三个字段为 0/false（当前 UI 未渲染，属潜在坑） |
| D10 | `SessionVo`（旧模型，178-190 附近） | 只有 `user/csrf/mode/model/authentication/faultsEnabled` | t020 `SessionVO` **多了 `businessDate`** | 不崩（Gson 忽略未知字段），但"当前业务日"信息浪费；建议后续用于页面日期展示 |

> **为什么不会崩**：`SafeBankJson` 为这 8 个类都注册了默认实例（`SafeBankJson.kt:89-98`），JSON 里没有的键保留 Kotlin 默认值 `""`/`0L`/`false`。所以表现是"静默错数据"，**比崩溃更难发现**——验收时必须对照后端原始响应看数值。

### 4.3 需要等后端负责人确认的清单（App 侧不改，只核对）

- [ ] 卡片操作是否**只需要** `confirm`（无风险判定 / 无 `payloadHash` 校验之外的授权两步）？与 `/api/actions/*` 的两步模型（authorize→submit）语义是否刻意不同？若刻意不同，App 需要单独的"待确认卡片变更"流程文案。
- [ ] 卡片操作状态是否会扩展（如 `PENDING`/`FAILED`）？`COMPLETED` 是否等价于"执行成功"（目前 App 没有成功态视觉）。
- [ ] `REPORT_LOST` 后卡片 `status=LOST` 是否终态（t020 代码：`INVALID_STATE 已挂失卡片不可恢复`）？App 是否要先屏蔽所有操作。
- [ ] `POST /api/cards/preview` 的 `expiresAt` 是 10 分钟（`now+10min`），App 未展示倒计时；是否需要展示？
- [ ] 金融推荐的 `mode/source/catalogVersion` 取值口径（当前 `DEMO` / `SafeBank 本地合成产品库` / `demo-finance-v1`），App 文案"示例理财产品筛选，不产生真实申购"是否满足披露要求。
- [ ] `FinanceProduct.riskNote / liquidityNote / annualFeeBps / redemptionBusinessDays` 是否**必须**展示（合规/知情）；若必须，App 需补 UI（目前完全没渲染）。
- [ ] 默认 profile 下 `GET /api/cards` 返回 `[]` 是否为预期（无种子卡）？是否需要后端补演示卡种子？
- [ ] t020 新增的 `GET /api/actions`（动作列表）是否应替代 App"`/api/audit` → 逐条 `GET /api/actions/{id}`"的探测方式（App 未用新端点）。
- [ ] `agent-gateway` profile 下 `fail-on-unknown-properties=true`：App 的 cards/finance 请求体与 DTO 完全一致（无多余字段），但若后续加字段需注意。
- [ ] 旧的 5 个 tab 依赖的端点（`/api/session`、`/api/accounts`、`/api/statement`、`/api/audit`、`/api/risk/summary`、`/api/flows*`、`/api/chat`）在 t020 下是否有**除命名外的语义变化**（这条建议直接采用后端负责人的结论，我不重复分析）。

### 4.4 t020 新增、App 完全没覆盖的能力（潜在功能缺口，非本次缺陷）

| 后端能力 | 端点 | App 现状 |
| --- | --- | --- |
| 动作列表 | `GET /api/actions` | 未用（仍走 audit 反推） |
| 代扣激活 | `POST /api/mandates/activate/preview` | 未覆盖 |
| 计划管理与回款 | `POST /api/schedules/{id}/preview`、`GET /api/schedules/transfers` | 未覆盖 |
| 年度/筛选/季度账单 | `GET /api/statement/year`、`/filter`、`/query`、`/months`、`/years` | 未覆盖（App 只有 `?month=`） |
| 模型配置 | `GET/POST /api/models/**`、`GET /api/model-budget` | 未覆盖 |
| 用户管理 | `GET/POST /api/users`、`/profile` | 未覆盖 |
| 网关轮次 | `GET /api/agent/turns/{id}`、`/api/conversations/{id}/turns/{requestId}`、`/statement-query`、`/close` | 未覆盖 |
| 卡片单条查询 | `GET /api/cards/operations/{id}` | 未覆盖（App 用列表筛选） |

---

## 5. 未完成 / 占位 / 写死 / 静态检查

### 5.1 grep 结果汇总（全 `app/src` 范围）

| 关键词 | 命中 | 判定 |
| --- | --- | --- |
| `TODO` | 1 处：`app/src/main/res/xml/data_extraction_rules.xml:8`（Android 模板注释） | 无业务 TODO |
| `TODO(` / `NotImplemented` | 0（`ActionErrors.kt:29` 只是兜底文案 `t is NotImplementedError`） | 无未实现函数 |
| `onClick = {}` / `onClick = { }` | 0 | 无空点击 |
| `假数据` | 0（App 无本地假数据层，全部来自后端） | — |
| `mock` / `MOCK` | 仅 `LANGGRAPH_MOCK` 等后端 mode 字符串 | 正常 |
| `placeholder` | 均为输入框占位符（`FormField`/`OutlinedTextField`） | 正常 |
| `示例` | `FinanceScreen.kt:105/154`、`MoreScreen.kt:103`、`StatementScreen.kt:494` | 文案用语，正常 |

### 5.2 未完成 / 死代码 / 不完善（逐条）

| # | 位置 | 问题 |
| --- | --- | --- |
| U1 | `CardsScreen.kt`（整页） | **缺少卡片操作类型**：后端支持 `APPLY / UPDATE_LIMITS / UPDATE_CONTROLS / LOCK / UNLOCK / REPORT_LOST` 六种，App 只有 LOCK/UNLOCK/UPDATE_CONTROLS 三种；**没有"申请新卡"（APPLY）和"挂失"（REPORT_LOST）入口，也不能改限额（UPDATE_LIMITS）**。而干净库无卡、APPLY 又是唯一造卡途径 → 该页在真实环境里几乎不可用 |
| U2 | `CardsScreen.kt:73` | `cardOperations()` 失败被静默吞掉（`onFailure { /* 历史读取失败不阻断 */ }`），用户看不到任何提示 |
| U3 | `CardsScreen.kt:49` / `FinanceScreen.kt:54` | `@Suppress("UNUSED_PARAMETER")` 但 `navigator`/`container` **两个参数都用到了** → 注释/抑制是复制粘贴残留，属误导 |
| U4 | `SafeBankApp.kt:136-153` | `PlaceholderScreen`（"该页面正在实现中"）**定义后无人调用** → 脚手架死代码（可删） |
| U5 | `MoreScreen.kt:46,131` | `var toast` 从未被赋值，`PageToast` 永远不显示 → 多余通道（要么接上错误提示，要么删） |
| U6 | `app/src/test/.../ExampleUnitTest.kt` | 模板遗留测试（`2 + 2 = 4`），无业务价值 |
| U7 | `CardsScreen.kt` / `FinanceScreen.kt` | 顶部**双标题**：`DetailTopBar(navigator,"卡片管理")` + `PageHeader(title="卡片管理")`，视觉重复 |
| U8 | 组件体系 | 两个新页用旧组件集（`ui/components/*` + `DetailTopBar`）；其它二级页（转账/审计/设置/储蓄）用 `ui/screens/more/ToolkitComponents` + `DetailPageScaffold`。**同一 App 两套面板/徽章/提示条实现**，样式会不一致 |
| U9 | 动作卡三份实现 | `ui/screens/actions/ActionCard.kt`(42 KB)、`ui/screens/savings/ActionCard.kt`(28 KB)、`ui/screens/more/PendingActionFlow.kt` —— 授权/验证/提交逻辑重复三处，**契约一变要改三处** |
| U10 | `CardsScreen.kt` 错误态 | 每行 `CardRow` 用 `remember` 存行内错误，列表重排时可能串行（低概率观感问题） |

### 5.3 写死值 / 硬编码

| # | 位置 | 硬编码内容 | 影响 |
| --- | --- | --- | --- |
| H1 | `FinanceScreen.kt:69` | `amountMinor = "100000"`（注释 ¥1,000.00） | 默认值本身合法；但输入框标签是「金额（整数分）」→ 用户按"元"填会**差 100 倍**（填 1000 实际 ¥10.00）。建议改成元输入 + `Formatters.parseAmountToMinor` |
| H2 | `FinanceScreen.kt:66-71` | 默认 `CONSERVATIVE / RESERVE / IMMEDIATE`、期限 6 | "预设答案"，掩盖筛选能力（可接受，但验收时要手动换值） |
| H3 | `CardsScreen.kt:188-193,209` | 状态字符串字面量 `AWAITING_AUTH`/`VERIFIED_SUCCESS`/`VERIFIED_FAILED`/`CANCELLED` 散落在 UI 里 | **与后端枚举强耦合且已过期**（R2 的根因），应改用 `Formatters.actionStatusLabel` 风格集中映射 |
| H4 | `MoreScreen.kt:173-180` | 6 种 mode 的中文文案写死 | 新增 mode 会掉进 default（"模型已配置 · 未验证"） |
| H5 | `MoreScreen.kt:51-55`、`StatementScreen.kt:165` | 版本号兜底 `"1.0"`、页脚 "SafeBank v0.7" | 版本展示口径不一致（`build.gradle.kts` versionName 是 1.0） |
| H6 | `FinanceScreen.kt:186-190` | `bps / 100.0` 直接拼 `%` | 正常，但未做本地化千分位；`periodReturnBps/ maxDrawdownBps` 语义（区间收益 vs 历史回撤）未在 UI 说明 |

### 5.4 静态可编译性检查（未运行 Gradle）

| 检查项 | 结果 |
| --- | --- |
| 新增 import 是否存在 | ✅ `CardsRoute`/`FinanceRoute` 被 `SafeBankApp.kt` 引入（35-38 行），类/函数均存在 |
| 引用的组件是否定义 | ✅ 逐一核对签名：`SectionCard(title,subtitle,modifier,action,content)`、`PageHeader(title,subtitle,modifier,action)`、`InfoBanner(text,tone,modifier,action)`、`StatusBadge(text,tone,modifier)`、`BadgeTone.{Default,Warning,Authorized,Success,Danger,Neutral}`、`BannerTone.{Info,Success,Warning,Error}`、`EmptyState(title,hint,modifier)`、`LoadingRow(text,modifier)`、`SecondaryButton(text,onClick,enabled,modifier)` —— 新页调用方式全部匹配（含 `enabled = !loading`） |
| MoreScreen 的同包引用 | ✅ `PageTitle`/`SectionCard`/`KeyValueRow`/`SecondaryActionButton`/`PageToast`/`Tone` 都在同包 `ui/screens/more/ToolkitComponents.kt` 中定义（`internal`，同模块可见），参数匹配 |
| 数据层签名一致 | ✅ 接口 6 个新方法与实现类一一对应（`AndroidBankRepository.kt:330-360`）；`apiType<...>()` 泛型可用；`CardChangeRequest` 在接口里用全限定名，能解析 |
| 资源/字符串 | ✅ 新页不使用 `strings.xml` / drawable / 任何 `R.*`；`material-icons-core` 已含用到的图标 |
| 依赖 | ✅ 未新增依赖，`build.gradle.kts` 未改 |
| 构建产物佐证 | ✅ `app/build/intermediates/built_in_kotlinc/debug/...` 有 `CardsScreenKt*`/`FinanceScreenKt*`（19:13:32）；APK 19:14:12（晚于最后源码 19:11:08）；21:13 单测 5 类 35 用例 0 失败 |
| 未使用 import（warning 级，不阻断编译） | `CardsScreen.kt`：`androidx.compose.foundation.lazy.items`；`FinanceScreen.kt`：`Box`、`fillMaxWidth`、`TextButton`、`EmptyState`；`SafeBankRepository.kt`：`CardConfirmRequest`、`MandateVo` |

> **结论：当前工作区源码无编译阻断项**（与 19:14 已出 APK 的事实一致）。上述 warning 建议顺手清掉，但不影响本次验收。

---

## 6. 风险点汇总

| 级别 | 编号 | 风险 | 触发条件 | 影响 | 建议 |
| --- | --- | --- | --- | --- | --- |
| **P0** | R1 | 卡片 3 个响应模型字段全错（snake vs camel） | 打开卡片页 | 余额/限额/开关/卡号/时间全为 0 或空 → 页面"看起来能开但全是假值" | 去掉 `BankCardVo`/`CardChangeVo`/`CardOperationVo` 上的 `@SerializedName` |
| **P0** | R2 | 卡片操作状态枚举不一致 | 预览任一卡片变更后 | **确认/取消按钮永不出现 → 卡片变更是死路** | 状态判断改为 `AWAITING_CONFIRMATION`（并加中文文案映射） |
| **P0** | R3 | `UPDATE_CONTROLS` 只传一个开关 | 点任一在线/境外按钮 | 必 400 → 开关功能全不可用 | 一次同时传 `onlineEnabled` + `overseasEnabled`（用当前卡的相反值） |
| **P1** | R4 | `payloadHash` 解析为空 | 若 R2 修好 | 确认请求带空 hash → 409 CONFLICT | 同 R1 一并修（都是 camelCase 问题） |
| **P1** | R5 | 金融推荐展示失真 | 生成推荐 | 起投 ¥0.00、假"随时可取"、无收益/回撤、排除项无名 → **数据不可信** | 同 R1（去掉 `Finance*Vo` 的 `@SerializedName`） |
| **P1** | R6 | 金融披露信息缺失 | 生成推荐 | `riskNote`/`liquidityNote`/`annualFeeBps`/`redemptionBusinessDays` 不展示 → 合规/知情风险 | 补 UI 或在页脚明示"完整风险说明见示例数据说明" |
| **P1** | R7 | 卡片功能不全（无 APPLY/REPORT_LOST/UPDATE_LIMITS） | 需要"申请新卡"或"改限额"或"挂失" | 干净库无卡且无法在 App 内造卡 → 该页无法自证可用 | 至少补 APPLY；或验收时用 curl 造卡（§3.2） |
| **P1** | R8 | 5 个 tab 的老链路未随新后端回归 | 后端换版 | 可能整体验收失败（与本次 App 改动无关，但验收必测） | 以代扣查询→授权→提交、转账预览→授权→提交为 smoke test |
| **P1** | R9 | 文档滞后 | 后续开发/验收参考 | 页面负责人按 `repository-contract.md` 写代码会漏 6 个方法；按 `api-contract.md` 解析新模块会继续错 | 本轮至少补 `api-contract.md` 两节 + `repository-contract.md` 方法表 |
| **P2** | R10 | LOST 卡仍显示"锁定" | 存在挂失卡 | 点击 400 INVALID_STATE，用户困惑 | 按 `status` 屏蔽按钮 |
| **P2** | R11 | `cardOperations()` 失败静默 | 后端抖动 | 历史区空白无提示 | 加错误条 |
| **P2** | R12 | 双标题 / 两套组件体系 / 动作卡三份实现 | 持续迭代 | 观感不一致、改契约成本×3 | 统一到 `ToolkitComponents` + `DetailPageScaffold`，抽取单一 `ActionCard` |
| **P2** | R13 | 金融金额输入单位是"分"、默认 100000 | 用户填表 | 差 100 倍误解 | 改"元"输入 + `parseAmountToMinor` |
| **P2** | R14 | 未使用 import / 死代码 / `@Suppress("UNUSED_PARAMETER")` 误用 | — | 噪声 | 顺手清理 |

---

## 7. 给 Lead 的验收建议路径（最短路径，约 30 分钟）

1. **起后端**（默认 profile）→ `GET /api/session` 应含 `user/csrf/mode/model/authentication/faultsEnabled/businessDate`。
2. **装 19:14 的 APK**，冷启动 → 落「更多」页（E1/E2），确认 6 个入口都在、会话摘要正常。 ← *确认新导航没把老页面弄坏*
3. **用 curl 造 1–2 张卡**（§3.2 命令）→ 进「卡片管理」：先验 F2（字段真实性，直接判 R1），再验 F4（确认按钮是否存在，判 R2），再验 F5/F6（开关是否 400，判 R3）。 ← *P0 三连，5 分钟能定性*
4. **进「金融推荐」** 用默认值提交一次（G3/G4/G5）：看"目录/生成时间是否空白""起投是否 ¥0.00""排除项是否无名" → 判 R5。
5. **老链路 smoke**：代扣管理「确认查询范围→授权→提交」；准备一笔转账「500 元→预览→授权→提交」（C2/H1）→ 判 R8（这一步同时能验证后端换版后 App 是否还活着）。
6. 有余力再补：储蓄联合任务（D3/D4）、实验与审计（H4）、设置自检（H5）。

> 若 3/4 步确认 P0/P1，建议**先把 R1~R5 作为 App 侧修复项排期**（工作量集中在 `Models.kt` 去掉 8 个 `@SerializedName` + `CardsScreen` 状态常量/开关参数 3 处小改），再跑一轮全量验收。

---

## 附录 A：证据索引（便于复核）

**App 侧**
- `app/src/main/java/cn/safebank/app/data/model/Models.kt:459-583`（金融 + 卡片模型）
- `app/src/main/java/cn/safebank/app/data/network/SafeBankJson.kt:62-99`（响应模型默认实例注册，含新增 8 类）
- `app/src/main/java/cn/safebank/app/data/SafeBankRepository.kt:145-159`、`data/AndroidBankRepository.kt:330-360`（新方法/新路径）
- `ui/navigation/Destinations.kt:62-75`、`ui/navigation/SafeBankApp.kt:35-38,100-116`（新目的地与路由）
- `ui/screens/more/MoreScreen.kt:96-121`（6 入口）
- `ui/screens/cards/CardsScreen.kt:62-76`（reload）、`161-181`（三个操作按钮）、`188-221`（状态映射与确认按钮）
- `ui/screens/finance/FinanceScreen.kt:66-93`（表单与校验）、`150-227`（结果渲染）

**后端（t020）侧**
- `safebank-agent-t020-integration/safebank-agent-t020-integration/src/main/java/cn/safebank/vo/BankCardVO.java`、`vo/CardOperationVO.java`、`vo/FinanceRecommendationVO.java`、`finance/FinanceProduct.java`、`dto/CardChangeDTO.java`、`dto/CardConfirmDTO.java`、`dto/FinanceRecommendationRequestDTO.java`（**均为裸 camelCase record**）
- `.../controller/CardManagementController.java`、`.../controller/FinanceRecommendationController.java`（路径核对）
- `.../service/CardManagementService.java`（`AWAITING_CONFIRMATION`/`COMPLETED`/`CANCELLED`；`controls()` 双开关必填；`APPLY` 校验）
- `.../service/FinanceRecommendationService.java`（筛选/排序/summary/rules 逻辑）
- `.../finance/DemoFinanceDataProvider.java`（8 个示例产品与目录元数据）
- `.../resources/schema.sql:36-42`（`card_operations` 状态 CHECK）
- `.../resources/application.properties`（无全局命名策略）
- `.../resources/data.sql`、`risk-demo-data.sql`（**无 `bank_cards` 种子**）

## 附录 B：本次分析边界

- 未运行 App、未连后端实际抓包：所有"字段/状态不匹配"的判断来自 **Gson 解析语义 + t020 源码静态比对**，属确定性推断；实际渲染表现建议按 §3 的编号逐项截图留证。
- 未运行 gradle：编译结论依据"19:13 编译产物 + 19:14 APK + 21:13 单测结果"三项本机遗迹，以及逐项签名/引用静态核对。
- 后端 5 个 tab 相关端点的**语义**变化不在本文范围（由后端负责人输出），本文只列"需与其结论对齐"的条目（§4.3 最后一条）。
