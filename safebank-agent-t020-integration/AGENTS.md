# SafeBank 开发导航

- 修改后端分层、事务或 Mapper 前，读取 CODING_STANDARDS.md 和 docs/superpowers/specs/2026-10-08-layered-mybatis-design.md。
- 修改表结构前，读取 docs/database/2026-10-08-database-design.md；本次迁移冻结现行 13 表，后续增强独立迁移。
- 当前计划：docs/superpowers/plans/2026-10-08-mybatis-layered-refactor.md；阶段结果见 docs/2026-10-08-mybatis-迁移验收.md。
- PowerShell：`./run.ps1 start`、`./run.ps1 test`、`./run.ps1 package`。测试使用临时库，付费联调显式启用。
- 银行、资金与身份验证只用于本地合成演示；模型只查询或生成预览，用户逐动作授权。
- UNKNOWN 查原 action_id；恢复保留原动作与期次，不重复创建资金动作。
- 密钥只从本机环境读取，禁止写入日志、测试报告、聊天或提交。
