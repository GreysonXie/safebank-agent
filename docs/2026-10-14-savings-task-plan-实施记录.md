# 储蓄任务图实施记录

2026-10-14。用户已批准设计、实施计划并指示执行，采用本会话顺序实现。无需在任务之间重复请求确认。

## 当前进度

事实目录增量完成：新增纯SavingsTaskFactProjector，仅同份Java回执快照，提供当前服务/动作/状态说明、计划日期、核验月度扣费、最新版本步骤/进度；不增加权限/表/实时查询，不暴露user/action/payload hash。60项Java、49项Python及10项真实隔离MySQL通过，末次费用角色提示投影3项复跑通过。两条DeepSeek具体服务/费用及日期回答各1次通过实际Java，新增上界0.037348、累计5.188808/5.50。候选8136/8137更新保持mock；charge_scope术语最后微调未另付费复测，原回答留存。详见2026-10-14-任务事实目录扩充.md。

真实回答+Java核验增量已收尾：登记说明误拦截、否定措辞误判、分析月被说成储蓄周期均定位并修复，57项Java/49项Python通过。最新登记、UNKNOWN和未来到账边界真实复测通过，原回答免费回放，失败/引述误判留出仍保留。本轮12发送，上界0.185440元，累计5.151460/5.50；只调用模型与离线Java oracle、Java只读快照，未调用银行工具。8136 Java和8137 Python已更新，任务库/原8134保留且仍mock。见2026-10-14-储蓄任务真实回答与Java核验.md，不冒称一次完整银行联网端到端验收。

快速多批复测：前三批9例/9发送，8例通过，发现仅缺sourceQuery仍生成空源计划；首次有界修正专项2发送又发现把旅游基金复制为源。加入missing_task_fields反馈及同轮只能clarification、禁止自补后，最终两条真实专项各1发送通过（只问源、补日常账户承接完整任务及2026-09）。49项免费回归通过，候选Python更新保持mock。总13发送，上界0.141356元，累计4.966020/5.50；余额前置握手失败0发送/0预留原记录保留，无银行工具调用。各失败/修复边界见2026-10-14-储蓄图多批真实复测.md，不重复消耗全套付费测试。

真实规划增量：用户明确追加最多0.50元，累计护栏5.50，本轮限定6次规划请求。实际6次均取得usage，上界0.059856元、累计含历史预留4.824664元；未调用银行工具。业务人工复核4/6，缺参漏问付款账户、补参遗漏2026-09账单月，未按合同5/6冒充业务通过。两真实图额外通过生产Java validator离线检查，确认结构合法仍可丢语义。新增显式月防遗漏/错月回归RED→GREEN，44项相关免费检查通过；补问提示加强，真实修复未新增复测（6次已用尽）。候选Python更新保持mock；明细见2026-10-14-储蓄图真实规划验收.md和evidence派生review，原响应/旧账本不改。

最新落地：正常/UNKNOWN查原单/改参旧确认失效的真实Edge批2项通过；配置回调新增仅允许8136回环候选，先观察拒绝8136的RED再修改，39项相关回归通过。已启动8136 Java/8137 Python免费候选，独立新合成MySQL库、DML受限账号、独立服务身份/预算文件，原8134/原库保留。实际Python服务在真实Edge中完成查询确认与聊天登记一项六节点任务，截图回读，留待用户逐项确认，未执行撤销/转账。本机忽略目录.superpowers/savings-candidate内保存身份/日志/进程登记，不提交任何密码。免费启动且两处API key显式为空，未消费DeepSeek。真实模型规划仍受原预算与预留规则约束，尚未验收。

网页操作批次已通过：真实Edge手机页面逐次勾选并提交两撤销+一定时委托，MySqlSavingsBrowserTest 1项通过，0失败/错误/跳过。数据库授权+3、委托+1、账务不变，最终核验8300分；刷新无重复提交、历史仍默认折叠，无脚本错误/横向溢出。截图回读完成。第一轮测试表名误写transfer_schedules已修正为现有schedules，未改生产表。本轮跨服务4项、浏览器1项和离线37项是不同验证层，均为0付费调用；未切换8134/上传GitHub。正常链关闭，剩余网页异常路径及真实模型规划见验收。

并发增量批次：MySqlSavingsCrossServiceTest最终4项通过，0失败/错误/跳过。并发真实HTTP相同key重放同一workflow、不同key同轮只有一个成功(PREVIEW_LIMIT)，没有新增授权或账务。下一批直接验证真实Edge页面三项授权/提交及刷新无重提；没有逐项申请继续或增加付费预算。

新跨服务批次：MySqlSavingsCrossServiceTest首批2项、加入真实离线MockModel后的第二批3项通过，0失败/错误/跳过。Java随机端口+合成MySQL，Python子进程独立随机MySQL，通过真实BankClient HTTP调用Java工具与最终回答核验/保存。登记后本地回执未保存的模拟崩溃恢复重用operationKey，Java重放原workflow，未授权、未记账；未确认查询则停止；公开mock演示句实际走新scope。Windows子进程stdin编码不一致曾使fixture失败，改为显式UTF-8后通过。新增mock四项及原33项合计37项通过。没有DeepSeek/iFinD消费或部署/上传。

Ruling: 免费mock采用公开JSON中的完整固定示例句精确匹配，不扩写自然语言正则规划器——目的是让队友验证新链路而不把规则命中当成模型能力，代价为mock仅支持已注明演示输入；真实规划仍需独立模型验收。

最新恢复批次：新增test_savings_task_runtime.py，在真实MySQL/LangGraph检查点上使用固定模型/银行替身，6项通过（含结构检查）。复现并修复创建任务后澄清interrupt恢复丢失previous_workflow_id的问题：wait现在传递已核验workflow_id，避免下一轮规划失去原任务关联。其他覆盖为结果落库前崩溃后不重复prepare/模型调用、丢回执同operationKey恢复、重启status、拒绝回执不记忆编号。33项相关离线规划/安全回归通过。没有新增付费调用、没有替换8134演示服务或上传GitHub。以下较早“检查点尚未验证”的状态由本条限定范围更新，跨服务HTTP故障链和真实模型验收仍待完成。

最新验证：本机MySQL服务恢复后，在随机safebank_v07_test_*库运行MySqlAgentPreviewTest，10项通过（含储蓄scope注册/重放、三项逐次授权和8300分定时委托）。Edge真实浏览器＋隔离MySQL应用验收1项通过，检查折叠记录、六节点、确认状态、手机无溢出及无脚本错误；截图目视检查。随后将UI依赖从内部m/r/s标识改成业务步骤名称，另复跑浏览器验证。详见阶段验收文档。没有新增付费调用或替换原应用版本。

最终业务名称版浏览器复跑已通过（1项，0失败/错误）。现阶段下一工作为Python新任务免费mock与检查点恢复/异常链验证，以及预算允许的真实自然语言验收；不再停留于重复单元检查。MySQL以受管session保持回环3307运行，测试Web随机端口上下文均关闭；原8134应用未替换。

后续接入批次：新增savings_task_prepare/savings_task_status受控scope、回执事实目录与任务卡响应；Python支持有限任务图规划分支、33项相关离线回归通过，任务编号仅取成功银行回执并在checkpoint中记忆；同会话已有未完成任务时拒绝重复注册。新scope与GroundedAnswer先54项通过，准备服务纳入后59项通过；补同会话UNKNOWN阻断测试后prepare服务6项通过。日志为task-gateway-final.log、task-integration-current.log、task-duplicate-guard.log。

网页新增折叠操作记录、按需读取完整步骤和依赖、版本冲突提示；node --check通过。尚无浏览器渲染/点击验收，不声称网页已验收或已替换用户运行版本。新代码尚未做隔离MySQL真实网关及重启联调、真实模型规划；mock未增加自然语言储蓄fixture。原运行服务及GitHub快照保持。

独立审查发现计划视图按旧版动作取findFirst的缺陷，新增回归复现expected new but was old，改为选择同业务步骤最高inputVersion，使旧CANCELLED不覆盖新AWAITING_AUTH/UNKNOWN。修复回归日志task-view-revision-red/green.log；检查结果另记。

修复结果：WorkflowPlanViewTest两项回归通过，0失败0错误，BUILD SUCCESS。已修复审查报告中的重要投影问题，仍保留MySQL并发/重启和网页实际点击未验收边界。

Ruling: 新任务首轮仅发一个savings_task_prepare调用，由Java内部重新校验查询确认及归属，不重复把完整代扣目录送模型。此scope不能授权/执行；原每轮模型次数/预算不变，plan输出上限从450提高到1200以容纳8节点JSON（上限不是实际计费用量）。未消费模型额度。

下一步：完成修复回归后，做隔离MySQL网关/恢复及浏览器QA，再在预算内有限真模型验证，不能将目前单元/合同测试替代端到端验收。

本轮连续推进更新：Task1新增Java合法1/4项、超限5项及未知字段/布尔版本检查；Task2新增SavingsTaskPlanService、WorkflowPlanViewService和typed VO，已提供GET /api/workflows/{id}/plan。注册仍受现有代扣确认回执约束，未连接Python工具scope，不能宣称聊天已可创建任务图。

27项相关Java回归通过（validator5、prepare3、projection1、原workflow18），0失败0错误，BUILD SUCCESS；其后新增过期回执/歧义账户和步骤API归属断言，另批验证。Python上一批3测试方法通过。Task3/4/5的受控scope、对话与网页展示仍待实施，原运行服务未替换。

补充批次结果：SavingsTaskPlanServiceTest和WorkflowApiTest合计7项通过，0失败0错误，BUILD SUCCESS（task-plan-batch3-api.log）。有效回执过期阻断、账户歧义无副作用、本人计划视图和他人越权拒绝均通过。本轮尚未完成全部任务图功能，不再申请实现授权，继续从Task3受控scope与事实目录开始。

Ruling: 步骤视图的读前置显示VALIDATED，表示创建原工作流时业务条件已校验，不伪装成银行查询动作成功回执。UNKNOWN来自原步骤及当前pendingAction观察，阻断汇总及委托。

环境记录：首轮沙箱禁止Java HTTP客户端回环连接，不能当作业务回归失败；权限自动审核第一次超时，获准重试后本地免费检查通过。仅测试使用临时库与空API key，未访问付费供应商；失败/成功日志分别task-plan-batch2.log和task-plan-batch2-local.log。

以下为前一批历史进度，以上更新优先：

- Task1：Python及Java独立图校验核心已实现；新增五类节点合同与共同合成fixture。尚需扩充Java节点数量/额外字段组合测试再关闭完整Task1。
- Task2—5：未实现。下一个步骤：补Java合同边界测试，然后Java任务注册及计划视图；继续按已批准计划执行。
- 未接入网页运行、未修改资金执行、未新增数据库表、未上传本轮源码、未调用付费模型。

## 验证

Python：先观察缺少safebank_agent.task_plan导致的失败，再实现。3个测试方法通过，覆盖9种非法图变体、合法1/2/4项及5项越界。Java：先观察新validator类未实现造成的编译失败，再实现；3项测试通过，0失败0错误，BUILD SUCCESS。不能将以上宣称为完整端到端任务图已完成。

Ruling: 复用现有隔离feature/v0.7-python-langgraph-mysql工作目录，不创建第二个工作树；保留其中既有用户更改。

Ruling: 本机用户身份变化导致Git dubious ownership，使run.ps1无法发现共享Maven。仅本次命令使用GIT_CONFIG_COUNT及safe.directory准确工作树路径，不修改全局Git配置；继续使用原Java21、共享Maven/.m2，不另安装。

测试日志（本地忽略路径）：.superpowers/task-plan-java-red.log、task-plan-java-green.log；Python缺模块失败与GREEN输出在本轮工具记录。实施计划：docs/superpowers/plans/2026-10-14-savings-task-plan-implementation.md。


2026-10-15收尾更新：Java66项（0跳过）、Python57项（首轮配置错误补齐后单项复核）及2条真实DeepSeek回答已按选定范围关闭；预算累计上界5.227300/5.50元。未覆盖项和本地增量未发布状态见[合并收尾验收](2026-10-15-合并收尾验收.md)，旧段落按其日期阅读。
