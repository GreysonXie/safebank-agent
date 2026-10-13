# SafeBank 三级权限与验证 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在现有银行demo中实现服务器三级权限、日累计占用、绑定动作的验证挑战与统一授权门禁，覆盖人工、模型预览、联合任务和定时期次。

**Architecture:** 保留Java单体和Controller→Service→MyBatis。权限/挑战/占用作为下层服务，动作和银行执行在短事务边界调用门禁；模型与验证供应商调用期间无数据库事务。新版本先在独立内存实例验证，原8096候选保持至最终验收。

**Tech Stack:** Java21、Spring Boot3.5.6、MyBatis Starter3.0.5、H2 2.3.232、现有原生网页及Playwright；不新增Agent框架、Redis或收费SMS。

**Spec:** [用户已确认的权限设计](../specs/2026-10-09-risk-permission-design.md)、[数据库](../../database/2026-10-09-risk-permission-design.md)、[DDL](../../database/2026-10-09-risk-permission.sql)、[API](../../api/2026-10-09-risk-permission.md)。用户已确认并执行；完成情况与裁定见三级权限实现验收记录。

## Global Constraints

- 转账日累计阈值为100,000分（1000元），恰好1000元仍YELLOW，大于1000元RED。
- 普通授权最长5分钟；验证码/外部挑战最长2分钟；均不跨业务日午夜。
- 验证码3次失败锁该挑战；可信用户10分钟内累计5次错误锁写操作10分钟；创建挑战间隔60秒。
- 原13表保持业务字段，新增7表及actions/schedules归属联合唯一约束；payload_hash算法与action_id不变。
- UNKNOWN/PENDING对应SUBMITTED，跨日保守占用，不按TTL释放；预览不占额，授权才RESERVED。
- 所有新增业务数据访问使用Mapper/XML；DDL由固定SQL资源及Spring初始化工具执行，不在Service写SQL，不使用生产JdbcTemplate/java.sql/SqlSession。
- LOCAL_DEMO_ONLY不宣传真实MFA；普通API/模型/日志不返回OTP、pepper或操作者令牌。生产模式无可信VerificationProvider时RED拒绝。
- 本阶段DeepSeek请求为0；使用脚本模型与空密钥候选。真实对话质量留后续限额验收。
- 左导航、中业务、右对话，窄屏对话在下方；改确认卡和查询时机，不重做布局。
- 用户级锁优先；workflow/schedule→action→安全记录→账户依次加锁，同级按ID排序。门禁不依赖上层执行服务。
- 新增QUERY_MANDATES是需一次确认的只读意图，不调用资金BankAdapter、不记账、不占转账额度。
- 新版本没有有效grant的旧AUTHORIZED不能执行；旧计划没有scope不能自动运行；旧日期/金额未知需reconciliation_required持续锁转账。

## 执行方式、隔离与命令

建议Native：我在当前会话逐任务实施，每任务做针对性RED→GREEN和提交；最后一次独立整体验证/审查。任务共享门禁、事务和类型接口，逐任务派开发Agent会增加交接成本。若用户明确选择逐任务子Agent，再使用对应执行技能；本计划阶段不派Agent实现。

计划确认后才使用using-git-worktrees建立`.worktrees/v05-risk-permission`、分支`feature/v0.5-risk-permission`，不在冻结demo分支上同时开发。复用现有Maven缓存；以下命令均在该隔离目录执行，变量初始化一次：

```powershell
$env:JAVA_HOME='<LOCAL_PATH> Files\Java\jdk-21'
$env:PATH=$env:JAVA_HOME+'\bin;'+$env:PATH
$env:JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8'
$env:SPRING_PROFILES_ACTIVE='test'
$riskMaven='<LOCAL_PATH>'
$riskRepoCache='<LOCAL_PATH>'
```

每次命令检查真实退出码和报告。两个Maven进程不并行。中间任务不用全套构建；仅修改文档时不重跑Java。test profile仅用于Maven测试，候选启动脚本显式指定risk-demo profile，不沿用test环境；任务8验收通过后按既有受控替换流程交付，不能自动迁移旧文件库或推送仓库。

## Review Focus

1. 午夜前排队、午夜后拿到用户锁：Task2测试以拿锁后的可信日期计算，旧grant跨日无效。
2. 银行已受理后进程中断：Task5测试观察/占用/授权受理状态原子存在，只查原action，不再扣款。
3. 空日期旧受理记录：Task6测试持续核对锁不会随验证码冷却解除，查询仍可用。
4. GET代扣需确认导致初始化失败：Task7浏览器测试账户/账单仍能初始化，查询卡确认后才展示列表。
5. 攻击者反复失败后业务异常回滚：Task3/4测试失败次数与锁定记录已提交，不能靠BankException重置计数。

## 文件与接口地图

下文生产Java简写路径以`src/main/java/cn/safebank/`为根，测试Java简写文件以`src/test/java/cn/safebank/`为根；资源路径显式给出。新增目录按职责创建，不增加另一套Controller/Service/DAO命名体系。

所有新表Entity位于`src/main/java/cn/safebank/entity/`，DAO位于`src/main/java/cn/safebank/dao/`，XML位于`src/main/resources/mapper/`，下表类名组合出对应精确文件名：

| 表 | Entity.java | Mapper.java / Mapper.xml |
|---|---|---|
| risk_user_guards | RiskUserGuardEntity | RiskUserGuardMapper |
| risk_decisions | RiskDecisionEntity | RiskDecisionMapper |
| verification_challenges | VerificationChallengeEntity | VerificationChallengeMapper |
| action_authorizations | ActionAuthorizationEntity | ActionAuthorizationMapper |
| risk_transfer_usage | RiskTransferUsageEntity | RiskTransferUsageMapper |
| schedule_authorizations | ScheduleAuthorizationEntity | ScheduleAuthorizationMapper |
| risk_events | RiskEventEntity | RiskEventMapper |

Entity逐列对应已审SQL，沿用JavaBeans，不引入Lombok。新增`common/RiskTypes.java`定义RiskLevel、AssuranceRequirement、VerificationMethod、AuthorizationAssurance、AuthorizationState、UsageState、ChallengeState、RiskChannel、UsageProvenance；固定枚举映射不得默认成功。新增`common/RiskOutcome.java`为`record RiskOutcome<T>(T value, RiskFailure failure)`，必须且仅有一项非空，提供`accepted()`；`common/RiskFailure.java`携code/message/actionId/security/retryAt。拒绝作为返回值提交审计/计数后再映射错误，不在事务内抛错回滚记录。

新增`vo/RiskDecisionVO.java`含decisionId/policyVersion/riskLevel/requiredAssurance/reasonCode/businessDate/effectiveMinor/projectedMinor/validUntil/nextStep/verificationMode/needsRefresh；新增`vo/RiskSummaryVO.java`含已成功、预留、未核实、锁定和核对标记。`ActionVO.security`为嵌套可空字段，不与@JsonUnwrapped参数冲突，withObservation保留security。`service/RiskUserLockService.java`提供`<T> T withUserLock(String userId, Supplier<T> work)`，取得guard锁后才读取Clock，支持同一事务内调用而不倒序加锁。

### Task 1：新表、类型与Mapper

**Files:** 创建文件地图中的21个Entity/Mapper/XML；创建`src/main/resources/schema-risk.sql`（已审DDL）、`src/main/java/cn/safebank/config/RiskSchemaInitializer.java`、`dao/RiskSchemaMapper.java`、`resources/mapper/RiskSchemaMapper.xml`及上述common/vo基础类型。修改`src/main/resources/application.properties`、`src/test/java/cn/safebank/BankTestFixture.java`，创建`src/test/resources/application-test.properties`、`src/test/java/cn/safebank/RiskPersistenceTest.java`。

**Interfaces:** Mapper提供`selectOwned(userId,id)`、需写路径的`selectOwnedForUpdate(userId,id)`、`insert(entity)`及明确状态条件更新；RiskUserGuardMapper提供`selectForUpdate(userId)`。SchemaMapper只读取H2元数据/完整性，初始化器读取固定资源，完整7表和约束存在时跳过，部分存在拒绝。自动建表仅允许显式fresh-schema模式且jdbc:h2:mem；现有文件库拒绝自动DDL，需独立备份迁移。afterEach/reset仍只重置测试库。

- [x] **1 写失败测试：** `schemaCanRestartWithoutRepeatingMigration`；`incompleteSchemaIsRejected`；`crossUserGrantCannotAttachToAnotherAction`，断言20表/第二次初始化不执行DDL/部分7表不能启动/归属FK拒绝。
- [x] **2 运行RED：** `& $riskMaven "-Dmaven.repo.local=$riskRepoCache" '-Dtest=RiskPersistenceTest' test`，应因缺少接口或约束失败，不能是连接/环境异常。
- [x] **3 实现：** Entity按DDL字段；Mapper显式resultMap和列，条件更新影响行数校验。guard创建仅捕获同一PK唯一冲突再读取，不吞其他SQL错误。初始化在基础schema初始化后、业务使用前完成，不读取密钥或改旧文件库。属性`bank.risk.schema.create-new`默认false；测试与候选显式true且仅允许内存库，测试fixture reset加载schema-risk.sql。新application-test.properties设置该属性、空模型密钥和兜底独立内存URL，通过上述显式test profile加载；缺完整风险schema拒绝业务启动。
- [x] **4 运行GREEN：** 同一命令0失败；再执行已存独立DDL检查，21项保持通过。加入缺少schema时“权限不可用”启动诊断，不能回落旧授权。
- [x] **5 提交：** `feat: add risk storage and typed contracts`；候选尚未上线。

### Task 2：日累计与策略

**Files:** 创建`service/RiskUserLockService.java`、`service/DailyUsageService.java`、`service/RiskPolicyService.java`、`service/RiskDecisionService.java`、`service/RiskEventService.java`、`common/RiskEvaluation.java`、`service/RiskExecutionContext.java`；创建`src/test/java/cn/safebank/RiskPolicyTest.java`、`RiskConcurrencyTest.java`、`MutableRiskClock.java`。

**Interfaces:** `DailyUsageService.effectiveMinor(String userId, LocalDate date, Instant now, String excludedActionId): long`；`expireUnsubmitted(String userId, Instant now): void`（调用前持guard锁）；`RiskPolicyService.evaluate(ActionKind kind,long amountMinor,long effectiveMinor,LocalDate date): RiskEvaluation`，kind/金额由门禁读取已验证action后解码。`RiskDecisionService.assessLocked(String userId,String actionId,RiskChannel channel): RiskDecisionVO`（持用户/action锁）、`view(String userId,String actionId): RiskDecisionVO`（只读）；`RiskEventService.append(RiskEventEntity event): void`。VerificationService与Gate复用下层DecisionService，二者不能反向依赖形成循环。上下文从注入Clock在锁后生成，普通HTTP不得提交日期。

`RiskEvaluation`为record(level,requiredAssurance,amountMinor,effectiveMinor,projectedMinor,reasonCode,businessDate,policyVersion)，类型依次RiskLevel/AssuranceRequirement/long/long/long/String/LocalDate/String；`RiskExecutionContext`为record(userId,requestId,channel,now,businessDate,demoMode)，requestId由服务器生成，不接受模型指定。字段类型依次String/String/RiskChannel/Instant/LocalDate/boolean。

- [x] **1 写失败测试：** `exactDailyBoundary`固定date=2026-10-09，断言`policy.evaluate(ActionKind.TRANSFER,100000,0,date).level()==YELLOW`及100001时RED；`unknownFromPreviousDayStillCounts`；`ownReservationIsNotDoubleCounted`；`overflowFailsClosed`；`midnightQueuedAuthorizationUsesLockedDate`。并发测试用两条DB连接/latch，不靠同一个synchronized单线程调用证明并发安全。
- [x] **2 运行RED：** `& $riskMaven "-Dmaven.repo.local=$riskRepoCache" '-Dtest=RiskPolicyTest,RiskConcurrencyTest' test`，记录缺失判定/错误金额的失败。
- [x] **3 实现：** 本金按用户全账户累计；当日COMMITTED、有效RESERVED及所有日期SUBMITTED按唯一action集合求和，排除当前动作；Math.addExact溢出拒绝。UNKNOWN不自动释放，reconciliation_required不随冷却解锁；同动作risk在有效期只升级。用户锁实现后续任务复用，Task4再补真实授权并发验收。
- [x] **4 运行GREEN：** 同命令0失败，纯查询始终不创建usage。除受控隔离实验上下文，不从文本/前端/模型读日期。
- [x] **5 提交：** `feat: add daily exposure and server risk policy`。

### Task 3：挑战、投递与锁定

**Files:** 创建`service/VerificationService.java`、`verification/VerificationProvider.java`、`verification/DemoOtpProvider.java`、`verification/UnavailableVerificationProvider.java`、`verification/DemoVerificationConsole.java`、`config/VerificationProperties.java`；创建`dto/ChallengeRequestDTO.java`、`dto/ChallengeVerifyRequestDTO.java`、`vo/ChallengeVO.java`、`vo/VerificationProofVO.java`、`src/test/java/cn/safebank/VerificationServiceTest.java`、`DemoVerificationConsoleTest.java`、`TestVerificationConfiguration.java`、`CapturingVerificationProvider.java`。

**Interfaces:** `VerificationService.create(String userId,String actionId,ChallengeRequestDTO request,RiskChannel channel): RiskOutcome<ChallengeVO>`；`verify(String userId,String actionId,String challengeId,ChallengeVerifyRequestDTO request): RiskOutcome<VerificationProofVO>`。Provider为`deliver(VerificationDelivery delivery): DeliveryResult`、`verify(VerificationAttempt attempt): ProviderVerificationResult`；对应record文件放同verification目录。DeliveryResult只带可信引用与状态，VerificationProofVO不含口令；demo secret仅受控Provider和独立验证台可见。

Provider records固定字段：VerificationDelivery(challengeId,userId,method,oneTimeCode,expiresAt)，VerificationAttempt(challengeId,userId,method,providedCode,providerReference)，DeliveryResult(delivered,providerReference,failureCode)，ProviderVerificationResult(valid,assurance,providerReference)。ID/口令/引用为String，method为VerificationMethod，期限为Instant，assurance为AuthorizationAssurance，delivered/valid为boolean。它们不作为普通HTTP响应序列化，不能用toString输出秘密。demo校验仍由服务器HMAC证明，外部MFA仅接可信Provider结果。

- [x] **1 写失败测试：** `correctCodeOnlyCreatesProof`断言`action.status=AWAITING_AUTH`且usage数0；`thirdFailureLocksChallenge`；`fiveFailuresPersistUserLock`；`deliveryFailureCannotProduceProof`；`crossActionOrUserProofRejected`；`expiryClampedToMidnight`；`ordinaryApiAndModelCannotReadCode`。测试Provider捕获随机口令，不打印口令。
- [x] **2 运行RED：** `& $riskMaven "-Dmaven.repo.local=$riskRepoCache" '-Dtest=VerificationServiceTest,DemoVerificationConsoleTest' test`，应业务断言失败。
- [x] **3 实现：** SecureRandom六位码，HMAC摘要+配置pepper、常量时间比较；2分钟及午夜限制、3次/5次规则、60秒间隔。事务先PENDING_DELIVERY，结束后投递，再保存结果；旧未消费挑战作废。失败计数以RiskOutcome提交，不回滚。验证码校验和消费分别属于验证/明确授权两步。测试配置以仅测试Provider捕获真实随机Delivery，仍调用VerificationService的真实摘要校验，不伪造VERIFIED结果；pepper只在测试内生成，验证台默认关闭，相关Bean不打进生产JAR。
- [x] **4 运行GREEN：** 同命令0失败；验证台仅显式demo profile、loopback和独立操作者令牌，不进入普通业务路由。缺少pepper/操作者配置则demo不可用；生产无可信外部provider返回VERIFICATION_UNAVAILABLE。
- [x] **5 提交：** `feat: add bound verification challenges and lockout`。

### Task 4：明确授权、原子占额与取消

**Files:** 创建`service/AuthorizationGateService.java`、`common/SubmissionPermit.java`；修改`service/ActionService.java`、`dto/AuthorizationRequestDTO.java`、`vo/ActionVO.java`、`src/test/java/cn/safebank/BankTestSupport.java`、`TestBankConfiguration.java`、`MyBatisActionTest.java`；创建`AuthorizationGateTest.java`；修改`RiskConcurrencyTest.java`。

**Interfaces:** `gate.authorize(String userId,String actionId,AuthorizationRequestDTO request,RiskChannel channel): RiskOutcome<ActionVO>`；`gate.cancel(String userId,String actionId,RiskChannel channel): RiskOutcome<ActionVO>`；`gate.riskView(String userId,String actionId): RiskDecisionVO`（无副作用）；`gate.createDecision(String userId,String actionId,RiskChannel channel): RiskDecisionVO`（POST事务内持锁）。DTO固定payloadHash/decisionId/userConfirmed/verificationProofId?，旧勾选不能形成step-up。

- [x] **1 写失败测试：** `twoSixHundredAuthorizationsRequireStepUp`以两个并发授权断言“仅一个黄色成功，另一个STEP_UP_REQUIRED”；`previewDoesNotReserve`；`duplicateAuthorizationReusesGrant`；`proofCannotChangeHashPolicyOrDate`；`unconfirmedRequestCannotConsumeVerifiedChallenge`；`rejectionCommitsFreshDecisionAndAudit`；`cancelOnlyReleasesUnsubmittedUsage`。
- [x] **2 运行RED：** `& $riskMaven "-Dmaven.repo.local=$riskRepoCache" '-Dtest=AuthorizationGateTest,RiskConcurrencyTest' test`。
- [x] **3 实现：** guard/action锁下重算、绑定/校验/消费proof、grant+RESERVED+AUTHORIZED同事务；一个action最多一个ACTIVE grant，用DB锁保证，不能只用Java同步锁。挑战或授权失效不创建新资金action；安全拒绝返回RiskOutcome并持久化decision/event。gate只依赖下层Mapper/codec/策略/验证，不注入上层业务服务。旧String-hash authorize签名如为中间提交编译保留，必须拒绝STALE而非授权，Task7移除全部调用；测试helper本任务即改读当前decision并真实确认/验证，保留原归属、金额和分录断言。
- [x] **4 运行GREEN：** 同命令0失败。正常授权最长5分钟/午夜；GET风险不写decision，风险变化仅提示needsRefresh。
- [x] **5 提交：** `feat: enforce bound authorization and exposure reservation`。

### Task 5：统一提交与银行结果

**Files:** 修改`service/ActionExecutionService.java`、`service/LocalBankExecutionService.java`、`bank/FaultInjectingBankAdapter.java`、`service/RiskPolicyService.java`、`common/ActionKind.java`、`common/ActionPayloadCodec.java`、`vo/ActionParametersVO.java`、`vo/ExecutionReceiptVO.java`；创建`vo/ActionExecutionResultVO.java`、`vo/QueryParametersVO.java`、`vo/QueryExecutionReceiptVO.java`、`service/RiskQueryService.java`、`src/test/java/cn/safebank/RiskExecutionTest.java`、`RiskQueryTest.java`；调整`BankTestSupport.java`、`TestBankConfiguration.java`、`ActionExecutionServiceTest.java`。

**Interfaces:** `gate.beginSubmission(String userId,String actionId,RiskChannel channel): RiskOutcome<SubmissionPermit>`；permit的mode为NEW_BANK_SUBMISSION/LOOKUP_ONLY/READ_QUERY，携actionId/authorizationId，不能由客户端构造。`gate.requireBankAcceptance(String userId,String actionId): void`；`gate.completeAccepted(String userId,String actionId,BankObservation observation): void`在原银行/观察事务内收尾。`RiskQueryService.prepareMandates(String userId,RiskChannel channel): ActionVO`、`executeAuthorized(String userId,String actionId): QueryExecutionReceiptVO`。

ActionExecutionResultVO为sealed interface，统一`ActionVO action()`，允许ExecutionReceiptVO及QueryExecutionReceiptVO；submit返回此接口，后续WorkflowService仍用action()，不改成Object/Map丢掉类型。JSON沿用顶层@JsonUnwrapped action字段，QueryExecutionReceiptVO另带可空data（List<MandateVO>）和queriedAt；重复查询data/queriedAt为空，明确只是原回执，不伪造新查询。observe遇QUERY_MANDATES只读原查询动作，不向BankAdapter查不存在的银行交易。

- [x] **1 写失败测试：** `submissionStateIsAtomicBeforeProviderCall`；`crashAfterSubmissionMarkerOnlyLooksUpOriginalAction`；`unknownIsNotReleasedByExpiry`；`acceptedPendingCanFinishAfterGrantExpiry`；`notSubmittedReturnsSameActionForReauthorization`；`directLocalBankCallCannotBypassGate`；`confirmedQueryDoesNotCallMoneyAdapterOrCreateLedger`；`repeatQueryReturnsReceiptWithoutPretendingFreshData`。
- [x] **2 运行RED：** `& $riskMaven "-Dmaven.repo.local=$riskRepoCache" '-Dtest=RiskExecutionTest,RiskQueryTest,ActionExecutionServiceTest' test`。
- [x] **3 实现：** SUBMISSION_STARTED grant、SUBMITTED usage、UNKNOWN观察原子提交，再调用bank；成功账务/终态/usage/grant收尾同事务。超时保留占用、只查原ID；确定失败释放；NOT_SUBMITTED复原同action待授权。LocalBank独立复核证据，已受理回调不重新确认。QUERY_MANDATES走只读服务、不调用BankAdapter，查询未执行不是资金VERIFIED_FAILED。
- [x] **4 运行GREEN：** 同命令0失败；测试兼容助手走真实新授权/提交路径，红色测试通过测试Provider生成/校验proof，不插假grant或保留可绕过的旧authorize重载。
- [x] **5 提交：** `feat: gate submission and settle verified exposure`。

### Task 6：定时、联合任务与旧证据核对

**Files:** 修改`service/ScheduleService.java`、`service/WorkflowService.java`、`service/SimulationService.java`、`service/LocalBankExecutionService.java`；创建`service/ScheduleAuthorizationService.java`、`common/ScheduleScope.java`、`service/RiskReconciliationService.java`；创建`src/test/java/cn/safebank/RiskScheduleTest.java`、`RiskReconciliationTest.java`；调整`WorkflowServiceTest.java`、`WorkflowRecoveryTest.java`和测试时钟配置。

**Interfaces:** `ScheduleAuthorizationService.registerScope(String userId,String scheduleId,String sourceAuthorizationId,ScheduleScope scope): void`；`authorizeOccurrence(String userId,String scheduleId,String actionId): RiskOutcome<ActionVO>`。`RiskReconciliationService.reconcileUser(String userId): ReconciliationResult`，record放common；仅可信原动作/账务/观察可回填。模拟上下文只在隔离实例提供风险日与账务日一致的Clock，不修改默认全局Clock。

ScheduleScope的record字段固定sourceId/recipientId/amountMinor/currency/firstDate/endDate/dayOfMonth/timezone/recurrence/policyVersion/maximumRiskLevel，金额long、日期LocalDate、日int、等级RiskLevel，其余String；currency=CNY、recurrence=MONTHLY、最高YELLOW，序列化原字节用于scope_hash。ReconciliationResult为record(int migrated,int unresolved,boolean transfersLocked,List<String> unresolvedActionIds)，不包含验证码或银行完整敏感信息。

- [x] **1 写失败测试：** `redOccurrencePausesAndReusesOriginalAction`断言同(schedule,dueDate)唯一ID且零扣款；`missingScopeDoesNotDelegate`；`workflowRevisionDoesNotReverseLockOrder`；`unknownLegacyDateSurvivesVerificationCooldown`；`legacySuccessCannotBecomeSyntheticAuthorization`；`simulatedDateControlsRiskAndBookingTogether`。
- [x] **2 运行RED：** `& $riskMaven "-Dmaven.repo.local=$riskRepoCache" '-Dtest=RiskScheduleTest,RiskReconciliationTest,WorkflowServiceTest,WorkflowRecoveryTest' test`。
- [x] **3 实现：** 登记计划与scope同事务，最高YELLOW；移除prepare(delegated=true)直接AUTHORIZED。每期复用原ID，RED暂停等待当前期proof；UNKNOWN阻塞下游。workflow/schedule写入口先取得guard锁，网络查单不在外层事务。旧已受理记录LEGACY_OBSERVED不伪造授权；未知日期或金额保持reconciliation_required，缺scope旧计划暂停。
- [x] **4 运行GREEN：** 同命令0失败。scope参数/版本不匹配拒绝。预检失败不改旧文件库；本阶段先验证新库及人工构造legacy样本，不自动执行真实文件库回填。
- [x] **5 提交：** `feat: enforce scheduled scope and reconcile legacy exposure`。

### Task 7：HTTP、工具和确认卡

**Files:** 创建`controller/RiskController.java`、`dto/QueryIntentRequestDTO.java`、`vo/RiskErrorVO.java`、`common/RiskRejectedException.java`；修改`controller/ActionController.java`、`controller/MandateController.java`、`common/ApiExceptionHandler.java`、`agent/BankToolGateway.java`、`AgentService.java`、`src/main/resources/static/app.js`和`theme.css`；创建`src/test/java/cn/safebank/RiskApiTest.java`、`RiskAgentBoundaryTest.java`、`fixtures/risk-permission-qa.cjs`。

**Interfaces:** HTTP按已审API契约；控制层在RiskOutcome提交后映射RiskRejectedException，新增异常响应保留code/message并追加security/retryAt。gate查询、challenge、verify、summary不向模型暴露授权执行或OTP工具。ActionVO.security只显示已存判定/实时建议，GET不授权；oldDTO无decisionId返回RISK_DECISION_STALE而非默认接受。

- [x] **1 写失败测试：** `apiIgnoresClientIdentityRiskAndDate`；`csrfStillRequiredForChallengeAndAuthorization`；`modelCanOnlyPrepareQueryOrPaymentCard`；`queryConfirmationDoesNotBlockPageInitialization`；浏览器断言“RED无proof确认不能执行/验证码成功后仍需勾选/同action升级时更新卡片/已受理查单不重发”。
- [x] **2 运行RED：** `& $riskMaven "-Dmaven.repo.local=$riskRepoCache" '-Dtest=RiskApiTest,RiskAgentBoundaryTest' test`；UI先用本地拦截状态定位，不调用模型。
- [x] **3 实现：** list_mandates在黄色模式返回pendingAction查询卡，AgentService暂停；internal validation不输出未经确认全列表。refresh移除无条件GET代扣，导航确认后填数据。卡片显示等级/原因/合成验证标识、challenge输入和明确确认；失败只更新原动作，不自动授权/重试/新建转账；不改左中右布局。错误码按API映射，纯查询和查单不被验证锁定挡住。
- [x] **4 运行GREEN：** 同命令0失败。浏览器脚本使用独立空密钥内存库，操作者token从测试私有通道注入且不写截图/报告；普通业务响应、模型消息、日志无OTP或provider秘密。
- [x] **5 提交：** `feat: expose risk-aware confirmation and query intents`。

### Task 8：一次整体验收、候选启动与交接

**Files:** 创建`start-risk-demo.ps1`、`src/main/resources/application-risk-demo.properties`、`docs/2026-10-09-三级权限实现验收.md`（日期以实际完成日为准）；更新`README.md`、`docs/开发进度.md`、`docs/任务索引.md`、`docs/演示候选使用说明与四人分工.md`；按需要更新已有API/合成测试helpers及`fixtures/demo-candidate-qa.cjs`、`ui-workspace-qa.cjs`、`business-workflow-qa.cjs`以使用新确认接口，不减弱账务断言。

**Interfaces:** 启动脚本默认8100、独立H2内存、空模型密钥、显式demo验证配置；仅验证台使用另一个空闲loopback端口，操作者凭据本机生成/保存到忽略目录，不打印。未知占用端口不终止他人进程；所有后台进程Hidden。已有8096被冻结的旧JAR继续可用，切换需登记PID/启动时刻/运行JAR核验。

- [x] **1 汇总待通过项：** 用设计“有限验收终点”逐项映射测试，所有新安全测试与既有银行事务/幂等断言保留；只修阻塞，普通优化另记。
- [x] **2 一次全套构建：** `& $riskMaven "-Dmaven.repo.local=$riskRepoCache" package`，预期0失败、付费联调仍跳过，记真实数量。若失败修复后重跑，不能拿旧193项成绩代表新版。
- [x] **3 浏览器候选验收：** 同一候选三业务场景与1440/1024/768/390布局；另一次连续600+600授权升级和RED挑战路径。必须0真实模型请求，明确模拟验证不是真实MFA。
- [x] **4 一次独立整体验证/审查：** requesting-code-review按本分支与实施前基线检查权限、锁、账务及材料，修Critical/Important后做对应回归；无新问题不循环扩展测试。核验运行JAR哈希、无密钥提交、冻结旧JAR和阶段限制；更新设计状态/事实，不把草案说成生产认证。
- [x] **5 提交与交接：** `docs: deliver tiered permission candidate and evidence`。输出候选入口、有限验收、使用/验证台操作方式和剩余真实接入项；不自动公网发布、创建GitHub远端或调用DeepSeek。完整通过后才按登记拥有的进程规则更新8096，重置合成内存须记录。

## 计划自查与交付边界

七表/约束→Task1；可信日/累计/溢出/并发→Task2和4；挑战投递/计数不回滚/隔离台→Task3；grant绑定/占额/取消→Task4；受理原子性/UNKNOWN/回调/查询→Task5；scope/统一锁序/legacy/模拟时钟→Task6；所有入口/错误/卡片→Task7；一次完整回归/候选/交接→Task8。五项Review Focus均已分配具体测试。

RiskOutcome、riskView、createDecision、beginSubmission、completeAccepted及DTO字段在后续任务使用同名接口；依赖只向下。scope结构采用已审来源、收款人、固定金额、日期/月付规则、政策版本，不引入一般DAG。真实短信/外部生产身份系统和文件库自动迁移不在本版已验证范围，合成OTP实现属于本版任务。

用户确认后以Native方式完成8任务；一次独立审查、修复回归、完整构建和实际候选验收完成。随后按用户选择快进合并回原开发分支，主目录再次完整构建通过（242项、0失败、1付费跳过）。原worktree暂保留8096运行依赖，后续开发使用主目录；详见 `docs/2026-10-09-v05本地合并验收.md`。
