# Savings Task Plan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for native implementation, or superpowers:subagent-driven-development if the user selects delegation. Execute task-by-task after plan review.

**Goal:** 从自然语言提出有限储蓄任务图，复用Java原工作流、逐动作授权和UNKNOWN查单，网页展示业务步骤。

**Architecture:** Python提出并初检业务DAG；Java独立校验、匹配归属与注册原Workflow，持有授权和动作终态。计划展示从原工作流及回执重建，不新增银行状态存储。

**Tech Stack:** 当前Java21/Spring Boot3.5/MyBatis、Python3.12/LangGraph、MySQL8.4与原生网页；不引入ruoyi、Redis、Flowable或MyBatis-Plus。

**Spec:** ../specs/2026-10-14-savings-task-plan-design.md（已获用户确认）。用户已批准实施计划并选择本会话连续顺序实施；不在任务间重复询问确认。

## Global Constraints

- 1—4项月付代扣，最多8节点；仅支持verified_success_subset聚合策略；只允许五类指定节点。
- 查询代扣须有效确认快照；写步骤到执行时逐项授权，模型不填金额、身份、授权或成功状态。
- UNKNOWN等待原动作核验；已受理步骤不能改参或重建；已完成动作不能靠任务取消回滚。
- Java Clock与整数分、MyBatis层次、原授权payload/hash和请求幂等不变；不改现有迁移校验和，不操作原运行库。
- 新增scope只准备任务；一轮最多一个写预览/任务注册，不与转账或撤销预览混调。节点数、工具次数、模型发送次数分开限制。
- 每项真实发送仍先预留预算；本计划免费验证先行，付费联调遵循用户预算。不因实施自动上传GitHub或重启原服务。

## Review Focus

1. 商户名含未知附加词、同名两条或选中非月付服务：追问/拒绝，不能任意挑一个。
2. 用户只问“取消有什么影响”、一次性转存或要求全部撤销才存：不能误注册部分成功定时任务。
3. 同会话已存在任务，用户改口或重复请求：读原任务，拒绝旧版本；不能产生第二条资金链。
4. 进程重启时用户恰好完成授权或银行响应丢失：从原workflow/action查单，投影不声称成功。
5. 用户日期跨月、已过去、每月29日或月底：使用Java Clock规则，不以模型猜测替代校验。

## Task 1：双层有限图合同（独立可测，不改运行）

**Files:** Create `agent-service/safebank_agent/task_plan.py`, `agent-service/tests/test_task_plan.py`, `src/main/java/cn/safebank/common/TaskNodeType.java`, `src/main/java/cn/safebank/dto/TaskNodeProposalDTO.java`, `src/main/java/cn/safebank/dto/SavingsTaskPlanDTO.java`, `src/main/java/cn/safebank/service/SavingsTaskPlanValidator.java`, `src/test/java/cn/safebank/SavingsTaskPlanValidatorTest.java`.

**Interfaces:** Python `validate_task_plan(value: dict) -> dict`返回校验后提案，失败ProtocolError。Java记录字段`TaskNodeProposalDTO(id,type,params,dependsOn)`，params仅动态协议边界JsonNode；`SavingsTaskPlanDTO(schemaVersion,goal,savingsPolicy,analysisMonth,nodes)`；`SavingsTaskPlanValidator.validate(JsonNode proposal) -> SavingsTaskPlanDTO`拒绝额外字段后转换类型。未指定analysisMonth允许省略或null。

- [ ] 写`rejects_cycle_dangling_hidden_write_or_amount`、`requires_all_revocations_in_savings_dependencies`、`rejects_duplicate_service_and_unreachable_node`；Python和Java各自断言拒绝同一fixture变体。
- [ ] 写合法1、2、4项及超限5项用例，断言只有五类节点及正确依赖；允许尚未授权的提案存在。
- [ ] 运行两组新测试，确认缺实现导致失败，保留RED证据。
- [ ] 实现ID正则`[A-Za-z][A-Za-z0-9_-]{0,31}`、对象字符串1—100字符、goal1—200字符、图UTF-8序列化上限16384字节；全字段白名单与稳定拓扑排序，识别唯一终节点和闭包。params不能含userId/actionId/amountMinor/status/authorized。
- [ ] 运行`python -m unittest tests.test_task_plan -v`与`./run.ps1 test -Tests SavingsTaskPlanValidatorTest`，全部通过才进Task2。

## Task 2：Java任务注册与权威计划视图

**Files:** Create `src/main/java/cn/safebank/service/SavingsTaskPlanService.java`, `src/main/java/cn/safebank/service/WorkflowPlanViewService.java`, `src/main/java/cn/safebank/vo/TaskPlanVO.java`, `src/main/java/cn/safebank/vo/TaskPlanNodeVO.java`, `src/test/java/cn/safebank/SavingsTaskPlanServiceTest.java`, `src/test/java/cn/safebank/WorkflowPlanViewTest.java`; modify `WorkflowController.java`.

**Interfaces:** `SavingsTaskPlanService.prepare(String user,String conversationId,String turnId,String operationKey,SavingsTaskPlanDTO proposal) -> Object`仅边界可返回明确notice或`WorkflowVO`。`WorkflowPlanViewService.get(String user,UUID workflowId) -> TaskPlanVO`，VO包含workflowId/version/state/savingsPolicy/analysisMonth/nodes，节点含id/type/dependsOn/displayState/actionId/errorCode。新增GET `/api/workflows/{id}/plan`，身份从HttpSession取。

- [ ] 失败用例：无查询回执/已过期回执/重名/非月付/账户不唯一/过去日期；断言无新workflow/actions/ledger，不静默丢弃服务。
- [ ] 幂等用例：UUID.nameUUIDFromBytes对Java受理的turn+operationKey UTF-8稳定派生workflowId；同键同提案返回原任务，同键改参拒绝。既有gateway receipt负责输入摘要，不允许模型指定id。
- [ ] 实现唯一匹配与DTO编译；analysisMonth缺省取Clock上海年月；source与recipient沿用现有账户/收款人服务；调用`WorkflowService.createIdentified`，不写第二套SQL/授权。
- [ ] 编写投影测试：两撤销节点分支、成功/失败/取消、UNKNOWN阻断、零节省不创建委托；断言金额只取原WorkflowVO。节点id用Java固定m/a/r1..r4/s/t，按原请求顺序绑定，版本变更后重建。
- [ ] 现有存储无需保留模型节点ID；原请求+workflow_steps完全重建固定拓扑。新增迁移应为0。如无法重建，停止并先修改数据库设计，不能偷加列。
- [ ] 运行新测试及`WorkflowServiceTest,WorkflowRecoveryTest,WorkflowApiTest,MyBatisWorkflowTest`；T03/T05—T11和Review Focus1/4/5通过。

## Task 3：受控scope、事实目录与网页响应

**Files:** Modify `AgentCapabilityService.java`, `GatewayResultService.java`, `GroundedAnswer.java`, `BankReplyComposer.java`；create `src/test/java/cn/safebank/SavingsTaskGatewayTest.java`，必要扩充原AgentGatewayTransportTest。

**Interfaces:** 新scope `savings_task_prepare`，args恰为`{taskPlan:<proposal>}`；只读scope `savings_task_status`，args恰为`{workflowId:<UUID>}`，由Task2的get(user,workflowId)核验归属后读取权威快照；两个scope结果经原receipt/hash/observedAt保存。事实类别`agent_savings_task`提供服务器校验的任务注册、待确认动作和必要计划摘要；进度查询事实使用`agent_savings_task_status`并带观察时点。`GatewayResultService`响应附`data.workflow`及原pendingAction，使既有`app.js`刷新任务列表。

- [ ] 写scope非法身份/字段/无grant/重复operationKey/同轮混合两个写准备测试，断言拒绝且无额外动作。
- [ ] 写注册丢ACK后重放测试，断言原workflowId和actionId不变；补原pendingActions只读投影，不能创建授权。
- [ ] 接入Task2服务到原grant短事务；将新scope纳入strict checkInput、evidence、receipt view及唯一写准备约束。检查Java每轮8次receipt及Python最多4次工具调用两种限制兼容。
- [ ] 事实核验只允许“任务已登记/待确认”表述，拒绝“已撤销/已到账/已完成未来转账”；不得把预览回执加入动作成功证据。
- [ ] 运行`SavingsTaskGatewayTest,AgentGatewayTransportTest,GroundedAnswerTest`，T04/T10/T11/T12通过。

## Task 4：Python规划、缺参暂停与任务进度追问

**Files:** Modify `agent-service/safebank_agent/graph.py`, `prompts.py`, `runtime.py`, `models.py`；create `agent-service/tests/test_savings_task_planning.py`；modify `agent-service/tests/test_graph_runtime.py`；查询现任务使用Task3的savings_task_status只读合同。

**Interfaces:** `planning`增加`kind=savings_task`，只接受Task1图合同。tools节点把该图编译成最多三项调用（mandate_receipt/account_query/savings_task_prepare），写准备只在前置返回可用时运行。两项前置可只在prepare内重新核验，但不能重复读回执后扩大权限。checkpoint保存提案/槽位/workflowId，原answer格式仍仅kind/answer。

- [ ] 写缺日期/服务/目标账户以及仅咨询/一次性转存/全成功策略测试：缺参kind=clarification，实际新workflow计数为0；不生成默认资金意图。
- [ ] 先离线验证足够的JSON输出长度；新规划分支max_tokens最多1200，原请求预算不变。不把原450token限制硬套8节点图，不增加第二个无上限规划循环。
- [ ] PLAN描述五类白名单、部分成功策略与授权边界；ANSWER只引用Java新事实。新分支不得改旧三场景协议。
- [ ] 缺有效查询回执时转查询确认提示，本轮不继续写准备；已有任务追问从Java读取原版本（不能用checkpoint旧草稿报终态）；改参引导任务卡。
- [ ] 扩免费mock限定fixture并标明mock，不写正则“智能”代替真实模型验收。增加同会话补参、保存结果重放、重启恢复测试；无模型结果的UNKNOWN不得重发。
- [ ] 运行新测试与原captured_protocol/live_contract_recovery/answer_contract/preview_planning/credential_output/transfer_safety，覆盖T01/T02/T13/T14及Review Focus2/3。

## Task 5：网页步骤清单、全链路验收和材料同步

**Files:** Modify `src/main/resources/static/app.js`,`src/main/resources/static/styles.css`；create `docs/2026-10-14-savings-task-plan-验收.md`；更新当前PRD、团队状态及competition架构/算法/安全自评。网页自动检查脚本按现有测试工具位置新增，不复制凭据或原运行库。

- [ ] 在原储蓄卡加载typed plan视图，展示依赖、待确认/未知及失败项；更新节点状态只取Java快照。完成历史默认折叠，当前待确认突出；沿用既定三栏及移动布局。
- [ ] 免费浏览器验证：查询确认→两项撤销分别授权→汇总→定时卡授权；模拟失败/丢回执→查原单→继续，刷新/重启不重复资金动作；同时检查旧三场景和窄屏。
- [ ] 隔离MySQL验证至少一次进程重启恢复，记录跳过/未验证项；没有Docker或独立库时明确停止条件，不以H2代替MySQL结论。
- [ ] 在用户实际预算内做T01/T02/T13和连续追问小批量真模型验收；发送数、用量和失败记录分开，边界失败即停相关路径修复，非全部通过不宣传完成。
- [ ] 运行相关Java/Python检查及Docker mock入口；保持原资金、授权、执行观察与余额断言。更新验收/PRD后交付具体试用步骤，未经后续发布指令不自动同步GitHub。

## 自审与交接

设计八节和T01—T14已分别映射上述五任务；依赖为1→2→3→4→5，生产实施不适合并行改共享合同。推荐本会话由主实现者逐项执行，完成后独立审查；不需要每项新建实现agent。规划必须先审核，执行方式由用户选择。当前仅完成计划，所有实现复选框仍为空。
