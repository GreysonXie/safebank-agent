# SafeBank v0.7 M2：Java Agent只读网关计划草稿

**状态：早期范围草稿；已由[逐文件实施计划](2026-10-10-v07-agent-gateway-implementation.md)落实，阶段证据见[M2验收](../../2026-10-10-v07-agent-gateway-验收.md)。** M1已在独立MySQL候选完成业务基础；本计划不能视为Python正式服务已经接入。接口字段以已批准v0.7架构设计第10节为权威，编码前补齐现有轮次服务与Adapter文件定位。

**目标：** 让后续Python Agent通过受限内部接口查询本地银行事实，并实现Java权威轮次/版本、短期能力、回执和ACK。全程fake Python / fake model，真实DeepSeek预算保持不变。银行授权与资金提交继续保留原Java用户入口。

## 约束

- 保留原服务与M1候选；下一独立候选使用8132，不换8096/8110入口。
- Controller只处理身份与请求，Service持有事务，Mapper/MyBatis持有SQL；DTO用于输入，Entity对应持久化，VO返回前端。模型不能传入可信user_id、bank authorization或执行资金工具。
- 内部服务身份与grant均核验；拒绝仅有其中一种身份。令牌原文不存库、日志或图状态；只读scope白名单，最长5分钟且不晚于会话expiry，每轮总调用上限8。
- 无模型/网络调用被包入银行短事务。Java唯一contextVersion，Python只保存输入投影；M2 fake服务不私自增加版本。
- 不添加Python生产图、真实银行/MFA、资金DAG或最终答辩发布材料。

## 顺序与验收

1. **固化现有轮次接口与fake服务边界。** 对照conversation/user/request唯一键、现有requestHash、PROCESSING与完成状态；先写重复request同hash复用、不同hash冲突、跨归属拒绝、陈旧版本拒绝测试。
2. **grant存取与签发/撤销。** 新建两张能力表的Entity、Mapper、DTO/VO和Service；每次签发锁turn并校验会话/轮次/版本/TTL。并发续签仅一个ACTIVE；撤销与新签发失败全部回滚；全轮quota不因续签归零。
3. **受限只读工具回调。** operation_key/input_hash稳定；锁turn，先查原receipt，同key同hash复用、不同hash拒绝；新本地查询、receipt写入和grant观测计数同事务。测试过期、错误scope、归属和版本，回滚不占quota，第9次新逻辑调用拒绝。
4. **Java网关与fake Python协议。** 有界HTTP超时、只读调用、不可变result/hash；外部身份由Java转换，敏感内部凭证不进入网页。fake服务模拟响应丢失、重复请求和延迟结果；拒绝任意资金tool。
5. **ACK与Java版本提交。** 同一原turn结果只提交一次，校验input_version、result_hash、归属和状态；版本增量、结果与证据持久化同一Java事务；重复ACK回原版本，不再加一。测试ACK响应丢失、不同hash重放、已开始新轮后的旧结果。
6. **有界对账与中断结果。** 定时扫描既定状态/索引、游标和批次；查询fake服务原run，已保存结果复用；未知运行明确暂停，不自行重发模型或重建资金动作。协议失败不声称已完成。
7. **独立候选/材料。** MySQL实际并发与事务测试；定向H2回归；只读HTTP联调。同步架构、算法、安全与风险R05，不把fake服务验收称正式LangGraph生产验收。保留M1候选供对照。

M3再落实Python运行表、官方LangGraph、effect/预算与恢复，并沿用M2的真实Java协议。届时少量DeepSeek质量验证需要单独明确调用上限；普通自动测试不访问付费供应商。

## M1交付后补充的实施前置

- 存储前置已完成：经用户授权，MySQL 程序及数据迁到 `<LOCAL_PATH>`，逻辑和冷备保留在 D 盘；26 表结构/全部行/用户校验一致，8130 已恢复。旧网页服务停止，代码与数据保留。仅清理 C 盘已迁移项目副本；后续临时库继续使用 D 盘实例。[迁移与恢复记录](../../2026-10-10-本地运行环境整理.md)。
- M2独立8132候选使用独立银行database/账号，例如safebank_bank_v07_m2；不能让M1/M2两个Java JVM并行写同一银行库。当前资金调用协调只保证既定单JVM范围；跨实例资金协调不是本轮已实现能力。
- 编码前补齐现有轮次Service的文件/接口定位和明确的执行任务，先设计新增迁移再写代码。本文件是范围草稿，不是已经实施的网关。
