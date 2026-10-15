# SafeBank 三层架构与 MyBatis 改造设计稿

日期：2026-10-08。状态：用户确认采用三层架构与 MyBatis，实施计划待审核，未实施。业务代码基线：c81fad0。

UI 待审补充：[红蓝视觉与滚动动效设计](2026-10-08-webank-inspired-ui-design.md)。该方案保留已经确认的工作台布局，参考微众官网配色与轻量显现；视觉改造与本文件的后端迁移分别审核、分别实施。

## 目标与判断

队友希望后端按 Controller、Service、DAO 分层，使用 MyBatis，并先确定数据库设计和 Java 类型，再建立代码。目的在于让四人团队中的 Java 开发者能够清楚接手和维护。

现有代码使用 Java 21、Spring Boot 3.5.6、Spring JDBC、H2。已经存在 Controller、Service 和 schema.sql，问题是分层不完整：BankService 同时承担 SQL、账单分析、动作管理、模拟资金记账和定时执行；ActionExecutionService、WorkflowService、FaultInjectingBankAdapter 也直接访问数据库；ApiController 汇集多类接口并大量返回 Object/Map。业务安全规则已有测试，应在重构时保留。

JdbcTemplate 本身不是废弃技术。改造理由是职责分离、显式类型、SQL 可读性和团队熟悉度，而非框架名字的新旧。

## 方案选择

| 方案 | 影响 | 判断 |
| --- | --- | --- |
| 只抽取 DAO，继续 Spring JDBC | 改动较小，可改善职责混杂 | 用户已排除，不作为实施选项 |
| 保留业务与表，逐模块引入 MyBatis 和显式类型 | 可逐阶段回归，保持演示可用 | 已确定采用 |
| 整体重写并同时换 MySQL、接口包装和前端 | 同时改变持久化、运行环境和调用契约，回归范围大 | 不纳入本次改造 |

目标保留 Java 单后端、H2、本地合成银行、现有网页和 HTTP 契约。计划固定 MyBatis Starter 3.0.5，官方发布说明已确认其基线为 Spring Boot 3.5.x；实施第一阶段验证实际依赖与运行兼容性。所有生产持久化访问迁移至 MyBatis Mapper，最终移除生产代码中的 JdbcTemplate；MyBatis 底层仍使用 JDBC 驱动。首轮不额外引入 MyBatis-Plus、微服务或 ServiceImpl 一对一空转接口。

## 类型与目录

| 类型 | 职责 | 例子 |
| --- | --- | --- |
| Controller | HTTP 路由、输入校验、取得会话身份、调用服务 | StatementController、ActionController |
| Service | 业务计算、状态转换、授权检查、事务编排 | StatementService、ActionService、WorkflowService |
| DAO / Mapper | 参数化 SQL、数据库行映射、数据库操作 | AccountMapper、ActionMapper |
| Entity | 与持久化结构对应的对象；不直接返回前端 | AccountEntity、ActionEntity |
| DTO | 接口请求或服务间数据传输 | TransferPreviewRequestDTO、WorkflowRequestDTO |
| VO | 提供前端所需的展示数据 | StatementVO、ActionVO、WorkflowVO |
| 业务模型 | 表之外的业务语义、状态与银行观察 | ActionKind、BankObservation |

DTO 不定义为“保存数据库的类”。Entity 的字段不能通过用户提交的对象批量赋值；userId、授权状态和执行终态由程序控制。

```text
cn.safebank
├── controller     # 按账单、账户、代扣、动作、任务、会话、模拟实验拆分
├── service        # 业务服务；复杂事务明确放在对应服务内
├── dao            # MyBatis *Mapper 接口，直接承担 DAO 职责
├── entity         # 持久化对象
├── dto            # 请求及跨服务传输对象
├── vo             # 响应视图对象
├── converter      # 有实际转换逻辑时使用，不建立机械转发层
├── agent          # 对话编排、事实核验、语义审核；不直接写数据库
├── bank           # BankAdapter 与本地银行、故障模拟实现
├── model          # 模型客户端、请求用量与预算
├── common         # 异常、时间与公共基础类型
└── config         # MyBatis 与运行配置
resources/mapper   # SQL XML；与 dao 中接口一一对应
```

Mapper 返回 Entity 或专用查询投影，不返回任意 Map。统计查询不强行映射成整张表的 Entity。动态模型工具参数和原始供应商 JSON 可保留 JsonNode；避免把它们传播成银行核心领域的无类型对象。

调用方向：Controller → Service → Mapper → 数据库。DTO 进入服务，服务计算结果由明确转换形成 VO。Agent 调用同一业务服务与工具接口，不通过 Controller，也不能直接调用授权/提交入口。

## 数据库先行

完整表设计、字段含义、关联和约束见 [数据库设计稿](../../database/2026-10-08-database-design.md)。现有 13 张表已经建模，先确认它们的职责与缺口，再生成 Entity 和 Mapper。

分层迁移的第一阶段保留现有表名、列名、主键类型和数据。数据库增强分为独立阶段：增加查询索引、检查存量数据、再增加适用的 NOT NULL / CHECK / 外键约束。所有 INSERT 显式列出列名，避免后续加列破坏按列序插入。

迁移脚本单独编号并记录已执行版本；接入版本化迁移工具时先对已有库建立明确基线，在数据库副本验证重复启动、前向迁移和恢复。不得将启动时反复执行的 schema.sql 当作完整的数据库升级方案，也不删除原文件库重新建表。

## 服务拆分

| 现有职责 | 目标服务 / Mapper |
| --- | --- |
| accounts、recipients 与转账输入检查 | AccountService、RecipientService；AccountMapper、RecipientMapper |
| statement、recurring | StatementService；TransactionMapper 和周期统计投影 |
| 代扣列表及撤销预览 | MandateService、ActionService；MandateMapper、ActionMapper |
| preview、authorize、cancel、动作读取 | ActionService；ActionMapper、AuditMapper |
| 本地 submit、资金记账与权威结果 | LocalBankExecutionService；AccountMapper、LedgerMapper、TransactionMapper、MandateMapper、ScheduleMapper、ActionMapper、AuditMapper |
| 适配器调用和 UNKNOWN 恢复 | ActionExecutionService；ExecutionObservationMapper |
| 定时登记及到期执行 | ScheduleService；ScheduleMapper、OccurrenceMapper，调用本地执行服务 |
| 联合任务版本、步骤及恢复 | WorkflowService；WorkflowMapper、WorkflowStepMapper |
| 实验故障配置 | SimulationService / 故障适配器；BankFaultMapper |

拆分不得形成循环依赖：ActionService 管理预览/授权，不反向依赖执行协调服务；本地银行执行独立；执行协调服务依赖 BankAdapter；WorkflowService 编排动作和执行服务。公共动作读取/核验能力置于 ActionService，定时执行调用本地银行业务，不能通过 HTTP 自调用。

## 事务、幂等与归属

- 所有客户对象的查改包含可信 userId 条件；收款目标可能属于其他客户，需先验证当前客户持有的 recipient，再访问其目标账户，不能给收款账户错误套上付款人归属条件。
- 一次本地转账中的余额变动、两条 ledger、两条交易流水、动作终态和审计必须原子提交或回滚。账户按稳定顺序锁定，取得行锁后重新检查余额和权限。
- action_id 和 ledger 的唯一约束、每期 schedule_id + due_date 唯一性、workflow_steps 的版本及动作唯一性均保留。
- 提交意图先在短事务中落库，再调用银行适配器；返回后在短事务中记录观察。UNKNOWN 查原 action_id，不重建资金动作。模型及外部调用不能包进长事务。
- 不对整个 ActionExecutionService 或 WorkflowService 简单加类级 @Transactional。可保留 TransactionTemplate 作为显式短事务边界；Mapper 使用同一个 DataSource 和事务管理器。
- 任务修改保留 expectedVersion 校验和行锁，旧版本未执行预览失效；已经提交或状态未知的动作按原标识恢复。
- action payload 与 payload_hash 保持既有字段、序列化和计算语义。转换 DTO/Entity 时不能重新序列化并改写已授权数据的哈希；迁移前后用契约测试验证一致。

## 接口兼容

保留 /api 路由、CSRF、会话身份、错误 code/message、当前金额单位与字段命名。Entity 使用 Java 命名习惯；VO 必要时显式标注 JsonProperty 保持 amount_minor 等前端字段，任务的 camelCase 契约也保持。首轮不新增全局 Result 包装导致网页和评测脚本整体改写。

业务接口改为明确的 DTO/VO；ActionVO 区分不同操作的可选字段，状态来源由银行数据决定。ExecutionReceipt 不再以继承 Map 充当隐式协议。Agent 的动态事实目录在转换适配层获得结构化数据，避免重构导致模型丢失查询事实。

## 实施次序与验收

先确认此设计和表结构；之后形成逐文件实施计划。建议次序：持久化类型和 Mapper 配置 → 账户/收款人/账单只读路径 → 预览与授权 → 本地记账和执行观察 → 定时及联合任务 → 控制器/响应类型整理 → 存量数据库增强。

每阶段要求现有行为与 HTTP JSON 契约保持一致。重点验证用户隔离、账单退款/内部转账口径、授权哈希、余额不足回滚、重复提交不重复记账、UNKNOWN 原动作恢复、同一期定时执行唯一、任务版本失效、文件数据库重启恢复。

现有基线测试为 145 项（其中付费联调默认跳过）。本次设计没有运行新测试、修改运行代码、安装依赖、迁移数据库或调用 DeepSeek。后续主要采用本地合成数据与现有回归，架构迁移本身不需要重复付费模型评测。

## 官方依据

- [MyBatis Starter 版本兼容表](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)：3.0 系列适配 Spring Boot 3.2–3.5，4.0 系列面向 Boot 4。
- [Spring 编程式事务文档](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)：TransactionTemplate 仍是受支持的显式事务方式。
