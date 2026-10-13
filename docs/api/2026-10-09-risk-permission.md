# 三级权限与验证接口契约

2026-10-09，已在v0.5合成候选实现。真实外部MFA未接入。沿用/api前缀、可信会话、X-CSRF-Token、原归属/错误契约。资金action_id和payload_hash不改变。规则见[设计](../superpowers/specs/2026-10-09-risk-permission-design.md)。所有例子为合成占位数据，非有效凭据。

## 操作矩阵

| 操作 | 等级/确认 | 额度占用 |
|---|---|---|
| 余额、交易记录、账单分析 | GREEN自动 | 无 |
| 新发起代扣列表查询 | QUERY_MANDATES，YELLOW一次明确确认，按赛题保守条目 | 无 |
| 撤销指定银行代扣 | YELLOW明确确认 | 无 |
| 转账预计日累计≤100000分 | YELLOW明确确认 | 授权时RESERVED |
| 转账预计日累计>100000分 | RED确认+有效step-up | 验证并授权后RESERVED |
| 定时计划登记 | YELLOW明确确认 | 登记不占额，期次执行重新判断 |

QUERY_MANDATES新增类型化QueryParametersVO，只绑定查询操作和归属，不包含转账金额。通过查询意图生成action、统一authorize获得单次查询授权，submit走受控只读服务分支，不调用资金BankAdapter、不记账；返回QueryExecutionReceiptVO（action、data、queriedAt）。重复submit只返回原查询已执行回执，不声称取得新数据；重新查询用新意图。旧GET /mandates直查在新版本返回QUERY_CONFIRMATION_REQUIRED，网页初始化不再偷偷发起该查询，用户确认后读取。查询结果不是资金授权，GET /actions/{id}也不暴露尚未确认的代扣列表。

此特殊查询规则是对材料“订阅查询”的保守实现，不声称组委会已认可解释；若官方确认为纯查询GREEN，可以配置恢复直接查询，不改变资金确认规则。

## 接口与类型

| 方法/路径 | DTO | 返回VO与效果 |
|---|---|---|
| POST /transfers/preview | 现TransferPreviewRequestDTO | ActionVO追加security；保存预览，不占额 |
| POST /mandates/preview | 现MandatePreviewRequestDTO | 同上，YELLOW，不执行撤销 |
| POST /query-intents | QueryIntentRequestDTO(operation=QUERY_MANDATES) | 只读意图确认卡，不执行查询 |
| GET /actions/{id}/risk | 无 | RiskDecisionVO：当前decision/nextStep、建议累计，不能靠GET授权 |
| POST /actions/{id}/risk/preview | 无业务参数 | 刷新原动作风险；未提交过期动作沿用ID重新确认，不授权、不执行 |
| POST /actions/{id}/verification-challenges | ChallengeRequestDTO(payloadHash,decisionId) | ChallengeVO，无口令，无授权；先提交投递状态再在事务外发送 |
| POST /actions/{id}/verification-challenges/{challengeId}/verify | ChallengeVerifyRequestDTO(code) | VerificationProofVO，仅证明校验通过，不执行 |
| POST /actions/{id}/authorize | AuthorizationRequestDTO(payloadHash,decisionId,userConfirmed,verificationProofId?) | 授权ActionVO，事务内重新判定并占额；RED必须匹配proof |
| POST /actions/{id}/submit | 无业务参数 | 原ExecutionReceiptVO或QueryExecutionReceiptVO；同动作幂等 |
| POST /actions/{id}/cancel | 无业务参数 | 未提交动作取消；原UNKNOWN不可普通取消 |
| GET /risk/summary | 无 | 当前可信业务日、已成功/未提交占额/未核实占額及锁定到期，不返回其他用户数据 |

GET /actions/{id}/risk是无副作用建议查询；首次/升级持久化decision由POST预览/挑战/授权处理，GET返回当前decisionId与动态建议、needsRefresh，不能偷偷创建/升级授权。旧authorize中的demoVerificationAccepted不再有提升验证等级的含义；过渡可作为userConfirmed别名，仅在无歧义且有当前decisionId时接受，RED不接受“勾选即验证”。旧DTO未提供decisionId时返回确认需刷新，而非默认放行。

POST /actions/{id}/submit由银行动作和只读查询类型区分返回结构，既有资金回执字段保持。用户、日期、等级、累计、policyVersion、status不能由DTO提交。模型工具没有authorize、verify、submit、领取演示OTP等名称，不把用户聊天中的“验证码123456”当验证证据。

## 风险卡与请求例子

```json
{
  "id": "demo-transfer-2",
  "payload_hash": "demo-existing-payload-hash",
  "status": "AWAITING_AUTH",
  "amount_minor": 60000,
  "security": {
    "decisionId": "demo-decision-2",
    "policyVersion": "bank-risk-v1",
    "riskLevel": "RED",
    "requiredAssurance": "STEP_UP",
    "reasonCode": "DAILY_OUTGOING_THRESHOLD_EXCEEDED",
    "businessDate": "2026-10-09",
    "effectiveMinor": 60000,
    "projectedMinor": 120000,
    "nextStep": "VERIFY_THEN_CONFIRM",
    "verificationMode": "LOCAL_DEMO_ONLY"
  }
}
```

```json
{
  "payloadHash": "demo-existing-payload-hash",
  "decisionId": "demo-decision-2",
  "userConfirmed": true,
  "verificationProofId": "demo-challenge-2"
}
```

proofId为VERIFIED challenge的不透明标识，不是客户端能随意声明的已验证token。服务器查同用户/动作/hash/decision/日期/有效期后消费；换对象、hash或策略必须重新确认/验证。实际哈希为现有SHA-256，本示例字符串仅便于阅读。

security还应返回validUntil、是否已有有效授权及明确展示原因；用户卡片显示“需确认/需补充验证”和原因，不展示内部模型思维链。生成预览的等级可变化，提交前升级时刷新同一action卡，不能自动按新action重做。

## 错误与幂等

| HTTP / code | 含义及页面动作 |
|---|---|
| 401 SESSION_REQUIRED / 403 CSRF_REQUIRED | 沿用会话与CSRF边界，不创建或执行动作 |
| 404 NOT_FOUND | 不属于用户或不存在，不泄露他人对象 |
| 409 RISK_DECISION_STALE | 旧decision/策略/日期，刷新同一动作卡，不自动重发授权 |
| 403 STEP_UP_REQUIRED | 本次需更高验证；携带当前security，不将action设失败 |
| 403 VERIFICATION_INVALID / VERIFICATION_EXPIRED | 验证错误/过期，保持资金未提交；计数提交后返回 |
| 409 VERIFICATION_REPLAYED / AUTH_EXPIRED | 证明已消费或授权过期；查原动作，不盲目换ID |
| 429 CHALLENGE_RATE_LIMITED | 发送太频繁，附retryAt |
| 423 SECURITY_LOCKED | 只读/查单仍可用，附lockedUntil，不自动绕过 |
| 423 RISK_RECONCILIATION_REQUIRED | 旧受理日期/金额未核对，暂停新转账；不会按验证码冷却自动解除 |
| 503 VERIFICATION_UNAVAILABLE | 未接验证服务或投递失败，RED不放行 |
| 409 QUERY_CONFIRMATION_REQUIRED | 代扣新查询需明确确认，不表示资金失败 |

错误顶层保留code/message，可附actionId/security/retryAt，不包含验证码摘要、pepper、provider原始响应或验证台令牌。供应商失败和业务拒绝不伪造已执行回执。

同一授权内容重复请求在有效期内返回原ACTIVE授权，不消费两次proof、不增加占额；同一提交有SUBMISSION_STARTED观察时只查原动作。未提交AUTH_EXPIRED可以重新确认同一action，风险decision/proof必须更新；SUBMITTED/UNKNOWN先核实。

演示验证台是独立loopback操作者通道，需独立本机访问令牌并显式启用demo profile；口令读取/消费不属于上述普通用户API、模型工具或前端业务JS。未启用则不能用固定码继续；真实生产验证由VerificationProvider实现并返回MFA_VERIFIED。
