# M2 Java 只读网关数据设计

本设计细化已批准 v0.7 设计第 4、10 节，不改变资金表。使用独立 `safebank_bank_v07_m2`，MySQL 8.4.11；M1 数据库只读保留。基础 V1/V2 不变，新增迁移放 `db/migration/mysql-agent`，仅 `agent-gateway` profile 启用，默认 mysql/native 路径仍只有 V1/V2。

V2 的 `agent_service_grants` 与 `agent_tool_call_receipts` 按原结构实现。总额度固定每轮 8，权威为唯一工具 receipt 数，续签不归零。锁顺序：conversation → 原 turn → grant；与原 ContextService 保持一致。Token 随机 256 位，仅存 SHA-256；所有 scope 固定为 statement、statement_compare、recurring_analysis、account_query。到期=min(签发时间+5分钟,conversation expiry,浏览器 session expiry)。同轮签发先撤销旧 ACTIVE 再插入，两者同一事务。

V3 新增 `agent_gateway_turns`，仅保存网关协议，不复制模型意图或槽位：

| 字段 | 类型 / 约束 | 用途 |
| --- | --- | --- |
| turn_id / conversation_id / user_id | varchar(80/80/40)，turn_id PK，复合 FK 指向原 turn | 可信归属 |
| request_id / user_text | varchar(80) / varchar(4000) NOT NULL | 恢复原 Java lease，非模型状态 |
| input_version | bigint >0 | 唯一 Java 输入版本 |
| session_expires_at | datetime(6) NOT NULL | 原浏览器会话上限，不由 Python 延长 |
| result_hash / result_json | char(64) / longtext NULL | 首次验证完成的不可变结果 |
| output_version | bigint NULL | 原 Java 完成 ACK 的版本 |
| reconcile_failures | int 0..3，默认0 | 连续服务不可达计数，达到3告警 |
| next_check_at / created_at | datetime(6) NOT NULL | 有界轮询、稳定游标 |
| state | varchar(24)，PENDING / ACK_PENDING / ACKED / PAUSED / INTERRUPTED | 协议状态 |

扫描索引 (state,next_check_at,turn_id)；按此排序 LIMIT 100。每30秒扫描到期记录，处理一次后更新 next_check_at，单轮失败不会无限占据第一批。ACKED 不再扫描。网络请求在事务外；保存结果、原 ContextService.complete、结果 hash、outputVersion、grant 撤销同一短事务，重复同 hash 返回原版本，即使当前 conversation 已开始下一轮。不同 hash/错误输入版本拒绝。Python 无 run 明确中断；未知/不可达保留待核对，3次失败 PAUSED，恢复后可继续对账。

Python 结果只提交结构化 `answer={kind,answer}`、evidenceIds 和 inputVersion。Java 仅从本轮工具快照构造 GroundedAnswer，拒绝未登记证据/手写金额/越权执行结论；先保存的结果不可被后续 Python 响应替换。M2 不调用付费语义审核，因此此核验只证明现有程序核验的边界，不能称模型语义质量已验证。
