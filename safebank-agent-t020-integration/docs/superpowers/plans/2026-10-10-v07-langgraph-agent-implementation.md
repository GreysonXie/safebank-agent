# SafeBank M3 LangGraph Agent Implementation Plan

> **For agentic workers:** 使用 superpowers:executing-plans 在当前隔离worktree内执行，任务RED/GREEN记录在本机账本；最终一次独立审查。

**Goal:** 正式接入官方LangGraph和MySQL检查点，复用Java只读网关、上下文与事实核验，完成有界规划/回答和恢复，避免模型重放重复收费。

**Architecture:** Python负责理解、规划和对话图，Java负责身份、银行事实、唯一版本、ACK及共享预算。Python应用表与checkpointer独立于bank库；每个网络副作用以稳定effect键记录。原网页入口字段和三栏布局保持。

**Tech Stack:** 原Java21/Boot3.5.6/MyBatis3.0.5/MySQL8.4.11；Python3.12.14与M0锁定LangGraph1.2.12/PyMySQLSaver3.0.0；stdlib HTTP和已有httpx，不新增未经验证的异步checkpointer。

**Spec:** ../specs/2026-10-10-python-langgraph-mysql-design.md；../../database/2026-10-10-langgraph-agent-runtime-design.md。

## Global Constraints

- 保留M1/M2分支、程序包和数据；M3使用8134/8135、独立bank/app库，MySQL文件仍在D盘；交付仅一个Java网页服务。
- 单binding一个活跃run；60秒lease、10秒heartbeat、revision CAS。HTTP不持DB事务，Python自身每30秒扫描100条到期run。
- API/body≤64KiB；银行事实回调≤256KiB；模型输出≤2048tokens、HTTP≤30秒、无自动重试。工具每轮8次由Java权威，模型每Java轮5次与共享20由Java权威。
- 共享真实预算继承11/20；自动测试/mock不占额度；阶段付费联调最多2次HTTP，无额外模型审核发送。密钥不进入代码、数据库、checkpoint、网页或日志。
- 只有JavaACK才能填outputVersion；同hash回原ACK，冲突暂停。恢复模型SENDING为UNKNOWN，无自动再发。
- 澄清interrupt节点没有模型或银行调用；补参新Java轮按上轮ACK/interrupt/归属/版本恢复，Javareset使旧等待失效。
- 只读Agent不获得资金工具；旧资金操作仍经Java逐项授权。模拟身份/银行、单Python实例checkpoint写入边界和生产TLS局限如实记录。

## Review Focus

1. 租约丢失与响应晚到：旧worker不得写结果或继续发送；未知模型effect不能变成安全重试。
2. 崩溃发生在结果已保存而checkpoint未保存：复用effect，无新预算/网络发送。
3. 澄清后新turn/旧ACK/用户reset：业务版本只能由Java推进，不能拿schemaVersion恢复旧等待。
4. 预算预留响应丢失/重复record：原reservation复用、不同hash冲突、不退款、不额外发送。
5. 商户文本提示注入/金额手写/不完整输出：不取得工具/身份，核验拒绝或有界修正，不能签发资金结论。

### Task 1: Python运行存储与effect

**Files:** agent-service/requirements.lock.txt；safebank_agent/config.py、store.py、effects.py、contracts.py；migrations/001_runtime.sql；tests/test_store_effects.py。

**Interfaces:** Store.accept(可信请求)→run；claim/heartbeat/CASResult/ack；Effects.call(run,node,sequence,purpose,input,operation)→保存的输出；模型UNKNOWN严格拒绝。

- [ ] 写失败回归：应用表/模块缺失，实际MySQL运行/lease/ACK/key合同RED。
- [ ] 按先行DDL建立五表/迁移；原生Saver独立setup；实现store、租约和effect，无网络长事务；实际MySQLGREEN。
- [ ] 验证不同进程结果复用、SENDING恢复无重发、同key错hash、丢lease写入拒绝；提交。

### Task 2: Java幂等预算与正式协议

**Files:** model/DebugRequestBudget.java；dto/AgentBudget*.java；service/AgentBudgetService.java；controller/InternalAgentController；agent/HttpAgentClient、GatewayCoordinator；service/GatewayReconciler、GatewayResultService；application-langgraph-agent.properties；tests/AgentBudgetIdempotencyTest.java。

**Interfaces:** reserve(operationKey,inputHash,javaTurnId,purpose)回原id与额度；record绑定原预留；Java→Python start含可信contextState/版本，恢复传新grant；ACK允许已确认NEEDS_CLARIFICATION。

- [ ] RED：原11次账本不丢失、samekey复用、wronghash/不同turn拒绝、每轮第6次/总第21次不发送、重复record。
- [ ] 实现预算严格内部身份+归属接口、原协议扩展和LangGraph模式标签，不改M2默认；GREEN定向旧预算/网关回归；提交。

### Task 3: LangGraph图、模型与服务

**Files:** safebank_agent/graph.py、models.py、bank_client.py、prompts.py、runtime.py、server.py；tests/test_graph_runtime.py、test_http.py。

**Interfaces:** 官方StateGraph plan/tools/draft/validate/wait；独立effect节点、MySQLSaver；受服务身份保护的turn POST/GET/ACK/recover；后台有界worker与恢复扫描。

- [ ] RED/GREEN：受限规划工具、具体问题回答、商户注入、无模型澄清wait，interrupt重启后新turn恢复、reset旧resume拒绝、已存effect/checkpoint恢复不重发、正文/尺寸/finish_reason核验。
- [ ] 迁移原SYSTEM_PROMPT的回答焦点/账务口径/未知边界；新增只读规划和结构化事实引用，不以fake规则宣称灵活模型质量。
- [ ] mock与真实DeepSeek adapter分开，默认mock；真实发送先Javareserve，最多两次阶段烟测、共享账本不重置；提交。

### Task 4: 独立候选、联调与材料

**Files:** start-langgraph-demo.ps1；docs/evidence/2026-10-10-v07-langgraph；docs/competition及M3验收。

- [ ] 创建独立bank/app账号与库，迁移一次、合成seed一次，保留M2原包/分支；8134/8135可用且原端口停止。
- [ ] 有限真实Java/Python/MySQL HTTP联调、进程重启/ACK/等待澄清恢复；网页状态、业务依据、右侧对话验证；只检查与新改动相关项目。
- [ ] 可选真实烟测总上限2次HTTP，记录真实usage/余额及失败，不循环付费修正。完成后同步架构、算法、安全与风险材料，PPT/最终发布仍暂缓。
- [ ] 一次独立审查，Important一次修复与定向GREEN；清理本阶段临时库，保留凭证/活动配置；提交，报告当前入口与真实限制。
