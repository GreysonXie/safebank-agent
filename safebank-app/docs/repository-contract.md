# 数据层使用说明（给页面负责人）

> 负责人：数据层。唯一入口：`AppContainer.repository: SafeBankRepository`
> （实现：`data/AndroidBankRepository.kt` + `data/network/**`）。
> 本文是**页面怎么用**的简明说明；后端字段语义以 `docs/api-contract.md` 为准。

## 1. 基本用法

```kotlin
val repo = container.repository            // AppContainer.repository

// 所有方法都是 suspend + Result<T>；结果要么 success，要么 failure(ApiException)
scope.launch {
    repo.accounts()
        .onSuccess { list -> /* List<AccountVo> */ }
        .onFailure { e ->
            val api = e as ApiException     // 数据层保证 failure 里一定是 ApiException
            banner = "${api.code}：${api.message}"   // message 已是中文、可直接展示
        }
}
```

要点：

- **失败时一定是 `cn.safebank.app.data.network.ApiException`**（网络错误也包装），
  不用再 `catch (IOException)`；页面不 `try/catch` 也不会崩（`Result` 已收敛）。
- `ApiException` 字段：`code`（业务码 / `HTTP_400` / `NETWORK_ERROR` / `PARSE_ERROR`）、
  `message`（中文可展示）、`httpStatus`（网络错误为 0，本地解析错误为 -1）、
  `body`（原始响应，调试用）、`actionId`、`security`（`RiskErrorVO` 才有，可用来重绘确认卡）、
  `retryAt`（限流/锁定时的可重试时间）。
- 便捷判断：`isNetworkError` / `isSessionError` / `isConflict` / `isAuthExpired` /
  `isRiskStale` / `isVerificationUnavailable`；常量在 `ApiException.Companion`（如 `ApiException.VALIDATION_ERROR`）。
- 金额全部是 **整数分 Long**，显示用 `Formatters.money(minor)` / `Formatters.moneySigned(minor)`；
  用户输入的"元"用 `Formatters.parseAmountToMinor(text)`（超过两位小数会返回 null，要提示）。

## 2. 方法列表（对应 REST 端点）

| 分组 | 方法 | 端点 | 返回 |
| --- | --- | --- | --- |
| 会话 | `ensureSession(force=false)` | GET /api/session | `SessionVo` |
| 会话 | `currentSession()` | —（本地缓存） | `SessionVo?` |
| 只读 | `accounts()` | GET /api/accounts | `List<AccountVo>` |
| 只读 | `recipients(query)` | GET /api/recipients?query= | `List<RecipientVo>`（精确匹配；重名必须让用户选） |
| 只读 | `statement(month)` | GET /api/statement?month= | `StatementVo`（month 空串用后端默认） |
| 只读 | `recurring()` | GET /api/recurring | `List<RecurringPaymentVo>` |
| 只读 | `schedules()` | GET /api/schedules | `List<ScheduleVo>` |
| 只读 | `audit()` | GET /api/audit | `List<AuditEventVo>`（最新 50 条，已倒序） |
| 只读 | `riskSummary()` | GET /api/risk/summary | `RiskSummaryVo` |
| 预览 | `previewTransfer(sourceId, recipientId, amountMinor)` | POST /api/transfers/preview | `ActionVo` |
| 预览 | `previewMandateRevoke(mandateId)` | POST /api/mandates/preview | `ActionVo` |
| 预览 | `previewSchedule(sourceId, recipientId, amountMinor, firstDate, endDate, dayOfMonth)` | POST /api/schedules/preview | `ActionVo` |
| 预览 | `createQueryIntent(conversationId?, expectedContextVersion?)` | POST /api/query-intents | `ActionVo`（QUERY_MANDATES） |
| 动作 | `action(id)` | GET /api/actions/{id} | `ActionExecutionResultVo`（查单） |
| 动作 | `actionRisk(id)` | GET /api/actions/{id}/risk | `RiskDecisionVo`（只读，可能 `needsRefresh=true`） |
| 动作 | `refreshRiskPreview(id)` | POST /api/actions/{id}/risk/preview | `RiskDecisionVo`（权威 decisionId，`needsRefresh` 恒 false） |
| 动作 | `createChallenge(id, payloadHash, decisionId)` | POST …/verification-challenges | `ChallengeVo`（RED 才需要） |
| 动作 | `verifyChallenge(id, challengeId, code)` | POST …/{challengeId}/verify | `VerificationProofVo` |
| 动作 | `authorize(id, payloadHash, decisionId?, verificationProofId?, userConfirmed=true)` | POST …/authorize | `ActionVo`（status=AUTHORIZED） |
| 动作 | `submit(id)` | POST …/submit | `ActionExecutionResultVo`（唯一会动钱的入口） |
| 动作 | `cancel(id)` | POST …/cancel | `ActionVo`（仅 AWAITING_AUTH/AUTHORIZED 可取消） |
| 任务 | `workflows()` / `workflow(id)` / `workflowPlan(id)` | GET /api/workflows… | `List<WorkflowVo>` / `WorkflowVo` / `TaskPlanVo` |
| 任务 | `createWorkflow(request)` | POST /api/workflows | `WorkflowVo` |
| 任务 | `resumeWorkflow(id, version)` | POST …/resume | `WorkflowVo`（查单并推进） |
| 任务 | `reviseWorkflow(id, version, request)` | POST …/revise | `WorkflowVo`（旧待确认项失效） |
| 任务 | `cancelWorkflow(id, version)` | POST …/cancel | `WorkflowVo` |
| 对话 | `createConversation()` / `conversation(id)` | POST /api/conversations · GET /api/conversations/{id} | `ConversationVo` |
| 对话 | `resetConversation(id, expectedContextVersion)` | POST …/context/reset | `ConversationVo` |
| 对话 | `chat(text, conversationId?, expectedContextVersion?, requestId?, modelRequestLimit?)` | POST /api/chat | `ChatResponse` |
| 模拟 | `injectFault(actionId, submitMode, lookupMode)` | POST /api/simulation/faults | `Unit` |
| 模拟 | `runDue(date)` | POST /api/simulation/run-due | `Unit`（**注意：接口返回 Unit，看不到生成的 ActionVO 列表，见 §6**） |

所有写操作（预览后的 authorize/submit/cancel、workflow 写操作）**必须串行走完整流程**，
不要并发点两下——数据层不做去重（后端每次预览都会新建动作）。

## 3. 会话 / CSRF 生命周期（数据层已自动处理，页面不用管）

- 首次调用任意方法前，数据层自动 `GET /api/session`，把 `JSESSIONID` 存在**内存 CookieJar**
  （不落盘），把 `csrf` 存在内存；后续所有非 GET 请求自动带 `X-CSRF-Token`，GET 不带。
- 后端会话 30 分钟不活动会失效。任何请求收到 `401 SESSION_REQUIRED`（或 `403 CSRF_REQUIRED`）时，
  数据层会：清空 cookie+csrf → 重新 `GET /api/session` → **把原请求重放一次**。
  这两个错误是 SessionGuard 在业务逻辑之前拦下的，重放不会重复扣款。
- 页面通常**看不到** `SESSION_REQUIRED`。如果真的收到了（连续两次失败），直接提示用户重试/检查后端。
- `ensureSession(force = true)`：强制重建会话（设置页"连通性自检"、改完服务器地址后用）。
  普通页面进入时**不需要**主动调 `ensureSession()`，直接拉数据即可。
- `baseUrl` 每次请求都从 `AppSettings.baseUrl` 现读：设置页改地址后，下一次请求自动用新地址；
  若新地址上旧会话无效，自动触发上面的"重建+重放"。
- `currentSession()` 返回缓存会话（含 `user`/`mode`/`model`/`faultsEnabled`），可用于顶部模式徽章。

## 4. 动作生命周期（页面必须自己按契约串起来）

```
previewXxx() ──► ActionVo(status=AWAITING_AUTH, payload_hash, security{decisionId,needsRefresh,riskLevel})
   │
   ├─ security.needsRefresh == true 或 decisionId == null
   │      → refreshRiskPreview(id) 取权威 decisionId
   ├─ riskLevel == "RED"
   │      → createChallenge(id, payloadHash, decisionId) → 用户输入 6 位码
   │      → verifyChallenge(id, challengeId, code) → verificationProofId
   │        （默认 profile 会 503 VERIFICATION_UNAVAILABLE，提示用 risk-demo profile）
   ▼
authorize(id, payloadHash, decisionId, verificationProofId?, userConfirmed = 用户主动勾选)
   ▼  status=AUTHORIZED（授权≠执行，可以取消）
submit(id) ──► ActionExecutionResultVo(status = VERIFIED_SUCCESS / VERIFIED_FAILED / PENDING / UNKNOWN)
   ▼
PENDING / UNKNOWN：只能用 action(id) 继续查单，禁止新建同参数动作（防重复扣款）
```

- `payload_hash`（响应 snake_case）→ `payloadHash`（请求 camelCase），数据层已按模型处理，页面只需原样传。
- **不要**发 `demoVerificationAccepted`（已废弃；agent-gateway profile 下会 400）。
- `submit` 之后再 submit 是安全的（后端转查单），但页面不要自动重试：失败按 `ApiException.code` 提示。

## 5. 错误码处理建议

| `ApiException.code` / httpStatus | 页面建议 |
| --- | --- |
| `NETWORK_ERROR`（httpStatus=0） | 显示"无法连接服务器"，检查设置页地址/后端是否启动；提供"重试" |
| `HTTP_*`（Spring 默认错误体） | 直接展示 `message`（已拼中文，含 path）；多为参数/路径问题 |
| `SESSION_REQUIRED` / `CSRF_REQUIRED` | 正常不会出现（数据层已自动重建+重放）；真出现就提示重试 |
| `VALIDATION_ERROR` | 展示 `message`（后端校验文案很具体），保留用户输入让用户改 |
| `INSUFFICIENT_FUNDS` / `LIMIT_EXCEEDED` | 展示 `message`；限额提示"单笔 ¥10,000" |
| `CONFLICT`（httpStatus 409 或 400） | 状态/版本冲突：刷新后重读同一对象（workflow 要 `workflow(id)` 重拉），**不要重发** |
| `AUTH_EXPIRED` | 预览/授权过期 → 重新 preview，重新走授权 |
| `RISK_DECISION_STALE` | 判定变了 → 重新 `refreshRiskPreview(id)` 再 authorize |
| `STEP_UP_REQUIRED` | 需要验证码：走 createChallenge/verifyChallenge 流程 |
| `VERIFICATION_INVALID` / `VERIFICATION_EXPIRED` | 验证码错/过期，提示重新获取（`retryAt` 有值则展示倒计时） |
| `CHALLENGE_RATE_LIMITED` / `SECURITY_LOCKED` | 用 `retryAt`（`Formatters.shortDateTime`）提示稍后再试 |
| `VERIFICATION_UNAVAILABLE` | 明确提示"当前后端未启用演示验证，请用 risk-demo profile 启动后端" |
| `SIMULATION_DISABLED` | 提示"仅 risk-demo profile 支持模拟/故障注入" |
| `QUERY_CONFIRMATION_REQUIRED` | 不要直接调 GET /api/mandates（恒 409）；改走 createQueryIntent 流程 |
| `CONTEXT_PROCESSING` / `CONTEXT_INTERRUPTED` | 提示核对原请求，**不要自动重放**；原输入保留在输入框 |
| `CONTEXT_EXPIRED` / httpStatus 410 | 对话上下文过期 → `createConversation()` 重新开始 |
| `NOT_FOUND` / httpStatus 404 | 资源不存在（动作不存在是 **400 NOT_FOUND**，见契约 §9） |
| `riskError` 里的 `security` | 若有值，用 `e.security` 重绘确认卡（decisionId/riskLevel 等） |

`ApiException.httpStatus` 可用于区分 409（版本冲突）与 400（参数问题）；
`/api/chat` 的 `CONTEXT_PROCESSING` 只按 `code` 判断，别依赖状态码。

## 6. 模型 / 数据层的已知约定与缺口

- `ChatResponse`：`data`/`clarification`/`modelUsage`/`timing`/`contextResults` 都是 `JsonElement`；
  页面自己解析（`data` 可能是 StatementVO / AccountVO[] / MandateVO[] / ActionVO / `{"workflow":…}`）。
  **数据层已把 JSON `null` 归一化成 Kotlin `null`**（不会给你 `JsonNull.INSTANCE`），
  所以 `clarification == null`、`proposal == null` 可以放心判断。
  `OFFLINE_DEMO` 下 `results`/`answerSource`/`factReferences` 为 **null**（键不存在），
  判断"有没有待确认动作"要用 `response.pendingActions`（= `actions.filter { it.status == "AWAITING_AUTH" }`），
  不要用 `answerSource`。
- `WorkflowVo.pendingAction` 在后端可能是空对象 `{}`：用 **`workflow.pendingActionOrNull`**，
  不要直接判 `pendingAction != null`。
- `ActionVo` 是"扁平的 4 种动作并集"：按 `kind` 取对应字段（全部可空）。
- 金额、日期、状态文案：优先用 `data/util/Formatters.kt` 的现成函数（`money`、`actionStatusLabel`、
  `workflowStateLabel`、`planNodeStateLabel`、`actionKindLabel`、`billingIntervalLabel`、
  `remainingSeconds`、`shortDateTime` 等）。
- `runDue(date)` / `injectFault(...)` 的仓库签名是 `Result<Unit>`：
  `run-due` 后端实际返回 `ActionVO[]`，当前接口**拿不到**列表；如需展示模拟结果，请报 Lead 改接口
  （数据层不擅自改公共接口）。
- 读超时：普通接口 20s，`/api/chat` 130s（模型 + 授权等待）。页面不要自己再套超时。
- 请求体一律 camelCase、响应体银行侧 snake_case，模型层已用 `@SerializedName` 处理，页面不要手动拼 JSON。
- 数据层**不做**失败自动重试（除会话重建这一次）；`retryOnConnectionFailure=false`。
  重试策略由页面决定（尤其是 authorize/submit 类，重试=查单）。
