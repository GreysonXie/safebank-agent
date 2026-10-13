# SafeBank v0.7 MySQL业务基础迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking. 当前会话直接实施为建议方式，计划已获用户批准并执行；本计划不授权提前实现下一子项目。

**Goal:** 在独立MySQL合成候选上复用现有Java/MyBatis业务，保留原版本，完成表迁移、时间与SQL语义验证。

**Architecture:** 首阶段设计拆为可独立验收的子项目：本计划仅M1数据库基础，后续M2网关/版本/对账与M3 Python图分别成计划。Java保留现有层次和接口；新增MySQL profile/版本化DDL，H2旧路径继续可用。没有Python生产服务或资金DAG代码。

**Tech Stack:** Java21、Spring Boot3.5.6、MyBatis3.0.5、MySQL8.4.11；Boot BOM固定管理Connector/J9.4.0和Flyway11.7.2，依赖树与实际启动需验证，不因BOM而假定兼容通过。

**Spec:** [v0.7架构设计](../specs/2026-10-10-python-langgraph-mysql-design.md)、[MySQL设计](../../database/2026-10-10-mysql-v07-design.md)。前置[检查点实验](../../2026-10-10-v07检查点预验证结果.md)已通过同步假图范围。

## Global Constraints

- 新分支feature/v0.7-python-langgraph-mysql；原8096/8110/8111、原目录/库/产物不动，不合并/推送。
- MySQL仅独立新实例/数据目录与safebank_bank_v07；数据库进程在本机ASCII路径运行，loopback候选端口启动前核验。
- 24张原表＋grant/tool receipt两表，保留原ID/授权payload原文与SHA-256、未知状态、归属、复合唯一/FK/CHECK；不改猜测旧数据。
- DATETIME(6)保存UTC，JavaOffsetDateTime通过显式type handler映射；上海日累计业务规则保留，金额BIGINT整数分。
- 运行账号不做DDL；迁移账号执行Flyway，启动schema验证拒绝缺表/坏约束；不自动运行旧H2 schema.sql。
- M1默认离线合成，无DeepSeek；模型请求测试使用fake；调试账本不重置。
- 每个任务验收一次达到交付点即停止扩大；资金关键断言保留，历史通过结果不作为新MySQL结论。
- 每个里程碑同步更新docs/competition的状态、证据和风险，不准备PPT/视频或最终源码发布包。

## Review Focus

1. UTC微秒截断造成expiry边界或上海零点风险误判：T2验证。
2. ID大小写/重名、空值、超长JSON被SQL转换误改：T1/T3验证。
3. MySQL upsert覆盖别人归属或覆盖原观察：T3验证。
4. 缺约束/重复初始化被启动自动修复，损坏已有候选：T1/T4验证。
5. H2测试“通过”掩盖MySQL锁/事务差异，或测试运行偷偷带key：T4/T5验证。

## 文件与职责

保留src/main/java和resources结构。MySQLDDL只放resources/db/migration/mysql；数据库特定SQL用MyBatis databaseId，避免复制全部业务Service。候选启动/核验脚本只操作明确登记的本项目实例。测试管理员与运行凭证从本机环境读取，不写常量，不传入可打印命令行。

### T1：数据库迁移与机器结构对照

**Files:** Modify `pom.xml`, `src/main/resources/application.properties`；Create `src/main/resources/db/migration/mysql/V1__bank_risk_context.sql`, `V2__agent_capabilities.sql`, `tools/mysql/compare_schema.py`, `src/test/java/cn/safebank/MySqlTestSupport.java`, `MySqlMigrationTest.java`, `verify-mysql.ps1`。

**Interfaces:** `compare_schema(h2_structure: dict, mysql_structure: dict) -> list[ColumnChange]`；差异记录含table/column/sourceType/targetType/nullability/default/keys/collation/reason。验证脚本以SAFEBANK_MYSQL_TEST_*环境参数运行指定测试，缺参数明确失败，不回退H2。新迁移执行器使用Flyway官方接口，开启clean-disabled。

- [x] 写MySqlMigrationTest断言26张应用表（排除flyway_schema_history），风险复合FK/唯一键、账务整数分和UTC精度；连续migrate两次行数/版本不变；错误CHECK输入拒绝。
- [x] 添加BOM管理的mysql-connector-j、flyway-core、flyway-mysql，并在application.properties显式spring.flyway.enabled=false，避免依赖引入后默认自动扫描MySQL脚本破坏旧H2路径。建立仅测试用MySqlTestSupport；以显式环境创建隔离测试连接/Flyway，限定database前缀safebank_v07_test_，不默认连接候选或原库。执行`./verify-mysql.ps1 -Tests MySqlMigrationTest`，确认未建立DDL时失败且不会访问原库；测试支撑属于本DDL任务。
- [x] 按24表源DDL转换V1最终结构；V2建立grant及tool receipt。每个CHECK有库内唯一名称，大JSON转TEXT/LONGTEXT，固定binary collation，禁止重序列化payload。编写机器diff，从隔离H2 INFORMATION_SCHEMA和MySQL目标读元数据，不读原运行库。
- [x] 同命令通过；生成列级diff与允许转换理由，未解释差异为失败。记录实际MySQL版本与flyway_schema_history，不记录凭证。
- [x] 提交迁移/验证代码与本阶段证据摘要，更新比赛材料中迁移状态；仅提交本任务文件。

### T2：UTC时间与可隔离的MySQL profile

**Files:** Modify `pom.xml`；Create `src/main/resources/application-mysql.properties`, `application-mysql-test.properties`, `src/main/java/cn/safebank/config/UtcOffsetDateTimeTypeHandler.java`, `MyBatisDialectConfig.java`, `src/test/java/cn/safebank/MySqlTimeMappingTest.java`。

**Interfaces:** `UtcOffsetDateTimeTypeHandler extends BaseTypeHandler<OffsetDateTime>`；写入`instant截断到微秒→UTC LocalDateTime`，读取`LocalDateTime→UTC OffsetDateTime`；NULL保留。MyBatis `databaseId`输出h2/mysql。

- [x] 写MySqlTimeMappingTest：+08与UTC同instant往返相同；纳秒截断规则固定；NULL不变；上海23:59:59.999999与次日00:00对应风险业务日正确；expires_at等于now时不可授权。
- [x] `./verify-mysql.ps1 -Tests MySqlTimeMappingTest`确认映射未实现时失败。
- [x] 使用T1已加入的BOM依赖；H2 profile默认Flyway关闭，mysql profile开启Flyway并关闭spring.sql.init、限制locations。迁移凭证与应用凭证分开；UTC handler只在mysql profile显式注册，禁止用全局MappedTypes改变旧H2 OffsetDateTime处理。
- [x] 同命令通过，保存实际dependency tree固定版本；若BOM组合启动不兼容，停止并记录具体依赖，不顺带升级Boot或覆盖全局版本。
- [x] 提交本任务文件，更新比赛技术文档时间存储和已验证边界。

### T3：MyBatis方言与种子语义

**Files:** Modify `src/main/resources/mapper/BankFaultMapper.xml`, `ExecutionObservationMapper.xml`；Create `src/main/resources/db/fixtures/mysql/v07-synthetic.sql`, `src/test/java/cn/safebank/MySqlMapperParityTest.java`。

**Interfaces:** 现有Mapper方法签名保持；h2分支MERGE，mysql分支受控ON DUPLICATE KEY UPDATE。seed为只在新合成profile显式导入的原数据语义投影，显式列/UTC，不带建表或清库语句。

- [x] 写MySqlMapperParityTest：所有13/7/4表映射读取；bank_faults/observations重复同归属更新；不同归属同action拒绝；中文商户、大小写不同ID、NULL、16000字符JSON、payload/hash原字节等价；退款/内部转账分类不变。
- [x] `./verify-mysql.ps1 -Tests MySqlMapperParityTest`确认H2 MERGE不兼容时真实MySQL失败。
- [x] 根据databaseId拆两条upsert，先验证action归属，不让MySQL写入覆盖不同user；审查其余XML方言。合成INSERT从risk-demo-data.sql已有种子明确转换，保留全部真实列值与ID，不跑其H2 CREATE段，不用INSERT IGNORE。
- [x] 同命令通过；初次种子导入后快照核对各表行数、ID、金额与payload/hash。重复启动不重新seed，重复显式seed应拒绝并说明，不静默覆盖数据。
- [x] 提交本任务，材料更新只读账单数据口径；不得宣称Python回答已迁移。

### T4：启动约束核验与实际事务检查

**Files:** Modify `src/main/java/cn/safebank/config/RiskSchemaInitializer.java`, `ContextSchemaInitializer.java`, `src/main/resources/mapper/RiskSchemaMapper.xml`, `ContextSchemaMapper.xml`；Create `src/main/java/cn/safebank/config/MySqlSchemaVerifier.java`, `src/test/java/cn/safebank/MySqlBankBoundaryTest.java`。

**Interfaces:** `MySqlSchemaVerifier.verify(): void`只查询当前database元数据，核查必须约束，不执行DDL；mysql profile禁用原H2 initializer建表分支，使用SQL方言元数据。原H2完整行为继续原配置。

- [x] 写MySqlBankBoundaryTest：漏1张风险表、缺1个归属FK、部分context表都启动失败；不自动修复。实际MySQL检查重复submit无双记账、余额不足回滚、借贷和0、UNKNOWN保留原ID/额度、两连接竞争锁与版本冲突。
- [x] `./verify-mysql.ps1 -Tests MySqlBankBoundaryTest`确认原initializer对MySQL不适配时失败。
- [x] 按databaseId实现必需元数据核验；保留现有Service短事务，不新增跨网络事务。重用MyBatisExecutionTest/RiskExecutionTest的关键断言，在新mysql测试里以实际连接核对而非仅改“总表数”。
- [x] 同命令通过，保存约束/账务断言结果；针对真实失败修复一次并定向复测，不无依据反复整组跑。
- [x] 提交本任务，比赛安全自评R07仅按覆盖项更新，真实MFA/银行等风险仍开放。

### T5：独立候选交付与材料同步

**Files:** Create `start-mysql-demo.ps1`；Update `docs/开发进度.md`, `docs/任务索引.md`, `docs/competition/材料进度与证据索引.md`, `技术文档-系统架构.md`, `技术文档-安全设计.md`, `安全自评报告.md`；Create `docs/2026-10-10-v07-mysql-foundation-验收.md`（日期按实际交付更新）。

**Interfaces:** `start-mysql-demo.ps1 -Port 8130`要求显式候选DB/运行账号、离线模型，检查端口和database名；未指定新库拒绝，不沿默认H2文件路径启动。不自动启动Python或创建bank外的库。

- [x] 验证脚本在缺候选库/账号、端口占用、迁移不完整时拒绝启动；安全日志不出现密码、API key或URL凭证。
- [x] 执行T1–T4一次组合验收并完成原H2关键回归一次，明确付费测试跳过；启动8130只做会话/账单GET，不发送模型请求，核对原入口未切换。
- [x] 写验收与列级diff证据索引；对照本计划逐项更新勾选和未覆盖项，不把M1称为Python迁移完成。
- [x] 同步比赛材料：MySQL业务基础标已验证范围，Python网关/对账/预算/图保持待实现；简介只有最终可演示能力变化才改。
- [x] 提交本阶段；不合并、不推送、不准备最终README/GitHub包。随后为M2写范围有限的独立实施计划。

## 本计划范围之外

网关内部认证、grant续签Service、工具receipt、幂等预算接口、JavaACK与reaper、Python运行表和生产graph/effect、网页进度全部属于下一子项目；其表DDL可先建立，但不能称这些协议已实现。M0假图结果不替代上述集成验收。

## 自审与状态

已对照首阶段设计和数据库规则：本计划覆盖M1，M2/M3/M4明确分离，避免未经验证的同时改动。Review Focus各有对应任务断言。现有旧脚本run.ps1固定test profile，因此新MySQL验证脚本不能简单调用run.ps1冒充MySQL验证。M1已执行并验证；一次独立最终审查完成后交付。执行取舍及覆盖边界见M1验收，M2只建立范围草稿。

最终状态：已完成独立审查与2项Important的失败回归修复；MySQL17项通过、H2269项通过，最新候选已重新打包与启动。测试临时库因C盘耗尽受控清理，证据和候选保留；范围与取舍见M1验收。
