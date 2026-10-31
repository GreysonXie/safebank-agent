# MySQL 检查点兼容预验证任务书

2026-10-10。状态：用户已确认并执行同步假图实验，9项检查通过；[结果与限制](../../2026-10-10-v07检查点预验证结果.md)。以下是批准的任务书，实验依赖与专用库只用于probe，正式业务表尚未建立。

## 问题与范围

官方LangGraph与社区langgraph-checkpoint-mysql在选定固定版本、Python3.12、MySQL8.4上，是否能保存澄清interrupt、跨进程resume、pending writes，以及持久化操作结果复用？第一轮用同步PyMySQLSaver；异步不是必要条件。

只用假节点、假HTTP供应商、实验库safebank_checkpoint_probe_v07。不创建银行24表、grant或业务图骨架；不连接原库、不读API key、不请求DeepSeek、不改原服务。实验产物明确throwaway，可复用结果与兼容报告，不将probe代码直接当生产安全实现。

## 时间盒

环境准备单列：检查Python/MySQL现有环境，缺失项明确报告；不升级/卸载用户现有实例。实验累计4小时有效工作，定位后最多一次修复复测；超过上限保留复现与结论，停止试组合。实际墙钟时间不承诺1–2天，团队每日投入尚未确认。

## 验证矩阵

| 项 | 观察与通过标准 |
|---|---|
| 固定依赖 | 导出精确版本、依赖树及锁文件，setup创建真实表/索引清单；仅新实验库 |
| 暂停 | 假查询完成→无副作用澄清interrupt，记录thread/checkpoint/节点与原输入 |
| 重启 | 关闭原进程，以新进程同thread补参resume，结果及历史状态可读取 |
| pending writes | 并行纯本地节点一成功一失败，恢复时成功节点结果仍保留；记录调用次数 |
| 成功effect复用 | fake model结果已存而graph尚未提交时故障，恢复读取结果；HTTP发送和预算计数不增加 |
| 未知effect | fake model SENDING状态时故障，不论服务收到与否都不自动重发；UNKNOWN可查询 |
| 重复恢复 | 同补参request重复读取原结果；下一轮改参必须新逻辑key，不能错误命中旧结果 |
| 拒绝旧版本 | stub Java权威版本变化，旧interrupt resume被拒，schemaVersion不能绕过 |

## 输出与决定

输出环境清单、真实DDL结构、固定版本、每个场景结果与故障位置；区分通过/失败/环境阻断，不将“能导入包”当持久化通过。

通过则形成应用实施计划。失败先定位同步driver/API/serializer边界；Plan B为同MySQL的最小同步Adapter。需要自研Saver时另行写范围/接口设计并审阅，官方BaseCheckpointSaver与conformance及上述矩阵都必须通过。时间盒耗尽不自动引入SQLite、内存或无checkpoint状态机；旧native版本仍可用。

本任务书的同步假图范围已执行；没有真实供应商成本、生产可靠性、多实例或async实测结论。结果不得用于宣称Java/Python正式迁移已完成。
