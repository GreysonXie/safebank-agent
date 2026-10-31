# SafeBank v0.7 M1：MySQL 业务基础迁移验收

2026-10-10。本阶段复用现有 Java/MyBatis 业务，未建立 Python 生产 Agent 服务。原版本保留；无合并、推送或比赛材料提交。本阶段真实 DeepSeek 请求 **0 次**。

## 实现与范围

- Java21 / Spring Boot3.5.6 / MyBatis3.0.5；MySQL8.4.11、Connector/J9.4.0、Flyway11.7.2。
- Flyway V1 建立现有24表的完整最终列结构，V2增加 grant/tool receipt 两表。后两表只有存储结构，令牌签发、续签、工具回调与 ACK 协议均未实现。
- mysql profile 使用独立连接和迁移凭证；应用账号仅有指定业务库的 SELECT/INSERT/UPDATE/DELETE。H2路径默认关闭Flyway，保留旧初始化行为。
- UTC DATETIME(6) / mysql专用类型处理器和微秒业务时钟；上海自然日与 BIGINT 整数分保持原含义。NULL保留，原 offset 不保存，纳秒向下截断为微秒。
- JSON/CLOB改LONGTEXT，不改 MySQL JSON 类型，不重新序列化授权payload/hash。固定 utf8mb4_0900_bin，大小写和尾部空格可区分；原utf8mb4_bin为PAD SPACE，不能满足此边界。[MySQL官方说明](https://dev.mysql.com/doc/refman/8.4/en/charset-binary-collations.html)
- MyBatis按databaseId区分两条upsert；核对动作归属，拒绝不同归属写入。DAO统一插入/更新返回值，仍保持一条记录成功的现有服务契约。
- MyBatis只读启动核验对照26表InnoDB/表collation、V1/V2列、CHECK谓词、唯一键和复合FK指纹；不创建、补表或修复约束。后续迁移需有意更新此清单。

## 实际验证

| 验证 | 结果与限制 |
|---|---|
| 迁移与源结构 | 隔离H2与实际MySQL导出结构，206原列逐项解释；原PK/唯一/FK保留；26业务表；连续migrate不重复执行；坏余额和孤立context记录被拒绝 |
| 标识符 | CaseId / caseid / CaseId加尾部空格可同时存在；不依赖默认大小写不敏感排序 |
| 时间 | +08 / UTC同instant、纳秒截断、NULL、上海跨日；expires_at等于now不能提交；生产微秒时钟补充验证见最终证据 |
| 映射/种子 | 24表原resultMap SQL读取、全部源合成行值对照；16000字符以上中文JSON与SHA-256原文等价；退款/内部转账口径保留；重复seed拒绝。空表读取不代表验证了所有实体写路径 |
| 账务 | 重复提交不双记账、两条ledger和为0；第二条ledger冲突回滚两端余额和交易行；余额不足不写账；UNKNOWN保留原action与SUBMITTED占用 |
| 启动损坏库 | 缺risk_events、缺context_slots、缺归属FK、缺余额CHECK、ledger改MyISAM均拒绝；再次启动不自动修复 |
| 并发 | 两条实际连接争同行锁；旧版本CAS更新0行，新版本更新1行；未声称所有业务竞态已覆盖 |
| MySQL组合 | 审查修复后最终17项，0失败/错误/跳过；时钟补充后，2项时间与7项应用边界共9项全部通过；结构CHECK导出补充1项通过 |
| 原H2回归 | 一次完整package：284项选择，269通过，15跳过（14项MySQL、1项付费）；0失败/错误；时钟profile调整后2项H2账务定向回归与重新打包通过 |
| 启动脚本 | 正确配置通过预检；缺配置、H2地址、root运行账号、原端口、被占用候选端口5项拒绝；完整坏schema拒绝由实际应用启动测试覆盖 |

证据：[列级diff](evidence/2026-10-10-v07-mysql/column-diff.json)、[隔离H2结构](evidence/2026-10-10-v07-mysql/h2-structure.json)、[实际MySQL结构](evidence/2026-10-10-v07-mysql/mysql-structure.json)。最终运行与构建摘要在同目录，不保存凭证、Cookie或原始运行日志。

## 候选交付与限制

独立候选8130已完成只读会话/账户/账单HTTP检查，模式OFFLINE_DEMO；运行账号建表被拒绝；重启后2个归属账户与2026-09支出536933分保持，未重新seed。全库5个账户、114条合成交易，总余额7588480分。8096、8110、8111不由本次替换或重启。

Flyway11.7.2运行时警告其标称最新支持MySQL8.1；本机8.4.11迁移及以上测试实际通过，不等同于官方完整兼容认证。正式部署前单独验证兼容受支持组合；本阶段不顺带升级Boot或依赖。

本地SSL使用自签名测试证书且未校验证书身份，仅限本机loopback。无真实登录、真实MFA或真实银行；未验证生产负载、异步/多实例LangGraph、Java/Python ACK、预算和对账。MySQL技术迁移不证明模型回答质量提高。

后续M2建立只读内部网关、归属/版本、短期能力和回执、ACK与对账；M3接入Python官方LangGraph。作品简介保留当前可演示能力，PPT/视频和最终源码发布材料仍留到最后。

## 实施取舍记录

- Windows使用PowerShell执行账本和脚本，不安装POSIX环境；若不等价，影响执行记录完整性。
- NO PAD collation与Boolean域约束保护源语义；若判断错，需新迁移而不是修改已应用V1。
- 全结构MyBatis manifest替代只数约束；后续DDL必须同步审阅manifest，否则启动会拒绝。
- 事务测试金额5000分，避免共享合成用户累计触发强验证；风险策略不变，该组不验证真实MFA。
- 程序时钟统一微秒，只在MySQL profile生效；不保留原offset或纳秒。
- 保留本计划忽略目录中的本机凭证和进程登记，以支持后续M2；不提交、不删除正在使用的候选配置。

## 最终审查修复与交付状态

一次独立审查发现2项Important、无Critical/Minor：缺少引擎检查、NULL归属upsert漏拦截。实际失败回归复现3个断言失败；一次修复后MySQL整组17项全部通过，NULL原字段不变且不猜填，MyISAM拒绝启动并不自动修复。[审查与修复](evidence/2026-10-10-v07-mysql/final-review.md)

修复后H2复测287项选择、269通过、18跳过（17项MySQL与1项付费），0失败/错误。活跃候选造成Windows JAR重命名失败；仅停止身份已核验的8130后重新打包成功，未重跑已通过测试。新包已启动，GET会话/账户/账单与Flyway V1/V2只读核对通过；原入口保持原进程。

工具收尾发现C盘耗尽。核验本项目独立MySQL数据目录后，只清理99个本阶段UUID临时测试库，保留26表候选和测试入口库，未删除原版、M0实例或其他项目数据；恢复约250MiB空间。所有结构/测试证据已导出，临时数据库可从迁移与fixture重建。[清理边界证据](evidence/2026-10-10-v07-mysql/test-db-cleanup.json)。后续开发前优先把本项目MySQL数据存储迁到空间充足的D盘，保留Windows所需ASCII入口。

最终运行/构建与包SHA-256见[验证摘要](evidence/2026-10-10-v07-mysql/verification.json)，实际迁移见[runtime-migrations.json](evidence/2026-10-10-v07-mysql/runtime-migrations.json)。无付费模型请求、无合并/推送。下一阶段范围见[M2草稿](superpowers/plans/2026-10-10-v07-java-agent-gateway.md)，尚未实施。

## 后续本地环境整理

经用户授权，MySQL 程序及数据已迁到 `D:/SafeBankRuntime/mysql`，逻辑及冷备保留在 D 盘。26 张表结构和全部数据、数据库用户校验一致，8130 候选已恢复。8096/8110/8111 旧服务现已停止，代码及数据保留；上文“原入口保持原进程”描述的是 M1 交付时状态。仅清理 C 盘两个已迁移副本，释放约 1.59 GiB。[当前运行位置及恢复说明](2026-10-10-本地运行环境整理.md)。
