# M1最终独立审查与一次修复

审查范围e23a8e7..b2adba1，单个独立审查者只读核查；无模型、网络或数据库调用。发现2项Important，无Critical或Minor。

1. 原manifest未检查表引擎：ledger误改MyISAM后仍能启动，破坏账务回滚保证。
2. upsert中 `user_id <> 参数` 漏掉NULL：归属不明的旧观察/故障字段被覆盖，仍返回成功且后续按归属无法读取。

失败回归真实复现：12项中3失败——MyISAM被启动放行、NULL观察与NULL故障返回1而非0。

一次修复：manifest新增26表的引擎与表collation指纹；两个upsert使用NULL-safe等值的否定，拒绝NULL归属且不填充旧归属。回归断言全部原字段不变；MyISAM拒绝启动并保持原引擎，不自动修复。

修复后实际MySQL17项全部通过（0失败/错误/跳过）。旧H2复测、当前包及候选检查结果见[verification.json](verification.json)与[candidate-http.json](candidate-http.json)。未再次派审查者；按已授权执行技能用失败回归与整组测试核验修复。

考虑后不作缺陷判断：M2/M3的Python、ACK、grant服务；设计冻结的旧可空列和历史FK；服务层负责同归属观察终态；已明确未覆盖的全部写路径/并发组合；已披露Flyway与本地TLS限制。以上保持对应实现范围或已知风险，不视为已解决。
