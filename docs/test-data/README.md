# v0.3 合成测试数据

全部为合成数据，不含真实银行、真实个人联系方式或真实使用记录。此目录只提供测试数据与评测草案；没有运行 Agent、真实模型或银行服务，不代表 24 例已通过。

## 产物与事实

- `fixtures/v03/generate.py`：Python 3 标准库生成器，固定种子 `20261007`；只写本目录三个生成文件，不连接数据库或网络。
- `fixtures/v03/v03-synthetic-bank.sql`：包含当前 `schema.sql` 的快照及初始化 INSERT，可独立装载到新隔离 H2，也可在 `schema.sql` 先建表后作为唯一 data 脚本加载。
- `fixtures/v03/facts.json`：期初、期末快照、每月统计、退款对应、文件哈希与业务事实。
- `fixtures/v03/scenarios.json`：24 例声明式评测草案，包含时钟、用户、初始快照、提示、允许动作、预期与运行时故障绑定。需要人工执行或另写适配器；不是现有 JUnit 用例格式。
- `fixtures/v03/VerifyFixture.java`：独立内存 H2 验证器，不使用应用的 DataSource、不启动服务、不读取环境密钥。

2026-04-01 至 2026-09-30 共 114 条流水（u1 113 条、u2 1 条），5 个账户、3 个收款人、5 项代扣。每月工资、租金、餐饮、超市、电费、通信、交通与订阅，另有旅行、医疗、维修与跨月全额退款。金额全为整数分：正数为收入/贷记，负数为支出/借记；内部转账有同日等额借贷两条。没有预造 actions、ledger、回执、审计、workflow 或故障执行记录。

| 账户 | 2026-04-01 期初（分） | 2026-10-07 初始快照余额（分） |
|---|---:|---:|
| checking | 3000000 | 5154480 |
| travel | 2000000 | 2334000 |
| other | 0 | 100000 |
| external-a / external-b | 0 | 0 |

快照余额等于期初加该账户全部有符号流水，按日期和 id 排序的过程余额非负。u1 两个账户每月内部转账 100000 分，合计净额为零，排除在月收入/支出外。外部工资、商户支出来自模拟银行外部边界，没有在当前账户集合伪造工资付款方或商户账户。

两名张三使用 `TEST-PHONE-3271` / `TEST-PHONE-8862`，这是不能拨打的测试标识；可用同学/同事、recipient id 或账户标签澄清。保留原 checking、travel、music、video、travel-recipient 等 ID 以供现有页面与工具读取。不要把测试标识当真实手机格式校验样本。

music / video 明确 MONTHLY，已核验撤销后可分别计 1500 / 6800 分预计月度节省。cloud-unknown 历史上近似按月但授权周期 UNKNOWN，不能直接计入已确认月度节省；quarterly 是 QUARTERLY；revoked 已撤销，没有新增节省。全部 contract_status 为 UNKNOWN，不代表商户退订或合同终止。没有使用记录，不能回答“从未使用”。

## 复现与已完成验证

在项目根目录运行（Python 3、Java 21 和已有本地 H2 jar）：

```powershell
python fixtures/v03/generate.py
& 'C:\Program Files\Java\jdk-21\bin\java.exe' '-Dfile.encoding=UTF-8' --class-path '.m2/com/h2database/h2/2.3.232/h2-2.3.232.jar' fixtures/v03/VerifyFixture.java fixtures/v03/v03-synthetic-bank.sql
# 或验证 Spring 等效的 schema 先建表、fixture 单独作为 data 路径：
& 'C:\Program Files\Java\jdk-21\bin\java.exe' '-Dfile.encoding=UTF-8' --class-path '.m2/com/h2database/h2/2.3.232/h2-2.3.232.jar' fixtures/v03/VerifyFixture.java fixtures/v03/v03-synthetic-bank.sql src/main/resources/schema.sql
```

本次已用本机 Python 3 与 Java 21 / H2 2.3.232 实际验证：独立装载和 schema 先建表两条路径、新内存库装载、114 条/六个月、余额与逐笔历史核对、同名收款人、交易归属、引用完整性、内部借贷配对、8300 分已知月度节省、运行时证据表为空、重复装载在空库防护处拒绝且原数据与余额不变。生成器重复运行三个产物 SHA256 完全相同。

SQL 要求所有业务表为空；非空时停止报 `SYNTHETIC_FIXTURE_REQUIRES_EMPTY_DATABASE`。装载器必须遇错立即停止（Spring 默认 `continue-on-error=false`，H2 RunScript 默认如此），不能开启 continue-on-error。脚本无 DELETE、DROP、TRUNCATE、UPDATE 或 MERGE，不用于已有运行库。嵌入 schema 的 DDL 不等于通用迁移，防护不替代新建隔离库要求。

## 隔离应用装载参数（供审核后执行）

数据生成子任务只说明启动方法，未自行启动服务；父任务后续已启动并完成下文的真实模型验证。项目根目录中可用 Maven 单独传参：

```powershell
./run.ps1 package
& 'C:\Program Files\Java\jdk-21\bin\java.exe' '-Dfile.encoding=UTF-8' -jar target/safebank-agent-0.4.1.jar '--spring.datasource.url=jdbc:h2:mem:v03synthetic;DB_CLOSE_DELAY=-1' '--spring.sql.init.schema-locations=classpath:schema.sql' '--spring.sql.init.data-locations=file:fixtures/v03/v03-synthetic-bank.sql' '--spring.sql.init.continue-on-error=false' '--server.port=8081' '--bank.model.api-key=' '--bank.simulation.faults-enabled=false'
```

覆盖 data-locations 后不会装载原 `data.sql`。使用内存库进程退出即丢弃，不碰默认 `data/safebank`。jar 版本路径随父项目版本变化；先核对构建产物名称。命令明确空模型密钥，只做本地合成模式；本任务未验证应用启动和模型对话。若执行部分失败草案，独立测试环境显式开启 `bank.simulation.faults-enabled=true`，在真实预览返回 action_id 后配置草案所列 FaultMode；绝不预造 action id 或授权。每个场景使用全新内存库与初始快照，并通过现有 TestClockConfiguration/测试依赖注入固定到 `2026-10-07T10:00:00+08:00`；该命令本身没有冻结应用时钟。

## 场景与已知限制

包含六个月正常账单、2026-03 空月份、退款跨月、内部转账排除、200000 分阈值、重名与澄清、缺金额/来源/计划日期、归属、按月/季度/未知周期、无使用记录、完整成功、部分失败、UNKNOWN 原动作查单恢复和已撤销对象。空月份只表示该合成历史窗口中无数据，不推断真实历史没有支出。

按次配送的日期间隔是 10/80/12/38 天，平均 35 天；现有 recurring 平均间隔规则可能纳为候选，租金、工资外的重复消费或内部转账也有规则局限。草案明确这不等于已验证的月订阅。维修恰好 200000 分、租金与家电超阈值只是异常候选，不等于欺诈。退款在现有账单工具归入正向收入，原八月支出不被追溯抹除；退款关联存在 facts.json 中，现有 schema 没有退款关联列。

联合任务成功/部分成功草案登记未来定时任务，当前 transferred_minor 为 0；8300 / 1500 分是已核验撤销形成的预计月度节省，不能当已退款或已经转账。未知周期和季度授权排除于该估算。用户回复条目是人工授权步骤说明，必须让用户看到具体对象/金额/时间再由现有授权接口确认，不是自动批准凭证。

SQL 验证通过仅说明数据装载与账务约束；24 例语义期望、Agent 表达、故障执行和留出集评测均未实际运行，待团队 C/B 独立适配和复测。本文不创建生产账户、真实个人资料或真实银行凭证。

## 父任务后续验证

六个月数据已在8093在线演示及隔离8094评测实例装载。另建的live-reply-cases.json包含20例查询/预览用例，evaluate_live_replies.py每例重复3次：60/60实际DeepSeek调用通过，初始账户余额保持不变；未调用授权或提交接口，预览草稿取消。报告target/eval/v03-live-replies.json。它与上述24例场景草案分别计数，不代表草案中的资金执行、故障和完整恢复路径已做真实模型评测。

父任务还验证了具体金额、收支分类、手机布局及原8092地址更新。8093/8094使用内存库，重启重置数据；8092保留原文件数据库，未将本SQL装入已有库。
