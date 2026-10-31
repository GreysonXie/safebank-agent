# v0.6 业务记忆与澄清 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有银行业务中持久化讨论对象和待补参数，可靠承接追问、修改、暂停与确认结果，保持自由回答及安全核验。

**Architecture:** 四表保存上下文、槽、证据和轮次回执；上下文协调独立于模型与银行执行。短事务保存意图/版本，模型在事务外调用；资金预览绑定原轮次，权限和执行仍由现有服务负责。

**Tech Stack:** Java 21、Spring Boot 3.5.6、MyBatis XML、H2、JUnit 5、现有原生 JS、Playwright。沿用当前模型客户端和语义审核，不新增框架。

**Spec:** `docs/superpowers/specs/2026-10-10-business-context-design.md`（用户2026-10-10批准）；DDL `docs/database/2026-10-10-business-context.sql`。设计提交 `7d3833c`；业务基线 `4eae7c2`。

## Global Constraints

- 四表增量设计优先；现有20表、银行权限、日累计及账务合同保留。不自动迁移旧文件库，不改变当前8096实例。
- Controller→Service→MyBatis；Entity/DTO/VO独立，明确业务类型。可信userId来自会话，模型不得指定身份、grant或动作标识。
- 金额整数分，Clock提供时刻；候选/选中代扣最多10项，已查历史账单最多四个月份；显示聊天仅最近四轮，上下文七天未活动过期。
- 模型和网络调用不占用数据库长事务；同会话并发返回409，版本冲突不静默覆盖。
- UNKNOWN/PENDING恢复原动作，不重建、重放提交或承诺回滚已执行动作；资金操作逐项授权，强验证不降级。
- 真实调试最多4组、API请求总上限20，生成/审核/失败/修正均计数；每轮≤5，语义修正最多一次，无供应商自动重试。
- max_tokens保留2048，usage缺失记未知；真实key不提交、不打印、不经命令行。预算账本仅本机忽略目录，跨进程重启不归零。
- 布局保留左导航/中业务/右对话、手机底部对话；只增上下文状态和恢复交互。本阶段不实现通用DAG。
- 执行前使用using-git-worktrees建立 `.worktrees/v06-business-context` 与分支 `feature/v0.6-business-context`；不操作其他worktree。
- 建议Native：同一实现者顺序完成6任务，末尾一次独立审查；不为每个小任务重复启动审查。执行方式仍待用户选择/确认。

## Review Focus

1. **首次查询已成功但网页断线：** 查询数据快照与成功状态同事务提交，恢复解释原快照，不冒充最新数据；Task3。
2. **预览创建后进程中断：** 银行对象与轮次原标识有原子关联，恢复不创建第二动作/任务；Task3。
3. **混合改口“改成300，先别转”：** 金额30000分只更新草案、暂停未提交动作，旧授权不继承；Task4。
4. **同用户两个标签页＋跨用户伪造ID：** requestId幂等、版本冲突和归属隔离成立，页面恢复不自动执行业务；Task2、Task5。
5. **已花额度的调试进程重启＋并发审核：** 第21次供应商发送被阻断，失败及审核已占额，缺失usage不伪装为零；Task6。

## 公共类型与文件约定

新增 `common/ContextTypes.java`：Intent/ConversationState/TurnState/SlotName/SlotProvenance 枚举按设计逐项定义。新增 `common/ContextContracts.java`：TurnLease(userId,conversationId,turnId,inputVersion,requestId)、ToolIntent(toolCallId,toolName,parametersHash,bankObjectId,state)、ContextSnapshot(conversationId,version,intent,state,slots,proposal,evidence,displayHistory)、TurnStart(lease,replayResponse,processing)、EvidenceInput(key,toolName,objectId,data,observedAt,validUntil)、ContextCommit(snapshot,evidence,response)。上述均为typed record，模型JSON只在codec边界转换。

`dto/ContextProposalDTO.java`：intent、updates（枚举槽→JsonNode值）、clears、missingSlots、pause；不接受provenance、userId、grant、actionId。`dto/ChatRequestDTO.java`追加可空conversationId/requestId/expectedContextVersion，保留原构造兼容助手供旧测试使用。新增 `dto/ContextResetRequestDTO.java`、`vo/ConversationVO.java`、`vo/ClarificationVO.java`；动作字段只读取服务器绑定记录。

金额/日期/来源校验放独立 `common/ContextCodec.java`，只支持槽白名单。BANK_CONFIRMED必须绑定银行证据；USER_EXPLICIT必须能从该轮原文核对，不能仅采信模型来源标签。模型提议缺少可核对来源时保持MODEL_PROPOSED，不进入可执行参数。

## Task 1：四表、类型与MyBatis存储

**Files:** 新增 `src/main/resources/schema-context.sql`；`src/main/java/cn/safebank/common/ContextTypes.java`、`ContextContracts.java`、`ContextCodec.java`；`entity/AgentConversationEntity.java`、`AgentContextSlotEntity.java`、`AgentContextEvidenceEntity.java`、`AgentTurnReceiptEntity.java`；`dao/AgentConversationMapper.java`、`AgentContextSlotMapper.java`、`AgentContextEvidenceMapper.java`、`AgentTurnReceiptMapper.java`；对应四个 `src/main/resources/mapper/Agent*Mapper.xml`，名称与接口一致；新增 `config/ContextSchemaInitializer.java`、`dao/ContextSchemaMapper.java`及其XML；修改 `src/test/resources/application-test.properties`。测试新增 `src/test/java/cn/safebank/ContextPersistenceTest.java`。

**Interfaces:** Mapper拥有 `selectOwned`（需要锁时独立`selectOwnedForUpdate`）、显式列名的insert/update和版本条件写入，userId为必填。ConversationMapper提供 `updateIfVersion(String userId,String id,long expectedVersion,AgentConversationEntity next)`；TurnMapper提供 `selectRequest(String userId,String conversationId,String requestId)`；Slot/Evidence Mapper提供`selectByConversation(String userId,String conversationId)`、`upsertOwned`和`clearPayloadsOwned`。Codec `decodeSlot(SlotName,JsonNode)`只返回允许业务类型。

- [ ] 写 `ContextPersistenceTest`：四表复合归属外键阻断跨用户槽/证据；重复requestId被拒；版本0和非法状态失败；同会话同槽唯一；写入返回行数正确，30000分不转为浮点。
- [x] `./run.ps1 test -Tests ContextPersistenceTest`，确认因新存储缺失失败，记录RED原因。
- [x] 将已批准DDL复制为schema-context.sql并实现类型、Entity、Mapper/XML；ContextSchemaInitializer只在显式create-new且全新H2内存库初始化，部分缺表/缺约束拒绝运行，旧文件库不自动修复。显式依赖riskSchemaInitializer，待原13表及risk初始化完成后运行；测试使用独立库。
- [ ] 同一命令验证GREEN；另在测试内断言部分schema和旧file模式拒绝，不启动真实数据库。
- [x] 提交 `feat: add owned conversation context persistence`，只暂存明确源码/测试文件，不提交数据、日志或key。

## Task 2：上下文生命周期、轮次幂等与过期

**Files:** 新增 `service/ContextService.java`、`ContextEvidenceService.java`、`ContextValidationService.java`、`ContextRecoveryService.java`；上述公共DTO/VO。新增 `ContextLifecycleTest.java`、`ContextConcurrencyTest.java`、`ContextEvidenceTest.java`，目录均为 `src/test/java/cn/safebank/`。

**Interfaces:** `ContextService.begin(String userId,ChatRequestDTO request): TurnStart`、`complete(TurnLease,ContextCommit): ConversationVO`、`fail(TurnLease,String code): void`、`read(String userId,String id): ConversationVO`、`reset(String userId,String id,long expectedVersion): ConversationVO`。`ContextEvidenceService.load(String userId,String conversationId): List<EvidenceInput>`、`store(TurnLease,List<EvidenceInput>): void`。`ContextValidationService.stage(TurnLease,ContextSnapshot,String userText,ContextProposalDTO): ContextSnapshot`。ContextRecoveryService启动时将独占实例遗留PROCESSING标为INTERRUPTED并保留银行对象关联。

- [ ] 写失败测试：六轮后primary_month仍为2026-09而显示历史≤4轮；同ID/哈希重放原响应零模型请求；同ID不同文本409；同会话并发只能一轮获租约；跨归属404；第七天边界过期410且不能重放旧响应；重启不重放工具。旧客户端缺ID时创建会话，提供ID时必须校验归属。
- [x] `./run.ps1 test -Tests 'ContextLifecycleTest,ContextConcurrencyTest,ContextEvidenceTest'`确认RED。
- [x] 实现短事务begin/complete，先重放检查再新轮次版本校验；不持数据库锁调用模型。完成递增版本一次；失败保存状态，不能丢失已建立银行关联。活动更新expiresAt=now+7天，过期清空敏感payload并保留请求hash/状态/银行对象标识。reset清草案而不暗中取消银行动作。
- [x] 证据只有成功银行查询能入库；四个月份稳定淘汰未活动月份，余额/状态不跨轮缓存；最新请求重查，已确认代扣快照须带原查询时刻。验证快照哈希篡改拒绝、商户注入仅作数据。
- [x] 同一测试命令GREEN，提交 `feat: persist context turns and bounded evidence`。

## Task 3：预览幂等关联与确认查询回执

**Files:** 新增 `service/ContextBankProposalService.java`、`ContextQueryReceiptService.java`；修改 `service/ActionService.java`、`RiskQueryService.java`、`WorkflowService.java`；新增 `ContextProposalRecoveryTest.java`、`ContextQueryReceiptTest.java`。原RiskQueryTest的重复提交data=null/queriedAt=null断言保留。

**Interfaces:** `ContextBankProposalService.preview(TurnLease,String toolCallId,String toolName,JsonNode args): Object`；内部 `ActionService.prepareIdentified(String userId,String serverActionId,ActionKind,ActionParametersVO): ActionVO`，仍经过原业务验证/门禁；`WorkflowService.createIdentified(String userId,UUID serverWorkflowId,WorkflowRequestDTO): WorkflowVO`。原公开方法继续随机服务器ID并保持HTTP合同。`ContextQueryReceiptService.capture(String userId,String actionId,List<MandateVO>,Instant queriedAt): void`与`readOriginal(String userId,String conversationId,String actionId): Optional<EvidenceInput>`。

- [x] 写失败测试：在银行preview创建后、turn complete前注入异常，恢复只有一个action_id且不提交；重复相同提案返回原ID；更改hash不复用旧ID。查询成功后模拟断线，恢复所得数据和queriedAt等于首次；修改当前代扣表后原快照不跟随变化。
- [x] `./run.ps1 test -Tests 'ContextProposalRecoveryTest,ContextQueryReceiptTest,RiskQueryTest'`确认RED。
- [x] 服务端预分配ID，tool意图先持久化；同一短事务保存银行对象与轮次绑定，失败全回滚。bank_references_json保留typed对象种类/ID/toolCallId，不因过期payload清理丢失关联。资金预览使用原user锁→action/账户锁序；不要先持context行锁再等待user锁。Workflow初始预览及绑定也走同事务，不另建执行通道。
- [x] RiskQueryService首次查询在原用户锁事务内capture关联会话的快照，与查询成功状态原子提交；capture不依赖RiskQueryService以避免构造循环。不关联会话的旧HTTP查询继续原行为；重复提交不会重新capture或查询。可关联动作须能在turn的typed tool意图中找到可信bankObjectId；客户端不能自造关联。
- [x] 原快照缺失/已过期时返回明确缺证据状态，要求新的确认查询，不静默访问MandateService；未知、未成功、跨用户或跨会话action不能导入。
- [x] 同一命令GREEN，提交 `feat: bind proposals and confirmed query snapshots to context`。

## Task 4：多轮理解、澄清、工具路由与针对性回答

**Files:** 新增 `agent/BusinessContextBuilder.java`、`BusinessToolSelector.java`、`service/ClarificationService.java`、`agent/ContextAgentCoordinator.java`；修改 `AgentService.java`、`agent/BankToolGateway.java`、`GroundedAnswer.java`、`BankReplyComposer.java`；新增 `ContextAgentConversationTest.java`、`ContextClarificationTest.java`、`ContextAnswerFocusTest.java`。

**Interfaces:** `BusinessContextBuilder.messages(ContextSnapshot,String userText): List<Map<String,Object>>`（协议边界）、`BusinessToolSelector.select(Intent): List<Object>`；`ClarificationService.describe(ContextSnapshot): ClarificationVO`；`ContextAgentCoordinator.chat(String userId,ChatRequestDTO,HttpSession): Map<String,Object>`。AgentService原chat重载保留测试兼容，调用内部上下文感知路径；模型工具definitions拆至selector，银行gateway不注入Mapper。

- [x] 用脚本ModelClient写失败测试：九月→最大那笔→维修背景→八月比较，只补查缺失月份；超过四轮仍承接；两个同名候选未选不生成预览；“第二个”只引用展示候选；“改成300，先别转”amount=30000、PAUSED、无新提交；“视频保留”不撤视频、不虚构已完成撤销的恢复。
- [x] `./run.ps1 test -Tests 'ContextAgentConversationTest,ContextClarificationTest,ContextAnswerFocusTest'`确认RED。
- [x] 接入propose_business_context限定schema，stage暂存至本轮完成；校验选中ID、去重候选、互斥选中/排除、缺参来源，模型自称“已授权”无效。金额建议可MODEL_PROPOSED展示，未核对不能进入preview；省略语必须有唯一可定位对象，否则澄清。
- [x] 模型缺参只问尚缺业务参数，工具可查信息先查；资金改参先查旧状态，未提交旧提案经原服务取消、旧grant不可继承，已受理/UNKNOWN仅查原动作。暂停优先于同句预览意图；已有workflow调整用原revise/cancel，不重建已执行任务。
- [x] Selector每组保留answer_customer/calculate_business_metric/propose_business_context；不确定意图允许只读发现/澄清，相关业务工具再按需扩展，不能把纯工具路由当成安全权限。确认查询快照导入GroundedAnswer时展示时间/范围，不附带新的读取许可。
- [x] 兜底按Intent/选中对象选择相关事实，明确SAFE_FALLBACK；保留自由答案及BusinessAnswerReview，不替换为固定业务脚本。修正最多一次，审核必要余量不足就停止。旧构造测试只做兼容适配，不削弱账务断言。
- [x] 同一命令GREEN；追加运行现有 `AgentServiceTest,RiskAgentBoundaryTest`，提交 `feat: add grounded multi-turn clarification and context routing`。

## Task 5：HTTP与网页恢复

**Files:** 新增 `controller/ConversationController.java`；修改 `controller/ChatController.java`、`dto/ChatRequestDTO.java`、`src/main/resources/static/app.js`、`index.html`、`styles.css`；新增 `ContextApiTest.java`、`fixtures/context-browser-qa.cjs`。网页增小型上下文/缺参状态区，不重做视觉风格。

**Interfaces:** 原POST /api/chat兼容，增conversationId/requestId/expectedContextVersion；GET /api/conversations/{id}；POST /api/conversations/{id}/context/reset（ContextResetRequestDTO）。chat返回原字段＋conversationId/contextVersion/context/clarification。成功查询submit后的页面读取归属内上下文，服务器按Task3已持久化的actionId关联读取原记录，不接收客户端回执正文；新GET恢复不执行任何银行动作。

- [x] 写ContextApiTest失败断言：旧{text}仍可发起；归属伪造404、version冲突409、expired410、重复requestId同响应且调用数不增加；恢复GET零银行提交。所有POST沿用CSRF，userId不取请求字段。
- [x] `./run.ps1 test -Tests ContextApiTest`确认RED，再实现Controller/DTO/VO接线。
- [x] 网页sessionStorage记录conversationId/version及待处理requestId；网络重试复用原ID/原请求体，不产生新动作。409显示冲突并读服务器状态，不自动重发；410明确建立新会话。刷新只恢复上下文/回执，不重新调用模型；切换用户清标签页上下文。
- [x] 代扣首次提交成功后同步该会话原查询快照并在下一轮解释，不把前端receipt.data作为可信输入。状态区显示讨论月份/对象、待补参数、暂停/待确认；候选按钮只填写选择，不授权。重置按钮明确仅清对话草案，活动银行提案仍有独立取消入口。
- [x] ContextApiTest GREEN；Browser插件若可用优先用，否则记录无插件并用本机Playwright/Edge。context-browser-qa检查刷新、断网后恢复同requestId、两个标签页冲突、错误可读，以及1440/390两宽左中右/手机底部布局，零pageerror、零真实模型请求。
- [x] 提交 `feat: expose resumable conversation context in the workspace`。

## Task 6：调试预算、有限真实对话和交付

**Files:** 新增 `model/DebugRequestBudget.java`、`src/test/java/cn/safebank/DebugRequestBudgetTest.java`、`src/main/resources/application-context-demo.properties`、`start-context-demo.ps1`、`fixtures/context-deepseek-debug.cjs`；修改 `model/DeepSeekModelClient.java`、`start-risk-demo.ps1`、`pom.xml`；新增 `docs/2026-10-10-业务记忆实施验收.md`及 `docs/evidence/2026-10-10-business-context/` 中不含凭据的结果摘要。最后更新README/开发进度/任务索引及设计实施状态。

**Interfaces:** `DebugRequestBudget.reserve(): String`（原子预留编号）、`record(String reservationId,boolean success,JsonNode usage): void`、`snapshot(): BudgetSnapshot`。DeepSeekModelClient在每次真实HTTP send前reserve，不只在主生成入口计数；关闭debug配置时原运行行为不变。预算路径必须显式为共享主项目 `.superpowers/deepseek-v06-debug/20261010-v06-context.json`，所有本轮实例同runId，不因worktree/进程变化新建预算。

- [x] 写失败测试：本地fake HTTP供应商收满20次，第21次发送数不增；生成/审核/失败各占额；两个Java进程并发≤20；重启读原次数；坏账本fail closed；usage缺失标unknown。用固定runId隔离临时测试账本，不能使用正式预算文件。
- [x] `./run.ps1 test -Tests DebugRequestBudgetTest`确认RED，实现FileChannel独立lock文件跨进程加锁、持久化预留后send、锁内原子更新账本；失败不返还额度、不自动重试。remaining不足必要生成＋审核则停止。max_tokens=2048保留，Debug profile每轮默认5。
- [x] pom标记0.6.0；start-risk-demo增加可选JarFileName/Profile参数，默认行为兼容。start-context-demo选0.6.0/context-demo、默认8110业务/8111验证台，拒绝端口冲突，只启动自身，隐藏窗口，复制运行JAR。显式fresh上下文/risk内存初始化，默认无模型key；Online从原本机apikey.txt经环境配置，账本路径固定共享根目录，不能覆盖8096。
- [x] 同一预算测试命令GREEN；执行一次 `./run.ps1 package` 全套构建，记录实际测试数/失败/跳过，不复制v0.5的242成绩。检查源码无凭据、profile默认无付费和旧资金合同保持；一次独立整体审查，Critical/Important做对应修复和定向复测。
- [x] 候选浏览器验收在Task5脚本完成；使用DeepSeek前先检查正式账本剩余，禁止删除/重置它。最多4组对话按账单追问→转账澄清→代扣确认→联合储蓄顺序，不保证全部跑完。每轮上限min(5,remaining)，服务端再次硬核验。余额/验证码/授权由本地服务验证，不让模型执行；真实调试仅独立合成库。
- [x] context-deepseek-debug.cjs保存输入/答案/期望、request_count、provider usage及耗时到忽略target；检查答案有没有重复总览、猜参、回执失真。预算耗尽或供应商失败立即停止，明确未验收项；不会通过“重新开始一轮调试”追加20次。必要修正最多一次，仍占本轮总额。
- [x] 将无key、无验证凭据的摘要和真实已完成案例写验收文档；若provider未报告usage就标未知，不能凭请求数估算人民币费用。记录单实例/合成身份/模拟验证、未实现通用DAG及未完成真实对话。提交 `feat: deliver bounded context candidate and debug evidence`，运行候选交付后按finishing技能处理集成，不自动替换8096。

## 自查与执行交接

覆盖：DDL/来源/归属→Task1；七天/重放/证据→Task2；原动作/查询快照→Task3；记忆/改口/意图/答案→Task4；HTTP/标签页/恢复→Task5；20次总预算/真实调试/构建/交付→Task6。五项Review Focus均分配了真实断言；只有实际执行完成才勾选。

一次全套构建仅在各任务定向验证通过后执行。后续失败只做必要修复，不新增无终点的压力测试。原20表与资金授权合同保留；四表schema不是当前运行库迁移承诺。现有模拟回答不作为真实DeepSeek质量证据。

Native顺序实施与一次独立审查已执行，候选交付见 docs/2026-10-10-业务记忆实施验收.md。集成等待最终选择。尚未勾选的三项包含未全部展开的细分数据库/精确边界断言，核心范围已验证；依用户允许测试后续完善的安排记录为覆盖缺口，不宣称全部验收。真实对话一轮通过、两轮兜底，分类比较本地修正后未继续付费复测。
