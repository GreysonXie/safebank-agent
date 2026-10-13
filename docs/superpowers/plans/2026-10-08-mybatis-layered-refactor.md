# SafeBank 三层架构与 MyBatis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将全部生产数据库访问迁移至 MyBatis，建立清晰的 Controller / Service / DAO（Mapper）与 Entity / DTO / VO，同时保持现有银行、智能体及网页行为。

**Architecture:** 在已确认的 13 张表上逐模块迁移；每阶段从持久化类型、Mapper 到服务及调用者形成可测试路径。现有 BankService 暂作兼容入口，最终删除；资金事务仍由业务服务编排，模型及银行外部调用位于数据库事务之外。

**Tech Stack:** Java 21、Spring Boot 3.5.6、MyBatis Spring Boot Starter 3.0.5、H2、JUnit 5 / Spring Boot Test；原生 HTML/CSS/JavaScript 页面保持。

**Spec:** [架构设计](../specs/2026-10-08-layered-mybatis-design.md)、[数据库设计](../../database/2026-10-08-database-design.md)。红蓝 UI 设计独立审核，不在本计划修改范围。

状态：待实施计划审核与执行方式确认；设计方向已确认。当前仓库提交 f620388，运行代码基线 c81fad0。

## Global Constraints

- 生产代码最终不得直接使用 JdbcTemplate、SqlSession 手写通用查询器或 JDBC Connection/Statement 访问持久化；由 MyBatis Mapper 承担 DAO。测试可用 JdbcTemplate 独立验证数据库账务，避免让验证完全依赖被测 Mapper。
- Java 单后端、H2、表名/列名/主键、现有网页、/api 路由、CSRF、会话身份、金额分单位与 JSON 字段保持。
- Entity 对应数据库；DTO 用于输入/传输；VO 用于输出。动态模型 JSON 和持久化快照可保留受控 JsonNode/编码字符串，银行核心不以任意 Map 传递。
- MyBatis SQL 使用 #{参数} 和显式列名；动态 ${列名} 不用于客户输入。行锁、唯一性、归属、五分钟授权有效期及单笔 1,000,000 分限额保留。
- 保留账单退款入账、内部转账排除、大额仅是候选、不推断风险或商户合同已终止的口径。
- 所有执行、取消、查单、定时与任务恢复保持既有语义；UNKNOWN 查原 action_id。不得将模型文字当作授权或成功凭证。
- 同一 DataSource/事务管理器；短事务使用 TransactionTemplate 或职责明确的独立事务服务。禁止整类长事务包裹模型或 BankAdapter 调用。
- 不调用 DeepSeek 做迁移测试，不读取密钥进入模型上下文，不改真实资金，不推送、不部署公网。
- 本轮冻结现有 DDL；新增数据库约束、版本化迁移工具和索引应用另立数据库升级计划，先在副本检查存量数据。本计划完成表模型与访问层迁移，不宣称已应用设计稿中的增强约束。

## Review Focus

1. Java 类型转换后出现不同 snake_case/camelCase、空值或日期序列化，导致网页、事实目录或授权哈希失效：Task 1/3/6 的兼容测试。
2. 收款账户属于另一客户，错误添加付款人归属筛选，或因此放松付款账户归属：Task 4 的跨客户合法收款及越权负例。
3. 余额修改后第二条分录或流水写入失败，出现部分扣款：Task 4 的事务回滚测试。
4. 已提交但回执丢失，重启后误建新动作/重复提交：Task 5 的 UNKNOWN 文件库恢复测试。
5. 快速重复触发同一期或恢复旧任务版本，生成重复委托或重复节省：Task 6 的唯一性与版本测试。

## 文件与类型约定

所有路径相对仓库根目录。以下每个 X 对应一个实体文件、一个 Mapper 接口及一个 SQL XML，随拥有该业务的任务建立，禁止先生成空壳全集。

| Task | Entity（src/main/java/cn/safebank/entity/XEntity.java） | Mapper（src/main/java/cn/safebank/dao/XMapper.java） / XML（src/main/resources/mapper/XMapper.xml） |
| --- | --- | --- |
| 1 | Account | Account |
| 2 | Recipient、Transaction、Mandate | Recipient、Transaction、Mandate |
| 3 | Action、AuditEvent | Action、AuditEvent |
| 4 | LedgerEntry、Schedule | LedgerEntry、Schedule |
| 5 | ExecutionObservation、BankFault | ExecutionObservation、BankFault |
| 6 | ScheduleOccurrence、Workflow、WorkflowStep | ScheduleOccurrence、Workflow、WorkflowStep |

Entity 为标准 JavaBean，无 Lombok；字段与数据库设计表一一对应，camelCase、LocalDate、OffsetDateTime、Long 等类型明确。SQL resultMap 显式映射；可空字段保留 null。DTO/VO 用独立 Java record，声明参数校验，必要时 JsonProperty 保留既有 HTTP 字段。统计投影放 dao/projection/，不冒充实体。

## Task 1：账户查询纵向迁移及 MyBatis 配置

**Files:** 修改 pom.xml、src/main/resources/application.properties、src/main/java/cn/safebank/BankService.java；建立表中 Account 文件，以及 service/AccountService.java、vo/AccountVO.java、converter/BankJsonConverter.java（均位于 src/main/java/cn/safebank/）；测试 src/test/java/cn/safebank/MyBatisAccountTest.java、现有 ApiTest.java。

**Interfaces:** AccountMapper.selectByUser(String userId): List<AccountEntity>；selectOwned(String userId,String accountId): AccountEntity（未找到返回 null）。AccountService.list(String userId): List<AccountVO>；balance(String userId,String accountId): long。AccountVO(String id,String label,long balanceMinor)，balanceMinor 序列化为 balance_minor。BankJsonConverter.toLegacyObject(Object value): Object 用既有 ObjectMapper 转为 JSON 等价对象，仅供过渡银行工具边界使用。

- [ ] 写 MyBatisAccountTest：注入并调用新 AccountService，断言 u1 有 checking/travel、checking 余额 1243000 分；读取 u2 的 other 返回 NOT_FOUND。ApiTest 断言 /accounts 保留 balance_minor，且不泄露 user_id。
- [ ] 运行 `./run.ps1 test -Tests 'MyBatisAccountTest,ApiTest'`，确认因缺失新路径而失败，并记录原因。
- [ ] 引入 org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.5，配置 mapper-locations=classpat<LOCAL_PATH> @Mapper；将账户 SELECT 移入 AccountMapper.xml，BankService.accounts/balance 暂经服务及兼容转换调用。保留现有唯一 DataSource 和事务管理器。
- [ ] 运行上述测试及 `./run.ps1 test`，0 失败；依赖树确认仅一个 MyBatis Starter 系列和一个兼容 MyBatis-Spring。依赖下载若受网络限制，申请该下载命令权限；不绕过。
- [ ] 仅提交此任务文件，提交说明 `refactor: migrate account reads to MyBatis`。

## Task 2：联系人、代扣、账单与周期查询

**Files:** 表中 Recipient/Transaction/Mandate 文件；建立 service/RecipientService.java、service/MandateService.java、service/StatementService.java；vo/RecipientVO.java、MandateVO.java、TransactionVO.java、StatementVO.java、RecurringPaymentVO.java；dao/projection/RecurringPaymentRow.java；修改 BankService.java；测试 MyBatisQueryTest.java、BankServiceTest.java、AgentConversationTest.java。

**Interfaces:** RecipientService.search(String userId,String query): List<RecipientVO>；owned(String userId,String recipientId): RecipientEntity。MandateService.list(String userId): List<MandateVO>。StatementService.statement(String userId,String month): StatementVO；recurring(String userId): List<RecurringPaymentVO>。TransactionMapper.selectPeriod(String userId,LocalDate start,LocalDate end): List<TransactionEntity>；selectRecurring(String userId): List<RecurringPaymentRow>。RecipientMapper.selectMatches(String userId,String query) 用现有 name/phone/note/id 的精确 OR 匹配并 ORDER BY id，不扩大成模糊搜索。

- [ ] 写 MyBatisQueryTest：u1 九月基线 expense_minor=340300、income_minor=850000；同用户内部转账排除，退款计收入，非法月份拒绝；“张三”返回两个独立对象、u2 看不到 u1 联系人；周期查询保留已有候选规则及 usage_known=false。
- [ ] 运行 `./run.ps1 test -Tests 'MyBatisQueryTest,BankServiceTest,AgentConversationTest'`，确认新服务尚不存在或尚未接通造成失败。
- [ ] 建立 Mapper、查询投影和服务。StatementVO 保留现有 9 个键：month、expense_minor、income_minor、internal_transfer_minor、categories、transactions、anomaly_candidates、anomaly_rule、usage_data_available；周期候选继续用平均间隔 25–35 天及既有统计。BankService 对应读取方法暂转换兼容对象。
- [ ] 运行上述测试和 AgentReplyTest，确认 facts 中日期、商户和类别仍可引用，不重新添加完整查询数据到模型工具历史；查询过程中账务不变化。
- [ ] 提交 `refactor: migrate statement and mandate reads to MyBatis`。

## Task 3：动作预览、授权与类型化参数

**Files:** 表中 Action/AuditEvent 文件；建立 service/ActionService.java、service/TransferValidationService.java、common/ActionPayloadCodec.java；dto/TransferPreviewRequestDTO.java、MandatePreviewRequestDTO.java、SchedulePreviewRequestDTO.java、AuthorizationRequestDTO.java；vo/ActionVO.java；common/ActionKind.java、ActionStatus.java；修改 BankService.java；测试 MyBatisActionTest.java、ApiTest.java、BankServiceTest.java。

**Interfaces:** ActionService.previewTransfer(String userId,TransferPreviewRequestDTO request): ActionVO；previewRevoke(String userId,MandatePreviewRequestDTO request): ActionVO；previewSchedule(String userId,SchedulePreviewRequestDTO request): ActionVO；authorize(String userId,String actionId,String expectedHash): ActionVO；cancel(String userId,String actionId): ActionVO；status(String userId,String actionId): ActionVO。ActionMapper.selectOwned(String userId,String id)、selectOwnedForUpdate(String userId,String id): ActionEntity。TransferValidationService.validate(String userId,String sourceId,String recipientId,long amountMinor): void。

**VO 字段约定:** ActionVO 的公共字段 id、user_id、kind、payload_hash、status、expires_at、receipt_id、error_code 与原结果保持，包括原有 null。新增 vo/ActionParametersVO.java（sealed），vo/TransferParametersVO.java、RevokeParametersVO.java、ScheduleParametersVO.java，以 JsonUnwrapped 展开明确参数。TRANSFER 字段 source_id/recipient_id/recipient_label/amount_minor/currency/fee_minor，加可选 schedule_id/occurrence_date；REVOKE 字段 mandate_id/merchant/charge_amount_minor/billing_interval/estimated_monthly_saving_minor/contract_status/scope；SCHEDULE 字段 source_id/recipient_id/amount_minor/first_date/end_date/day_of_month/timezone/insufficient_funds。原来不出现的可选参数不序列化为额外 null；不能对整个 VO 统一 NON_NULL 删除公共 null 字段。

- [ ] 写 MyBatisActionTest：预览不改余额；错误哈希和过期拒绝；50000 分预览保留参数与原哈希算法；非法授权状态/跨用户动作拒绝。持久化预先存入的 payload 原字符串，转换 Entity/VO 后 hash 不变。
- [ ] 运行 `./run.ps1 test -Tests 'MyBatisActionTest,ApiTest,BankServiceTest'`，确认失败。
- [ ] 移动预览、授权、取消逻辑及 SQL；保持 SHA-256(userId+":"+kind+":"+原 payload 字符串) 和原编码字段/顺序语义，读取旧 payload 不重写。DTO 字段沿用现有 Controller 请求 record；VO 明确声明现有 status 返回的字段，不使用 Map 继承或 JsonAnyGetter。TransferValidationService 使用 Task 1/2 的 owned 查询，拒绝同账户及大于 1,000,000 分金额。
- [ ] 运行上述测试，确认授权审计与状态在同一短事务中。BankService 暂委托此服务，授权接口语义不变。
- [ ] 提交 `refactor: migrate action authorization to MyBatis`。

## Task 4：本地银行资金记账与计划登记

**Files:** 表中 LedgerEntry/Schedule 文件；修改 AccountMapper、TransactionMapper、MandateMapper、ActionMapper、AuditEventMapper 的 XML/接口；建立 service/LocalBankExecutionService.java、service/ScheduleService.java、vo/ScheduleVO.java；修改 BankService.java、bank/LocalBankAdapter.java；测试 MyBatisExecutionTest.java、BankServiceTest.java、ActionExecutionServiceTest.java。

**Interfaces:** LocalBankExecutionService.submit(String userId,String actionId): ActionVO；completePending(String userId,String actionId): ActionVO；acceptPending(String userId,String actionId): ActionVO；rejectAuthorized(String userId,String actionId,String code): ActionVO；rejectUnaccepted(String userId,String actionId,String code): ActionVO。ScheduleService.list(String userId): List<ScheduleVO>。AccountMapper.lockById(String accountId): AccountEntity（仅在已验证收款人后内部调用）；debit(String accountId,long amountMinor): int；credit(String accountId,long amountMinor): int。所有 UPDATE/INSERT 返回受影响行数并检查预期值。

- [ ] 写 MyBatisExecutionTest：向 external-a 合法转账 50000 分，两条 ledger 合计 0、来源余额 1193000 分、目标余额 50000 分；付款人不能用 other 当来源。重复 submit 分录数仍为 2。
- [ ] 加事务故障用例：在测试中制造第二条 ledger 的唯一键冲突，断言异常后两个余额、交易流水、动作状态均回滚；使用独立 SQL 验证结果，不以被测 Mapper 再读替代。
- [ ] 运行 `./run.ps1 test -Tests 'MyBatisExecutionTest,BankServiceTest,ActionExecutionServiceTest'`，确认新路径失败。
- [ ] 将 TRANSFER/REVOKE/SCHEDULE 分支移入 LocalBankExecutionService；动作行锁、账户按 id 排序锁定、锁后重验、分录/流水/终态/审计原子提交。失败记录在已回滚的执行事务外按原规则记录。ScheduleMapper 显式列名插入；LocalBankAdapter 使用 typed 服务并形成 BankObservation。
- [ ] 运行上述测试，保留处理中的提交/完成语义与 ledger 核验，确认 adapter 调用没有被新增外层事务包裹。
- [ ] 提交 `refactor: migrate local bank execution to MyBatis`。

## Task 5：执行观察、故障与 UNKNOWN 恢复

**Files:** 表中 ExecutionObservation/BankFault 文件；建立 service/SimulationService.java、vo/ExecutionReceiptVO.java、service/ActionExecutionService.java；原 ActionExecutionService.java 暂改为无 SQL 的兼容适配，Task 7 删除；修改 bank/FaultInjectingBankAdapter.java 的依赖（保留 BankAdapter / BankObservation 契约）；测试 ActionExecutionServiceTest.java、MyBatisRecoveryTest.java。

**Interfaces:** ActionExecutionService.submit(String userId,String actionId): ExecutionReceiptVO；observe(String userId,String actionId): ExecutionReceiptVO。ExecutionObservationMapper.selectOwned(String userId,String actionId): ExecutionObservationEntity；upsert(ExecutionObservationEntity row): int；deleteOwned(String userId,String actionId): int。SimulationService.configure(String userId,String actionId,FaultMode submitMode,FaultMode lookupMode): void；enabled(): boolean。BankFaultMapper.selectOwned(String userId,String actionId): BankFaultEntity。

- [ ] 写 MyBatisRecoveryTest：AFTER_COMMIT_RESPONSE_LOST 后仅查原动作可恢复成功，记账仍 2 条；LOOKUP_UNAVAILABLE 保持 UNKNOWN；明确 NOT_SUBMITTED 才允许原动作重试。用临时目录的 H2 文件库关闭再打开验证观察恢复，不访问运行 data/。
- [ ] 运行 `./run.ps1 test -Tests 'MyBatisRecoveryTest,ActionExecutionServiceTest'`，确认失败。
- [ ] 执行协调先短事务持久化 SUBMISSION_STARTED/UNKNOWN，再出事务调用 adapter，随后短事务保存观察；MERGE/H2 upsert 的 SQL 留在 XML。Fault 模式按实体字段读取，不拼接列名。ExecutionReceiptVO 组合 typed ActionVO 与观察字段，对外仍是既有扁平 JSON；原入口暂将 VO 转旧 ExecutionReceipt，仅供尚未迁移调用者使用。新服务临时 bean 名 layeredActionExecutionService，防止与旧 actionExecutionService 冲突，Task 7 删除旧适配后恢复默认命名。
- [ ] 运行上述测试及 ApiTest，确认模拟模式禁用时接口拒绝，执行观察不把模型文字变成成功依据。
- [ ] 提交 `refactor: migrate execution observations and recovery to MyBatis`。

## Task 6：定时、联合任务及版本恢复

**Files:** 表中 ScheduleOccurrence/Workflow/WorkflowStep 文件；修改 ScheduleService.java；建立 service/WorkflowService.java；原 workflow/WorkflowService.java 暂作无 SQL 的类型兼容适配；将 WorkflowContracts 请求与输出拆入 dto/WorkflowRequestDTO.java、vo/WorkflowVO.java、vo/WorkflowStepVO.java；测试 WorkflowServiceTest.java、WorkflowRecoveryTest.java、WorkflowApiTest.java、MyBatisWorkflowTest.java。

**Interfaces:** ScheduleService.runDue(String userId,String simulatedDate): List<ActionVO>。WorkflowService.create(String userId,WorkflowRequestDTO request): WorkflowVO；list(String userId): List<WorkflowVO>；get(String userId,UUID id): WorkflowVO；resume/cancel(String userId,UUID id,long expectedVersion): WorkflowVO；revise(String userId,UUID id,long expectedVersion,WorkflowRequestDTO request): WorkflowVO。WorkflowMapper.selectOwnedForUpdate(String userId,String id): WorkflowEntity。ScheduleOccurrenceMapper.select(String scheduleId,LocalDate dueDate): ScheduleOccurrenceEntity；insert(ScheduleOccurrenceEntity row): int。

- [ ] 写 MyBatisWorkflowTest：同一期执行两次 occurrence 仅一条且复用 action；两次并发相同一期也不重复扣款，唯一键冲突路径恢复原动作。任务 expectedVersion 过期拒绝；旧步骤不能重复增加 savingMinor。
- [ ] 运行 `./run.ps1 test -Tests 'MyBatisWorkflowTest,WorkflowServiceTest,WorkflowRecoveryTest,WorkflowApiTest'`，确认失败。
- [ ] 移动定时与任务 SQL，保留复合键、inputVersion、action_id 唯一、evidence_json。恢复先在短事务外 observe，再在锁住任务且版本重验的短事务中更新步骤及推进。每期预先查询并依靠数据库唯一键保障；冲突先回滚再查询原 occurrence，不在失败事务内继续。新服务临时 bean 名 layeredWorkflowService；旧 WorkflowRequest/WorkflowView 在无 SQL 适配中转换，Task 7 删除旧入口和契约后恢复默认 bean 名。BankService.runDue 暂转换 Task 6 的 VO 以保持逐阶段可编译。
- [ ] 运行上述测试及 BankServiceTest：15 元部分成功、83 元正常路径、取消、版本修改、未知恢复和实际节省口径保持；新建任务不代表转账完成。
- [ ] 提交 `refactor: migrate schedules and workflows to MyBatis`。

## Task 7：Controller / 智能体接线及旧入口清理

**Files:** 建立 controller/SessionController.java、AccountController.java、StatementController.java、MandateController.java、ActionController.java、ScheduleController.java、AuditController.java、SimulationController.java、WorkflowController.java、ChatController.java；建立 common/ApiExceptionHandler.java、dto/ChatRequestDTO.java、SimulationFaultRequestDTO.java、SimulationRunRequestDTO.java、vo/SessionVO.java、ErrorVO.java；建立 agent/BankToolGateway.java；修改 AgentService.java、GroundedAnswer.java、BankReplyComposer.java、BusinessAnswerReview.java 及相关测试；删除 ApiController.java、旧 workflow/WorkflowController.java、workflow/WorkflowService.java、workflow/WorkflowContracts.java、BankService.java、旧 ActionExecutionService.java、ExecutionReceipt.java 和仅用于过渡的 BankJsonConverter（只在全部消费者迁移后删除）。

**Interfaces:** BankToolGateway.execute(String userId,String toolName,JsonNode arguments): Object；声明现有允许工具名单并负责参数解码、DTO/VO 与动态 JSON 兼容，不向模型开放 authorize、submit、取消等新增资金权能。Controller 明确返回 Task 1–6 的 VO；ChatController 保留聊天动态数据边界及 modelRequestLimit 1–12，SessionGuard 的身份和 CSRF 行为保持。

- [ ] 新增 src/test/java/cn/safebank/LayeredApiContractTest.java：固定既有账户/账单/动作/观察/任务 JSON 样例，对比缺失/多余字段、日期和 null；断言非法 CSRF、跨用户请求不访问目标。新 gateway 未接入时对应测试失败。
- [ ] 运行 `./run.ps1 test -Tests 'LayeredApiContractTest,ApiTest,WorkflowApiTest,AgentConversationTest'`，记录失败。
- [ ] 切换 Controller 到业务服务、Agent 到 BankToolGateway；保留工具名称和参数 schema。工具结果转换为现有事实目录接受的 JSON 等价结构，statement stablePrefix 支持新 typed 路径。离线对话也走同一 gateway。将所有原测试改为直接使用新服务；BankTestFixture 保留清库和独立账务检查，不为生产保留旧 BankService。
- [ ] 执行全量 `./run.ps1 test`，以及 `rg -n 'JdbcTemplate|java\.sql\.(Connection|Statement|PreparedStatement)|\bSqlSession\b' src/main/java`（预期无命中）。检查 Controller 不注入 Mapper，Service 不包含 SQL 字面量，生产不再引用旧入口；模型权限、预算、回答核验、事实缓存回归保持。
- [ ] 提交 `refactor: finish layered controllers and remove legacy persistence access`。

## Task 8：兼容验证、文档和本地交付

**Files:** 修改 README.md、docs/开发进度.md、docs/任务索引.md、CODING_STANDARDS.md、AGENTS.md；新增 docs/2026-10-08-mybatis-迁移验收.md、src/test/java/cn/safebank/MyBatisFileDatabaseTest.java；必要时更新 fixtures/ui-workspace-qa.cjs 的测试接线，不改产品视觉。

- [ ] 写 MyBatisFileDatabaseTest：临时文件库以现行 schema/data 初始化并完成授权/合成动作，关闭上下文后用新版 Mapper 再打开，断言余额、payload_hash、分录、任务版本不变。测试目录不能指向 data/ 或已有在线数据库。
- [ ] 运行 `./run.ps1 test -Tests MyBatisFileDatabaseTest`，确认未接通兼容流程的失败，修正运行配置或类型映射；不得通过删除旧数据解决。
- [ ] 执行 `./run.ps1 test`、现有本地 Python evaluator 单元测试和 `./run.ps1 package`。只记录本轮真实结果；默认付费联调仍跳过。完成一次独立分支审查并修复实质问题。
- [ ] 使用原生 UI 回归脚本拦截 /api/chat 测试新版本地服务，验证网页结构、查询依据及确认控件；只读核对原数据库副本的结构/行数/余额。正式替换已记录服务前保留 jar 与数据库备份，停止旧进程后独占文件库启动，禁止新旧进程同时写库。
- [ ] 更新实际类图、数据库冻结/后续增强边界、测试结果和没有付费语义联调的限制；扫描提交文件无密钥，明确 stage 后本地提交 `docs: record MyBatis migration acceptance`。无需公网部署或推送。

## 执行方式与回退

推荐本会话原生执行：我逐阶段实施并运行本地回归，最后一次独立审查；相较每任务新建实施/审查上下文，成本更可控。可选择逐任务子 agent 实施与审查，代价是更多上下文开销。用户确认计划与方式后开始；按 worktree 技能为代码迁移创建隔离分支，保护当前演示。

每个任务结束保留可运行提交，不升级数据库结构，代码可回到上一可运行提交。遇到失败停止该阶段交付并修复，保留当前演示实例。正式切换前备份；回退仅在确认表结构仍兼容时使用旧 jar，不删除数据库、不使用 git reset 丢弃团队改动。

## 计划自检

- 已覆盖表模型、Mapper、业务服务、DTO/VO、接口兼容、事实目录、短事务、跨用户收款、幂等、重启恢复及前端回归。
- 五个 Review Focus 均指定归属任务和具体断言；Mapper/服务方法名在后续任务保持一致。
- 数据库新增约束/索引及红蓝 UI 是独立后续改动，本计划不冒充已经完成它们。
- 版本依据：[MyBatis Starter 3.0.5 官方发布说明](http<LOCAL_PATH> Spring Boot 基线为 3.5.x；实际依赖和测试验证列入 Task 1。
- 本次只编写计划并更新设计决策，未安装依赖、修改运行代码、迁移数据库或调用 DeepSeek。
