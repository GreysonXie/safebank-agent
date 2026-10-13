# M3 LangGraph Agent 运行数据设计

实现已批准v0.7设计第4、6、10节。新候选 Java 8134 / Python 8135；bank库 `safebank_bank_v07_m3` 沿用V1/V2/V3，27表。Python库 `safebank_agent_v07_m3` 使用独立账号，不能读写bank库。M1/M2程序包、分支和数据保留，交付时只运行M3网页。

Python自有五表使用版本化SQL迁移（migration历史另表）；同步 `PyMySQLSaver` 原生setup建立自身表，不重写其内部DDL。沿用M0锁文件：Python3.12.14、LangGraph1.2.12、langgraph-checkpoint4.2.0、langgraph-checkpoint-mysql3.0.0、PyMySQL1.2.3。关闭LangSmith tracing。首次创建由单独迁移账号，运行账号只对本库SELECT/INSERT/UPDATE/DELETE，启动核验schema，不以运行账号自动建表。

## 应用表

- `agent_thread_bindings`：id/user_id/conversation_id/thread_id/schema_version/last_output_version/waiting_run_id，时间created_at/updated_at。PK id；UNIQUE(user_id,conversation_id)、UNIQUE(thread_id)。锁binding后判断活跃run；thread由服务生成，用户无法指定。
- `agent_runs`：id/binding_id/java_turn_id/request_id/request_hash/request_json/thread_id/input_version/output_version/state/lease_owner/lease_revision/lease_until/result_json/result_hash/result_kind/error_code/interrupt_id/created_at/updated_at/acknowledged_at。UNIQUE(java_turn_id)、UNIQUE(binding_id,request_id)，FKbinding。request_json只含Java可信投影和客户文本，无grant/key/Cookie。inputVersion只来自Java，outputVersion只在原ACK后填。
- `agent_effect_receipts`：operation_key/origin_run_id/binding_id/origin_java_turn_id/node_name/logical_sequence/purpose/input_hash/state/reservation_id/result_json/result_hash/lease_revision/created_at/updated_at，PK稳定operation_key，FKrun/binding。状态PREPARED/SENDING/SUCCEEDED/FAILED/UNKNOWN；SUCCEEDED需结果/hash，SENDING模型恢复只标UNKNOWN不重发。逻辑key不含leaseRevision，银行tool重放同原key。
- `agent_model_requests`：id/run_id/reservation_id/purpose/state/provider_response_id/actual_model/prompt_tokens/completion_tokens/cache_hit_tokens/cache_miss_tokens/reserved_at/finished_at/error_code。reservation_id UNIQUE；缺失usage为NULL，不能用0假装供应商无收费。
- `agent_run_events`：id/run_id/sequence/node_name/event_code/detail_json/recorded_at；UNIQUE(run_id,sequence)，不保存CoT或原始凭证。

时间统一UTC DATETIME(6)，金额只来源Java整数分。run状态PROCESSING/RESULT_READY/NEEDS_CLARIFICATION/COMPLETED/FAILED/INTERRUPTED/RECONCILIATION_REQUIRED。UNKNOWN只属于effect。索引runs(state,lease_until,id)、runs(state,updated_at,id)、effects(state,updated_at,operation_key)。

## 原子性与恢复

claim锁binding与run，60秒租约、revision fencing，每10秒续约。单binding同时一个PROCESSING或未ACK RESULT_READY；结果不可变，ACK必须匹配原turn/inputVersion+1/resultHash。ACK后才保存binding最后业务版本和等待interrupt的原run，不能用schemaVersion替代。

graph节点为plan→tools→draft→validate，最多一次回答修正；澄清wait节点只interrupt/接收新Java已受理轮次，无网络副作用。用户补参是新Java turn，核验上轮已ACK版本、binding和interrupt ID，复用同thread的checkpoint。Javareset/版本不匹配使旧等待失效，保存旧记录并建立新thread；模型可在新规划阶段识别用户改口，不强行套用原意图。

所有模型/tool发送经过effect。先PREPARED，真实模型先Java幂等reserve，再租约CAS标SENDING，退出事务单次HTTP，不启用SDK自动重试；SUCCEEDED结果持久化后才交给graph。重放复用相同hash结果。模型SENDING/FAILED禁止自动重发；tool不确定可用原key查询Java原快照。租约丢失后旧runner不能写run/effect；checkpoint操作前后核验lease，graph本阶段单进程同步执行，未声称跨Python实例checkpoint写fencing。

POST立即受理并由有限worker执行；GET查询原run。Java对账发现恢复需grant时签发本轮新能力，向Python原run恢复入口传递，不生成新requestId。Python重启后丢失的仅为内存token；不会把token从checkpoint取回。存在未知模型effect时暂停，不发送恢复调用。

## 费用

M3独立审查补充：仅langgraph-agent启用V4银行表`agent_model_turn_limits(turn_id PK/FK agent_gateway_turns.turn_id, input_limit INT CHECK 1..5, created_at DATETIME(6))`。Java受理原轮时在同一事务保存min(5,请求上限或默认5)，预算预留读取持久化值，Python不能覆盖。历史已受理轮次按原请求摘要匹配1..4，其他为5；M2 V3数据库不改动。Java start投影可附modelRequestLimit，Python限制只用于规划提示，收费权威仍在Java。PREPARED预留响应丢失释放原租约并要求续签原轮grant；原key再取原reservation，SENDING/UNKNOWN仍不得自动重发。

Java DebugRequestBudget继续唯一写共享账本 `20261010-v06-context.json`，继承既有11/20，不能重置。幂等reserve绑定operationKey/inputHash/javaTurnId/purpose；同key不同输入拒绝，重复reserve回原id；每Java轮最多5次，所有新旧native调用仍共用20。record只填同一预留的usage，不增加发送权限。mock模型不请求供应商，也不占真实共享账本；预算契约用隔离测试账本验证。阶段真实烟测最多2次模型HTTP，先检查剩余额度，不为修正/审核突破此阶段上限。
