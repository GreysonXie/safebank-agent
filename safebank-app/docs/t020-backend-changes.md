# T020 后端变更与接口清单（Android App 对接视角）

> 产出目的：供 Lead 在本机（Android 模拟器）验证 App 前，快速掌握"更新后的 Java 后端相比旧契约改了什么、多出了哪些接口、怎么在本机起起来、哪些字段会让 App 静默走错"。
> 编写方式：只读分析源码与文档，**未启动后端、未改后端源码、未跑 gradle**。所有结论都标了源码位置，便于复核。

---

## 0. 结论摘要（先看这 8 条）

1. **工程根**：`C:\Users\15094\Desktop\shenzhen\safebank-agent-t020-integration\safebank-agent-t020-integration`（外层目录里只有这一层同名嵌套，`pom.xml` 在内层）。
2. **版本**：`cn.safebank:safebank-agent:0.6.0`，Spring Boot 3.5.6 / Java 21 / MyBatis 3.0.5 / H2+MySQL / Flyway（`pom.xml:3-5`）。
3. **没有 t020 专属 profile**。全仓库搜 `t020` 只命中 `agent-service`（Python 侧）测试注释里的 `T020.2-R1/R2` 修复标记；Java 后端可激活的 profile 只有 `risk-demo`、`context-demo`、`agent-gateway`、`langgraph-agent`、`mysql`、`mysql-test`、`test`。
4. **App 侧 `/api/**` 端点从旧契约的 35 个增长到 66 个**：新增 31 个、删除 0 个、行为/字段有变化的约 7 个。
5. **新增 4 个业务域**：卡片管理 `/api/cards`（6 端点）、金融推荐 `/api/finance`（1）、用户/联系人管理 `/api/users`（5）、模型设置 `/api/models`（6）+ 模型用量 `/api/model-budget`（1）。另有账单分析扩展 5 个、储蓄计划管理 1 个、代扣开启 1 个、动作列表 1 个、计划到期转账 1 个、原请求恢复 3 个。
6. **启动最大障碍不是代码，而是依赖缓存**：`%USERPROFILE%\.m2\repository` **不存在**（`.m2` 下只有 `wrapper\dists`），工程内也没有 `.tools`、`.m2`、`target`。**当前无法离线构建/启动**，必须先联网做一次依赖解析（详见 §6）。
7. **默认 profile 在全新 H2 文件库上会直接启动失败**：`RiskSchemaInitializer` / `ContextSchemaInitializer` 只允许"全新内存库"或"已审核迁移的文件库"，否则抛 `IllegalStateException`。本机可用的最简组合是 `risk-demo,context-demo`（内存库，数据每次重启重置）。
8. **最可能让 App 出问题的是"新增模块的字段命名"**：后端新增的卡片/金融 VO 全部是 **camelCase**（无 `@JsonProperty`），而 App 的 `Models.kt` 对这两块用了 **snake_case 的 `@SerializedName`**（`Models.kt:465-566`）→ 卡片限额、卡号、起投金额、收益等字段会**静默取默认值**。详见 §8.1。

---

## 1. 工程根与关键文件

| 项目 | 路径 / 事实 |
| --- | --- |
| 工程根（含 pom.xml） | `...\safebank-agent-t020-integration\safebank-agent-t020-integration` |
| Maven 坐标 | `cn.safebank:safebank-agent:0.6.0`，parent `spring-boot-starter-parent:3.5.6` |
| 依赖 | web、validation、mybatis-spring-boot-starter 3.0.5、h2(runtime)、mysql-connector-j(runtime)、flyway-core、flyway-mysql、spring-boot-starter-test |
| 构建插件 | **只有** `spring-boot-maven-plugin`（parent 提供 `repackage`）；未声明 maven-jar-plugin |
| 主类 | `src/main/java/cn/safebank/Application.java` |
| 主配置 | `src/main/resources/application.properties`（8080、仅 127.0.0.1、H2 文件库 `./data/safebank`、`spring.sql.init.mode=always`、`flyway.enabled=false`） |
| 静态 Web | `src/main/resources/static/*`（同一进程提供 `/` 页面，非 App 依赖） |
| 契约/文档 | `README.md`、`AGENTS.md`、`CODING_STANDARDS.md`、`docs/`（无 T020 发布说明；最新为 2026-10-24 卡片、10-27 金融推荐、10-28 视觉升级/源码检查修复） |
| 旧契约 | `safebank-app/docs/api-contract.md`（35 端点，§4.1–4.35） |

---

## 2. 变更总览表

| 类别 | 数量 | 明细 |
| --- | --- | --- |
| App 可见 `/api/**` 端点（新后端合计） | **66** | 见 §4 |
| 旧契约已有、仍然存在 | 35 | 旧契约 §4 全部端点均保留 |
| **新增端点** | **31** | 卡片 6 / 用户 5 / 模型设置 6 / 账单扩展 5 / 原请求恢复 3 / 储蓄管理 1 / 代扣开启 1 / 动作列表 1 / 计划转账 1 / 金融推荐 1 / 模型用量 1 |
| 删除端点 | 0 | 无 |
| 行为或字段变化 | 6 | `/api/session`（+`businessDate`）、`/api/statement`（默认月语义）、`/api/schedules/preview`（日号 1..31）、`/api/workflows`(+`/revise`，日号校验放宽)、`/api/actions/{id}` 家族（可能返回新 kind）、`/api/mandates`（仍恒 409，但错误体为 `RiskErrorVO`） |
| 内部端点（非 App） | 7 | `/internal/agent/*`（agent-gateway）、`/internal/agent/model/*`、`/internal/agent/model-budget/*`（langgraph-agent） |
| 新增 ActionKind | 2 | `ACTIVATE_MANDATE`、`MANAGE_SCHEDULE` |
| 新增错误码（BankException） | **9** | 见 §5.2 |
| 新增风险码（RiskErrorVO） | 2 | `SCHEDULE_CONFIRMATION_REQUIRED`、`SCHEDULE_SCOPE_MISMATCH` |
| 旧文档中已退役的错误码 | 1 | `MODEL_REQUEST_BUDGET_EXCEEDED` 现源码已不再抛出（仅 `GatewayCoordinator.java:88,96` 做兼容映射、旧测试与前端分支还引用它）；现由 `MODEL_TURN_BUDGET_EXCEEDED` 承担 |

---

## 3. 新增业务域：功能在哪、用哪些接口、数据从哪来

### 3.1 卡片管理（cards）

- 模块：`controller/CardManagementController` → `service/CardManagementService` → `mapper/CardMapper.xml` → 表 `bank_cards`、`card_operations`（H2：`src/main/resources/schema.sql` 末尾；MySQL：`db/migration/mysql/V6__workspace_management.sql:10-27`）。
- 接口（6 个，全部 App 可见）：
  - `GET /api/cards` → `BankCardVO[]`
  - `GET /api/cards/operations` → `CardOperationVO[]`
  - `GET /api/cards/operations/{id}` → `CardOperationVO`
  - `POST /api/cards/preview` → `CardOperationVO`（待确认）
  - `POST /api/cards/operations/{id}/confirm` → `CardOperationVO`（已生效）
  - `POST /api/cards/operations/{id}/cancel` → `CardOperationVO`
- 数据来源：卡片是**账户之上的本地合成设置**，余额来自 `accounts`（同一账户多张卡共用余额）；申请时可选本人账户，卡状态 `ACTIVE/LOCKED/LOST`，卡类型 `DEBIT/VIRTUAL`，卡号仅存后 4 位、返回 `DEMO •••• 1234`（`CardManagementService.java:56-69,107`）。
- 关键业务规则（会影响联调预期）：
  - 预览有效期 **10 分钟**（`CardManagementService.java:37`，注意与动作预览的 5 分钟不同）。
  - 每人最多 **8 张未挂失卡**，超限 `LIMIT_EXCEEDED`（`:87`）。
  - 操作种类仅 `APPLY/UPDATE_LIMITS/UPDATE_CONTROLS/LOCK/UNLOCK/REPORT_LOST`；挂失 `LOST` 不可逆，再办卡是**新卡**（`:93,60`）。
  - 限额：单笔 ≥1、单笔 ≤ 日限额、日限额 ≤ 10,000,000 分（¥100,000），否则 `VALIDATION_ERROR`（`:104`）。
  - 确认必须 `payloadHash` 原样回传 + `confirmed=true`，否则 `CONFLICT`/`AUTH_REQUIRED`；重复确认幂等返回原回执；卡片版本变化后旧预览 `CONFLICT`（`:41-51`）。
  - 持久化：H2 内存库下重启即清空；MySQL 下才真正保留。

### 3.2 金融推荐（finance）

- 模块：`controller/FinanceRecommendationController` → `service/FinanceRecommendationService` → `finance/DemoFinanceDataProvider`（**纯内存静态目录，无 Mapper、无 DB、不调模型**）。
- 接口：`POST /api/finance/recommendations`（只读但走 POST，需要会话 + CSRF）。
- 数据：`mode=DEMO`、`source=SafeBank 本地合成产品库`、`catalogVersion=demo-finance-v1`、`asOf=2026-09-30`、收益/回撤区间 `2025-09-30 ~ 2026-09-30`；共 **7 个虚构方向**（`DEMO-CASH/MONEY/DEPOSIT/SHORT/BOND/BALANCED/INDEX`）；存款类收益/回撤/费率为 `null`，不得当 0（`DemoFinanceDataProvider.java:9-35`）。
- 请求枚举：`riskPreference=CONSERVATIVE|BALANCED|GROWTH`、`purpose=RESERVE|PLANNED|GROWTH`、`liquidity=IMMEDIATE|FLEXIBLE|LOCKABLE`、`amountMinor∈[100, 10^10]`、`horizonMonths∈[1,120]`、`capitalProtection=boolean`（`FinanceRecommendationRequestDTO.java:6-18`）。
- 响应：`FinanceRecommendationVO`（camelCase）= `mode/source/catalogVersion/asOf/periodStart/periodEnd/generatedAt/disclosure/preferences/evaluatedCount/summary/candidates[{product,reasons}]/exclusions[{productId,productName,reasons}]/rules[]`；`product` 字段见 `FinanceProduct.java`（camelCase）。
- 错误：校验失败统一 `400 INVALID_PREFERENCES`（控制器自带 `@ExceptionHandler`，不走 `ApiExceptionHandler`，`FinanceRecommendationController.java:27-38`）。

### 3.3 用户 / 联系人管理（users）

- 模块：`controller/UserManagementController` → `service/UserManagementService` → `UserManagementMapper.xml`/`RecipientMapper`/`AccountMapper` → 表 `user_profiles`、`recipient_details`（V6 迁移），联系人本体仍写 `recipients`，新建合成账户写 `accounts(user_id='external')`。
- 接口（5 个）：`GET /api/users`、`POST /api/users`、`POST /api/users/{id}`、`GET /api/users/profile`、`POST /api/users/profile`。
- 数据来源与边界（`UserManagementService.java`）：
  - 列表 = 本人 `recipients` + 关联账户标签/余额（余额只读）。
  - 新建联系人不传 `accountId` 时，服务端新建 `demo-<uuid>` 合成账户，`user_id='external'`，可用 `initialBalanceMinor` 赋初值。
  - 传本人 `accountId` 时 `initialBalanceMinor` 必须为 0，否则 `VALIDATION_ERROR`。
  - 编辑只能改名/手机号/性别/备注/储蓄目标标记，**不能改关联账户与余额**。
  - 手机号白名单：`^1[0-9]{10}$` 或 `TEST-PHONE-\d{4}` 或空串；性别 `UNKNOWN|MALE|FEMALE|UNDISCLOSED`（`ManagedUserRequestDTO.java:6-12`）。

### 3.4 账单分析扩展（statement）

- 同一 `StatementController` 上新增 5 个接口，全部只读；金额口径唯一实现是 `service/StatementRules`（内部转账不计收支、退款计入入账、单笔支出 ≥2000 元仅作异常候选）。
- 新增：`GET /api/statement/year`、`GET /api/statement/filter`、`POST /api/statement/query`、`GET /api/statement/months`、`GET /api/statement/years`。
- `POST /api/statement/query` 是"通用查询信封"：请求体只接受 `start,end,bucket,groupBy,filter,compare,topN,rows`（`StatementQueryService.java:44-46`）；`bucket∈day|week|month|quarter|year|none`（桶数上限 62/53/36/12/10/1）、`groupBy∈category|merchant|account|none`、`compare∈none|previous_period|same_period_last_year`、`rows` 可为 `false` 或 `{offset≤200, limit≤50}`、`topN≤20`；区间回溯最多 5 年、最远到业务日 +1 年（`:49-58,84-95`）。响应 `StatementQueryVO` 与工具调用共用同一实现。
- 数据来源：`transactions` 表 + 账户标签，服务端重算合计，无模型参与。

### 3.5 储蓄计划管理 / 代扣开启（新 ActionKind）

- `POST /api/schedules/{id}/preview`（新增）：body `{operation:EDIT|PAUSE|RESUME|CANCEL, sourceId?, recipientId?, amountMinor?, firstDate?, endDate?, dayOfMonth?}`，生成 `kind=MANAGE_SCHEDULE` 的动作，参数含 `schedule_id / operation / expected_state_hash / source_id / recipient_id / amount_minor / first_date / end_date / day_of_month / timezone`（`ActionService.java:88-105`，`ScheduleManagementParametersVO.java`）。计划状态新增 `PAUSED`、`CANCELLED`（`ScheduleVO.status` 由字符串承载）。
- `POST /api/mandates/activate/preview`（新增）：body `{mandateId?, merchant, amountMinor, billingInterval, renewalDate}`，生成 `kind=ACTIVATE_MANDATE`；`mandateId` 有值表示"重新开启已撤销代扣"（必须与原服务/金额/周期一致），空表示新开服务（`ActionService.java:74-87`，`MandateActivationParametersVO.java`）。
- `GET /api/schedules/transfers`（新增）：返回 `ScheduleTransferVO[]`（`scheduleId`、`dueDate` + `@JsonUnwrapped ActionVO`），即"计划触发的到期转账动作卡"。
- 风险码：计划执行/委托范围校验失败会返回 `SCHEDULE_CONFIRMATION_REQUIRED` / `SCHEDULE_SCOPE_MISMATCH`（`ScheduleAuthorizationService.java:44-50`），按 409 处理。

### 3.6 模型设置（models）与模型用量

- `controller/ModelSettingsController` → `service/ModelConfigurationService`：把用户填写的 provider/baseUrl/apiKey/model 持久化到本机目录（默认 `./data/model-settings`，可用 `BANK_MODEL_SETTINGS_PATH` / `SAFEBANK_MODEL_SETTINGS_PATH` 覆盖，`ModelConfigurationService.java:26`）。
- 接口：`GET /api/models/providers`（11 家内置 + 自定义）、`GET/POST /api/models/configuration`、`POST /api/models/discover`、`POST /api/models/test`、`POST /api/models/disable`。
- **注意**：`POST /api/models/test` 与 `/discover` 会真的走外网访问模型供应商（`CompatibleModelHttp`）；密钥明文永不出现在响应，只回末四位（`ModelSettingsVO.java`）。联调时不要让 App/脚本误触这两个端点。
- `GET /api/model-budget` 仅 `langgraph-agent` profile 存在（`ModelBudgetController.java:10-17`），返回 `{applies,limit,used,remaining,unlimited}`。

### 3.7 原请求恢复 / 侧栏口径（agent-gateway profile only）

`AgentGatewayStatusController`（`@Profile("agent-gateway")`）新增 3 个：`GET /api/conversations/{cid}/turns/{rid}`、`GET /api/conversations/{cid}/statement-query`、`POST /api/conversations/{cid}/turns/{rid}/close`。默认 profile 下这 3 个 + 旧 `GET /api/agent/turns/{id}` 均 **404（控制器未注册）**。

### 3.8 内部端点（非 App，列出以免误用）

| 端点 | profile | 鉴权 |
| --- | --- | --- |
| `POST /internal/agent/tools` | agent-gateway | `Authorization: Bearer <bank.agent.service-key>`（≥64 字符）+ `X-Agent-Grant` |
| `POST /internal/agent/answers/validate` | agent-gateway | 同上 |
| `POST /internal/agent/grants/renew` | agent-gateway | 同上 |
| `POST /internal/agent/model/configuration`、`/complete` | langgraph-agent | `X-Agent-Grant` |
| `POST /internal/agent/model-budget/reserve`、`/record` | langgraph-agent | `X-Agent-Grant` |

`InternalAgentGuard` 还限制非 GET 请求体 ≤ 64KB，超限 413 `INTERNAL_REQUEST_TOO_LARGE`；鉴权失败 403 `INTERNAL_AUTH_REQUIRED`。

---

## 4. 端点清单（App 可见 66 个）

标记：**沿用** = 旧契约已有且形状未变；**改** = 旧端点但请求/字段/语义有变化；**新** = 旧契约没有。

### 4.1 会话与只读查询（12）

| 方法 | 路径 | 状态 | 请求 | 响应关键字段 / 备注 |
| --- | --- | --- | --- | --- |
| GET | `/api/session` | **改** | — | `SessionVO{user,csrf,mode,model,authentication,faultsEnabled,businessDate}`；新增 `businessDate`（业务日期 Asia/Shanghai，`SessionController.java:33-35`，`SessionVO.java:3`） |
| GET | `/api/accounts` | 沿用 | — | `AccountVO[]{id,label,balance_minor}` |
| GET | `/api/recipients?query=` | 沿用 | query 必填 | `RecipientVO[]{id,name,note,label}` |
| GET | `/api/statement?month=` | **改** | `month` 可选 | `StatementVO` 9 字段不变；**不传 month 时旧契约是固定 `2026-09`，现在是服务端业务月（今天所在月）**（`StatementController.java:22-26`） |
| GET | `/api/statement/year?year=` | **新** | `year` 可选 | `YearStatementVO{year,expense_minor,income_minor,internal_transfer_minor,categories,months[{month,expense_minor,income_minor,internal_transfer_minor,transaction_count}],record_count,coverage{business_date,year_in_progress,first_booked_on,last_booked_on,beyond_business_date}}` |
| GET | `/api/statement/filter` | **新** | `month` 必填；`category`=全部、`direction`=支出、`minAmountMinor`=0 可选 | `StatementFilterVO{month,category,direction,direction_code,min_amount_minor,expense_minor,income_minor,matched_count,list_scope_note,categories,transactions}` |
| POST | `/api/statement/query` | **新** | JSON：`start,end`（必填 YYYY-MM-DD）、`bucket`、`groupBy`、`filter{direction,categories[],merchants[],accounts[],minAmountMinor,maxAmountMinor,excludeInternal}`、`compare`、`topN`、`rows` | `StatementQueryVO{query,totals,buckets[],breakdown,comparison,anomaly_candidates[],rows,coverage,notes[]}`；参数为闭集，多传键直接 400 |
| GET | `/api/statement/months` | **新** | — | `string[]`（有数据的月份） |
| GET | `/api/statement/years` | **新** | — | `string[]`（有数据的年份） |
| GET | `/api/recurring` | 沿用 | — | `RecurringPaymentVO[]` |
| GET | `/api/audit` | 沿用 | — | `AuditEventVO[]`（最新 50 条；`event` 新增可能值见 §5） |
| GET | `/api/risk/summary` | 沿用 | — | `RiskSummaryVO` |

### 4.2 动作与代扣（16）

| 方法 | 路径 | 状态 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/transfers/preview` | 沿用 | 响应扁平 `ActionVO(kind=TRANSFER)` |
| GET | `/api/mandates` | 沿用（**恒 409**） | 固定抛 `RiskRejectedException(QUERY_CONFIRMATION_REQUIRED)` → 409 `RiskErrorVO`；App 不应调用 |
| POST | `/api/mandates/preview` | 沿用 | `kind=REVOKE`；`estimated_monthly_saving_minor` 仅在 MONTHLY 时出现 |
| POST | `/api/mandates/activate/preview` | **新** | `kind=ACTIVATE_MANDATE`，参数见 §3.5 |
| POST | `/api/schedules/preview` | **改** | `dayOfMonth` 由旧契约 `1..28` 放宽为 **1..31**，且"首次日期日号"改为"短月取月末"匹配（`ActionService.java:71`） |
| POST | `/api/schedules/{id}/preview` | **新** | `kind=MANAGE_SCHEDULE`（EDIT/PAUSE/RESUME/CANCEL） |
| GET | `/api/actions` | **新** | `ActionVO[]`，按 `expires_at` 倒序，每个都带 `security`（`ActionService.java:135-137`） |
| GET | `/api/actions/{id}` | 沿用（**可能返回新 kind**） | 非查询类返回扁平 `ExecutionReceipt`；查询类返回 `QueryExecutionReceipt{...,data,queriedAt}` |
| GET | `/api/actions/{id}/risk` | 沿用 | `RiskDecisionVO` |
| POST | `/api/actions/{id}/risk/preview` | 沿用 | 同上；body 可为 `{}` |
| POST | `/api/actions/{id}/verification-challenges`、`.../{challengeId}/verify` | 沿用 | `ChallengeVO` / `VerificationProofVO`；默认 profile 一律 `503 VERIFICATION_UNAVAILABLE` |
| POST | `/api/actions/{id}/authorize`、`/submit`、`/cancel` | 沿用 | 两步授权不变；预览 TTL 仍 5 分钟（`ActionService.java:115`） |
| POST | `/api/query-intents` | 沿用 | 只接受 `operation=QUERY_MANDATES`；带 `conversationId` 时仅 agent-gateway 可用，否则 400 `VALIDATION_ERROR`（`RiskQueryService.java:14-17`） |

### 4.3 储蓄计划 / 工作流 / 对话 / 模拟（19）

| 方法 | 路径 | 状态 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/schedules` | 沿用（**值域扩大**） | `ScheduleVO[]`，`status` 现在可能是 `ACTIVE/PAUSED/CANCELLED` |
| GET | `/api/schedules/transfers` | **新** | `ScheduleTransferVO[]` |
| GET/POST | `/api/workflows` | 沿用（请求校验放宽） | `WorkflowVO` 形状不变（含 `analysis`）；`dayOfMonth` 1..31 |
| GET | `/api/workflows/{id}`、`/plan` | 沿用 | `WorkflowVO` / `TaskPlanVO` |
| POST | `/api/workflows/{id}/resume`、`/revise`、`/cancel` | 沿用 | 都带 `version` |
| POST | `/api/conversations`、`GET /{id}`、`POST /{id}/context/reset` | 沿用 | `ConversationVO` 不变（含 `bankReferences`） |
| POST | `/api/chat` | 沿用 | 请求 DTO 字段与旧契约一致（`text`、`modelRequestLimit`、`conversationId`、`requestId`、`expectedContextVersion`）；响应顶层键集合未见新增（`text/mode/data/actions/trace/contextResults/modelUsage/timing/clarification/conversationId/contextVersion/context/[results/answerSource/factReferences]/[agentBackend/toolResults]`） |
| GET | `/api/conversations/{cid}/turns/{rid}` | **新**（仅 agent-gateway） | `{turnId,state,requiresReview,hasOutput,closed,answer? 或 answerMissing?}` |
| GET | `/api/conversations/{cid}/statement-query` | **新**（仅 agent-gateway） | `{found,query,result,observedAt}`；无回执 `{found:false}` |
| POST | `/api/conversations/{cid}/turns/{rid}/close` | **新**（仅 agent-gateway） | `{state,hasOutput,closed}` + `agentUnblocked/agentReason/blockingTurnId?`；非 PAUSED 状态 409 |
| GET | `/api/agent/turns/{id}` | 沿用（仅 agent-gateway） | `AgentTurnStatusVO{turnId,state,reconcileFailures,outputVersion,requiresReview}` |
| POST | `/api/simulation/faults`、`/run-due` | 沿用 | 仅 `risk-demo` 生效，否则 400 `SIMULATION_DISABLED` |

### 4.4 卡片管理（6，全部**新**）

| 方法 | 路径 | 请求 | 响应 |
| --- | --- | --- | --- |
| GET | `/api/cards` | — | `BankCardVO[]` |
| GET | `/api/cards/operations` | — | `CardOperationVO[]` |
| GET | `/api/cards/operations/{id}` | — | `CardOperationVO` |
| POST | `/api/cards/preview` | `CardChangeDTO{kind,cardId?,accountId?,label?,cardType?,perTransactionLimitMinor?,dailyLimitMinor?,onlineEnabled?,overseasEnabled?}` | `CardOperationVO`（`status=AWAITING_CONFIRMATION`，`payloadHash` 必须原样回传） |
| POST | `/api/cards/operations/{id}/confirm` | `{payloadHash, confirmed:true}` | `CardOperationVO`（`status=COMPLETED`，含 `receiptId` 与 `card`） |
| POST | `/api/cards/operations/{id}/cancel` | 空体 | `CardOperationVO`（`status=CANCELLED`） |

`BankCardVO`：`{id, accountId, accountLabel, balanceMinor, label, cardType, maskedNumber, status, perTransactionLimitMinor, dailyLimitMinor, onlineEnabled, overseasEnabled, version}`（**camelCase**）。
`CardOperationVO`：`{id, kind, status, payloadHash, request, expectedVersion, createdAt, expiresAt, executedAt, receiptId, card}`（**camelCase**，`request` 复用上面的 DTO 形状）。

### 4.5 用户 / 模型 / 金融（13，全部**新**）

| 方法 | 路径 | 请求 | 响应 |
| --- | --- | --- | --- |
| GET | `/api/users` | — | `ManagedUserVO[]{id,name,phone,gender,note,accountId,accountLabel,balanceMinor,ownAccount,savingsTarget}`（camelCase） |
| POST | `/api/users` | `{name, phone, gender, note, savingsTarget, accountId?, initialBalanceMinor}` | `ManagedUserVO` |
| POST | `/api/users/{id}` | 同上（accountId/initialBalanceMinor 会被拒） | `ManagedUserVO` |
| GET/POST | `/api/users/profile` | `{name, phone, gender}` | `UserProfileVO{name,phone,gender}` |
| POST | `/api/finance/recommendations` | 见 §3.2 | `FinanceRecommendationVO` |
| GET | `/api/models/providers` | — | `ModelProviderVO[]{id,name,icon,baseUrl,models[]}` |
| GET/POST | `/api/models/configuration` | `{provider,baseUrl,apiKey?,model?,enabled?,clearApiKey}` | `ModelSettingsVO{provider,baseUrl,model,hasApiKey,apiKeyHint,enabled,verified,verifiedAt,revision,source}` |
| POST | `/api/models/discover` | 同上 | `{models:[], source:"live"}`（会外网调用） |
| POST | `/api/models/test` | 同上 | `{valid,model,baseUrl,host,keySource,keyHint,latencyMs,message}`（会外网调用） |
| POST | `/api/models/disable` | 空体 | `ModelSettingsVO` |
| GET | `/api/model-budget` | — | `{applies,limit,used,remaining,unlimited}`（仅 langgraph-agent） |

---

## 5. 新增枚举 / 状态 / 错误码

### 5.1 枚举增量

| 枚举 | 新增值 | 影响 |
| --- | --- | --- |
| `ActionKind` | `ACTIVATE_MANDATE`、`MANAGE_SCHEDULE` | 旧契约只声明 4 种；App 若对 `kind` 做穷举渲染会漏显示 |
| `Schedule.status` | `PAUSED`、`CANCELLED`（另仍有 `ACTIVE`） | App 列表/详情需能显示非 ACTIVE 状态 |
| 卡片 `status` | `ACTIVE`、`LOCKED`、`LOST` | 新模块 |
| 卡片 `cardType` | `DEBIT`、`VIRTUAL` | 新模块 |
| 卡片操作 `kind` | `APPLY`、`UPDATE_LIMITS`、`UPDATE_CONTROLS`、`LOCK`、`UNLOCK`、`REPORT_LOST` | 新模块 |
| 卡片操作 `status` | `AWAITING_CONFIRMATION`、`COMPLETED`、`CANCELLED` | 新模块（不是动作状态机） |
| 金融 `riskPreference/purpose/liquidity` | `CONSERVATIVE/BALANCED/GROWTH`、`RESERVE/PLANNED/GROWTH`、`IMMEDIATE/FLEXIBLE/LOCKABLE` | 新模块 |
| 性别 | `UNKNOWN/MALE/FEMALE/UNDISCLOSED` | users 模块 |
| Agent turn state | `PAUSED`（需人工核对）、`INTERRUPTED`（受控结束）、`ACKED`（已完成） | 仅 agent-gateway |

`ActionStatus`、`WorkflowState`、`RiskLevel`、`AssuranceRequirement`、`ChallengeState`、`AuthorizationState`、`UsageState` **未变**。

### 5.2 新增错误码

| 错误码 | 触发点 | HTTP |
| --- | --- | --- |
| `INVALID_STATE` | 卡片状态不允许该操作（如已挂失再解锁、只能锁定 ACTIVE 卡） | 400 |
| `INVALID_PREFERENCES` | 金融推荐参数缺失/越界（控制器自带处理器） | 400 |
| `MODEL_CONFIGURATION_CHANGED` | 模型配置 revision 变化 | 400 |
| `MODEL_CONFIGURATION_INVALID` | 模型配置非法 | 400 |
| `MODEL_DEBUG_BUDGET_INVALID` | 用量账本文件损坏/不可写 | 400 |
| `MODEL_DISCOVERY_UNAVAILABLE` | `/api/models/discover` 供应商不返回标准模型列表 | 400 |
| `MODEL_SETTINGS_UNAVAILABLE` | 模型设置读写失败 | 400 |
| `MODEL_REQUEST_UNCERTAIN` | 模型请求结果不明 | 400 |
| `AGENT_TURN_CLOSED` | 网关轮次已结束 | 400（`/api/agent/**` 走"其他 /api/** 一律 400"分支） |

> `MODEL_REQUEST_BUDGET_EXCEEDED` 已不在当前会被抛出的错误码集合里（`DebugRequestBudget` 现在不设调用上限），只在 `GatewayCoordinator.java:88,96` 做旧客户端兼容映射。
| 风险码 `SCHEDULE_CONFIRMATION_REQUIRED` | 计划/委托需重新确认 | 409 |
| 风险码 `SCHEDULE_SCOPE_MISMATCH` | 期次超出已确认委托范围 | 409 |

HTTP 状态映射规则本身未变（`ApiExceptionHandler.java:8-24`）：
- `/api/workflows**`：`CONFLICT`→409，其余 400。
- `/api/chat`、`/api/conversations/**`：`CONFLICT/CONTEXT_PROCESSING/CONTEXT_INTERRUPTED`→409、`NOT_FOUND`→404、`CONTEXT_EXPIRED`→410，其余 400。
- **其他所有 `/api/**`（含 cards/finance/users/models/statement/actions）→ 一律 400**（包括 `NOT_FOUND`、`AUTH_EXPIRED`、`LIMIT_EXCEEDED`）。
- `RiskErrorVO`：403（STEP_UP/VERIFICATION_*）、429（CHALLENGE_RATE_LIMITED）、423（SECURITY_LOCKED/RISK_RECONCILIATION_REQUIRED）、503（VERIFICATION_UNAVAILABLE）、404（NOT_FOUND）、其余 409。

### 5.3 审计事件 / 权限

- `SessionGuard` 不变：`GET /api/session` 免会话；其余 `/api/**` 需会话（否则 401 `SESSION_REQUIRED`）；所有非 GET 需 `X-CSRF-Token`（否则 403 `CSRF_REQUIRED`）并校验 `Origin`（否则 403 `ORIGIN_DENIED`）。**新增的 cards/finance/users/models/statement POST 全部落在这套规则内**。
- `GET /api/audit` 仍是"最新 50 条"（`AuditEventMapper.xml`：`ORDER BY recorded_at DESC LIMIT 50`），`event` 是自由字符串。现源码会写入的事件包括：`PREVIEWED`、`AUTHORIZATION_GRANTED`、`EXECUTED`、`CANCELLED`、`ACTION_CANCELLED`、`BANK_ACCEPTED`、`BANK_REJECTED`、`BANK_PROCESSING_FAILED`、`BANK_NOT_SUBMITTED`、`BANK_SUBMISSION_STARTED`、`CONFIRMED_QUERY_EXECUTED`、`QUERY_EXECUTED`、`RISK_ASSESSED`、`SECURITY_REJECTED`、`STEP_UP_REQUIRED`、`CHALLENGE_RATE_LIMITED`、`SCHEDULE_SCOPE_AUTHORIZED`、`LOCAL_DEMO_ONLY`。
- **卡片、用户资料、模型设置不写审计表**（`CardManagementService`/`UserManagementService`/`ModelConfigurationService` 内无 `audit.record`），所以 App 的"实验与审计"页看不到这三类变更。

---

## 6. 本机如何"离线"启动（实测环境核查结果）

### 6.1 环境事实（已逐项核查）

| 项 | 结论 |
| --- | --- |
| `%USERPROFILE%\.m2\repository` | **不存在**。`C:\Users\15094\.m2` 下只有 `wrapper\dists\apache-maven-3.9.16-bin`（Maven Wrapper 下载的分发，不含任何依赖 jar） |
| 工程内 `.tools` / `.m2` / `target` | **都不存在**（`run.ps1` 期望的 `.tools\apache-maven-3.9.9` 也没有） |
| `mvn` / `mvnw` | 系统 PATH 无 `mvn`；工程无 `mvnw.cmd`、无 `.mvn` |
| `java` | PATH 上是 Oracle JDK **17**（不满足 `java.version=21` 编译） |
| 可用 JDK 21 | `E:\ToolApp\DataGrip 2025.1.4.1\jbr` = **21.0.8**，含 `javac`（推荐用它） |
| IDEA 自带 | `E:\ToolApp\IDEA\IntelliJ IDEA 2026.1.3\jbr` = **JDK 25**（旧 App 联调曾用 JBR 25 跑通，但非工程目标版本）；Maven 为 `...\plugins\maven\lib\maven3\bin\mvn.cmd`（3.9.11） |
| Docker | PATH 无 `docker`，`C:\Program Files\Docker\...` 不存在 → **无法从旧镜像里取 fat jar 兜底** |
| `run.ps1` | **不可直接用**：它用 `git rev-parse --git-common-dir` 找共享根，而 `C:\Users\15094\.git` 存在，于是共享根被解析成 `C:\Users\15094`，去那里找 `.tools`/`.m2` 必然失败，最后报"找不到 Maven" |

**因此：当前这台机器上"纯离线"启动新后端不可行**——必须先联网做一次完整依赖解析（约 60–120 MB：Spring Boot 3.5.6 全套 + 编译/打包插件）。之后 `-o` 才是真离线。

### 6.2 首次（需联网，一次性）：构建

```powershell
$root='C:\Users\15094\Desktop\shenzhen\safebank-agent-t020-integration\safebank-agent-t020-integration'
$env:JAVA_HOME='E:\ToolApp\DataGrip 2025.1.4.1\jbr'      # JDK 21，编译必须是 21
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$mvn='E:\ToolApp\IDEA\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd'
Set-Location $root
& $mvn -DskipTests package      # 首次联网；产物 target\safebank-agent-0.6.0.jar
```

> 关于旧版"`mvn -o dependency:build-classpath` 生成 cp.txt + `java -cp`"的绕行：**新工程正常情况下不需要**。`pom.xml` 只声明了 `spring-boot-maven-plugin`（parent 已绑定 `repackage`），`mvn package` 产出的就是可执行 fat jar；旧版绕行的根因（maven-jar-plugin 依赖不全）在新 pom 里不存在。只有在"依赖缓存只覆盖运行期依赖、缺打包/编译插件"的特殊情况下，才退回 cp.txt 方案（见 §6.5）。

### 6.3 启动（推荐：本机内存库 + 演示 profile）

```powershell
$root='C:\Users\15094\Desktop\shenzhen\safebank-agent-t020-integration\safebank-agent-t020-integration'
$env:JAVA_HOME='E:\ToolApp\DataGrip 2025.1.4.1\jbr'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:SPRING_PROFILES_ACTIVE='risk-demo,context-demo'      # 必须两个一起，原因见下
$env:BANK_DEMO_PEPPER='safebank-demo-pepper-0123456789abcdef'          # ≥24 字符
$env:BANK_DEMO_OPERATOR_TOKEN='safebank-demo-operator-token-0123456789' # ≥24 字符
$env:BANK_DEBUG_BUDGET_PATH="$root\.demo\debug-budget.json"            # 必须是绝对路径
$env:SAFEBANK_PORT='8080'
$mvn='E:\ToolApp\IDEA\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd'
Set-Location $root
& $mvn spring-boot:run
# 或者（已 package 过）：
# & "$env:JAVA_HOME\bin\java.exe" -jar target\safebank-agent-0.6.0.jar
```

为什么必须 `risk-demo,context-demo` 且必须给这些环境变量：

| 约束 | 源码证据 | 后果 |
| --- | --- | --- |
| 全新 H2 **文件**库无法初始化风险表 | `RiskSchemaInitializer.java:25-28`：`count==0` 时只有 `createNew && url.startsWith("jdbc:h2:mem:")` 才建表，否则抛 `IllegalStateException("Risk schema unavailable: ...")` | 默认 profile + 全新 `./data/safebank` **启动失败** |
| 上下文表同理 | `ContextSchemaInitializer.java:26-30`：`count==0` 时必须 `create=true` 且是内存库 | 只开 `risk-demo` 也会在上下文表处失败 → 必须带 `context-demo`（它设 `bank.context.schema.create-new=true` + 内存库） |
| `context-demo` 打开用量账本 | `application-context-demo.properties:3-4` + `DebugRequestBudget.java:30-32`：`enabled=true` 时 path 为空或非绝对路径 → `IllegalStateException("Model usage ledger requires an absolute path")` | 不设 `BANK_DEBUG_BUDGET_PATH` **启动失败** |
| `risk-demo` 的强验证 | `VerificationConfiguration.java:10-12`：pepper/token 各 <24 字符 → 退化为 `UnavailableVerificationProvider` | 不致命；但不设就只能拿到 `503 VERIFICATION_UNAVAILABLE`（RED 动作无法完成授权）；设了会额外在 8101 起一个演示验证台 |
| 内存库 | `application-risk-demo.properties:2` | **每次重启数据全部重置**：卡片、联系人、动作、工作流都清空（账单/账户来自种子 SQL） |

数据库与端口：
- 内存库种子来自 `risk-demo-data.sql`（`spring.sql.init.data-locations` 被 profile 覆盖），账户为 `checking`/`travel`（余额 5,154,480 / 2,334,000 分）、联系人 `zhang-a`/`zhang-b`/`travel-recipient`（注意其账户标签是 `张三 TEST-3271` 这类演示格式）、代扣 `music`/`video`；账单覆盖 **2026-04 ~ 2026-09 六个月**（每月 17–20 笔）。
- 服务只监听 `127.0.0.1:8080`（`application.properties:1-2`）。模拟器直连 `10.0.2.2:8080` 不通，需 `adb reverse tcp:8080 tcp:8080`（App 的 baseUrl 已持久化为 `http://127.0.0.1:8080`）；或临时用 `$env:SERVER_ADDRESS='0.0.0.0'` 再走 `10.0.2.2`。

### 6.4 之后的"离线"启动

首次构建成功后，依赖都进 `C:\Users\15094\.m2\repository`，后续加 `-o` 即可：

```powershell
& $mvn -o spring-boot:run
# 或
& $mvn -o -DskipTests package
& "$env:JAVA_HOME\bin\java.exe" -jar target\safebank-agent-0.6.0.jar
```

### 6.5 备选（仅当 `package`/插件离线不可用时）

```powershell
# 仍需要一次在线解析；之后可离线
& $mvn -o dependency:build-classpath "-Dmdep.outputFile=cp.txt"
& "$env:JAVA_HOME\bin\java.exe" -cp "target\classes;$(Get-Content cp.txt -Raw)" cn.safebank.Application
```
注意：这种方式仍要 `SPRING_PROFILES_ACTIVE` / `BANK_DEBUG_BUDGET_PATH` / `BANK_DEMO_*` 与 §6.3 完全一致，且 `target\classes` 必须存在（即先编译过）。

### 6.6 若要持久化（卡片/联系人跨重启）

默认 profile 的 H2 文件库需要"已审核迁移"的风险/上下文表，本机没有；要持久化只能走 MySQL 路径（`mysql` profile + `SAFEBANK_MYSQL_*` 环境变量 + Flyway V1/V2/V6 迁移，可选 `agent-gateway`/`langgraph-agent` 追加 V3/V4/V5）。联调 App 不建议走这条，成本高且与本次验证目标无关；接受内存库"重启即重置"更划算。

---

## 7. App 需注意的字段 / 语义变化（按风险排序）

### 7.1 【最高风险】新增模块的 JSON 命名是 camelCase，而 App 按 snake_case 建模

证据链：
- 后端 `BankCardVO`、`CardOperationVO`、`FinanceProduct`、`FinanceRecommendationVO`、`FinanceRecommendationRequestDTO` **没有任何 `@JsonProperty`**，Jackson 按 record 组件名输出 → `accountId/balanceMinor/perTransactionLimitMinor/maskedNumber/...`、`minimumAmountMinor/periodReturnBps/...`、`catalogVersion/generatedAt/evaluatedCount/capitalProtection/...`。
- Web 前端也是按 camelCase 消费（`static/finance.js:62,112,142`，`static/workspace.js:353-378`），可交叉印证后端实际输出就是 camelCase。
- App 端 `Models.kt:465-475,480-485,493-505,528-566` 给这些字段写的是 `@SerializedName("minimum_amount_minor")`、`@SerializedName("account_id")`、`@SerializedName("per_transaction_limit_minor")`、`@SerializedName("catalog_version")` 等 **snake_case**；且 `SafeBankJson` 是"缺字段保留 Kotlin 默认值"（`SafeBankJson.kt:44-52`）。
- 结果：**不报错、不崩溃，但字段静默取默认值**——卡片限额显示 0、卡号空白、起投金额 0、收益/回撤"暂无数据"、`evaluatedCount=0` 等；`preferences` 回显也全默认。
- 请求侧反而是对的：`CardChangeRequest`/`FinanceRecommendationRequest` 没有 `@SerializedName`，按 camelCase 发出，与后端 DTO 匹配；所以"预览/推荐能调通、但结果字段空"是最典型的症状。
- 修复方向（二选一）：App 把这几处 `@SerializedName` 改为 camelCase；或后端给这些 VO/DTO 补 `@JsonProperty`（会改动后端，本轮不做）。

### 7.2 `/api/statement` 的默认月份语义变了

旧契约：不传 `month` → 固定 `2026-09`。现在：不传 → 服务端业务月（真实时钟，今天所在月，`StatementController.java:22-26`）。
App 目前总是显式传月份（`StatementScreen.kt:495` 固定 `INITIAL_MONTH="2026-09"`），所以账单页不受影响；但任何"想用默认月"的调用（脚本/新页面）行为已变；另外 App 的"回到当前月"按钮会跳到 `2026-10`，而种子数据只覆盖 7/8/9 月 → 可能看到空账单，属预期，不是 bug。

### 7.3 `day_of_month` 由 1..28 放宽到 1..31，且"首次日号匹配"改为短月取月末

- 后端：`ActionService.java:71` 校验 `1..31` 且 `ScheduleRules.isDue(start, day)`（`ScheduleRules.java:6` 用 `min(day, 当月天数)`），提示文案为"每月 1—31 日；…短月取月末"。
- App：`SavingsUi.kt:505,530` 仍在前端限制 **1..28**，所以 App 不会传 29/30/31 → 不会报错，但也用不上新能力；若 Lead 想用 31 号计划验证，**只能用 Web/curl 建**，App 会拒绝。
- 旧契约 `WorkflowRequestDTO.dayOfMonth` 也写"1..28 且必须等于 firstDate 的日"，现后端放宽为 1..31 + 短月夹取（`WorkflowService.java:82`）。

### 7.4 新增的 ActionKind 可能出现在 App 的动作渲染里

- `GET /api/actions`（App 未用）和 `GET /api/actions/{id}`（App 在用）现在可能返回 `kind=ACTIVATE_MANDATE` / `MANAGE_SCHEDULE`。
- 触发场景：Web 端/脚本/计划执行创建了这两类动作后，App 若通过"审计 → 动作详情"恢复卡片（`SafeBankRepository` 已封装 `audit()` 与 `action(id)`，`AndroidBankRepository.kt:94-96,163-165`）就会拿到不认识的 kind/参数形态 → 卡片可能空白或只显示 id。
- 默认联调顺序（App 只做转账/储蓄/代扣）不会触发；但**如果先用 Web 页面点过"暂停/修改计划"或"开启代扣"，再切到 App 就会遇到**。

### 7.5 其他需要改/注意的点

| 变化 | 影响 | 建议 |
| --- | --- | --- |
| `SessionVO` 新增 `businessDate` | App 忽略未知字段，无影响；但 App 目前用设备本地日期算"当前月"（`Formatters.currentMonth()`），与后端业务日可能差 1 天 | 可后续改用 `session.businessDate` |
| `ScheduleVO.status` 新增 `PAUSED/CANCELLED` | App 列表/详情若只判断 `ACTIVE` 会显示异常 | 联调前确认列表页对非 ACTIVE 的文案 |
| 卡片/用户/金融数据在内存库重启后消失 | 每次重启要重新造数据 | 演示前一次性造好，不要中途重启后端 |
| `POST /api/models/test`、`/discover` 会发外网请求 | 误触会消耗供应商额度/时间 | 禁止在自动化或联调脚本里调用 |
| 新增错误码 `INVALID_STATE`/`INVALID_PREFERENCES` 等 | App 的 `ErrorParser` 按 code 分支的地方可能落到"未知错误" | 检查 `ErrorParser.kt` 的兜底文案是否可接受 |

---

## 8. 与旧契约的冲突点（逐条）

| # | 旧契约说法 | 新后端实际 | 冲突等级 |
| --- | --- | --- | --- |
| 1 | §4 共 35 个端点，无 cards/finance/users/models/statement 扩展 | 实际 66 个，多出 31 个 | 中（App 不调就没有破坏，但"全量对齐 Web"已不成立） |
| 2 | 响应体"银行类对象一律 snake_case"（§5.1 记忆规则 2） | 卡片/用户/金融/模型这些**新** VO 全部 camelCase，同一进程两套命名风格 | **高**（App 已按 snake 建模，见 §7.1） |
| 3 | `GET /api/statement` 默认月 = `2026-09` | 默认月 = 服务端业务月 | 低 |
| 4 | `dayOfMonth` 1..28 且等于首次日期日号 | 1..31，短月夹取到月末 | 低（App 更严，不会触发） |
| 5 | `ActionKind` 只有 4 种 | 6 种 | 中 |
| 6 | 错误码表无 `INVALID_STATE`/`INVALID_PREFERENCES`/`MODEL_CONFIGURATION_*` 等 | 新增 10 个 | 低 |
| 7 | 旧文档写 `MODEL_REQUEST_BUDGET_EXCEEDED` | 现后端不再抛出该码（仅网关兼容映射），实际用 `MODEL_TURN_BUDGET_EXCEEDED` | 低（文档过期） |
| 8 | `GET /api/mandates` 永远 409 `QUERY_CONFIRMATION_REQUIRED` | 仍是（`MandateController.java:20-22`），但走 `RiskErrorVO` 而非 `ErrorVO` | 无（旧契约已注明） |
| 9 | `GET /api/agent/turns/{id}` 仅 agent-gateway | 仍是；另有 3 个同 profile 端点未在旧契约 | 低（默认 profile 404） |
| 10 | 启动命令 `./run.ps1 start` | 本机 **不可用**（git 根解析错误 + 无 `.tools`/`.m2`） | 高（运维层面，见 §6） |

---

## 9. 建议的模拟器验证顺序（给 Lead）

前置：按 §6.3 起后端（`risk-demo,context-demo`，内存库），`adb reverse tcp:8080 tcp:8080`，App 设置页 baseUrl 用 `http://127.0.0.1:8080`。

1. `GET /api/session` → 拿 `csrf` + `businessDate`，确认 `mode=OFFLINE_DEMO`（无模型 key 时）。
2. 只读回归：`/api/accounts`、`/api/statement?month=2026-09`、`/api/recurring`、`/api/schedules`、`/api/audit`、`/api/risk/summary`、`/api/cards`（应为空数组）、`/api/users`（应含 3 个联系人）、`/api/statement/months`。
3. 新模块字段核对（**重点**）：`/api/finance/recommendations` 与 `/api/cards/preview`，逐个字段看 App 是否拿到真实值还是默认值 → 验证 §7.1 的判断。
4. 卡片全流程：preview(APPLY) → confirm → `/api/cards` 看到卡 → preview(UPDATE_LIMITS) → confirm → preview(LOCK) → confirm → preview(REPORT_LOST) → confirm → 记录 `card_operations`。
5. 转账（YELLOW）：`/api/transfers/preview` → authorize → submit → `/api/actions/{id}` 查单。
6. 账单扩展（Web 或 curl）：`/api/statement/year?year=2026`、`/api/statement/query`（`{start,end,bucket:"month"}`）、`/api/statement/filter`。
7. 计划管理（Web/curl，App 未接）：`/api/schedules/{id}/preview` 的 PAUSE→RESUME→CANCEL。
8. 代扣开启（Web/curl）：`/api/mandates/activate/preview` → authorize → submit。

---

## 10. 本次分析的边界（未做/未验证）

- **没有启动后端**，没有真实 HTTP 抓包；所有 JSON 形状来自源码 record/`@JsonProperty` 静态推导（camelCase 结论另有 Web 前端消费代码交叉印证）。
- **没有改后端源码**，没有跑 `mvn`/`gradle`/测试；`target/` 仍不存在。
- 没有验证联网下载依赖是否成功（本会话按规则不发起网络动作），§6 给出的是"联网一次后即可离线"的可行路径。
- 没有覆盖 MySQL 路径的实际启动（需要 MySQL 实例与 `SAFEBANK_MYSQL_*` 全套凭证）。
- Agent 网关（`agent-gateway`/`langgraph-agent`）链路需要额外启动 `agent-service`（Python）与 MySQL，本轮只做接口存在性/形状说明。
