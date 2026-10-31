# SafeBank 后端 API 契约（Android 客户端对接版）

> 本文是 **只读代码反向推导** 的接口契约，用于 Android（Kotlin + kotlinx.serialization）端建模。
> 唯一事实来源：`safebank-agent-main` 的 `src/main/java` + `src/main/resources`（未修改任何源文件）。
>
> 阅读顺序建议：§1 环境 → §2 传输层（Cookie/CSRF）→ §3 数据类型 → §4 端点 → §6 状态机 → §8 Kotlin 建议。

| 项 | 值 |
| --- | --- |
| 后端 | Java 21 + Spring Boot 3（`@RestController` + `record` DTO/VO）+ MyBatis |
| 服务地址 | `http://127.0.0.1:8080`（`server.address=127.0.0.1`，`server.port=${SAFEBANK_PORT:8080}`） |
| Content-Type | 请求与响应均为 `application/json`（前端统一带 `Content-Type: application/json`） |
| 字符集 | UTF-8；响应 `application/json`（Spring 默认 `application/json`，`charset` 由 Jackson/Spring 决定，实际为 UTF-8） |
| 鉴权 | HTTP Session（Cookie `JSESSIONID`）+ 自定义头 `X-CSRF-Token` |
| 金额 | **整数分**（`long`），币种固定 `CNY` |
| 业务时区 | `Asia/Shanghai`（仅用于“业务日”判定）；所有时间戳为 UTC ISO-8601 |
| 单用户 | 后端把会话硬编码为用户 `u1`（`"合成用户 / u1"`），没有登录/注册端点 |

---

## 1. 服务地址、页面与 API 的关系

### 1.1 端口与绑定

```properties
# src/main/resources/application.properties
server.address=127.0.0.1
server.port=${SAFEBANK_PORT:8080}
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=strict
server.servlet.session.timeout=30m
spring.jackson.serialization.write-dates-as-timestamps=false
spring.jackson.deserialization.accept-float-as-int=false
spring.jackson.serialization... （无全局命名策略覆盖）
server.error.include-message=never
```

- 默认 **8080**，可用环境变量 `SAFEBANK_PORT` 覆盖。
- **只监听 127.0.0.1**。Android 真机无法直连；模拟器需后端改为 `server.address=0.0.0.0`（或用 `adb reverse tcp:8080 tcp:8080`，此时客户端仍访问 `127.0.0.1:8080`）。
- 启动方式：`./run.ps1 start`（等价 `mvn spring-boot:run`），**默认不激活任何 profile**。

### 1.2 页面与 API 的关系

- 同一个 Spring Boot 进程同时提供：
  - 静态单页应用：`GET /` → `src/main/resources/static/index.html`（+ `app.js`、`context-ui.js`、`motion.js`、`styles.css`、`theme.css`、`favicon.svg`、`assets/logo2.png`）。
  - REST API：全部 `/api/**`。
- **页面与 API 完全同源**（同 scheme/host/port），前端用相对路径 `fetch('/api' + path)`，因此不存在跨域 Cookie 问题。
- 页面本身不带任何服务端渲染数据；页面加载后**第一件事**就是 `GET /api/session` 拿 CSRF，然后 `GET /api/accounts`、`/api/schedules`、`/api/audit`、`/api/workflows` 做首屏。
- 前端路由只是 `hidden` 切换的 6 个 section（`chat/statement/mandates/savings/tools/audit`），**没有对应的 URL 或 API**。

### 1.3 CORS / 同源

- 代码中**没有任何** CORS 配置（无 `@CrossOrigin`、无 `CorsConfiguration`、无 `addCorsMappings`）→ 浏览器跨域请求不会拿到 `Access-Control-Allow-*`，读不到响应。
- 更硬的一层：`SessionGuard` 对所有非 GET 的 `/api/**` 做了 **Origin 白名单校验**（见 §2.2）。即使加了 CORS，跨源非 GET 也会被 `403 ORIGIN_DENIED`。
- **Android（OkHttp）默认不发 `Origin` 头 → 不受影响**。若使用 WebView 加载页面，则必须同源访问后端。
- `OPTIONS` 预检也属于“非 GET”，同样会被要求 CSRF 与 Origin → 结论：**不要依赖浏览器 CORS，Android 端直接用原生 HTTP 调 `/api/**`**。

### 1.4 端点可用性矩阵（profile）

| 端点 | 默认（无 profile） | `risk-demo` | `agent-gateway` | `langgraph-agent` |
| --- | --- | --- | --- | --- |
| `GET /api/session` 及 §4 全部 `/api` 端点 | ✅ | ✅ | ✅ | ✅ |
| `POST /api/simulation/faults` | ❌ `400 SIMULATION_DISABLED` | ✅（`bank.simulation.faults-enabled=true`） | ❌ | ❌ |
| `POST /api/simulation/run-due` | ❌ `400 SIMULATION_DISABLED` | ✅（`bank.simulation.clock-enabled=true`） | ❌ | ❌ |
| 红色动作的验证挑战 | ❌ `503 VERIFICATION_UNAVAILABLE` | ✅（需同时设置 `BANK_DEMO_PEPPER` / `BANK_DEMO_OPERATOR_TOKEN`，各 ≥24 字符） | ❌ | ❌ |
| `POST /api/query-intents` 带 `conversationId` | ❌ `400 VALIDATION_ERROR` | ❌ | ✅ | ✅（见下方说明，需与 `agent-gateway` 同时激活） |
| `GET /api/agent/turns/{id}` | ❌ 404（控制器未注册） | ❌ | ✅ | ❌ |
| `/internal/agent/**` | ❌ 404 | ❌ | ✅（还要求 `Authorization: Bearer <service-key>`） | 追加 `/internal/agent/model-budget/**` |
| `/api/chat` 走 Python 智能体 | ❌ 走内置 `AgentService` | ❌ | ✅（`FAKE_AGENT`） | ✅（`LANGGRAPH_MOCK` / `LANGGRAPH_MODEL`） |

> **profile 组合规则（重要）**：`langgraph-agent` 只有**与 `agent-gateway` 同时激活**才有意义。
> 项目里所有相关测试都是 `profiles("mysql","mysql-test","agent-gateway","langgraph-agent")`。
> 只开 `langgraph-agent` 而不开 `agent-gateway` 会进入不一致状态：
> `GET /api/session` 报 `LANGGRAPH_*`（`SessionController` 优先判断 langgraph），
> 但 `GatewayCoordinator` 不存在 → `/api/chat` 实际仍走内置 `AgentService`，
> 且 `query-intents` 带 `conversationId` 仍会 `400 VALIDATION_ERROR`（`AgentMandateReceiptService` 只在 `agent-gateway` 下注册）。
> **所以判断"是否网关链路"要看 `/api/chat` 响应里的 `agentBackend` / `mode`，不要只看 `session.mode`。**

各 profile 关键配置：

```properties
# application-risk-demo.properties
bank.risk.schema.create-new=true
spring.datasource.url=jdbc:h2:mem:risk-demo;DB_CLOSE_DELAY=-1
bank.verification.demo-enabled=true
bank.verification.pepper=${BANK_DEMO_PEPPER:}
bank.verification.operator-token=${BANK_DEMO_OPERATOR_TOKEN:}
bank.verification.console-port=${BANK_DEMO_CONSOLE_PORT:8101}
bank.simulation.clock-enabled=true
bank.simulation.faults-enabled=true

# application-agent-gateway.properties
bank.agent.base-url=${SAFEBANK_AGENT_BASE_URL:http://127.0.0.1:8133}
bank.agent.service-key=${SAFEBANK_AGENT_SERVICE_KEY}
spring.jackson.deserialization.fail-on-unknown-properties=true   # ← 注意：未知字段会 400

# application-langgraph-agent.properties
bank.agent.base-url=${SAFEBANK_AGENT_BASE_URL:http://127.0.0.1:8135}
bank.agent.model-mode=${SAFEBANK_MODEL_MODE:mock}
```

**默认 profile 的两个重要后果**（Android 端必须知道）：

1. `bank.verification.demo-enabled=false` → 所有 `RISK_LEVEL=RED` 的动作**无法在默认环境完成授权**（一定 `503 VERIFICATION_UNAVAILABLE`）。要演示红色流程必须用 `risk-demo`。
2. `spring.jackson.deserialization.fail-on-unknown-properties` 默认 `false`（Spring Boot 默认），所以默认 profile 下多传字段被忽略；但 `agent-gateway` profile 把它打开成 `true`，同一个请求体可能突然 400。

---

## 2. 传输层约定（Android 必读）

### 2.1 会话建立与 Cookie

```java
// SessionController
@GetMapping("/session") public SessionVO session(HttpSession s) {
    if (s.getAttribute("user") == null) {
        s.setAttribute("user", "u1");
        s.setAttribute("csrf", UUID.randomUUID().toString());
    }
    ...
}
```

- `GET /api/session` 是**唯一**允许无会话调用的 `/api` 端点（`SessionGuard` 用 `excludePathPatterns("/api/session")` 排除）。
- 传 `HttpSession` 参数 → Spring 调用 `getSession(true)` → **响应 `Set-Cookie: JSESSIONID=...`**。
- Cookie 名：**`JSESSIONID`**（没有配置 `server.servlet.session.cookie.name`）。
  - `HttpOnly=true`（JS 不可读，Android 不受影响）
  - `SameSite=Strict`
  - 会话超时 **30 分钟**（`server.servlet.session.timeout=30m`），30 分钟不活动后 `user` 属性消失 → 下次非 `/api/session` 请求返回 `401 SESSION_REQUIRED`。
- 会话里只有两个属性：`user="u1"`、`csrf=<UUID 字符串>`。
- **CSRF token 不在任何响应头/Cookie 里**，只在 `GET /api/session` 的响应体 `csrf` 字段中。
- 注意：`GET /api/session` 重复调用**不会**换 token（仅在 `user` 为空时生成），所以客户端可以把它当“读会话”用。

### 2.2 所有 POST 必须带 `X-CSRF-Token`

```java
// SessionGuard
registry.addInterceptor(this).addPathPatterns("/api/**").excludePathPatterns("/api/session");

if (session == null || session.getAttribute("user") == null)
    return reject(response, 401, "SESSION_REQUIRED");

if (!request.getMethod().equals("GET")) {
    if (!Objects.equals(session.getAttribute("csrf"), request.getHeader("X-CSRF-Token")))
        return reject(response, 403, "CSRF_REQUIRED");
    String origin = request.getHeader("Origin");
    if (origin != null) { /* scheme+host+port 必须与请求完全一致，否则 403 ORIGIN_DENIED */ }
}
```

结论：

1. **每一个非 GET 的 `/api/**` 请求都必须带 `X-CSRF-Token`**（大小写按 HTTP 头不敏感，值必须与会话内 token 完全相等）。
2. 判据是 **“方法不是 GET”**，不是“方法等于 POST”。`HEAD` / `OPTIONS` / `PUT` 同样要求 CSRF。
3. **GET 不需要 CSRF，但仍需要会话 Cookie**。
4. `Origin` 头可以不发（OkHttp 默认不发）；一旦发送，scheme/host/port 必须与当前请求一致，否则 `403 ORIGIN_DENIED`。
5. 拦截器抛出的错误体是**硬编码字符串**，不是统一错误 VO：

```json
{"code":"SESSION_REQUIRED","message":"请刷新页面建立本地演示会话"}
```

（`CSRF_REQUIRED` / `ORIGIN_DENIED` 用同一条 `message`。）
HTTP 状态：`401`（无会话）/ `403`（CSRF 或 Origin）。

### 2.3 请求头清单

| 头 | 何时必须 | 值 |
| --- | --- | --- |
| `Cookie: JSESSIONID=...` | 除 `GET /api/session` 外的所有 `/api/**` | 由服务端下发 |
| `X-CSRF-Token` | 所有**非 GET** 的 `/api/**` | `GET /api/session` 响应体的 `csrf` |
| `Content-Type: application/json` | 有请求体的 POST | 固定 |
| `Origin` | 不要发 | — |
| `Authorization: Bearer <key>` | 仅 `/internal/**`（Android 不该调用） | `bank.agent.service-key` |
| `X-Agent-Grant` | 仅 `/internal/agent/**`（Android 不该调用） | 一次性授权 token |

### 2.4 OkHttp `CookieJar` 行为要点

必须实现**内存型** `CookieJar`，语义如下：

1. `GET /api/session` 的响应必须被 `saveFromResponse` 收下（哪怕 `Set-Cookie` 只有 `JSESSIONID`）。
2. 之后每个请求都从 jar 里 `loadForRequest` 取回，**一个 App 进程一个 jar**；不要用磁盘持久化 cookie 跨进程共享（30 分钟超时 + 后端内存态 H2 数据，跨进程复用没有意义，且容易拿到已失效会话）。
3. 判断“会话失效”的依据是 HTTP `401` + `{"code":"SESSION_REQUIRED"}`，此时应：清空 cookie jar → 重新 `GET /api/session` → 用**新** `csrf` 重放（注意：重放前要考虑当前是否有未完成动作，见 §6.6）。
4. 不要手动设置 `Cookie` 头（会被 jar 覆盖或重复）。
5. 不要开启 OkHttp 的自动重定向到其他 host（本服务不会重定向）。
6. `HttpOnly` / `SameSite=Strict` 对 OkHttp **无影响**（那是浏览器策略），但 `SameSite=Strict` 意味着：如果以后用 WebView 混合加载，跨站请求会丢 cookie。

### 2.5 URL 编码

- `query` 参数（`/api/recipients`）与路径变量一律使用标准 URL 编码；前端用 `encodeURIComponent`。
- `conversationId`、`actionId` 是 UUID 形态字符串，无需特殊编码。

---

## 3. 数据类型定义（TypeScript 风格，可直接照抄建 data class）

> 命名规则已逐字照抄源码：**响应体混用 `snake_case` 与 `camelCase`**，请求体则**全部 `camelCase`**（见 §5.1 的坑位表）。

### 3.1 枚举

```ts
type ActionKind = "TRANSFER" | "REVOKE" | "SCHEDULE" | "QUERY_MANDATES";

type ActionStatus =
  | "AWAITING_AUTH"      // 预览已生成，等待用户确认（进入 authorize 前的唯一合法状态之一）
  | "AUTHORIZED"         // 已授权，尚未提交银行
  | "VERIFIED_SUCCESS"   // 银行侧已核验成功（转账会校验双边账务分录）
  | "VERIFIED_FAILED"    // 银行侧明确失败
  | "CANCELLED"          // 用户/任务取消（未提交前）
  | "PENDING"            // 银行已受理、处理中
  | "UNKNOWN";           // 结果未知，必须按原 action id 查单，禁止新建动作

type RiskLevel = "GREEN" | "YELLOW" | "RED";
type AssuranceRequirement = "NONE" | "CONFIRMED" | "STEP_UP";

// 仅 workflow 层出现，不在 ActionStatus 里
type WorkflowState =
  | "PLANNING"            // 瞬态：create 内部先写 PLANNING 再立刻 advance()，HTTP 响应里几乎观察不到
  | "AWAITING_AUTH"
  | "PENDING"
  | "UNKNOWN"
  | "VERIFIED_SUCCESS"
  | "VERIFIED_FAILED"
  | "PARTIAL_SUCCESS"
  | "CANCELLED"
  | "CANCEL_REQUESTED";

type PlanNodeType =
  | "mandate_lookup" | "account_query" | "mandate_revoke"
  | "compute_savings" | "schedule_create";

type PlanDisplayState =
  | "VALIDATED" | "WAITING_DEPENDENCY" | "BLOCKED_UNKNOWN"
  | "AWAITING_AUTH" | "AUTHORIZED" | "VERIFIED_SUCCESS"
  | "VERIFIED_FAILED" | "CANCELLED" | "PENDING";

type ConversationState =
  | "ACTIVE" | "NEEDS_CLARIFICATION" | "AWAITING_CONFIRMATION"
  | "PAUSED" | "EXPIRED";

type Intent =
  | "STATEMENT_ANALYSIS" | "TRANSACTION_CHECK" | "MONTH_COMPARISON"
  | "TRANSFER_DRAFT" | "MANDATE_QUERY" | "MANDATE_REVOKE_DRAFT"
  | "SAVINGS_WORKFLOW" | "ACTION_STATUS" | "CLARIFICATION" | "GENERAL";

// /api/chat 的 answerSource
type AnswerSource =
  | "MODEL_GROUNDED" | "SAFE_FALLBACK" | "ACTION_RECEIPT"
  | "PROGRAM_VERIFIED_FAKE_AGENT" | "PROGRAM_VERIFIED_LANGGRAPH";

// /api/session.mode 与 /api/chat.mode
type AgentMode =
  | "OFFLINE_DEMO"          // 无模型密钥，规则匹配
  | "MODEL_CONFIGURED"      // 有密钥但本进程尚未验证
  | "CONNECTION_VERIFIED"   // 本进程已拿到有效响应
  | "FAKE_AGENT"            // agent-gateway profile
  | "LANGGRAPH_MOCK" | "LANGGRAPH_MODEL";
```

### 3.2 错误体（两种形态！）

```ts
/** 普通业务错误：BankException → ApiExceptionHandler.bankError */
interface ErrorVO {
  code: string;      // 见 §7 错误码表
  message: string;   // 中文、可直接展示
}

/** 风险/验证门禁错误：RiskRejectedException → ApiExceptionHandler.riskError */
interface RiskErrorVO {
  code: string;
  message: string;
  actionId: string | null;
  security: RiskDecisionVO | null;  // 最新风险判定，前端会用 e.security 重绘确认卡
  retryAt: string | null;           // ISO-8601，可能为 null
}

/** @Valid 校验失败 / JSON 解析失败 → Spring 默认错误体（不走上面两个 VO） */
interface SpringDefaultError {
  timestamp: string;
  status: number;
  error: string;        // "Bad Request"
  path: string;
  // message 被 server.error.include-message=never 关闭
}
```

### 3.3 会话

```ts
interface SessionVO {
  user: string;              // 固定 "合成用户 / u1"
  csrf: string;              // UUID 字符串，后续所有非 GET 请求的 X-CSRF-Token
  mode: AgentMode;           // 见上
  model: string;             // modelName，"deepseek-v4-flash" / "fake-python-protocol"
  authentication: string;    // 固定 "LOCAL_DEMO_ONLY"
  faultsEnabled: boolean;    // 是否显示"实验故障设置"面板
}
```

### 3.4 只读查询 VO

```ts
interface AccountVO {
  id: string;              // "checking" / "travel"
  label: string;           // "日常账户"
  balance_minor: number;   // 整数分，long
}

interface RecipientVO {
  id: string;      // "zhang-a" / "travel-recipient"
  name: string;    // "张三"
  note: string;    // "同学"
  label: string;   // 对方账户标签，如 "张三 3271"
}

interface TransactionVO {
  id: string;
  merchant: string;
  category: string;
  amount_minor: number | null;  // 负数=支出；Long 可空
  booked_on: string | null;     // "2026-09-20" (LocalDate)
}

interface StatementVO {
  month: string;                       // "2026-09"
  expense_minor: number;               // 支出合计（不含内部转账，取绝对值）
  income_minor: number;                // 入账合计（含退款）
  internal_transfer_minor: number;     // 本人账户互转合计
  categories: Record<string, number>;  // 类别 -> 支出分（按 key 升序，TreeMap）
  transactions: TransactionVO[];
  anomaly_candidates: TransactionVO[]; // 单笔 |支出| >= 200000 分（≥2000 元）
  anomaly_rule: string;                // 固定文案
  usage_data_available: boolean;       // 当前恒为 false
}

interface RecurringPaymentVO {
  merchant: string;
  occurrences: number;
  first_seen: string;          // LocalDate
  last_seen: string;           // LocalDate
  min_amount_minor: number;
  max_amount_minor: number;
  classification: string;      // "周期扣费候选"
  usage_known: boolean;        // false
}

interface MandateVO {
  id: string;                  // "music" / "video"
  user_id: string;             // "u1"
  merchant: string;
  amount_minor: number | null;
  renewal_date: string | null; // LocalDate
  status: string;              // "ACTIVE" | "REVOKED"（字符串，非枚举类型）
  contract_status: string;     // "UNKNOWN"
  billing_interval: string;    // "MONTHLY" | "QUARTERLY" | "YEARLY"
}

interface ScheduleVO {
  id: string;
  user_id: string;
  source_id: string;
  recipient_id: string;
  amount_minor: number | null;
  day_of_month: number | null;   // 1..28
  first_date: string | null;     // LocalDate
  end_date: string | null;       // LocalDate
  status: string;                // "ACTIVE"
}

interface AuditEventVO {
  action_id: string;
  event: string;          // PREVIEWED / AUTHORIZATION_GRANTED / EXECUTED / CANCELLED / BANK_REJECTED / BANK_ACCEPTED / BANK_PROCESSING_FAILED / CONFIRMED_QUERY_EXECUTED
  recorded_at: string;    // OffsetDateTime ISO-8601
}

interface RiskSummaryVO {
  businessDate: string;        // LocalDate（Asia/Shanghai 业务日）
  committedMinor: number;
  reservedMinor: number;
  unverifiedMinor: number;
  lockedUntil: string | null;  // Instant
  reconciliationRequired: boolean;
}
```

> **`AuditEventVO` 顺序**：SQL 为 `ORDER BY recorded_at DESC LIMIT 50`（最新在前，最多 50 条）。
> **`ScheduleVO` / `WorkflowVO` 列表顺序**：`workflows` 是 `ORDER BY created_at DESC`。

### 3.5 Action 家族（响应体是**扁平**的，没有 `parameters` 包裹）

`ActionVO` 的 `parameters` 字段带 `@JsonUnwrapped`，所以参数被**摊平到顶层**。前端与契约测试都确认了这一点（`assertFalse(view.has("parameters"))`）。

```ts
interface ActionCommon {
  id: string;                // UUID 字符串（服务端生成的 actionId）
  user_id: string;           // "u1"
  kind: ActionKind;
  payload_hash: string;      // 64 位 hex；authorize 时必须原样回传
  status: ActionStatus;
  expires_at: string;        // OffsetDateTime ISO-8601（UTC，通常以 "Z" 结尾）；预览有效期 5 分钟
  receipt_id: string | null; // 成功后的回执号（SCHEDULE 时等于 schedule id）
  error_code: string | null; // 失败原因码
  security: RiskDecisionVO | null;
}

interface TransferAction extends ActionCommon {
  kind: "TRANSFER";
  source_id: string;
  recipient_id: string;
  recipient_label: string;      // "张三 / 张三 3271"
  amount_minor: number;
  currency: string;             // "CNY"
  fee_minor: number | null;     // 恒为 0
  schedule_id?: string;         // 仅定时任务触发的转账才出现
  occurrence_date?: string;     // 仅定时任务触发的转账才出现（"2026-11-20"）
}

interface RevokeAction extends ActionCommon {
  kind: "REVOKE";
  mandate_id: string;
  merchant: string;
  charge_amount_minor: number;
  billing_interval: string;                  // MONTHLY / QUARTERLY / YEARLY
  estimated_monthly_saving_minor?: number;   // 仅 MONTHLY 时出现（NON_NULL）
  contract_status: string;                   // "UNKNOWN"
  scope: string;                             // "仅撤销银行侧代扣授权，商户合同状态未知"
}

interface ScheduleAction extends ActionCommon {
  kind: "SCHEDULE";
  source_id: string;
  recipient_id: string;
  amount_minor: number;
  first_date: string;        // "2026-11-20"
  end_date: string;          // "2027-10-20"
  day_of_month: number;      // 1..28
  timezone: string;          // "Asia/Shanghai"
  insufficient_funds: string;// "本次失败，不补扣"
}

interface QueryAction extends ActionCommon {
  kind: "QUERY_MANDATES";
  query: string;             // "OWN_BANK_MANDATES"
}

type ActionVO = TransferAction | RevokeAction | ScheduleAction | QueryAction;
```

**执行结果 VO（`ActionExecutionResultVO`，`action` 同样是 `@JsonUnwrapped`）**

```ts
/** 带 execution_observations 的回执（TRANSFER / REVOKE / SCHEDULE） */
interface ExecutionReceipt extends ActionVO {
  observed_at?: string;  // 仅真正查单后才出现（String = Instant.toString()）
  observation?: string;  // 仅当银行返回 NOT_SUBMITTED 时出现："NOT_SUBMITTED"
}

/** QUERY_MANDATES 专用 */
interface QueryExecutionReceipt extends QueryAction {
  data: MandateVO[] | null;   // 首次提交返回数组；重复提交返回 null（原回执已被冻结）
  queriedAt: string | null;   // ⚠ camelCase，不是 queried_at
}

type ActionExecutionResult = ExecutionReceipt | QueryExecutionReceipt;
```

### 3.6 风险与验证

```ts
interface RiskDecisionVO {
  decisionId: string | null;     // 传给 authorize / 验证挑战的 decisionId（可能为 null，见 needsRefresh）
  policyVersion: string;         // "bank-risk-v1"
  riskLevel: RiskLevel;
  requiredAssurance: AssuranceRequirement;
  reasonCode: string;            // "DAILY_OUTGOING_THRESHOLD_EXCEEDED" | "EXPLICIT_CONFIRMATION_REQUIRED" | "PRIOR_STEP_UP_REQUIREMENT"
  businessDate: string;          // LocalDate
  effectiveMinor: number;        // 当日已占用（含已授权未提交）
  projectedMinor: number;        // effective + 本次金额（仅 TRANSFER 累加）
  validUntil: string;            // Instant
  nextStep: string;              // "CONFIRM" | "VERIFY_THEN_CONFIRM"
  verificationMode: string;      // "LOCAL_DEMO_ONLY" | "EXTERNAL_MFA_REQUIRED"
  needsRefresh: boolean;         // true = 该对象不足以直接授权，需先 POST /risk/preview
}

interface ChallengeVO {
  challengeId: string;
  actionId: string;
  decisionId: string;
  expiresAt: string;          // Instant
  status: string;             // "PENDING_DELIVERY" | "SENT" | ... (最后一次写入后为 "SENT")
  verificationMode: string;   // "LOCAL_DEMO_ONLY"
}

interface VerificationProofVO {
  verificationProofId: string;  // 传给 authorize.verificationProofId
  actionId: string;
  decisionId: string;
  expiresAt: string;
  verificationMode: string;     // "LOCAL_DEMO_ONLY"
}
```

> 风险策略常量：`DAILY_THRESHOLD = 100000`（¥1,000）、`policyVersion = "bank-risk-v1"`。
> `kind == TRANSFER && (effectiveMinor + amountMinor) > 100000` → **RED**（要求 `STEP_UP`），否则 **YELLOW**（要求 `CONFIRMED`）。
> 单笔上限是 `amount_minor > 1000000`（¥10,000）→ 预览阶段直接 `400 LIMIT_EXCEEDED`。

### 3.7 联合任务（Workflow）

```ts
interface WorkflowRequestDTO {
  month: string;            // "2026-09"
  mandateIds: string[];     // 1..10，不重复
  sourceId: string;         // "checking"
  recipientId: string;      // "travel-recipient"
  firstDate: string;        // "2026-11-20"
  endDate: string;          // "2027-10-20"
  dayOfMonth: number;       // 1..28，且必须等于 firstDate 的日
  onlyConfirmedSavings: boolean;  // 必须为 true
}

interface WorkflowStepVO {
  key: string;            // "revoke:music" / "schedule"
  kind: string;           // ActionKind 名称
  status: string;         // ActionStatus 名称
  actionId: string;
  inputVersion: number;
  savingMinor: number;    // REVOKE 步骤=该代扣月费；schedule 步骤=0
  errorCode: string | null;
}

interface WorkflowVO {
  id: string;                          // UUID 字符串
  version: number;                     // 乐观锁版本，所有写操作都要回传
  state: WorkflowState;
  request: WorkflowRequestDTO;
  estimatedSavingMinor: number;        // 只累计"已核验撤销"的月度节省
  partial: boolean;                    // 已有步骤成功但整体未完成
  steps: WorkflowStepVO[];
  pendingAction: ActionVO | Record<string, never>;  // 无待确认项时是 {}（空对象）
  analysis: StatementVO;               // 创建时快照的账单
}

interface TaskPlanNodeVO {
  id: string;              // "m" | "a" | "r1" | "r2" | "s" | "t"
  type: PlanNodeType;
  dependsOn: string[];     // 节点 id 数组
  displayState: PlanDisplayState;
  actionId: string | null;
  errorCode: string | null;
}

interface TaskPlanVO {
  workflowId: string;         // UUID
  version: number;
  state: WorkflowState;
  savingsPolicy: string;      // 固定 "verified_success_subset"
  analysisMonth: string;      // "2026-09"
  nodes: TaskPlanNodeVO[];    // 固定 6 类节点，顺序 m,a,r1..rN,s,t
}
```

### 3.8 对话上下文

```ts
interface BankReference {
  toolCallId: string;
  kind: string;          // "ACTION" | "WORKFLOW"
  bankObjectId: string;
}

interface ConversationDisplayTurn {
  user: string;
  assistant: string;
}

interface ConversationVO {
  conversationId: string;                        // UUID 字符串
  version: number;                               // 每次成功对话 +1；写请求用 expectedContextVersion 校验
  intent: Intent;
  state: ConversationState;
  slots: Record<string, unknown>;                // key 为小写槽名，见下
  proposal: unknown | null;                      // JSON 对象或 null
  displayHistory: ConversationDisplayTurn[];     // 最多 4 轮
  bankReferences: BankReference[];               // 仅 GET /api/conversations/{id} 有内容
}
```

`slots` 的 key 取值（源码 `SlotName.key()` = 枚举名小写）：

```
primary_month, comparison_month, focused_transaction_id, recipient_candidates,
selected_recipient_id, source_account_id, amount_minor, mandate_candidates,
selected_mandate_ids, excluded_mandate_ids, first_date, end_date,
day_of_month, active_action_id, active_workflow_id, missing_slots
```

### 3.9 `/api/chat`

```ts
interface ClarificationVO {
  question: string;
  missingSlots: string[];
  recipientCandidates: RecipientVO[];
}

interface ChatEvidence {          // BankReplyComposer.Evidence
  tool: string;                   // "get_statement" / "list_mandates" / "preview_transfer" ...
  data: unknown;                  // 该工具的业务结果（StatementVO / MandateVO[] / ActionVO ...）
  observation: Record<string, unknown>;  // 记忆证据才有的元信息，通常 {}
}

interface ChatToolResult {        // 仅 agent-gateway / langgraph 模式
  tool: string;
  result: unknown;
  evidenceId: string;
  observedAt: string;
}

interface ModelUsageCall {
  kind: "generation" | "review";
  success: boolean;
  finish_reason?: string;          // "STOP" | "LENGTH" | ...
  response_complete?: boolean;
  prompt_tokens?: number;
  completion_tokens?: number;
  total_tokens?: number;
  prompt_cache_hit_tokens?: number;
  prompt_cache_miss_tokens?: number;
}

interface ModelUsage {             // ⚠ 全部 snake_case
  request_count: number;
  request_limit: number;
  generation_requests: number;
  review_requests: number;
  usage_reported_requests: number;
  usage_complete: boolean;
  prompt_tokens: number | null;
  completion_tokens: number | null;
  total_tokens: number | null;
  prompt_cache_hit_tokens: number | null;
  prompt_cache_miss_tokens: number | null;
  calls: ModelUsageCall[];
}

interface ChatResponse {
  // ---- 任何模式都有 ----
  text: string;                       // ← 回答正文（不是 "answer"）
  mode: AgentMode;
  data: Record<string, unknown>;      // 最后一个工具的业务结果；无结果时是 {}（不是 null）
  actions: ActionVO[];                // 待确认操作卡（0 或 1 个）
  trace: string[];                    // 工具调用轨迹，可直接展示/折叠
  contextResults: unknown[];          // 历史上下文结果，默认 []
  modelUsage: ModelUsage;
  timing: { requestElapsedMs: number; authorizationWaitIncluded: boolean };
  clarification: ClarificationVO | { required: boolean } | null;  // ⚠ 三种形态
  conversationId: string;
  contextVersion: number;
  context: ConversationVO;

  // ---- 仅“真正走过模型链路”时才有（OFFLINE_DEMO 完全不带这三个键）----
  results?: ChatEvidence[];           // 本轮工具证据（"evidence"/"toolResults" 的等价物）
  answerSource?: AnswerSource;
  factReferences?: string[];          // 回答引用的事实 ID

  // ---- 仅 agent-gateway / langgraph 模式 ----
  agentBackend?: "FAKE_PYTHON_PROTOCOL" | "PYTHON_LANGGRAPH";
  toolResults?: ChatToolResult[];
}
```

---

## 4. 端点逐个说明

统一前缀 `http://127.0.0.1:8080`。

| # | 方法 | 路径 | 需 CSRF | 说明 |
| --- | --- | --- | --- | --- |
| 1 | GET | `/api/session` | ❌ | 建立会话、取 CSRF |
| 2 | GET | `/api/accounts` | — | 账户余额 |
| 3 | GET | `/api/recipients?query=` | — | 收款人检索 |
| 4 | GET | `/api/statement?month=YYYY-MM` | — | 月度账单 |
| 5 | GET | `/api/recurring` | — | 周期扣费候选 |
| 6 | GET | `/api/schedules` | — | 已登记定时计划 |
| 7 | GET | `/api/audit` | — | 审计事件（最近 50） |
| 8 | GET | `/api/mandates` | — | **永远 409**，必须走查询动作 |
| 9 | GET | `/api/risk/summary` | — | 当日风险额度汇总 |
| 10 | POST | `/api/transfers/preview` | ✅ | 转账预览 |
| 11 | POST | `/api/mandates/preview` | ✅ | 代扣撤销预览 |
| 12 | POST | `/api/schedules/preview` | ✅ | 定时转账预览 |
| 13 | POST | `/api/query-intents` | ✅ | 生成"代扣查询"动作 |
| 14 | GET | `/api/actions/{id}` | — | 查单 / 读动作现状 |
| 15 | GET | `/api/actions/{id}/risk` | — | 读风险（无副作用） |
| 16 | POST | `/api/actions/{id}/risk/preview` | ✅ | 强制刷新并落库风险判定 |
| 17 | POST | `/api/actions/{id}/verification-challenges` | ✅ | 创建验证挑战 |
| 18 | POST | `/api/actions/{id}/verification-challenges/{challengeId}/verify` | ✅ | 校验验证码 |
| 19 | POST | `/api/actions/{id}/authorize` | ✅ | 授权 |
| 20 | POST | `/api/actions/{id}/submit` | ✅ | 提交银行 |
| 21 | POST | `/api/actions/{id}/cancel` | ✅ | 取消草稿 |
| 22 | POST | `/api/workflows` | ✅ | 创建联合任务 |
| 23 | GET | `/api/workflows` | — | 任务列表 |
| 24 | GET | `/api/workflows/{id}` | — | 任务详情 |
| 25 | GET | `/api/workflows/{id}/plan` | — | 任务计划视图 |
| 26 | POST | `/api/workflows/{id}/resume` | ✅ | 查单并推进 |
| 27 | POST | `/api/workflows/{id}/revise` | ✅ | 改参（生成新版本） |
| 28 | POST | `/api/workflows/{id}/cancel` | ✅ | 取消后续步骤 |
| 29 | POST | `/api/conversations` | ✅ | 新建对话上下文 |
| 30 | GET | `/api/conversations/{id}` | — | 读对话上下文 |
| 31 | POST | `/api/conversations/{id}/context/reset` | ✅ | 清空草案 |
| 32 | POST | `/api/chat` | ✅ | 对话（核心） |
| 33 | POST | `/api/simulation/faults` | ✅ | 故障注入（需 `risk-demo`） |
| 34 | POST | `/api/simulation/run-due` | ✅ | 模拟到期执行（需 `risk-demo`） |
| 35 | GET | `/api/agent/turns/{id}` | — | 智能体轮次状态（需 `agent-gateway`） |

### 4.1 `GET /api/session`

- 请求：无参数、无 body、**无需 Cookie/CSRF**。
- 响应 `200 SessionVO`：

```json
{
  "user": "合成用户 / u1",
  "csrf": "0f6f4c1e-8f6e-4a1e-9d20-2f9a1b6c7d33",
  "mode": "OFFLINE_DEMO",
  "model": "deepseek-v4-flash",
  "authentication": "LOCAL_DEMO_ONLY",
  "faultsEnabled": false
}
```

- 附带 `Set-Cookie: JSESSIONID=...; Path=/; HttpOnly; SameSite=Strict`。
- `mode` 由 profile 决定：默认 `OFFLINE_DEMO`；`agent-gateway` → `FAKE_AGENT`（`model` = `fake-python-protocol`）；`langgraph-agent` → `LANGGRAPH_MOCK` / `LANGGRAPH_MODEL`。
- 错误：理论上只有网络层错误；不会返回 401/403。

### 4.2 `GET /api/accounts`

- 响应 `200 AccountVO[]`：

```json
[
  {"id":"checking","label":"日常账户","balance_minor":1243000},
  {"id":"travel","label":"旅游账户","balance_minor":5820000}
]
```

（注意：**没有** `user_id` 字段。）

### 4.3 `GET /api/recipients?query=xxx`

- Query：`query` **必填**（`@RequestParam String query`）。为空字符串 → `400 VALIDATION_ERROR`（"请输入收款人"）；缺失 → Spring `MissingServletRequestParameterException` → `400` 默认错误体。
- 匹配规则：`name = query OR phone = query OR note = query OR id = query OR account.label = query`（**精确匹配**，不是模糊）。
- 响应 `200 RecipientVO[]`（可能为空数组）：

```json
[{"id":"zhang-a","name":"张三","note":"同学","label":"张三 3271"},
 {"id":"zhang-b","name":"张三","note":"同事","label":"张三 8862"}]
```

- 种子数据：`zhang-a`、`zhang-b`、`travel-recipient` 属于 `u1`。

### 4.4 `GET /api/statement?month=YYYY-MM`

- Query：`month` 可选，**默认 `2026-09`**；格式 `YYYY-MM`（`YearMonth.parse`），非法 → `400 VALIDATION_ERROR`（"月份格式应为 YYYY-MM"）。
- 响应 `200 StatementVO`（字段集合被契约测试锁定为 9 个）：

```json
{
  "month": "2026-09",
  "expense_minor": 340300,
  "income_minor": 850000,
  "internal_transfer_minor": 0,
  "categories": {"居住":250000,"订阅":8300,"餐饮":82000},
  "transactions": [
    {"id":"rent-9","merchant":"租金","category":"居住","amount_minor":-250000,"booked_on":"2026-09-01"}
  ],
  "anomaly_candidates": [],
  "anomaly_rule": "单笔支出达到 2000 元，仅为候选，不能推断欺诈；本人账户内部转账不计收入支出",
  "usage_data_available": false
}
```

- 语义：`expense_minor` 只统计负向且**类别不是"内部转账"**的交易；正数计入 `income_minor`（含退款）；`internal_transfer_minor` 为内部转账绝对值合计。
- `categories` 的 key 是中文类别名（来自 `transactions.category`），value 为正的支出分。

### 4.5 `GET /api/recurring`

- 响应 `200 RecurringPaymentVO[]`：按 `(last_seen-first_seen)/(occurrences-1)` 落在 25~35 天之间才算"周期扣费候选"。

```json
[{"merchant":"音乐服务","occurrences":2,"first_seen":"2026-08-20","last_seen":"2026-09-20",
  "min_amount_minor":1500,"max_amount_minor":1500,"classification":"周期扣费候选","usage_known":false}]
```

### 4.6 `GET /api/schedules`

- 响应 `200 ScheduleVO[]`（无数据时 `[]`）。

### 4.7 `GET /api/audit`

- 响应 `200 AuditEventVO[]`，`ORDER BY recorded_at DESC LIMIT 50`：

```json
[{"action_id":"8b0c...","event":"PREVIEWED","recorded_at":"2026-10-27T14:20:11.123456Z"}]
```

- 前端用它恢复"最近的动作卡"：`[...new Set(audit.map(r => r.action_id))].slice(0, 12)`，再逐个 `GET /api/actions/{id}`。

### 4.8 `GET /api/mandates` —— 永远是错误

```java
@GetMapping("/mandates") public List<MandateVO> mandates(HttpSession s) {
    throw new RiskRejectedException(new RiskFailure(
        "QUERY_CONFIRMATION_REQUIRED", "读取本人银行代扣列表前需要确认查询范围", null, null, null));
}
```

- 响应 `409`：

```json
{"code":"QUERY_CONFIRMATION_REQUIRED","message":"读取本人银行代扣列表前需要确认查询范围",
 "actionId":null,"security":null,"retryAt":null}
```

- 正确做法：**先创建 `QUERY_MANDATES` 动作并授权提交**（见 §4.13 + §5.5），数据从 `submit` 的 `data` 字段拿。
- 前端对应逻辑：`renderMandates(null)` 时只渲染一个"确认查询范围"按钮。

### 4.9 `GET /api/risk/summary`

- 响应 `200 RiskSummaryVO`：

```json
{"businessDate":"2026-10-27","committedMinor":0,"reservedMinor":0,
 "unverifiedMinor":0,"lockedUntil":null,"reconciliationRequired":false}
```

### 4.10 `POST /api/transfers/preview`

- 请求体（**camelCase**）：

```ts
interface TransferPreviewRequest {
  sourceId: string;      // @NotBlank，账户 id，如 "checking"
  recipientId: string;   // @NotBlank，收款人 id，如 "zhang-a"
  amountMinor: number;   // @Positive long，整数分；小数会被拒（accept-float-as-int=false）
}
```

```json
{"sourceId":"checking","recipientId":"zhang-a","amountMinor":50000}
```

- 校验（`TransferValidationService`）：
  - `amountMinor <= 0` → `400 VALIDATION_ERROR`
  - 余额不足 → `400 INSUFFICIENT_FUNDS`
  - `amountMinor > 1000000` → `400 LIMIT_EXCEEDED`
  - 收款人账户 == 来源账户 → `400 VALIDATION_ERROR`（"不能转入同一账户"）
  - 账户/收款人不属于当前用户 → `400 NOT_FOUND`
- 成功 `200`，**扁平 ActionVO**（契约测试锁定的 15 个字段）：

```json
{
  "id": "9a1f3c62-5b1e-4a77-9c2d-1f0b7a5e4d11",
  "user_id": "u1",
  "kind": "TRANSFER",
  "payload_hash": "b1d0...64hex",
  "status": "AWAITING_AUTH",
  "expires_at": "2026-10-27T14:35:12.345678Z",
  "receipt_id": null,
  "error_code": null,
  "source_id": "checking",
  "recipient_id": "zhang-a",
  "recipient_label": "张三 / 张三 3271",
  "amount_minor": 50000,
  "currency": "CNY",
  "fee_minor": 0,
  "security": {
    "decisionId": "e77c...",
    "policyVersion": "bank-risk-v1",
    "riskLevel": "YELLOW",
    "requiredAssurance": "CONFIRMED",
    "reasonCode": "EXPLICIT_CONFIRMATION_REQUIRED",
    "businessDate": "2026-10-27",
    "effectiveMinor": 0,
    "projectedMinor": 50000,
    "validUntil": "2026-10-27T14:35:12.345678Z",
    "nextStep": "CONFIRM",
    "verificationMode": "EXTERNAL_MFA_REQUIRED",
    "needsRefresh": false
  }
}
```

- 幂等：同一次预览由服务端生成新 id；**没有**天然的重复预览去重（除非走 `/api/chat` 的 `ContextBankProposalService`，那里按工具调用做幂等）。前端每次点按钮都会新建一个动作，所以 UI 上应避免重复点击。
- 错误：`400`（`ErrorVO` 或 Spring 默认体，取决于失败类型）。

### 4.11 `POST /api/mandates/preview`

```ts
interface MandatePreviewRequest { mandateId: string; }   // @NotBlank
```

```json
{"mandateId":"music"}
```

- 校验：代扣不属于当前用户 → `400 NOT_FOUND`；已撤销 → `400 CONFLICT`（"该代扣授权已撤销"）。
- 成功 `200`（`kind="REVOKE"`）：

```json
{
  "id":"...","user_id":"u1","kind":"REVOKE","payload_hash":"...","status":"AWAITING_AUTH",
  "expires_at":"...","receipt_id":null,"error_code":null,
  "mandate_id":"music","merchant":"音乐服务","charge_amount_minor":1500,
  "billing_interval":"MONTHLY","estimated_monthly_saving_minor":1500,
  "contract_status":"UNKNOWN","scope":"仅撤销银行侧代扣授权，商户合同状态未知",
  "security":{ "...":"..." }
}
```

- `estimated_monthly_saving_minor` **只在 `billing_interval == "MONTHLY"` 时出现**，其他周期该 key 完全缺失。

### 4.12 `POST /api/schedules/preview`

```ts
interface SchedulePreviewRequest {
  sourceId: string;      // @NotBlank
  recipientId: string;   // @NotBlank
  amountMinor: number;   // @Positive long
  firstDate: string;     // @NotBlank，"YYYY-MM-DD"
  endDate: string;       // @NotBlank，"YYYY-MM-DD"
  dayOfMonth: number;    // @Min(1) @Max(28) int
}
```

```json
{"sourceId":"checking","recipientId":"travel-recipient","amountMinor":8300,
 "firstDate":"2026-11-20","endDate":"2027-10-20","dayOfMonth":20}
```

- 校验：日期解析失败 → `400 VALIDATION_ERROR`（"日期格式应为 YYYY-MM-DD"）；
  `dayOfMonth∉[1,28]`、`firstDate.day != dayOfMonth`、`endDate < firstDate`、`endDate > firstDate+1y` → `400 VALIDATION_ERROR`。
  余额/单笔限额/同账户 → 同转账校验。
- 成功 `200`（`kind="SCHEDULE"`，字段全部非空）：

```json
{"id":"...","user_id":"u1","kind":"SCHEDULE","payload_hash":"...","status":"AWAITING_AUTH",
 "expires_at":"...","receipt_id":null,"error_code":null,
 "source_id":"checking","recipient_id":"travel-recipient","amount_minor":8300,
 "first_date":"2026-11-20","end_date":"2027-10-20","day_of_month":20,
 "timezone":"Asia/Shanghai","insufficient_funds":"本次失败，不补扣","security":{...}}
```

- 注意：**创建计划本身不扣款**，提交成功后只是登记 `schedules`（`status=ACTIVE`，`id = receipt_id`）。

### 4.13 `POST /api/query-intents`

```ts
interface QueryIntentRequest {
  operation: string;              // 只接受 "QUERY_MANDATES"
  conversationId?: string;        // [A-Za-z0-9_-]{1,80}
  expectedContextVersion?: number;// @Min(1)
}
```

```json
{"operation":"QUERY_MANDATES"}
// 或者在 agent 模式（LANGGRAPH_* / FAKE_AGENT）下：
{"operation":"QUERY_MANDATES","conversationId":"<uuid>","expectedContextVersion":3}
```

- `operation != "QUERY_MANDATES"` → `400 VALIDATION_ERROR`（"不支持的查询意图"）。
- 带 `conversationId`：
  - 默认 profile（`ContextQueryReceiptService` 之外的 `AgentMandateReceiptService` 未注册）→ `400 VALIDATION_ERROR`（"当前模式不支持共享查询回执"）。
  - 对话不存在 → `400 NOT_FOUND`；对话过期 → `400 CONTEXT_EXPIRED`；版本不符或该对话有 PROCESSING 轮次 → `400 CONFLICT`。
- 成功 `200`（`kind="QUERY_MANDATES"`）：

```json
{"id":"...","user_id":"u1","kind":"QUERY_MANDATES","payload_hash":"...","status":"AWAITING_AUTH",
 "expires_at":"...","receipt_id":null,"error_code":null,
 "query":"OWN_BANK_MANDATES","security":{...}}
```

- 这是 `RiskLevel.YELLOW`（非 TRANSFER），所以 `requiredAssurance="CONFIRMED"`，`userConfirmed:true` 即可授权。

### 4.14 `GET /api/actions/{id}`（查单）

- 路径参数 `id`：动作 UUID 字符串（不是 UUID 类型，**非法字符串不会 400，而是 `400 NOT_FOUND`**）。
- 响应 `200 ActionExecutionResult`：
  - `kind != QUERY_MANDATES` 且**尚未提交**（`AWAITING_AUTH`/`AUTHORIZED`/`CANCELLED` 且无 observation）→ 只有 15 个 ActionVO 字段，**没有** `observed_at` / `observation`。
  - 已提交 → 会真的去"银行适配器"查单，返回 `ExecutionReceipt`：

```json
{
  "id":"...","user_id":"u1","kind":"TRANSFER","payload_hash":"...","status":"VERIFIED_SUCCESS",
  "expires_at":"...","receipt_id":"c1a2...","error_code":null,
  "source_id":"checking","recipient_id":"zhang-a","recipient_label":"张三 / 张三 3271",
  "amount_minor":50000,"currency":"CNY","fee_minor":0,
  "security":{...},
  "observed_at":"2026-10-27T14:22:03.456789Z"
}
```

  - `kind == QUERY_MANDATES` → `QueryExecutionReceipt`，**但 `data`/`queriedAt` 恒为 null**（数据只在 submit 那一次返回）：

```json
{"id":"...","kind":"QUERY_MANDATES","query":"OWN_BANK_MANDATES","status":"VERIFIED_SUCCESS",
 "receipt_id":"...","security":{...},"data":null,"queriedAt":null}
```

- `observation` 只会在 `"NOT_SUBMITTED"` 时出现（表示"银行侧确认未提交"，此时动作被回退成 `AWAITING_AUTH`）。
- 错误：`400 NOT_FOUND`（**不是 404！**）。

### 4.15 `GET /api/actions/{id}/risk`

- 无副作用（不写库）；响应 `200 RiskDecisionVO`。
- 若当前判定与库里最新 decision 不一致，会返回一个**合成对象**：`decisionId` = 旧 decision 的 id（可能为 `null`）、`needsRefresh = true`。
- 规则：`needsRefresh == true` 时**不要**拿它去 authorize，先调 `/risk/preview`。

### 4.16 `POST /api/actions/{id}/risk/preview`

- 无 body（前端传 `{}`）。
- 副作用：若判定变化则**落库新 decision**（`revision+1`）；若动作已过期（5 分钟），会顺带把动作重置为 `AWAITING_AUTH` 并把 `expires_at` 延后 5 分钟。
- 响应 `200 RiskDecisionVO`，`needsRefresh` **恒为 false** → 这是拿"可授权 decisionId"的正确入口。
- 错误：动作已受理（有 observation）或状态不在 `AWAITING_AUTH`/`AUTHORIZED` → `400 CONFLICT`（"已受理动作仅能查单"）。

### 4.17 `POST /api/actions/{id}/verification-challenges`

```ts
interface ChallengeRequest {
  payloadHash?: string;   // 必须等于动作的 payload_hash
  decisionId?: string;    // 必须等于**最新** decision 的 id
}
```

```json
{"payloadHash":"b1d0...","decisionId":"e77c..."}
```

- 成功 `200 ChallengeVO`（`status="SENT"`）：

```json
{"challengeId":"7c1f...","actionId":"9a1f...","decisionId":"e77c...",
 "expiresAt":"2026-10-27T14:27:12Z","status":"SENT","verificationMode":"LOCAL_DEMO_ONLY"}
```

- 失败：
  - `payloadHash`/`decisionId` 不匹配最新判定 → `409 RISK_DECISION_STALE`
  - 动作状态不允许或 `riskLevel != RED` → `409 CONFLICT`
  - 未启用演示验证（默认 profile）→ **`503 VERIFICATION_UNAVAILABLE`**
  - 60 秒内重复发起 → `429 CHALLENGE_RATE_LIMITED`（`retryAt` 给出可重试时间）
  - 安全锁定 → `423 SECURITY_LOCKED`
- 演示环境取码：独立控制台 `GET http://127.0.0.1:8101/challenges/{challengeId}`，头 `Authorization: Bearer $BANK_DEMO_OPERATOR_TOKEN`，返回 `{"challengeId","code","verificationMode":"LOCAL_DEMO_ONLY"}`。**Android 端不应实现取码**（这是"另一个人的手机"的隐喻）。

### 4.18 `POST /api/actions/{id}/verification-challenges/{challengeId}/verify`

```ts
interface ChallengeVerifyRequest { code?: string; }   // 6 位数字字符串 "123456"
```

```json
{"code":"123456"}
```

- 成功 `200 VerificationProofVO`（`verificationMode="LOCAL_DEMO_ONLY"`）。
- 失败：
  - 码格式错/不匹配 → `403 VERIFICATION_INVALID`（同一挑战错 3 次 → `LOCKED`；用户 10 分钟内错 5 次 → `423 SECURITY_LOCKED` 600 秒）
  - 过期 → `403 VERIFICATION_EXPIRED`
  - 已消费 → `409 VERIFICATION_REPLAYED`
  - 判定已变（`decisionId`/`payloadHash` 不一致）→ `409 RISK_DECISION_STALE`
- **重要**：verify 内部会重新评估风险并比较 `challenge.decisionId`，所以挑战创建后**不要再动其他转账**（会改变 `effectiveMinor` 从而产生新 decision，导致挑战失效）。

### 4.19 `POST /api/actions/{id}/authorize`（两步授权的第一步）

```ts
interface AuthorizationRequest {
  payloadHash: string;          // @NotBlank，必填
  decisionId?: string | null;   // 建议必填；缺失 → 409 RISK_DECISION_STALE
  userConfirmed: boolean;       // 基础类型；省略等价 false → 409 AUTH_REQUIRED
  verificationProofId?: string | null;  // RED 动作必填
}
```

```json
{"payloadHash":"b1d0...","decisionId":"e77c...","userConfirmed":true}
```

- 成功 `200 ActionVO`，`status` 变为 `AUTHORIZED`，`security` 为最新判定。
- 失败（全部为 `RiskErrorVO`）：

| 条件 | HTTP | code |
| --- | --- | --- |
| 安全锁定 / 需对账 | 423 | `SECURITY_LOCKED` / `RISK_RECONCILIATION_REQUIRED` |
| 动作已提交/已终止 | 409 | `CONFLICT` |
| `payloadHash` 不匹配 | 409 | `CONFLICT`（"确认参数已改变"） |
| 动作过期（> `expires_at`） | 409 | `AUTH_EXPIRED` |
| `decisionId` 缺失/空 | 409 | `RISK_DECISION_STALE` |
| `userConfirmed=false` | 409 | `AUTH_REQUIRED` |
| RED 且无 `verificationProofId` | 403 | `STEP_UP_REQUIRED` |
| `decisionId` 与最新不一致 | 409 | `RISK_DECISION_STALE` |
| 证明被复用 | 409 | `VERIFICATION_REPLAYED` |
| 证明不属于该动作/未 VERIFIED | 403 | `VERIFICATION_INVALID` |
| 证明过期 | 403 | `VERIFICATION_EXPIRED` |
| 无可用验证服务 | 503 | `VERIFICATION_UNAVAILABLE` |

- **`demoVerificationAccepted` 已经废弃**：`AuthorizationRequestDTO` 里没有这个字段。README/旧文档仍提到它，源码注释明确说"旧 `authorize` 中的 demoVerificationAccepted 不再有提升验证等级的含义"。默认 profile 下多传会被忽略；`agent-gateway` profile 下会导致 `400`（`fail-on-unknown-properties=true`）。**Android 端不要发这个字段。**
- 幂等：重复 authorize（同一 payloadHash/decisionId，且 grant 仍 ACTIVE）会直接返回现有授权，不会重复扣额度。

### 4.20 `POST /api/actions/{id}/submit`（两步授权的第二步）

- 无 body（前端传 `{}`，但仍需 `X-CSRF-Token`）。
- 前置：必须是 `AUTHORIZED` 且存在 ACTIVE 授权，否则 `409 AUTH_REQUIRED`（"提交前需要当前动作的有效授权"）；授权/动作过期 → `409 AUTH_EXPIRED`（动作会被回退成 `AWAITING_AUTH`）；判定变化 → `409 STEP_UP_REQUIRED` 或 `409 RISK_DECISION_STALE`。
- 响应 `200`：
  - `QUERY_MANDATES` → `QueryExecutionReceipt`（**这里有 `data`**）：

```json
{"id":"...","kind":"QUERY_MANDATES","status":"VERIFIED_SUCCESS","receipt_id":"...",
 "query":"OWN_BANK_MANDATES","security":{...},
 "data":[{"id":"music","user_id":"u1","merchant":"音乐服务","amount_minor":1500,
          "renewal_date":"2026-10-20","status":"ACTIVE","contract_status":"UNKNOWN",
          "billing_interval":"MONTHLY"}],
 "queriedAt":"2026-10-27T14:23:00.123456Z"}
```

  - 其他 → `ExecutionReceipt`（`status` = `VERIFIED_SUCCESS` / `VERIFIED_FAILED` / `PENDING` / `UNKNOWN`）。
- `submit` 对"已提交过"的动作是安全的：`beginSubmission` 检测到已有 observation 时返回 `LOOKUP_ONLY`，直接转查单，不会重复扣款。
- 明确失败（`INSUFFICIENT_FUNDS` / `LIMIT_EXCEEDED` / `ALREADY_REVOKED` / `VALIDATION_ERROR`）不会抛异常，而是返回 `status=VERIFIED_FAILED` + `error_code`。

### 4.21 `POST /api/actions/{id}/cancel`

- 无 body（前端传 `{}`）。
- 成功 `200 ActionVO`，`status = "CANCELLED"`。
- 失败：已受理/已提交/已终止 → `409 CONFLICT`（"已受理动作只能查单"）。
- 取消会：吊销未消费的授权（`REVOKED`/`EXPIRED`）、释放额度预留（`RELEASED`）、作废未完成的验证挑战（`INVALIDATED`）。

### 4.22 `POST /api/workflows`（创建联合任务）

```json
{"month":"2026-09","mandateIds":["music"],"sourceId":"checking",
 "recipientId":"travel-recipient","firstDate":"2026-11-20","endDate":"2027-10-20",
 "dayOfMonth":20,"onlyConfirmedSavings":true}
```

- 校验（`WorkflowService.validate`）——失败均为 `400 VALIDATION_ERROR`（`CONFLICT` 才是 409）：
  - `onlyConfirmedSavings` 必须 `true`；`mandateIds` 非空、≤10、无重复。
  - `month` 必须能被 `statement` 解析；`sourceId` 账户存在。
  - `recipientId` 必须**精确命中唯一收款人**。
  - 收款人账户不能等于 `sourceId`（"储蓄目标不能与付款账户相同"）。
  - `firstDate` 必须是**今天或未来**（Asia/Shanghai）、`dayOfMonth∈[1,28]` 且等于 `firstDate` 的日、`endDate∈[firstDate, firstDate+1y]`。
  - 每个 `mandateId` 必须 `billing_interval == "MONTHLY"` 且 `status == "ACTIVE"`（除非该任务里它已被核验撤销）。
- 成功 `200 WorkflowVO`，初始状态 `state="AWAITING_AUTH"`，`steps` 里已有第一个 `revoke:<mandateId>` 步骤（`status=AWAITING_AUTH`）。
- 响应示例（注意 workflow 层是 **camelCase**，action 层是 snake_case）：

```json
{
  "id": "5b7c1a2e-0d4f-4c9a-9c11-2a3b4c5d6e7f",
  "version": 1,
  "state": "AWAITING_AUTH",
  "request": {"month":"2026-09","mandateIds":["music"],"sourceId":"checking",
              "recipientId":"travel-recipient","firstDate":"2026-11-20",
              "endDate":"2027-10-20","dayOfMonth":20,"onlyConfirmedSavings":true},
  "estimatedSavingMinor": 0,
  "partial": false,
  "steps": [
    {"key":"revoke:music","kind":"REVOKE","status":"AWAITING_AUTH",
     "actionId":"...","inputVersion":1,"savingMinor":1500,"errorCode":null}
  ],
  "pendingAction": {"id":"...","kind":"REVOKE","status":"AWAITING_AUTH","mandate_id":"music",
                    "charge_amount_minor":1500,"billing_interval":"MONTHLY",
                    "estimated_monthly_saving_minor":1500,"contract_status":"UNKNOWN",
                    "scope":"...","payload_hash":"...","expires_at":"...",
                    "receipt_id":null,"error_code":null,"merchant":"音乐服务","security":{...}},
  "analysis": { "StatementVO": "创建时的账单快照" }
}
```

- 无待确认项时 `pendingAction` 是**空对象 `{}`**（`NoPendingActionVO` 有专用序列化器）。
- 错误：`400 VALIDATION_ERROR`；`409 CONFLICT`（同 id 但参数不同，仅内部路径会出现）。

### 4.23 `GET /api/workflows`

- 响应 `200 WorkflowVO[]`，`ORDER BY created_at DESC`（最新在前）。

### 4.24 `GET /api/workflows/{id}`

- `id` 必须是**合法 UUID**（`@PathVariable UUID`）；非法 → `400`（`MethodArgumentTypeMismatchException`，Spring 默认错误体）。
- 不存在/不属于当前用户 → `400 NOT_FOUND`。

### 4.25 `GET /api/workflows/{id}/plan`

- 响应 `200 TaskPlanVO`，节点固定生成：

```json
{"workflowId":"5b7c...","version":1,"state":"AWAITING_AUTH",
 "savingsPolicy":"verified_success_subset","analysisMonth":"2026-09",
 "nodes":[
  {"id":"m","type":"mandate_lookup","dependsOn":[],"displayState":"VALIDATED","actionId":null,"errorCode":null},
  {"id":"a","type":"account_query","dependsOn":[],"displayState":"VALIDATED","actionId":null,"errorCode":null},
  {"id":"r1","type":"mandate_revoke","dependsOn":["m"],"displayState":"AWAITING_AUTH","actionId":"...","errorCode":null},
  {"id":"s","type":"compute_savings","dependsOn":["r1"],"displayState":"WAITING_DEPENDENCY","actionId":null,"errorCode":null},
  {"id":"t","type":"schedule_create","dependsOn":["s","a"],"displayState":"WAITING_DEPENDENCY","actionId":null,"errorCode":null}]}
```

### 4.26 `POST /api/workflows/{id}/resume`（查单并推进）

```json
{"version": 1}
```

- body 为 `WorkflowVersionRequestDTO`，`version` **必填且 >0**（`@Positive long`）。
- 语义：对每个未终态步骤调用一次查单 → 更新步骤状态 → `advance()`（推进到下一个撤销步骤，或生成 schedule 步骤）。
- 版本冲突 → `409 CONFLICT`（"任务版本已改变，请刷新后再操作"，**这是唯一会被映射成 409 的 workflow 错误码**）。
- 幂等：对 `CANCELLED`/`VERIFIED_SUCCESS` 直接返回当前视图；对已过期的待确认动作会先返回（不推进）。
- 响应 `200 WorkflowVO`（新版本）。

### 4.27 `POST /api/workflows/{id}/revise`（改参）

```json
{"version":1,
 "request":{"month":"2026-09","mandateIds":["music"],"sourceId":"checking",
            "recipientId":"zhang-a","firstDate":"2026-11-20","endDate":"2027-10-20",
            "dayOfMonth":20,"onlyConfirmedSavings":true}}
```

- `version` `@Positive`；`request` `@NotNull` 且递归 `@Valid`。
- 行为：校验新参数 → 取消所有**未受理**的旧步骤动作 → 版本+1 → 重新 `advance()`。
- 拒绝：已 `VERIFIED_SUCCESS`/`PARTIAL_SUCCESS`/`CANCELLED`/`CANCEL_REQUESTED` → `409 CONFLICT`（"已完成或已申请取消的任务不能改参"）；有已受理/未知步骤 → `409 CONFLICT`（"已受理或状态未知的动作需要先查单"）。
- 副作用提示：**旧待确认动作会失效**（前端文案："原待确认项将失效"）。

### 4.28 `POST /api/workflows/{id}/cancel`

```json
{"version":1}
```

- 取消所有未受理步骤 → 若全部终态则 `state="CANCELLED"`，否则 `state="CANCEL_REQUESTED"`。
- 已 `VERIFIED_SUCCESS`/`PARTIAL_SUCCESS` → `409 CONFLICT`（"已完成的银行动作不能通过取消任务撤回"）。
- **已完成的银行动作不会回滚**（`partial=true` 时尤其要看提示）。

### 4.29 `POST /api/conversations`

- 无 body。成功 `200 ConversationVO`：

```json
{"conversationId":"<uuid>","version":1,"intent":"GENERAL","state":"ACTIVE",
 "slots":{},"proposal":null,"displayHistory":[],"bankReferences":[]}
```

- 有效期：创建后 7 天（每次成功对话会续期 7 天）。

### 4.30 `GET /api/conversations/{id}`

- 成功 `200 ConversationVO`（含 `displayHistory` 最多 4 轮、`bankReferences` 真实内容）。
- 不存在 → `404 NOT_FOUND`；过期 → **`410 CONTEXT_EXPIRED`**（这两个状态码是 `/api/conversations/**` 专属映射）。

### 4.31 `POST /api/conversations/{id}/context/reset`

```ts
interface ContextResetRequest { expectedContextVersion: number; }  // @Min(1) long，必填
```

```json
{"expectedContextVersion":3}
```

- 成功 `200 ConversationVO`（`version = expected+1`，`intent=GENERAL`，`state=ACTIVE`，`slots={}`，`displayHistory=[]`）。
- 失败：版本冲突或仍在处理 → `409 CONFLICT`；过期 → `410 CONTEXT_EXPIRED`；不存在 → `404 NOT_FOUND`。
- 副作用：清空槽位与证据、作废该对话绑定的代扣查询回执，并清掉 HttpSession 里 `conversation`/`answerEvidence`/`mandatesContextSeen`/`lastContextResults`/`conversationUser`。**不影响已存在的银行动作**。

### 4.32 `POST /api/chat`（核心）

```ts
interface ChatRequest {
  text: string;                     // @NotBlank @Size(max=4000)；服务端还会截 4500 校验
  modelRequestLimit?: number;       // @Min(1) @Max(12)；缺省 5（上下文链路 max 5）
  conversationId?: string;          // [A-Za-z0-9_-]{1,80}
  requestId?: string;               // [A-Za-z0-9_-]{1,80}，幂等键；缺省服务端生成 UUID
  expectedContextVersion?: number;  // @Min(1)
}
```

```json
{"text":"分析2026-09账单","modelRequestLimit":5,
 "requestId":"8f14e45f-ceea-467a-9c15-1b0f5e2d3c44",
 "conversationId":"<uuid>","expectedContextVersion":3}
```

**幂等与并发（重要）**

| 情形 | 结果 |
| --- | --- |
| `requestId` 相同 + `text`/`modelRequestLimit` 相同 + 上轮 `COMPLETED` | 直接返回**原响应**（不调模型、不调银行），且带同样 `contextVersion` |
| `requestId` 相同但内容不同 | `409 CONFLICT`（"相同请求编号对应不同内容"） |
| `requestId` 相同且上轮 `PROCESSING` | `409 CONTEXT_PROCESSING`（"原请求仍在处理，未重复调用模型或银行"） |
| `requestId` 相同且上轮 `FAILED`/`INTERRUPTED` | `409 CONTEXT_INTERRUPTED`（"原请求未完成，请先核对关联动作，不自动重放"） |
| `expectedContextVersion` 与当前不一致 | `409 CONFLICT`（"对话版本已改变，请读取最新上下文"） |
| 该对话还有别的 `PROCESSING` 轮次 | `409 CONFLICT`（"上一轮尚在处理中…"） |
| 未传 `conversationId` | 自动用会话里 `activeConversationId`，没有就新建（**并写入 HttpSession**） |

前端重试策略：仅在 `LANGGRAPH_*` / `FAKE_AGENT` 模式下对 `CONTEXT_PROCESSING` / `AGENT_RECONCILING` 用**同一个 requestId** 轮询，最多 25 次 × 2 秒；其他模式直接报错并把原请求文本留在输入框，显示"核对原请求"按钮，**不自动重发**。

**成功响应 `200 ChatResponse`（`mode = "OFFLINE_DEMO"`，无模型密钥）**

> 关键差异：离线规则模式**不返回** `results` / `answerSource` / `factReferences` 三个键
> （源码中 `offline()` 走 `reply()`，只有 `online()` 的 `finish()` 才会补这三个字段）。
> 因此 Kotlin 端这三个字段必须是**可缺省**（`= emptyList()` / `= null`）。

```json
{
  "text": "离线工具演示（未调用模型）：\n2026-09 支出合计 3,403.00 元……",
  "mode": "OFFLINE_DEMO",
  "data": {"month":"2026-09","expense_minor":340300,"...":"StatementVO"},
  "actions": [],
  "trace": ["查询交易","程序汇总"],
  "contextResults": [],
  "modelUsage": {
    "request_count": 0, "request_limit": 5, "generation_requests": 0, "review_requests": 0,
    "usage_reported_requests": 0, "usage_complete": true,
    "prompt_tokens": null, "completion_tokens": null, "total_tokens": null,
    "prompt_cache_hit_tokens": null, "prompt_cache_miss_tokens": null,
    "calls": []
  },
  "timing": {"requestElapsedMs": 41, "authorizationWaitIncluded": false},
  "clarification": null,
  "conversationId": "<uuid>",
  "contextVersion": 2,
  "context": {
    "conversationId":"<uuid>","version":2,"intent":"STATEMENT_ANALYSIS","state":"ACTIVE",
    "slots":{"primary_month":"2026-09"},"proposal":null,
    "displayHistory":[{"user":"分析2026-09账单","assistant":"离线工具演示（未调用模型）：……"}],
    "bankReferences":[]
  }
}
```

**已配置模型时的响应（`mode = MODEL_CONFIGURED` / `CONNECTION_VERIFIED`）** 会额外带：

```json
{
  "results": [{"tool":"get_statement","data":{"month":"2026-09","...":"..."},"observation":{}}],
  "answerSource": "MODEL_GROUNDED",
  "factReferences": ["statement_2026-09.expense_minor"]
}
```

`answerSource` 取值含义：

| 值 | 含义 |
| --- | --- |
| `MODEL_GROUNDED` | 模型输出 + 程序事实/业务核验全部通过 |
| `SAFE_FALLBACK` | 模型输出未通过核验，`text` 前缀会被加上"本轮模型回答未通过核验，以下为工具数据说明：" |
| `ACTION_RECEIPT` | 本轮生成了待确认操作卡（`actions` 非空） |
| `PROGRAM_VERIFIED_FAKE_AGENT` | `agent-gateway` 模式，结果由程序核验 |
| `PROGRAM_VERIFIED_LANGGRAPH` | `langgraph-agent` 模式，结果由程序核验 |

**需要生成操作卡时的响应**（`actions` 里有 1 个待确认动作；`data` 与该动作同构）：

```json
{
  "text": "已生成转账确认卡，请在确认卡上核对后逐项确认。",
  "mode": "OFFLINE_DEMO",
  "data": {"id":"...","kind":"TRANSFER","status":"AWAITING_AUTH","source_id":"checking",
           "recipient_id":"zhang-a","amount_minor":50000,"payload_hash":"...",
           "user_id":"u1","recipient_label":"张三 / 张三 3271","currency":"CNY",
           "fee_minor":0,"expires_at":"...","receipt_id":null,"error_code":null,
           "security":{"riskLevel":"YELLOW","decisionId":"...","needsRefresh":false,"...":"..."}},
  "actions": [ { "...": "与 data 相同的 ActionVO 结构" } ],
  "trace": ["收款人解析","余额/限额检查","等待用户授权"],
  "contextResults": [],
  "modelUsage": { "...": "..." },
  "timing": {"requestElapsedMs": 12, "authorizationWaitIncluded": false},
  "clarification": null,
  "conversationId":"<uuid>","contextVersion":3,
  "context": {"state":"AWAITING_CONFIRMATION","...":"..."}
}
```

> 注意：离线模式生成的这张卡**同样没有** `answerSource`。不要用"有没有 `answerSource`"来判断"有没有待确认动作"，
> 要用 `actions?.any { it.status == "AWAITING_AUTH" }`。

**`/api/chat` 的字段来源与语义**

| 字段 | 语义 | Android 用法 |
| --- | --- | --- |
| `text` | 回答正文（纯文本，可能有多段，不含 Markdown 表格/加粗） | 直接渲染为聊天气泡；`"\n"` 分段 |
| `mode` | 当前智能体模式 | 顶部徽标 |
| `data` | 最后一个工具的业务结果（可能是 StatementVO / AccountVO[] / MandateVO[] / ActionVO / `{"workflow": WorkflowVO}`） | 右侧"查询依据"面板；`data.workflow` 存在时应刷新任务列表 |
| `actions` | 待确认操作卡（0/1 个 ActionVO） | 渲染确认卡；`status=="AWAITING_AUTH"` 的才需要用户操作 |
| `trace` | 工具轨迹字符串数组；格式可能是 `"tool"`、`"tool / CODE"`、`"tool / CODE / 说明"` | 折叠的"查看本轮查询依据" |
| `results` | 每个工具一条 `{tool,data,observation}` | 可逐条渲染结果块 |
| `answerSource` | **仅模型链路才有此键**。`MODEL_GROUNDED`（模型+程序核验通过）/ `SAFE_FALLBACK`（模型回答未过核验，`text` 前缀会带"本轮模型回答未通过核验，以下为工具数据说明："）/ `ACTION_RECEIPT`（生成了确认卡）/ `PROGRAM_VERIFIED_*`（网关模式） | 用于 UI 提示可信度；`OFFLINE_DEMO` 下为 `null`（键缺失） |
| `factReferences` | 回答引用的事实 ID | 调试用，可忽略 |
| `contextResults` | 历史上下文结果，默认 `[]` | 可忽略 |
| `modelUsage` | 本轮模型调用统计（snake_case） | 设置页/调试面板 |
| `timing.requestElapsedMs` | 服务端处理耗时（含模型） | 调试 |
| `clarification` | 默认模式：`ClarificationVO` 或 `null`；网关模式：`{"required": boolean}` | **必须容忍三种形态** |
| `context` | 最新对话视图 | 更新"当前业务上下文"条 |
| `conversationId` / `contextVersion` | 下次请求要回传 | 保存到本地 |

**网关模式（`agent-gateway` / `langgraph-agent`）额外特性**

```json
{
  "text":"...", "mode":"FAKE_AGENT", "agentBackend":"FAKE_PYTHON_PROTOCOL",
  "toolResults":[{"tool":"statement","result":{...},"evidenceId":"...","observedAt":"2026-10-27T..."}],
  "results":[...], "data":{...}, "actions":[...], "trace":[],
  "answerSource":"PROGRAM_VERIFIED_FAKE_AGENT",
  "clarification":{"required":false},
  "conversationId":"...","contextVersion":3,"context":{...}
}
```

此时错误码换成：`AGENT_RECONCILING`、`AGENT_REVIEW_REQUIRED`、`AGENT_BUDGET_EXCEEDED`、`TOOL_QUOTA_EXCEEDED` 等；`/api/chat` 路径下未列出的码一律 `400`。

**`/api/chat` 错误与 HTTP 状态**

| code | HTTP |
| --- | --- |
| `CONFLICT` / `CONTEXT_PROCESSING` / `CONTEXT_INTERRUPTED` | 409 |
| `AGENT_RECONCILING` | **400**（前端仅按 `code` 决定是否轮询，不依赖状态码） |
| `NOT_FOUND` | 404 |
| `CONTEXT_EXPIRED` | 410 |
| 其他（`VALIDATION_ERROR`、`MODEL_*`、`ANSWER_*`、`AGENT_REVIEW_REQUIRED`、`AGENT_BUDGET_EXCEEDED`、`TOOL_QUOTA_EXCEEDED`…） | 400 |

### 4.33 `POST /api/simulation/faults`（仅 `risk-demo`）

```ts
type FaultMode = "NONE" | "BEFORE_SUBMIT_FAILURE" | "AFTER_COMMIT_RESPONSE_LOST"
               | "PENDING_THEN_SUCCESS" | "LOOKUP_UNAVAILABLE";

interface SimulationFaultRequest {
  actionId: string;      // @NotBlank
  submitMode: FaultMode; // @NotNull；不能是 LOOKUP_UNAVAILABLE
  lookupMode: FaultMode; // @NotNull；只能是 NONE 或 LOOKUP_UNAVAILABLE
}
```

```json
{"actionId":"9a1f...","submitMode":"AFTER_COMMIT_RESPONSE_LOST","lookupMode":"NONE"}
```

- 响应 `200 {"configured": true}`。
- 未启用故障注入 → `400 SIMULATION_DISABLED`；模式位置错误 → `400 VALIDATION_ERROR`；动作不存在 → `400 NOT_FOUND`。
- 语义：

| submitMode | 效果 |
| --- | --- |
| `NONE` | 正常提交 |
| `BEFORE_SUBMIT_FAILURE` | 银行明确拒绝 → `VERIFIED_FAILED` + `error_code="SIMULATED_BANK_REJECTION"` |
| `AFTER_COMMIT_RESPONSE_LOST` | 钱已动，但响应丢失 → `status="UNKNOWN"`、`error_code="RESPONSE_LOST"` |
| `PENDING_THEN_SUCCESS` | 先 `PENDING`，下次查单再真正执行 |
| lookupMode=`LOOKUP_UNAVAILABLE` | 查单永远返回 `UNKNOWN` + `error_code="LOOKUP_UNAVAILABLE"` |

### 4.34 `POST /api/simulation/run-due`（仅 `risk-demo`）

```json
{"date":"2026-11-20"}
```

- `date` `@NotBlank`，`YYYY-MM-DD`；非法 → `400 VALIDATION_ERROR`。
- 未启用模拟时钟 → `400 SIMULATION_DISABLED`（"仅隔离实验时钟支持模拟日期"）。
- 响应 `200 ActionVO[]`：每个到期计划生成/复用一个 `TRANSFER` 动作（自动授权 + 提交），返回**最终状态**的动作列表；同一 `(scheduleId, dueDate)` 重复触发不会重复扣款。

```json
[{"id":"...","kind":"TRANSFER","status":"VERIFIED_SUCCESS","source_id":"checking",
  "recipient_id":"travel-recipient","amount_minor":8300,"currency":"CNY","fee_minor":0,
  "schedule_id":"<scheduleId>","occurrence_date":"2026-11-20","receipt_id":"...","..." :"..."}]
```

### 4.35 `GET /api/agent/turns/{id}`（仅 `agent-gateway`）

```ts
interface AgentTurnStatusVO {
  turnId: string;
  state: string;              // PROCESSING / COMPLETED / ACK_PENDING / ACKED / PAUSED / INTERRUPTED
  reconcileFailures: number;
  outputVersion: number;
  requiresReview: boolean;    // state == "PAUSED"
}
```

- `id` 必须匹配 `[A-Za-z0-9_-]{1,80}`，否则 `400 NOT_FOUND`；不属于当前会话 → `400 NOT_FOUND`。

---

## 5. 请求 / 响应命名差异（最容易踩的坑）

### 5.1 差异总表

| 概念 | 请求体字段 | 响应字段 |
| --- | --- | --- |
| 动作参数哈希 | `payloadHash` | `payload_hash` |
| 风险判定 id | `decisionId` | `security.decisionId`（camelCase） |
| 金额 | `amountMinor` | `amount_minor` |
| 来源账户 | `sourceId` | `source_id` |
| 收款人 | `recipientId` | `recipient_id` |
| 首次/结束日期 | `firstDate` / `endDate` | `first_date` / `end_date` |
| 每月日 | `dayOfMonth` | `day_of_month` |
| 代扣 id | `mandateId` | `mandate_id` |
| 代扣列表 | `mandateIds` | `request.mandateIds`（workflow 内仍是 camelCase） |
| 动作 id（路径） | `{id}` | `id` |
| 任务版本 | `version` | `version`（camelCase） |
| 会话版本 | `expectedContextVersion` | `contextVersion` / `version` |
| 查单时间 | — | `observed_at`（execution receipt）/ `queriedAt`（query receipt） |
| 模型用量 | — | `request_count`…（全 snake_case） |

**记忆规则**：
1. **请求体 100% camelCase**（所有 DTO 都是裸 `record`，没有 `@JsonProperty`）。
2. **响应体只要属于“银行动作/账户/账单/代扣/计划/审计”就是 snake_case**；属于“workflow / 风险 / 对话 / 计划节点 / 模型用量”的基本是 camelCase（`modelUsage` 内部例外地又是 snake_case）。
3. `security` 对象内部**永远 camelCase**。

### 5.2 可空性（决定 Kotlin 是否需要 `?`）

| 字段 | 可空/可缺失 |
| --- | --- |
| `ActionVO.receipt_id` / `error_code` | **键始终存在**，值为 `null` |
| `ActionVO.expires_at` | 始终存在 |
| `ActionVO.security` | 键存在，可能为 `null` |
| `TransferAction.fee_minor` | 键存在，可能为 `null` |
| `TransferAction.schedule_id` / `occurrence_date` | **键可能不存在**（`NON_NULL`） |
| `RevokeAction.estimated_monthly_saving_minor` | **键可能不存在**（`NON_NULL`） |
| `ExecutionReceipt.observed_at` / `observation` | **键可能不存在**（`NON_NULL`） |
| `QueryExecutionReceipt.data` / `queriedAt` | 键始终存在，值可为 `null` |
| `WorkflowVO.pendingAction` | 键始终存在，值为 `{}` 或 ActionVO |
| `StatementVO.*` | 全部始终存在；`transactions[].amount_minor` / `booked_on` 可为 `null` |
| `ConversationVO.proposal` | 可为 `null` |
| `ChatResponse.clarification` | 可为 `null` 或对象（两种形状） |
| `ChatResponse.results` / `answerSource` / `factReferences` | **`OFFLINE_DEMO` 下三个键都不存在**，必须给 default |
| `ChatResponse.data` | 键始终存在，但可能是 `{}`（不是 `null`） |
| `ChatResponse.toolResults` / `agentBackend` | 仅网关模式存在 |
| `ModelUsage.prompt_tokens` 等 | 可为 `null`（`usage_complete=false` 时） |
| `RiskErrorVO.actionId` / `security` / `retryAt` | 可为 `null` |

---

## 6. 状态机与业务流程

### 6.1 Action 状态机

```
                    POST /xxx/preview  |  POST /query-intents
                              │
                              ▼
                       ┌──────────────┐
                       │ AWAITING_AUTH│◄──────────────┐
                       └──────┬───────┘               │
              authorize (YELLOW: userConfirmed=true)  │
              authorize (RED: + verificationProofId)  │  取消/过期/条件变化
                              │                      │  （回退）
                              ▼                      │
                       ┌──────────────┐              │
                       │  AUTHORIZED  │──────────────┘
                       └──────┬───────┘
                       submit │  （必须先 BEGIN_SUBMISSION，落 observation）
              ┌───────────────┼────────────────┬──────────────┐
              ▼               ▼                ▼              ▼
      VERIFIED_SUCCESS   VERIFIED_FAILED   PENDING         UNKNOWN
              │               │                │              │
              └───────────────┴────────────────┘              │
                     （终态，只能查单）                  GET /api/actions/{id}
                                                          继续查单，禁止新建动作
                       ┌──────────────┐
                       │  CANCELLED   │  （仅 AWAITING_AUTH / AUTHORIZED 可取消）
                       └──────────────┘
```

关键不变量：

1. **只有 `AWAITING_AUTH` 和 `AUTHORIZED` 可以 authorize/cancel**；一旦有 observation 就只能查单（`create`/`get`/`submit` 都会检查）。
2. `submit` 是**唯一**会真正动钱的入口，且要求 `AUTHORIZED` + 存在 ACTIVE 授权（`ActionAuthorizationEntity`）。
3. `UNKNOWN` / `PENDING` **不是错误**，是"结果未定"：只能 `GET /api/actions/{id}` 继续查单，**绝不能新建同参数动作**（会造成重复扣款）。`ACTION_ALREADY_COMPLETED`、`ACTION_RECONCILING` 等错误码也提示同样语义。
4. `VERIFIED_SUCCESS` 的 TRANSFER 会强制校验双边账务分录（借/贷两条、金额匹配），否则报 `400 CONFLICT`（"账务验证失败，不能报告成功"）。
5. 预览有效期 **5 分钟**（`expires_at`），授权有效期也是 **5 分钟且不跨业务日**（取 `min(now+5min, 次日 00:00 Asia/Shanghai)`）。

### 6.2 两步授权（授权 ≠ 执行）

```
POST /transfers/preview
  → 200 ActionVO { id, payload_hash, status:"AWAITING_AUTH", security:{decisionId,...} }
      │
      ├─(YELLOW)──────────────────────────────────────────────┐
      │                                                       │
      └─(RED)─ POST /actions/{id}/risk/preview  ← 拿权威 decisionId
                 → 200 RiskDecisionVO { decisionId, riskLevel:"RED", needsRefresh:false }
                 POST /actions/{id}/verification-challenges { payloadHash, decisionId }
                 → 200 ChallengeVO { challengeId, expiresAt }
                 （在独立验证台取 6 位码）
                 POST /actions/{id}/verification-challenges/{challengeId}/verify { code }
                 → 200 VerificationProofVO { verificationProofId }
                                                               │
      ┌────────────────────────────────────────────────────────┘
      ▼
POST /actions/{id}/authorize
   { payloadHash, decisionId, userConfirmed:true, verificationProofId? }
   → 200 ActionVO { status:"AUTHORIZED" }
      │
      ▼
POST /actions/{id}/submit        （无 body）
   → 200 ExecutionReceipt | QueryExecutionReceipt
      │
      ▼
GET /api/actions/{id}            （PENDING/UNKNOWN 时轮询；前端是"查询最新状态"按钮，非自动轮询）
```

**红线**：
- 授权与执行是两个独立请求，中间用户可以取消。授权后**不**自动提交。
- `userConfirmed` 必须是用户**在勾选框上主动确认**的结果；聊天里说"确认"不构成授权。
- `payloadHash` 必须来自**同一个**预览响应；任何参数变化都会让 `payloadHash` 不匹配 → `409 CONFLICT`。
- `decisionId` 必须是**当前**判定；RED 动作在验证证明生成后如果判定再变，证明即失效（`409 RISK_DECISION_STALE` / `403 VERIFICATION_INVALID`）。

### 6.3 代扣查询（唯一的读取代扣方式）

```
POST /api/query-intents { operation:"QUERY_MANDATES" }
  → ActionVO kind=QUERY_MANDATES, query=OWN_BANK_MANDATES
POST /api/actions/{id}/authorize { payloadHash, decisionId, userConfirmed:true }   （YELLOW）
POST /api/actions/{id}/submit
  → QueryExecutionReceipt { status:VERIFIED_SUCCESS, data:[MandateVO...], queriedAt }
```

- 重复 `submit` 返回 `data:null, queriedAt:null`（原始回执已冻结，不会假装刷新到新数据）。
- 查询本身**不产生**转账、不写 ledger、不写 observation。
- `GET /api/mandates` 永远 409，不要用。

### 6.4 定时计划（schedule）预览与模拟执行

```
POST /api/schedules/preview   → ActionVO kind=SCHEDULE（status=AWAITING_AUTH，不扣款）
POST /api/actions/{id}/authorize → AUTHORIZED
POST /api/actions/{id}/submit    → VERIFIED_SUCCESS，receipt_id = 新建 schedule id
   ⇒ schedules 表新增一行 status=ACTIVE，并登记 ScheduleScope（金额上限、期间、日）
POST /api/simulation/run-due { date }   （需 risk-demo）
   ⇒ 对每个到期 schedule 生成 TRANSFER 动作（自动授权 + 提交），返回 ActionVO[]
```

- `run-due` 的幂等键是 `(schedule_id, due_date)`，重复触发同一天不会重复扣款。
- 计划只允许 `1..28` 日、最长一年；`insufficient_funds="本次失败，不补扣"`。
- **"登记定时计划" ≠ "未来一定会转账"**（前端文案明确要求这样展示）。

### 6.5 联合任务工作流（workflow）

状态：

```
PLANNING ──(advance)──► AWAITING_AUTH ──(所有撤销步骤 VERIFIED_SUCCESS)──►
   │                        │                                      │
   │                        ├─► PENDING / UNKNOWN（步骤银行处理中/未定）
   │                        │                                      ▼
   │                        │                          生成 schedule 步骤 → AWAITING_AUTH
   │                        │                                      │
   │                        ├──────── 无可计节省 ──► VERIFIED_FAILED
   │                        │                                      │
   └── cancel ──► CANCEL_REQUESTED ──(全部终态)──► CANCELLED        │
                                                                   ▼
                                              schedule VERIFIED_SUCCESS → VERIFIED_SUCCESS
                                              否则 → PARTIAL_SUCCESS
```

步骤生成规则（`advance()`，每次推进只走一步）：

1. 若还有非终态步骤 → 任务状态 = 该步骤状态（`UNKNOWN`/`PENDING` 原样，其余映射成 `AWAITING_AUTH`）。
2. 否则按 `request.mandateIds` 顺序，为**还没有** `revoke:<id>` 步骤（或该步骤已取消且属于旧版本）的代扣生成 `REVOKE` 预览 → 新步骤 `AWAITING_AUTH`。
3. 全部代扣处理完 → 计算 `estimatedSavingMinor`（只累计本次任务内 `VERIFIED_SUCCESS` 的 REVOKE 的 `savingMinor`）；为 0 → `VERIFIED_FAILED`。
4. 有节省 → 生成 `schedule` 步骤（金额 = 节省额）→ `AWAITING_AUTH`。
5. schedule 步骤 `VERIFIED_SUCCESS` → `VERIFIED_SUCCESS`；否则 → `PARTIAL_SUCCESS`。

`revise`：只能改"未来参数"；会先取消所有未受理步骤动作，版本 +1。**已受理/未知的步骤会阻塞改参与取消**（`409 CONFLICT`）。
`resume`：先对每个非终态步骤查单，再推进；这是"查单并继续"唯一入口。
`cancel`：只取消未受理步骤；已成功的银行动作不可撤回。

### 6.6 客户端必须避免的三件事

1. **不要用 `/api/chat` 代替授权**。聊天只能说"已生成预览"，不能签发授权或成功回执（系统提示与代码双重约束）。
2. **不要"重试"提交类请求**。`submit`/`authorize` 的失败要区分：`409` 表示"刷新后重读同一动作"，不是"再发一次"。
3. **不要在会话失效后盲目重放**。`401 SESSION_REQUIRED` 之后重建会话，再用 `GET /api/actions/{id}`（用旧 id）确认状态，而不是重新预览。

---

## 7. 金额、日期、币种、时区约定

| 项 | 约定 |
| --- | --- |
| 金额单位 | **整数分（minor units）**，字段名一律 `*_minor` / `*Minor` |
| 显示换算 | `minor / 100` 元，两位小数；前端用 `Intl.NumberFormat('zh-CN',{currency:'CNY'})` |
| 币种 | 固定 `"CNY"`（转账参数里有 `currency` 字段；账单/账户不重复返回币种） |
| 金额上下限 | 单笔 ≤ `1000000` 分（¥10,000）；`> 0`；日累计 RED 阈值 `100000` 分（¥1,000） |
| 负数含义 | 只有 `TransactionVO.amount_minor` 会是负数（支出）；其余金额均为正 |
| 月份 | `YYYY-MM`（`YearMonth`），如 `2026-09`；`/api/statement` 默认 `2026-09` |
| 日期 | `YYYY-MM-DD`（`LocalDate`），如 `2026-11-20` |
| 时间戳 | ISO-8601 **UTC**，如 `2026-10-27T14:35:12.345678Z`（可能带微秒，以 `Z` 结尾）；`Instant` / `OffsetDateTime` 都用这个格式 |
| 业务日 | `Asia/Shanghai` 的本地日期（`RiskDecisionVO.businessDate`、`RiskSummaryVO.businessDate`、`Submit` 截止时刻） |
| 计划时区 | `ScheduleAction.timezone = "Asia/Shanghai"` |
| 只允许日 1–28 | 避免月末不存在的问题；且 `dayOfMonth` 必须等于 `firstDate` 的日 |
| 计划最长 | `endDate ≤ firstDate + 1 年` |
| 预览/授权有效期 | 5 分钟且不跨业务日 |
| 对话有效期 | 创建/每轮成功后 +7 天；HttpSession 30 分钟 |
| 验证码 | 6 位数字，挑战有效期 2 分钟（不跨业务日） |

**全 0 金额语义**：`fee_minor = 0` 表示本演示不收手续费；`estimatedSavingMinor = 0` 表示"暂无已核验节省"，不是"没有花费"。

---

## 8. Kotlin / kotlinx.serialization 建模建议

### 8.1 `Json` 配置

```kotlin
val ApiJson = Json {
    ignoreUnknownKeys = true      // 必须：默认 profile 忽略，agent-gateway 会因未知字段 400，但客户端仍要宽松
    isLenient = false
    explicitNulls = false         // 让缺省/null 都能落到默认值
    encodeDefaults = true         // 请求体要显式带上 userConfirmed=false 之类的布尔
    coerceInputValues = false
}
```

### 8.2 反序列化 tolerance（哪些字段要 default）

**必须**给默认值（键可能缺失）：

```kotlin
@Serializable
data class ActionDto(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    val kind: String = "",
    @SerialName("payload_hash") val payloadHash: String = "",
    val status: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("receipt_id") val receiptId: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    // ---- 参数（@JsonUnwrapped 摊平后的并集，全部可空/可缺省）----
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("recipient_id") val recipientId: String? = null,
    @SerialName("recipient_label") val recipientLabel: String? = null,
    @SerialName("amount_minor") val amountMinor: Long? = null,
    val currency: String? = null,
    @SerialName("fee_minor") val feeMinor: Long? = null,
    @SerialName("schedule_id") val scheduleId: String? = null,
    @SerialName("occurrence_date") val occurrenceDate: String? = null,
    @SerialName("mandate_id") val mandateId: String? = null,
    val merchant: String? = null,
    @SerialName("charge_amount_minor") val chargeAmountMinor: Long? = null,
    @SerialName("billing_interval") val billingInterval: String? = null,
    @SerialName("estimated_monthly_saving_minor") val estimatedMonthlySavingMinor: Long? = null,
    @SerialName("contract_status") val contractStatus: String? = null,
    val scope: String? = null,
    @SerialName("first_date") val firstDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("day_of_month") val dayOfMonth: Int? = null,
    val timezone: String? = null,
    @SerialName("insufficient_funds") val insufficientFunds: String? = null,
    val query: String? = null,
    // ---- 查单附加 ----
    @SerialName("observed_at") val observedAt: String? = null,
    val observation: String? = null,
    val data: List<MandateDto>? = null,     // 只有 query 提交时才有
    val queriedAt: String? = null,          // ⚠ camelCase
    val security: RiskDecisionDto? = null
)
```

**为什么不建议用 sealed class 多态**：`kind` 是**普通字段**而不是 `@classDiscriminator`（它和业务字段平级，且同一个 `kind` 对应 4 种参数并集）。用 `sealed` 需要自定义 `JsonContentPolymorphicSerializer`，收益低。**推荐一个扁平 DTO + 可空字段**，再用 `when (kind)` 提供强类型扩展属性（例如 `amountYuan`）。

`/api/chat` 的响应同理，三个模型侧字段必须可缺省：

```kotlin
@Serializable
data class ChatResponseDto(
    val text: String = "",
    val mode: String = "",
    val data: JsonElement = JsonObject(emptyMap()),
    val actions: List<ActionDto> = emptyList(),
    val trace: List<String> = emptyList(),
    val contextResults: List<JsonElement> = emptyList(),
    val modelUsage: ModelUsageDto? = null,
    val timing: TimingDto? = null,
    val clarification: JsonElement? = null,          // null | {question,...} | {required:Boolean}
    val conversationId: String = "",
    val contextVersion: Long = 0,
    val context: ConversationDto? = null,
    // 以下键在 OFFLINE_DEMO 下不存在
    val results: List<ChatEvidenceDto> = emptyList(),
    val answerSource: String? = null,
    val factReferences: List<String> = emptyList(),
    val toolResults: List<ChatToolResultDto> = emptyList(),
    val agentBackend: String? = null
)
```

`pendingAction` 也需要同样的容错（它可能是 `{}`）：

```kotlin
// 先当 JsonElement 收，再判断
val pending: JsonElement = workflow.pendingAction
val action: ActionDto? = if (pending is JsonObject && pending.containsKey("id"))
    ApiJson.decodeFromJsonElement(ActionDto.serializer(), pending) else null
```


### 8.3 各字段的 Kotlin 类型映射

| JSON | Kotlin |
| --- | --- |
| `amount_minor`, `*_minor`, `*Minor` | `Long`（不要用 `Double`；后端 `accept-float-as-int=false`） |
| `version`, `inputVersion`, `contextVersion`, `outputVersion`, `occurrences`, `day_of_month` | `Int`（后端是 `long`/`int`，Int 足够；如需极端安全用 `Long`） |
| `requestElapsedMs`, `reconcileFailures` | `Int`（后端 `long`/`int`；建议 `Long` 更稳） |
| `expires_at`, `validUntil`, `expiresAt`, `observed_at`, `queriedAt`, `recorded_at`, `retryAt`, `lockedUntil`, `observedAt` | `String`（保留原样）或 `kotlinx-datetime.Instant`（`Instant.parse` 可直接解析带 `Z` 与微秒的串） |
| `first_date`/`end_date`/`businessDate`/`booked_on`/`renewal_date` | `String`（`LocalDate.parse`）；**注意 schedule 的 `first_date` 在 action 里是字符串、在 ScheduleVO 里是 LocalDate，线上格式相同** |
| `month` | `String`（`YYYY-MM`）；`YearMonth.parse` |
| 枚举（`kind`/`status`/`riskLevel`/`state`/`intent`/`type`/`displayState`） | 建议 `String` + 常量对象；若用 `@Serializable enum`，**必须**加未知值兜底（见下） |
| `slots` | `JsonObject` 或 `Map<String, JsonElement>` |
| `proposal` | `JsonElement?` |
| `data`（chat） | `JsonElement`（类型随工具变化） |
| `clarification`（chat） | 自定义：`JsonElement?` → 先看 `has("question")` / `has("required")` |

### 8.4 枚举兜底

```kotlin
@Serializable
enum class ActionStatus {
    AWAITING_AUTH, AUTHORIZED, VERIFIED_SUCCESS, VERIFIED_FAILED, CANCELLED, PENDING, UNKNOWN,
    @SerialName("__UNKNOWN__") UNKNOWN_VALUE;

    companion object {
        fun safe(raw: String): ActionStatus =
            entries.firstOrNull { it.name == raw } ?: UNKNOWN_VALUE
    }
}
```

注意：
- **`ActionStatus` 不能复用到 workflow**：`PARTIAL_SUCCESS`、`CANCEL_REQUESTED`、`PLANNING` 不在 `ActionStatus` 里（源码里是独立字符串）。
- 若用 `@Serializable enum` 且服务端将来加值，默认会抛 `SerializationException`；**保险做法是全用 `String` 常量，或注册 `SerializersModule` 做 fallback**。

### 8.5 ID 的形态

| 字段 | 形态 | 备注 |
| --- | --- | --- |
| `action.id` / `actions[].id` | UUID 字符串（带 `-`） | 服务端 `UUID.randomUUID().toString()` |
| `workflow.id` / `workflowId` | UUID 字符串 | **路径参数必须是合法 UUID**，否则 400 |
| `conversationId` | UUID 字符串 | 服务端生成；客户端请求里要匹配 `[A-Za-z0-9_-]{1,80}` |
| `requestId` | 客户端生成，UUID 即可 | 必须匹配 `[A-Za-z0-9_-]{1,80}`；**幂等键** |
| `security.decisionId` | UUID 字符串或 `null` | 可空！ |
| `payload_hash` | 64 位小写 hex | 只回传，不解析 |
| `challengeId` / `verificationProofId` | UUID 字符串 | 演示控制台只接受 `[A-Za-z0-9-]{1,80}` |
| `receipt_id` | UUID 字符串（SCHEDULE 时 = schedule id） | 可空 |
| `mandate_id` / `recipient_id` / `source_id` / `account.id` | 短 slug（如 `music`、`zhang-a`、`checking`） | **不是** UUID |
| `tools` 里的 `evidenceId` | 字符串（HTTP 模式通常不出现） | 可忽略 |

### 8.6 客户端骨架建议

```kotlin
class ApiClient(private val client: OkHttpClient) {
    private var csrf: String = ""
    private val jar = MemoryCookieJar()   // 实现 CookieJar，进程内单例

    suspend fun bootstrap() {             // 启动/会话失效后调用
        val s = get<SessionDto>("/api/session")
        csrf = s.csrf
    }

    private fun request(path: String, method: String, body: String?): Request =
        Request.Builder()
            .url(BASE + path)
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .apply { if (method != "GET") header("X-CSRF-Token", csrf) }
            .build()

    /** 统一错误解析：先试 RiskErrorVO，再试 ErrorVO，最后兜底 Spring 默认体 */
    suspend fun <T> call(path: String, method: String = "GET", body: String? = null, d: DeserializationStrategy<T>): T { ... }
}
```

- **`/api/chat` 的 readTimeout 建议 ≥ 120 秒**（前端用 `AbortController` 110 秒，服务端有 90 秒 agent 预算）。
- 其他接口 15 秒足够。
- 不要开 OkHttp 的 `retryOnConnectionFailure` 对 `submit` 类请求做自动重试（虽然服务端幂等，但会产生无意义的重复观测）。
- **手工状态管理**：把 `conversationId` + `contextVersion` + `pendingRequestId` + `csrf` 存在 `SavedStateHandle`/DataStore 里；进程重启后先用 `GET /api/conversations/{id}` 校验上下文是否还有效（`404/410` 就丢弃）。

### 8.7 UI 映射建议（照抄前端的语义）

- `AWAITING_AUTH` → "请先核对以下信息，再勾选确认。聊天中的确认不会执行操作。"
- `AUTHORIZED` → "授权已记录，尚未提交。请核对最新状态后继续。"
- `VERIFIED_SUCCESS` → 分类型文案：QUERY → "本次代扣查询已完成；查询没有转账或记账"；SCHEDULE → "定时计划已登记，尚未发生转账"；REVOKE → "银行侧代扣已撤销，商户合同状态未知，余额没有因此增加"；TRANSFER → "转账已完成，并核验双边账务分录"。
- `VERIFIED_FAILED` → "本操作未完成，请展开详情查看原因。"
- `PENDING`/`UNKNOWN`/其他 → "请先查询原操作的最新状态，勿重复创建相同操作。"
- 确认按钮的启用条件（前端原样）：`勾选框已勾选 && security.decisionId != null && !security.needsRefresh && (riskLevel != RED || 已是 AUTHORIZED || 已有 verificationProof)`。

---

## 9. 错误码总表与 HTTP 状态映射

### 9.1 `ErrorVO`（`BankException`）→ 状态码规则

`ApiExceptionHandler.bankError` 按**路径**决定状态码：

| 路径 | 规则 |
| --- | --- |
| `/api/workflows`、`/api/workflows/**` | `CONFLICT` → **409**；其他一律 **400** |
| `/api/chat`、`/api/conversations/**` | `CONFLICT`/`CONTEXT_PROCESSING`/`CONTEXT_INTERRUPTED` → **409**；`NOT_FOUND` → **404**；`CONTEXT_EXPIRED` → **410**；其他 → **400** |
| 其他所有 `/api/**` | **一律 400**（包括 `NOT_FOUND`！） |

> 这就是为什么"动作不存在"返回 **400 而不是 404**。

常见 code：

| code | 含义 | 典型 HTTP |
| --- | --- | --- |
| `SESSION_REQUIRED` | 无会话（拦截器直出） | 401 |
| `CSRF_REQUIRED` / `ORIGIN_DENIED` | 缺/错 CSRF、跨源（拦截器直出） | 403 |
| `VALIDATION_ERROR` | 参数/状态不合法 | 400 |
| `NOT_FOUND` | 资源不存在或不属于当前用户 | 400（动作/账户/计划/任务）或 404（对话） |
| `CONFLICT` | 状态冲突、参数已变、版本冲突、账务校验失败 | 400 或 409（见上表） |
| `AUTH_REQUIRED` | 缺少有效授权 | 400（`beginSubmission`） |
| `AUTH_EXPIRED` | 预览/授权过期 | 400 |
| `INSUFFICIENT_FUNDS` / `LIMIT_EXCEEDED` / `ALREADY_REVOKED` | 银行业务拒绝 | 400（submit 时转为 `VERIFIED_FAILED`） |
| `QUERY_CONFIRMATION_REQUIRED` | `GET /api/mandates` 固定抛 | 409 |
| `SIMULATION_DISABLED` | 未启用模拟/故障注入 | 400 |
| `CONTEXT_PROCESSING` / `CONTEXT_INTERRUPTED` | 原请求仍在处理 / 未完成 | 409 |
| `CONTEXT_EXPIRED` | 对话过期 | 410 |
| `MODEL_NOT_CONFIGURED` / `MODEL_UNAVAILABLE` / `MODEL_API_ERROR` / `MODEL_RESPONSE_INVALID` / `MODEL_REQUEST_BUDGET_EXCEEDED` / `MODEL_TURN_BUDGET_EXCEEDED` / `MODEL_INTERRUPTED` | 模型侧问题 | 400 |
| `ANSWER_NOT_GROUNDED` / `ANSWER_REVIEW_CONTRACT_INVALID` | 回答未通过事实核验 | 400 |
| `AGENT_BUDGET_EXCEEDED` / `AGENT_RECONCILING` / `AGENT_REVIEW_REQUIRED` | 智能体预算/对账/需人工核对 | 400（`AGENT_RECONCILING` 在 `/api/chat` 下是 400，非 409） |
| `TOOL_QUOTA_EXCEEDED` / `PERMISSION_DENIED` / `PREVIEW_LIMIT` / `PREVIEW_STALE` / `INVALID_TASK_PLAN` | 网关/工具限制 | 400 |
| `RISK_AMOUNT_INVALID` / `RISK_AMOUNT_OVERFLOW` | 风险金额异常 | 400 |
| `ACTION_ALREADY_COMPLETED` / `ACTION_RECONCILING` | 动作已终结/核对中 | 400 |

### 9.2 `RiskErrorVO`（`RiskRejectedException`）→ 状态码规则

| code | HTTP |
| --- | --- |
| `STEP_UP_REQUIRED` | 403 |
| `VERIFICATION_INVALID` | 403 |
| `VERIFICATION_EXPIRED` | 403 |
| `CHALLENGE_RATE_LIMITED` | 429（带 `retryAt`） |
| `SECURITY_LOCKED` | 423（带 `retryAt`） |
| `RISK_RECONCILIATION_REQUIRED` | 423 |
| `VERIFICATION_UNAVAILABLE` | 503 |
| `NOT_FOUND` | 404 |
| 其他（`CONFLICT` / `AUTH_REQUIRED` / `AUTH_EXPIRED` / `RISK_DECISION_STALE` / `VERIFICATION_REPLAYED` / `QUERY_CONFIRMATION_REQUIRED`） | 409 |

---

## 10. 附录：端到端时序（可直接作为集成测试脚本）

### 10.1 转账（黄色，默认 profile 可跑通）

```
1  GET  /api/session                                   → csrf, Set-Cookie
2  POST /api/transfers/preview {sourceId,recipientId,amountMinor}
                                                       → ActionVO(AWAITING_AUTH, payload_hash, security.decisionId)
3  POST /api/actions/{id}/authorize {payloadHash,decisionId,userConfirmed:true}
                                                       → ActionVO(AUTHORIZED)
4  POST /api/actions/{id}/submit                       → ExecutionReceipt(VERIFIED_SUCCESS, receipt_id)
5  GET  /api/actions/{id}                              → ExecutionReceipt(observed_at)
6  GET  /api/accounts                                  → 余额已变化，且 GET /api/statement 出现两条"转账"交易
```

### 10.2 转账（红色，需 `risk-demo`）

```
1  GET  /api/session                                   → mode, faultsEnabled=true
2  POST /api/transfers/preview {amountMinor:150000}     → security.riskLevel="RED"
3  POST /api/actions/{id}/risk/preview                  → decisionId（权威）
4  POST /api/actions/{id}/verification-challenges {payloadHash,decisionId}  → challengeId
5  （外部验证台取码）
6  POST /api/actions/{id}/verification-challenges/{cid}/verify {code}       → verificationProofId
7  POST /api/actions/{id}/authorize {payloadHash,decisionId,userConfirmed:true,verificationProofId}
8  POST /api/actions/{id}/submit
```

### 10.3 查代扣

```
1  POST /api/query-intents {operation:"QUERY_MANDATES"}  → ActionVO(QUERY_MANDATES)
2  POST /api/actions/{id}/authorize {...}                → AUTHORIZED
3  POST /api/actions/{id}/submit                          → QueryExecutionReceipt(data=[MandateVO])
```

### 10.4 联合任务

```
1  POST /api/workflows {…}                             → WorkflowVO(state=AWAITING_AUTH, steps[0]=revoke:music)
2  对 steps[0].actionId 走 10.1 的 3~4 步               → 步骤 VERIFIED_SUCCESS
3  POST /api/workflows/{id}/resume {version}            → 下一根步骤（revoke:video）或 schedule 步骤
4  GET  /api/workflows/{id}/plan                        → TaskPlanVO（节点状态）
5  POST /api/workflows/{id}/revise {version, request}   → 新版本（旧待确认项失效）
6  POST /api/workflows/{id}/cancel {version}            → CANCELLED / CANCEL_REQUESTED
```

### 10.5 对话 + 确认卡

```
1  POST /api/conversations                              → conversationId, version=1
2  POST /api/chat {text,modelRequestLimit:5,requestId,conversationId,expectedContextVersion:1}
                                                        → ChatResponse{text,data,actions,results,trace,contextVersion:2}
3  （若 actions[0].status=="AWAITING_AUTH"）→ 走 10.1 的 3~4 步
4  想清空草案：POST /api/conversations/{id}/context/reset {expectedContextVersion:2}
```

---

## 11. 附录：易错点清单（Checklist）

- [ ] 请求体全 `camelCase`，响应体银行侧全 `snake_case`；**`payload_hash`（响应）↔ `payloadHash`（请求）** 是最高频错误。
- [ ] 每个非 GET 请求都带 `X-CSRF-Token`；`GET /api/session` 是唯一不需要会话的端点。
- [ ] `GET /api/mandates` 永远 409，代扣必须走 `query-intents` + 授权 + submit。
- [ ] 动作不存在／不属于你 → **400**，不是 404；对话不存在才是 404。
- [ ] `authorize` 必须同时给 `payloadHash` + `decisionId` + `userConfirmed:true`；RED 再加 `verificationProofId`。
- [ ] **不要**发 `demoVerificationAccepted`（已从 DTO 删除；`agent-gateway` profile 下会 400）。
- [ ] 默认 profile 无法完成 RED 授权（`503 VERIFICATION_UNAVAILABLE`），必须 `risk-demo`。
- [ ] `submit` 之后再 `submit` 是安全的（转查单），但 `PENDING`/`UNKNOWN` 时**只能** `GET /api/actions/{id}`。
- [ ] `pendingAction` 可能是 `{}`；`clarification` 可能是 `null` / `ClarificationVO` / `{"required":…}`。
- [ ] `OFFLINE_DEMO` 模式的 `/api/chat` **没有** `results` / `answerSource` / `factReferences` 三个键。
- [ ] `QueryExecutionReceipt` 的 `queriedAt` 是 camelCase；`data`/`queriedAt` 在重复提交时为 `null`。
- [ ] `WorkflowVO`/`WorkflowStepVO`/`TaskPlanVO` 全是 camelCase；`estimatedSavingMinor`、`savingMinor`、`inputVersion`、`displayState`。
- [ ] 金额一律 `Long` 分；不要发小数（`accept-float-as-int=false` 直接 400）。
- [ ] `workflow` 路径的 `id` 必须是合法 UUID（否则 400）。
- [ ] `run-due` / `faults` 只在 `risk-demo` 下可用（否则 400 `SIMULATION_DISABLED`）。
- [ ] `/api/chat` 的 `requestId` 是幂等键：**重试必须用同一个 `requestId`**，新问题必须换新 id。
- [ ] `expectedContextVersion` 传错会 409；成功后要用响应里的 `contextVersion` 更新本地。
- [ ] `CookieJar` 一定要保存 `JSESSIONID`；401 时重建会话再重放，而不是刷新页面。
- [ ] 时间戳按 UTC ISO-8601 解析（带 `Z` 与可选微秒），业务日按 `Asia/Shanghai` 展示。

---

## 12. 附：源码位置索引（便于复核）

| 主题 | 文件 |
| --- | --- |
| 会话/CSRF/Origin | `src/main/java/cn/safebank/SessionGuard.java`、`controller/SessionController.java`、`vo/SessionVO.java` |
| 错误映射 | `common/ApiExceptionHandler.java`、`common/RiskRejectedException.java`、`vo/ErrorVO.java`、`vo/RiskErrorVO.java` |
| 动作与扁平化 | `vo/ActionVO.java`、`vo/*ParametersVO.java`、`vo/ExecutionReceiptVO.java`、`vo/QueryExecutionReceiptVO.java`、`converter/EmptyPendingActionSerializer.java` |
| 授权门禁 | `service/AuthorizationGateService.java`、`common/ActionPayloadCodec.java`、`common/SubmissionPermit.java` |
| 风险策略 | `service/RiskPolicyService.java`、`service/RiskDecisionService.java`、`common/RiskTypes.java` |
| 验证 | `service/VerificationService.java`、`config/VerificationConfiguration.java`、`verification/DemoVerificationConsole.java` |
| 执行/查单 | `service/ActionExecutionService.java`、`service/LocalBankExecutionService.java`、`bank/LocalBankAdapter.java`、`bank/FaultInjectingBankAdapter.java` |
| 联合任务 | `service/WorkflowService.java`、`service/WorkflowPlanViewService.java`、`vo/WorkflowVO.java`、`vo/TaskPlanVO.java` |
| 对话 | `service/ContextService.java`、`agent/ContextAgentCoordinator.java`、`AgentService.java`、`vo/ConversationVO.java`、`common/ContextTypes.java`、`vo/ClarificationVO.java` |
| 聊天响应组装 | `AgentService.chat/finish`、`model/ModelUsageMeter.java`、`BankReplyComposer.java` |
| 网关差异 | `agent/GatewayCoordinator.java`、`service/GatewayResultService.java`、`controller/AgentGatewayStatusController.java` |
| 前端调用方式 | `static/app.js`、`static/context-ui.js` |
| 契约回归测试（最权威） | `src/test/java/cn/safebank/LayeredApiContractTest.java`、`RiskApiTest.java` |
| Python 侧 | `agent-service/safebank_agent/contracts.py`（请求投影/哈希）、`server.py`（内部 HTTP 协议）、`schema.py`（数据库 schema 断言，与 HTTP 契约无关） |
