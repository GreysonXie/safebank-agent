# 撤销代扣→核算节省→定时储蓄：业务任务图设计

2026-10-14。状态：用户已确认设计，进入实施计划；尚未改变运行行为。基线：团队版v0.7，Java/MyBatis负责银行业务，Python官方LangGraph负责理解、规划与对话。

## 1. 客户任务和范围

用户表达：“取消音乐和视频代扣，把省下的钱每月转到储蓄账户。”系统将其解释为一项具有明确步骤和风险边界的目标，询问尚缺的服务对象、付款/收款对象和日期，展示任务计划。每项代扣撤销与最终定时委托分别取得授权；只有经核验的本任务撤销节省额进入储蓄预览。出现UNKNOWN先核对原动作，不能创建替代交易。

首版只支持此业务链，不开放任意节点、任意代码、并行资金执行或其他跨场景。自然语言选择1—4项代扣；既有手工表单最多10项的契约保持。仅支持本任务起始时有效、周期明确为MONTHLY的授权。季度、年度、未知周期项目解释原因并要求用户重新明确范围，不能静默剔除。若要“省下多少钱就一次性转账”，不能误用定时委托，需要澄清目标。

预期亮点：用户能看到它打算做什么、哪步等待自己、为什么下一步不能继续，以及已有结果如何用于后续操作。

## 2. 已有资产和现有缺口

- `WorkflowService.createIdentified/advance/resume/revise/cancel`持有固定流程、动作ID与版本；`saving`只累计本任务VERIFIED_SUCCESS的撤销项。已有部分成功、未知状态及禁止回滚已受理动作的规则。
- `WorkflowVO/WorkflowStepVO`返回业务状态和下一待确认动作；`WorkflowController`提供查询、恢复、改参及取消接口。
- 网页`app.js`已有储蓄表单、任务卡和恢复入口。
- `AgentCapabilityService.SCOPES`目前只有账单、账户、代扣回执及转账/撤销预览七种scope；Python`graph.py::planning`仅校验最多四项工具列表，不是业务DAG。
- 现有LangGraph只在补参时暂停，不直接等待网页银行授权；授权等待以Java工作流为权威，不强行让一个Python轮长期占着租约或grant。

设计采用“模型提案→双层图校验→Java注册固定业务任务→展示并跟随原工作流”的路径。先让Python能正确提出和解释这一种有限DAG，再扩大节点集合；不把整个Java执行器改写成Python通用调度器。

## 3. 节点及前置依赖

节点数最多8：2项读前置+最多4项撤销+1项核算+1项定时委托。业务步骤数与每轮工具调用上限是不同概念，不扩大原模型预算。

| 节点类型 | 输入 | 必须前置 | 实际语义与权限 |
|---|---|---|---|
| `mandate_lookup` | 无用户身份参数 | 无 | 获取当前会话有效、已确认的本人代扣快照；无回执暂停并引导确认查询 |
| `account_query` | 无用户身份参数 | 无 | 查询本人账户，辅助澄清付款与储蓄目标；只读 |
| `mandate_revoke` | `merchantQuery`，不接受模型自填授权/状态 | `mandate_lookup` | Java唯一匹配有效月付授权并准备原工作流动作；逐项确认后方可执行 |
| `compute_savings` | 不允许输入金额 | 全部撤销节点 | 等所有相关撤销终态已知，按成功项计算；失败与取消明确列出，UNKNOWN阻断 |
| `schedule_create` | `sourceQuery`,`recipientQuery`,`firstDate`,`endDate`,`dayOfMonth` | `compute_savings`与`account_query` | 核算值只由Java填入定时预览；确认及需要的强验证后登记委托，不能报本期已转账 |

`compute_savings`的聚合策略固定为`verified_success_subset`：这是“所有上游终态已知后，仅汇总已核验成功项”，不是任意上游失败便忽略，更不是all-success依赖。用户最初须确认只按成功项继续；明确要求“两项都撤销才存钱”时，本版应澄清或告知不支持该策略，不能偷偷降级。全部撤销失败/取消或节省为0时停止，不建立0金额委托。

与原工作流衔接：每个写步骤绑定一个现有actionId；资金执行、授权风险计算、回执核验都走原路径。撤销按稳定拓扑序串行推进，首版不引入并发执行。

## 4. 提案格式及校验

新增规划分支`kind=savings_task`，原analysis/general/clarification契约继续可用。示例（仅意图，未授权未执行）：

```json
{
  "kind": "savings_task",
  "taskPlan": {
    "schemaVersion": 1,
    "goal": "撤销选定月付代扣并按核验节省额定时储蓄",
    "savingsPolicy": "verified_success_subset",
    "nodes": [
      {"id":"m","type":"mandate_lookup","params":{},"dependsOn":[]},
      {"id":"a","type":"account_query","params":{},"dependsOn":[]},
      {"id":"r1","type":"mandate_revoke","params":{"merchantQuery":"音乐服务"},"dependsOn":["m"]},
      {"id":"r2","type":"mandate_revoke","params":{"merchantQuery":"视频服务"},"dependsOn":["m"]},
      {"id":"s","type":"compute_savings","params":{},"dependsOn":["r1","r2"]},
      {"id":"t","type":"schedule_create","params":{"sourceQuery":"日常账户","recipientQuery":"储蓄账户","firstDate":"2026-11-20","endDate":"2027-10-20","dayOfMonth":20},"dependsOn":["s","a"]}
    ]
  }
}
```

Python首先验证结构、字段、类型、节点数、唯一ID、依赖存在和无环；Java再次独立校验同一图合同及业务条件，不能只信Python已验证。首版仅接受上表模板关系：两项读节点、1—4项撤销、一个汇总、一个委托；每个节点必须被最终委托依赖闭包覆盖。禁止无关节点、重复服务选择、循环、自依赖、重复依赖、漏掉任一撤销项及额外执行分支。

拒绝未知字段：`userId`、`amountMinor`、`authorized`、`status`、`receiptId`、模型指定`actionId`等均不能出现在提案。节点ID只允许短ASCII标识，不与数据库身份混用。字符串和整个图设长度上限，防超大输入；具体上限在实施计划和合同测试中固定。

缺参或歧义→追问缺失项，保存尚未注册的规划草稿；在完整对象/日期/策略核对前不创建撤销动作。不是把半个图塞进原必填`WorkflowRequestDTO`。模型生成的日期只作为意图，Java沿用Clock与最长一年、每月1—28日、首次不得过去等检查。

接口衔接补充：原`WorkflowRequestDTO.month`为账单分析月份，不是委托首次日期。提案可选`analysisMonth`；明确指定时校验yyyy-MM，否则Java按注入Clock的Asia/Shanghai当前月份填入并在计划卡显示。模型不能自行把首次日期月份当作账单月份。该默认不决定储蓄金额，节省仍只来自核验撤销结果。

授权校验分两步：提案注册无需“已经授权”，否则根本无法展示计划；写步骤到执行门槛时必须已有有效逐动作授权及必要验证。代扣查询仍需查询确认，不因为它出现在图中变成自动绿灯。

## 5. Java接入与状态所有权

拟新增受控scope `savings_task_prepare`，它只注册任务并产生待确认预览，不能授权或执行；它与现有银行资金授权不同。不得通过旧`start_savings_workflow`协议绕过新网关。

新`SavingsTaskPlanService`独立校验提案，借助本人账户/收款人和已确认代扣快照唯一匹配，构造原`WorkflowRequestDTO`，再调用`WorkflowService.createIdentified`。不在Service直接写SQL，不在模型层注入Mapper。不在网络/模型调用期间保持数据库事务。

幂等ID由Java基于原turn和受控operationKey派生并保存，不能由模型指定。相同键相同输入返回原任务；相同键不同输入拒绝。新scope必须进入`AgentCapabilityService`输入校验、结果回执与事实目录路径；不直接调用公开HTTP接口再伪造内部证据。

Python checkpoint仅保存提案、澄清槽位与已有workflowId投影，不存service key或原grant。Java工作流和action终态是唯一业务权威。模型轮结束即可释放运行资源，后续网页授权或对话查询读取原workflow，不能假定旧grant仍有效。

计划版本与workflow版本关联；网页改参沿用原`revise`，取消尚未受理动作并产生新待确认卡。后续对话若要求改已注册任务，首版引导对应任务卡处理，不猜新建另一个任务。自然语言修改既有图作为独立后续切片，当前明确不宣称已支持。

## 6. 步骤展示与恢复

拟增加`GET /api/workflows/{id}/plan`，返回类型化的计划视图：版本、节点ID/类型/依赖、展示状态、原actionId及必要错误码。它是现有工作流的投影，不另存一套动作终态。原`WorkflowVO`接口兼容，前端储蓄任务卡增加步骤清单，不改变左导航/中央业务/右对话布局。

展示状态可分`READY`、`AWAITING_CONFIRMATION`、`PROCESSING`、`VERIFIED_SUCCESS`、`VERIFIED_FAILED`、`CANCELLED`、`BLOCKED_UNKNOWN`、`WAITING_DEPENDENCY`；必须明确这是计划展示状态，与银行action状态有映射而非完全相同的枚举。登记任务完成仅表示计划注册成功，创建预览不表示撤销成功，定时委托生效不表示未来各期完成。

重启后以已有workflowId读取任务和动作，重建展示投影；遇到UNKNOWN查询原actionId，结果明确后走原resume，不重复创建资金动作。计划中的已完成步骤保持其核验结果，修改仅作用于未受理部分。并发客户端旧版本提交被拒，用户刷新后继续。

## 7. 验收矩阵

| 编号 | 输入/故障 | 必须观察到的结果 |
|---|---|---|
| T01 | 两项月付服务，账户日期明确 | 图有两条撤销分支及正确汇总依赖；完成前无授权、无扣款 |
| T02 | 服务重名、缺目标账户、缺首次日期 | 只追问相关缺失项；无新撤销动作、无猜测ID或日期 |
| T03 | 没有当前确认的代扣回执 | 引导确认查询；不能泄露名单或准备撤销 |
| T04 | 节点环、悬空依赖、隐藏写节点、手填金额/成功 | Python和Java分别拒绝，返回可辨识错误；数据库无动作新增 |
| T05 | 选中季度/年付/周期未知项目 | 说明不适用并澄清范围；不静默计算月度节省 |
| T06 | A成功B明确失败 | 在用户明确的部分成功策略下，Java仅汇总A并展示B失败；最终委托金额精确为A |
| T07 | A已成功B为UNKNOWN | 汇总和委托阻断，查B原actionId；无重复执行、无成功措辞 |
| T08 | 全部失败或取消 | 停止，节省0，不生成定时委托 |
| T09 | 修改日期/账户及旧版本确认 | 未受理卡失效，新版本卡须再授权；已受理/UNKNOWN拒绝改参 |
| T10 | 请求重放、网络丢回执、进程重启 | 返回同workflowId/actionId；账务与模型已保存副作用不重复 |
| T11 | 分两轮确认撤销，然后确认定时预览 | 各动作分别授权；适用红灯时不能绕过强验证；委托登记不报到账 |
| T12 | 任意商户文本含注入、索取验证码或冒充授权 | 文本作为不可信数据，不改图/授权；拒绝收集聊天凭据 |
| T13 | 客户只要解释费用、一次性转存或不同部分失败策略 | 不误创建该定时工作流；解释或追问实际意图 |
| T14 | 对话查询任务进度 | 读取原工作流快照、正确说明等待节点；不能把模型旧草稿当银行终态 |

先通过图合同和Java固定工作流免费测试，再做隔离MySQL恢复验证和网页交互检查。最后仅在已授权预算内小批量真实模型验证T01/T02/T13及一条连续追问链；mock不充当自然语言规划质量证据。

## 8. 数据与实施切片建议

复用既有workflow/request/steps、agent run/events、tool/effect receipts和checkpoint，不新建资金或授权表。开始时先确定可由固定模板完全重建的计划视图；如果恢复必须保存非模板信息，再单独提出迁移设计，禁止偷偷更改现有migration校验和。

拟议切片，每片有独立交付与回归：

1. Python/Java有限图合同及非法图测试；不改变现有对话运行。
2. Java提案准备服务、原工作流幂等注册、计划视图；用mock参数验T03/T05—T11。
3. Python自然语言规划分支、缺参暂停、受控scope/证据整合；已有三场景保持兼容。
4. 网页步骤展示、查询确认、原动作恢复与改参说明；免费端到端检查。
5. 小批量真实语义验收和版本文档同步，按预算停止。

此稿批准后再写逐文件实施计划并按顺序执行；不会在本稿尚未审核时改生产行为或调用收费接口。
