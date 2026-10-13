# SafeBank M2 Java Agent Gateway Implementation Plan

> **For agentic workers:** 使用 superpowers:executing-plans 当前会话内执行，按任务记录 RED/GREEN；全阶段完成后一次独立审查。

**Goal:** 实现独立候选 Java 只读能力、工具快照和幂等 ACK，以离线 fake Python 服务验证跨服务恢复。

**Architecture:** 复用 ContextService 的 Java 版本权威与会话/轮次锁，新增 GatewayTurnService、AgentCapabilityService 与 GatewayCoordinator。内部 Controller 严格服务认证；HttpAgentClient 的所有请求在事务外。原 /api/chat 只在 agent-gateway profile 路由到新协调器，native 路径不变。

**Tech Stack:** 现有 Java 21 / Boot 3.5.6 / MyBatis 3.0.5 / MySQL 8.4.11；Java HttpClient、Python stdlib fake 服务，不引入新产品依赖。

**Spec:** ../specs/2026-10-10-python-langgraph-mysql-design.md 第4及10节；../../database/2026-10-10-agent-gateway-design.md。

## Global Constraints

- M1 的 8130 与数据库不改动；候选 8132 / safebank_bank_v07_m2，验收后停止 M1 网页进程，只保留新候选运行。
- 基础迁移 V1/V2、授权 payload/hash、资金执行与 native 槽位逻辑不变；V3 仅 gateway profile。
- Model/API key 不读取；0 DeepSeek 调用。Loopback 合成演示；前端显示 fake 模式，不能声称 LangGraph 已接入。
- Token 原文不存 DB、日志、结果、网页或 fake 持久状态。内部密钥随机至少32字节，严格服务身份加本轮 grant 双重检查。
- 全轮8次工具新调用；5分钟/会话有效期上限；同 operationKey/inputHash 返回原 observedAt 快照。
- 金额整数分，时间 Clock/UTC 微秒；Controller/Service/DAO/DTO/Entity/VO 各负其责。
- 网络无银行事务；同 conversation 仅一 PROCESSING；轮询30秒、批量100，连续3次不可达明确暂停/告警。

## Review Focus

1. 新轮已开始后的旧 ACK：同 hash 回原版本，不重复 complete。
2. NULL/伪造归属、错 inputHash、额外 tool 参数：拒绝且不消费 quota。
3. 两连接并发续签/同 key 调用：唯一 ACTIVE/唯一 receipt，失败回滚。
4. HTTP 超时或 ACK 丢失：按原 turn 查询，不重发 POST、不重新运行模型。
5. native/H2 启动：不访问 V3 表、不全局切换恢复行为。

### Task 1: 持久化、轮次与能力工具

**Files:** 新增 migration/mysql-agent/V3、entity/AgentGatewayTurnEntity、AgentServiceGrantEntity、AgentToolCallReceiptEntity；dao/AgentGatewayMapper、resources/mapper/AgentGatewayMapper.xml；dto/AgentToolRequestDTO、vo/AgentToolReceiptVO；service/GatewayTurnService、AgentCapabilityService；config/MySqlSchemaVerifier 增加 manifest 配置；application-agent-gateway.properties。Test: MySqlAgentGatewayTest。

**Interfaces:** GatewayTurnService.begin(user,ChatRequestDTO,Instant sessionExpiry) 返回 TurnStart；AgentCapabilityService.issue(turnId) 返回短期 token；call(token,AgentToolRequestDTO) 返回快照及事实目录。锁会话/原 turn、scope 白名单、receipt 数额度，不接受用户身份字段。

- [x] 编写实际 MySQL 测试：V3/新服务未存在时 RED；重复请求、归属/version、TTL边界、scope、inputHash、同key复用、续签配额8、并发续签和回滚。
- [x] 新增 DDL 和类型/Mapper，再实现服务；测试 GREEN。V3 manifest 从真实隔离数据库导出并审阅，非启动时自动生成。
- [x] 提交代码与数据设计；记录实际测试结果。

### Task 2: HTTP 协议、ACK、恢复和聊天接入

**Files:** dto/AgentResultDTO；vo/AgentAckVO；service/GatewayResultService；agent/HttpAgentClient、GatewayCoordinator；config/InternalAgentGuard；controller/InternalAgentController；service/GatewayReconciler；修改 ContextAgentCoordinator（按 profile 协调）/ContextRecoveryService（gateway 不全局 interrupt）/SessionController（显示 fake 模式）。Test: MySqlAgentGatewayTest、AgentGatewayHttpTest。

**Interfaces:** validate/complete 原 turn/result/inputVersion；HttpAgentClient start/get/ack 双端认证、超时有界；GatewayReconciler 查询原 run、完成后补 ACK，不能重发 start。Response 保持 text/toolResults/contextVersion/context 字段兼容。

- [x] RED：同结果重复 ACK 仅增一次、下一轮后旧 ACK、不同 hash/陈旧结果拒绝；证据核验、非法内部认证、Cookie不能代替服务身份、资金工具拒绝；fake 服务丢响应/ACK 与无run/3次不可达。
- [x] 实现结构化结果核验、幂等 complete 和短事务；仅 original completed hash 返回旧结果。扫描到期批次与 cursor/order；明确未完成状态。
- [x] GREEN：真实 MySQL 新测试及有限 ContextApi/ContextConcurrency/ContextLifecycle H2 回归；提交。

### Task 3: Fake 候选与交付

**Files:** tools/fake-agent-server.py、start-agent-gateway-demo.ps1；docs/验收、evidence、competition 相关架构/算法/安全/自评材料。

- [x] Fake Python 完成服务认证、幂等 turn、tools/validate 回调、immutable RESULT_READY、ACK；无模型、不保存 grant、不允许资金。
- [x] 仅创建独立 m2 DB/账号、Flyway V1/V2/V3 一次初始化；重复请求/启动核验 seed 不重复。打包前停止旧候选避免 Windows JAR 锁，保留其数据。
- [x] 启动 8132 和 fake 服务并验收聊天、鉴权、原请求重放、版本与回执；确认只剩一个网页服务；同步阶段材料和风险。
- [x] 一次独立审查；重要问题一次修复并定向复测；不无限重跑全套。提交、给出当前入口及真实边界。
