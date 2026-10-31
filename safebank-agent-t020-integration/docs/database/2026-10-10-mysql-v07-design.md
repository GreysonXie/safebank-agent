# SafeBank v0.7 MySQL 首阶段数据库设计

评审修订（2026-10-10）：新增工具副作用回执和Python effect回执；Java业务库目标26表，Python自有应用表5张，另有checkpointer管理表。版本/ACK/对账的权威规则见配套架构设计第10节；没有执行DDL。

2026-10-10。配套[首阶段架构设计](../superpowers/specs/2026-10-10-python-langgraph-mysql-design.md)，待审阅，未创建数据库或执行 DDL。源结构为 schema.sql（13表）、schema-risk.sql（7表）、schema-context.sql（4表）。现有13表历史设计不代表当前只有13表。

## 1. 物理规则

- 目标 MySQL 8.4 系列、InnoDB、utf8mb4，精确patch实施首任务锁定。bank/agent使用独立database、账号；外键不跨两个database建立，以服务核验关联。
- ID、user_id、哈希、状态与令牌摘要使用大小写敏感比较；实施采用 utf8mb4_0900_bin（NO PAD，尾部空格与大小写均区分），避免默认不区分大小写使原主键/授权对象混同。中文姓名按已有Java语义消歧，不依赖模糊排序猜对象。
- 金额仍为有符号 BIGINT 整数分，JSON中保持整数；Python Decimal/整数计算不使用float，Java金额权威不迁入图状态。
- DATE 保留业务自然日。OffsetDateTime 对应 DATETIME(6)，数据库值统一UTC，Java显式type handler将offset时间写为UTC/读取为UTC OffsetDateTime，连接/会话UTC；Asia/Shanghai仅用于业务日累计和展示。采用DATETIME避免TIMESTAMP范围限制，不声称MySQL保留原offset。
- 原TIMESTAMP(9)精度下降到微秒；业务瞬时比较统一microseconds，证据顺序由turn/version保障。导入已有payload及hash的原字符串禁止重新序列化，证据snapshot原文及content_hash亦保留，不能因格式调整误判证据变化。
- 原CLOB及大型JSON文本改LONGTEXT；actions.payload保持原始文本，可改TEXT避免utf8mb4大VARCHAR行尺寸。所有长JSON字段采用TEXT/LONGTEXT且在应用限制长度，不直接换MySQL JSON造成原授权字符串格式变化。JSON合法性由原codec核验，状态/金额/关联仍为结构化列。
- 外键、CHECK、唯一键、索引按源结构保留；枚举包含处理中/未知等现行值。所有外键关联列type/长度/collation一致；DDL逐项命名CHECK约束，避免MySQL库内约束名冲突。账务跨行借贷平衡由事务与业务断言保证。
- 替换H2 ALTER ADD COLUMN IF NOT EXISTS、MERGE/特定日期函数等方言，不能只批量换类型。种子用独立合成profile的受控一次导入；显式列名，避免IGNORE掩盖约束错误。

## 2. safebank_bank_v07：现有表

| 表组 | 表 | 首阶段处理 |
|---|---|---|
| 账户 | accounts、recipients | 保留ID/归属/余额与联系人消歧；新增accounts(user_id,id)、recipients(user_id,id)索引 |
| 账单 | transactions、mandates | 保留正退款/内部转账口径、billing_interval未知值策略；transactions(user_id,booked_on,id)、mandates(user_id,id)索引 |
| 动作/账务 | actions、ledger | 保留payload原文、hash、过期、receipt；ledger(action_id,account_id)唯一，actions(id,user_id)唯一供风险FK |
| 定时 | schedules、occurrences | 保留schedule/user唯一与期次主键；不生成新action代替旧期次 |
| 观察/实验 | execution_observations、bank_faults | 观察按原action读取；faults仅合成profile启用 |
| 历史任务 | workflows、workflow_steps | 保留version、request/analysis/evidence原文和步骤动作唯一；python首阶段不运行它们的银行写任务 |
| 账务审计 | audit | 追加历史，不级联删除；保持user/action/time关联 |
| 风险 | risk_user_guards、risk_decisions | 保留lock、reconciliation、policy与额度判定；所有原复合FK/唯一键和CHECK |
| 验证/授权 | verification_challenges、action_authorizations | 原状态、assurance、hash、有效期、原challenge与decision关联不变 |
| 累计/委托 | risk_transfer_usage、schedule_authorizations | 保留RESERVED/SUBMITTED/COMMITTED/RELEASED、业务日/UNKNOWN口径及原授权关联 |
| 风险审计 | risk_events | 保留现有字段与索引，不把缺少新trace号的历史记录自动删除 |
| 上下文 | agent_conversations、agent_turn_receipts、agent_context_evidence、agent_context_slots | 四表继续由Java持有；python路径只由graph负责推导意图，Java保存归属/轮次/展示证据，旧native槽位保留 |

以上24表，字段含义沿用已有Entity和原DDL；首阶段不增加所有历史设计中的目标外键，不修复性猜填可空字段。合成种子经过完整性核查后才导入；未来存量导入先出缺陷清单。

## 3. 新增 Java 内部能力表

`safebank_bank_v07.agent_service_grants`：只读回调能力令牌。Java签发/读取/撤销，Python账号无访问权限。

| 字段 | 类型与约束 | 说明 |
|---|---|---|
| id | VARCHAR(80) PK | 能力记录编号，不是令牌原文 |
| token_hash | CHAR(64) NOT NULL UNIQUE | 高熵随机令牌SHA-256摘要 |
| user_id / conversation_id / turn_id | VARCHAR(40/80/80) NOT NULL | 从Java轮次与会话归属生成 |
| request_id | VARCHAR(80) NOT NULL | 对应外部请求 |
| scope_json | TEXT NOT NULL | 明确只读工具列表，应用codec白名单核验 |
| state | VARCHAR(16) NOT NULL | ACTIVE / REVOKED / EXPIRED |
| call_limit / calls_used | INT NOT NULL | limit>0；used在0..limit；消耗原子条件更新 |
| created_at / expires_at | DATETIME(6) NOT NULL | UTC；expires_at>created_at |

复合外键(turn_id,conversation_id,user_id)→agent_turn_receipts(id,conversation_id,user_id)。索引(user_id,turn_id,state)及(expires_at)；续签锁turn行、核验归属/版本，在同一事务撤销旧ACTIVE并新增，失败全部回滚。同轮总额度按下表已提交receipt数量计算，续签后不归零；原calls_used为单grant观测，禁止作为整轮总额度权威。失效时间最多5分钟且不晚于会话expiry，最大工具调用数为每轮8次；续签维持原8次而非追加8次。令牌不是银行authorization。

新增 `safebank_bank_v07.agent_tool_call_receipts`：

| 字段 | 类型/约束 | 作用 |
|---|---|---|
| id | VARCHAR(80) PK | 服务生成编号 |
| turn_id/conversation_id/user_id | VARCHAR(80/80/40) NOT NULL | 复合FK→agent_turn_receipts(id,conversation_id,user_id) |
| operation_key / input_hash | CHAR(64) NOT NULL | UNIQUE(turn_id,operation_key)；同key不同hash拒绝 |
| tool_name / grant_id | VARCHAR(80) NOT NULL | 白名单工具、FK→agent_service_grants.id |
| result_json / result_hash | LONGTEXT / CHAR(64) NOT NULL | 仅成功提交的查询快照；不存令牌原文 |
| observed_at | DATETIME(6) NOT NULL | UTC；重放仍展示此原观察时间 |

服务锁同turn行后检查有效grant、版本、原receipt和总quota；同key命中直接复用，无新增调用计数；新调用在同一事务完成本地查询、receipt插入与单grant观测计数。事务回滚不占工具quota。原始查询必须限本地DB，不包入网络/LLM请求；需要刷新事实时新key且消耗quota。

## 4. safebank_agent_v07：Python 应用表

Python自有表使用版本化迁移，所有必填时间UTC DATETIME(6)。不存在跨库FK，API验证Java归属与编号。实体业务状态不是checkpointer内部字段的替代。

| 表 | 字段 / 类型 | 键与不变量 |
|---|---|---|
| agent_thread_bindings | id VARCHAR(80)、user_id VARCHAR(40)、conversation_id VARCHAR(80)、thread_id VARCHAR(80)、schema_version INT、created_at/updated_at DATETIME(6) | PK id；UNIQUE(user_id,conversation_id)、UNIQUE(thread_id)；Java确认归属后建立 |
| agent_runs | id VARCHAR(80)、binding_id VARCHAR(80)、java_turn_id VARCHAR(80)、request_id VARCHAR(80)、request_hash CHAR(64)、input_version/output_version BIGINT、state VARCHAR(24)、lease_owner VARCHAR(80)、lease_revision BIGINT、lease_until DATETIME(6)、result_json LONGTEXT、result_hash CHAR(64)、result_kind VARCHAR(24)、error_code VARCHAR(80)、created_at/updated_at/acknowledged_at DATETIME(6) | PK id；UNIQUE(binding_id,request_id)、UNIQUE(java_turn_id)；FK binding；PROCESSING / RESULT_READY / NEEDS_CLARIFICATION / COMPLETED / FAILED / INTERRUPTED / RECONCILIATION_REQUIRED；output_version和ack时间只有Java确认后填，格式版本不能替代业务版本 |
| agent_run_events | id VARCHAR(80)、run_id VARCHAR(80)、sequence BIGINT、node_name VARCHAR(80)、event_code VARCHAR(40)、detail_json TEXT、recorded_at DATETIME(6) | PK id；UNIQUE(run_id,sequence)；FK run；已发生事件追加，不保存隐式CoT或原始令牌 |
| agent_model_requests | id VARCHAR(80)、run_id VARCHAR(80)、reservation_id VARCHAR(80)、purpose VARCHAR(24)、state VARCHAR(16)、provider_response_id VARCHAR(160)、actual_model VARCHAR(120)、prompt_tokens/completion_tokens/cache_hit_tokens/cache_miss_tokens BIGINT、reserved_at/finished_at DATETIME(6)、error_code VARCHAR(80) | PK id；UNIQUE(reservation_id)；FK run；purpose规划/生成/修正/审核；缺失usage为NULL非0；记录不授予额外预算 |
| agent_effect_receipts | operation_key CHAR(64)、origin_run_id VARCHAR(80)、binding_id VARCHAR(80)、origin_java_turn_id VARCHAR(80)、node_name VARCHAR(80)、logical_sequence BIGINT、purpose VARCHAR(24)、input_hash CHAR(64)、state VARCHAR(16)、reservation_id VARCHAR(80)、result_json LONGTEXT、result_hash CHAR(64)、lease_revision BIGINT、created_at/updated_at DATETIME(6) | PK operation_key；FK origin_run/binding；PREPARED / SENDING / SUCCEEDED / FAILED / UNKNOWN；唯一稳定key不随resume的lease/run编号改变；SUCCEEDED必须有结果/hash，模型SENDING后结果未知不得自动再发 |

运行状态等待澄清时保存该轮结果；客户补充为新的requestId/java_turnId与同一binding对应，resume校验会话版本。租约必须有CAS/fencing revision，同binding一次只允许一个活跃运行：锁binding行并检查未释放run后取得lease，失败立即返回处理中。丢租约后禁止继续模型发送或更新图状态，防旧进程覆盖新轮。

业务contextVersion由Java单调生成，Pythoninput_version只投影，output_version仅接受原turn的JavaACK。图状态/schema格式版本另列，不独立增加业务版本。result_kind限定ANSWER / CLARIFICATION；RESULT_READY对应不可变result/hash，ACK后才能进入COMPLETED或NEEDS_CLARIFICATION。UNKNOWN是effect状态；存在UNKNOWN时run为INTERRUPTED或RECONCILIATION_REQUIRED，禁止混用交易UNKNOWN。

新增索引：agent_runs(state,lease_until,id)、agent_runs(state,updated_at,id)、agent_effect_receipts(state,updated_at,operation_key)。Java agent_turn_receipts(state,created_at,id)用于有界对账扫描；状态转换CAS和归属限制不可依赖Python传入版本绕过。

## 5. LangGraph 检查点表与部署

社区MySQL saver自有checkpoint/migration/blob/write表及generated column/index以固定3.0.0候选源码和兼容验证为准，不手写猜测字段或自行改内部表。实施首任务核对其元数据、驱动、serializer与LangGraph固定版本，使用隔离agent库、setup一次创建并导出真实结构清单。升级不能直接覆盖已有checkpoint。

数据库管理/迁移账号只用于显式初始化；随后运行Java/Python以最小权限账号，Python仅访问自己库。运行中的saver不调用setup。MySQL端口3307须检查占用；若已有本机8.4实例可满足隔离库与账号要求，评估后使用，不卸载或覆盖用户现有实例。

## 6. 合成迁移和验收

0. 先在专用可抛弃实验库完成有时间盒的checkpointer假图预验证；在通过之前不建立24表应用候选。初始化环境时间与实验有效时间分别记录。
1. Flyway V1建立完整24表MySQL基线（把源ALTER收敛为最终列），V2新增grant与tool receipt两表；后续索引/约束变更独立编号。不对旧H2直接运行Flyway baseline冒充数据迁移。生成列级diff：源DDL及从隔离H2结构读取的列/约束，与隔离MySQL INFORMATION_SCHEMA导出的目标列/约束对照，列出每项类型/精度/NULL/默认值/键/collation变化与理由；不连接原运行库。
2. 使用明确合成profile一次导入现有测试数据，种子与手工导入一律显式列名、显式UTC微秒时间值，导入连接时区UTC；核对所有ID、余额、payload/hash、回执和关键状态，不让裸SQL绕过时间转换；禁止线上默认profile重置数据。
3. MyBatis显式timestamp类型处理，检查所有XML的FOR UPDATE、LIMIT和日期/布尔SQL；不删除旧H2 profile，必要方言差异有清楚配置。
4. 实际MySQL检查重复启动、唯一键/FK/CHECK、行锁与事务回滚、授权过期、上海零点业务日、UNKNOWN原动作、账务平衡；保留既有关键断言。
5. Python最小假图验证checkpoint持久化、interrupt重启resume、pending writes与重复恢复；同时验证effect已保存结果不重发、SENDING未知暂停、JavaACK重放只加一次版本、续签总quota不变与并发续签只有一个ACTIVE。适配失败按架构第10节Plan B处理，不改MySQL选择或自动退化内存。

当前只有设计与原结构盘点，没有通过MySQL执行、Python依赖安装或服务联调。可执行DDL与锁文件在设计审核后的实施阶段生成并验证。

## 实施状态更新

以上“未执行DDL”为设计时状态。M1现已建立26表MySQL独立候选并验证合成账务/时间/结构边界，见[验收](../2026-10-10-v07-mysql-foundation-验收.md)。V1增加Boolean 0/1域CHECK，V2 call_limit限制1..8；CHECK转表级以满足跨列引用。Python应用表和网关协议仍待实现。
