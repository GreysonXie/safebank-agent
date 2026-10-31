# 权限与验证数据库增量设计

2026-10-09，待审阅。基线13表见[原设计](2026-10-08-database-design.md)。新DDL见[SQL草案](2026-10-09-risk-permission.sql)，规则及锁序见[权限设计](../superpowers/specs/2026-10-09-risk-permission-design.md)，接口见[契约](../api/2026-10-09-risk-permission.md)。本轮不修改运行数据库或src/main/resources/schema.sql。

## 表与Java映射

| 新表 / Entity | 职责 | 主键与重要约束 | Mapper |
|---|---|---|---|
| risk_user_guards / RiskUserGuardEntity | 跨日共享用户锁、失败窗口、锁定及挑战发送间隔 | PK(user_id)，非负计数/版本 | RiskUserGuardMapper |
| risk_decisions / RiskDecisionEntity | 不可变风险判定、政策版本、等级、当时累计、payload绑定 | PK(id)，UNIQUE(action_id,revision)，等级与验证要求匹配 | RiskDecisionMapper |
| verification_challenges / VerificationChallengeEntity | 验证挑战、摘要、投递状态、次数和期限 | PK(id)，同动作/同用户的decision外键，失败次数0–3 | VerificationChallengeMapper |
| action_authorizations / ActionAuthorizationEntity | 客户确认/补充验证或限定委托形成的授权证据 | PK(id)，同动作/用户decision与challenge外键，方法与assurance匹配 | ActionAuthorizationMapper |
| risk_transfer_usage / RiskTransferUsageEntity | 每个转账动作唯一的日累计占用和最终归类 | PK(action_id)，正整数分，绑定同用户/动作grant | RiskTransferUsageMapper |
| schedule_authorizations / ScheduleAuthorizationEntity | 计划委托scope与来源授权 | PK(schedule_id)，同用户计划和来源grant外键，最高YELLOW | ScheduleAuthorizationMapper |
| risk_events / RiskEventEntity | 权限/挑战/锁定的关联事件 | PK(id)，request/action/decision关联，不保存OTP/密钥 | RiskEventMapper |

新增7表，总计20表。原actions、schedules只加(id,user_id)联合唯一约束，用于阻止跨用户的外键绑定；不改变原业务payload、payload_hash算法、账务唯一约束和主键。没有真实users表，user guard不是登录身份表，不能通过插入guard获得用户身份。

金额使用BIGINT和Java long，日期LocalDate，绝对时间OffsetDateTime；状态映射明确枚举。decision/challenge/grant主键为随机UUID字符串。HMAC摘要和scope hash不是明文OTP；验证码、provider原始token、完整银行账号及模型提示不作为审计事件字段。

## 访问与并发契约

所有归属读取使用user_id和id同时过滤。Mapper只显式字段、参数绑定、resultMap；所有条件写检查影响行数为1（幂等读取例外）。不能为了通用性开放SQL工具给模型。

- guard：幂等创建（仅唯一冲突后重读），selectForUpdate；修改失败窗口/锁定前必须持锁，创建失败不能吞数据库故障。
- decision：append、selectOwned、selectLatest；判定不更新旧行，用revision和审计记录解释变化。新decision在拒绝分支也需提交。
- challenge：按归属和状态条件写SENT/VERIFIED/CONSUMED；已消费不复活；同action只有一个可用挑战由guard锁下校验，不依赖进程内synchronized。
- authorization：append、selectActiveOwnedForUpdate、状态条件写。一个action最多一个ACTIVE grant由guard/action锁下保证；授权切换先撤销旧grant，再生成新grant并重绑唯一usage。数据库索引不是“仅一个ACTIVE”的自动约束，Service必须实施该不变量。
- usage：一动作一行，RESERVED→SUBMITTED→COMMITTED或RELEASED；未提交过期可RELEASED，SUBMITTED不按时间释放。释放后沿用原action重新授权允许将该行改回RESERVED，必须先验证未受理和新grant。

usage.provenance区分CURRENT_AUTHORIZATION和LEGACY_OBSERVED；后者只用于有原动作/银行观察的迁移证据，authorization_id为空且禁止RESERVED，避免伪造历史确认。新授权占用必须有匹配grant；从已证明未受理的旧动作重新确认后，可切换来源并绑定新grant。

只有LEGACY_OBSERVED/SUBMITTED允许business_date为空；未知日期或金额设置guard.reconciliation_required，阻止新转账且不因验证码冷却自动解除。待可信核对后才能定日期、完成usage归类并解除标志，不能用迁移日期顶替银行业务日期。
- schedule scope：只在创建计划/明确撤销委托时改状态，不可默认覆盖参数。scope与既有计划参数不一致即拒绝；RED实际期次不能复用YELLOW委托。
- event：仅insert与按归属查询；不提供修改/删除接口。无可信会话的请求留在入口安全日志，不伪造user_id插入此表。

Foreign key防止把u1授权挂到u2动作。它不验证decision等级与grant风险是否足够、不验证action kind是不是TRANSFER、不验证scope JSON含义；这些必须由统一门禁和类型化服务检查。不可把FK存在解释为资金操作已安全。

## 迁移前检查和顺序

1. 核对应用版本、数据库路径、独占状态和备份；DDL不能直接运行在8096内存实例上。本SQL为一次迁移，不使用IF NOT EXISTS掩盖半迁移；重复执行应失败。
2. 只读检查actions归属、状态、payload、hash，以及schedules的来源/收款人归属和日期；识别已提交且未核实动作。发现损坏、孤儿或无法确定受理日期的记录，停止迁移并给出清单，不删除或猜测数据。
3. 加归属联合约束和7表；H2 DDL不按普通DML事务回滚来保证全有/全无，应先验证副本，并通过版本记录与完整表检查防止半完成误启服务。实际实施选独立候选库，文件库迁移另行停机执行。
4. 幂等创建可信用户guard；从可信转账账务和动作重建当天已提交/已成功usage。普通历史消费、退款、租金不能都计为转账；新合成库无旧执行动作时无需回填授权。
5. 旧AUTHORIZED且没有提交观察：原action回待授权，不能生成“客户已确认”记录。旧PENDING/UNKNOWN：原action待查单并保守占额，不能释放；日期/证据不能复原时锁写并人工核对。旧计划无scope：不自动运行，等待重新确认。
6. 写迁移版本和预检/后检结果，启动新实例，仅一个应用版本可写同库。结构DDL验证通过不代表上述数据回填已验证或已实施。

## DB-first实施边界

审核表结构和接口后，才在src建立Entity/DTO/VO、Mapper XML及Service。生产代码不使用JDBC直接访问；本轮单独SQL校验程序使用H2 driver仅作独立验证工具，不放入生产模块。

本轮验证结果在设计验收记录中补充：原schema+合成fixture可应用DDL、20表存在、合法链可插入，跨用户绑定/错误等级/负数占额/缺失grant/验证码次数越界/重复迁移等应拒绝。此范围不含多线程服务事务和真实验证供应商。
