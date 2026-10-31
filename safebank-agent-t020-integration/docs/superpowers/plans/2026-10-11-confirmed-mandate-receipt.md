# 确认后的代扣查询回执接入设计与实施计划

2026-10-11，用户已授权执行。复用现有代扣QUERY_MANDATES确认和风险分级，不新增直接读取完整列表的模型工具。

新增V5表agent_mandate_query_receipts：action_id主键，user_id、conversation_id共同归属外键指向动作/会话，查询时刻及expires_at、snapshot_json和snapshot_hash；bound_version保存绑定时会话版本，invalidated保存失效状态，重置时主动失效。先绑定待确认动作，只有RiskQueryService完成真实授权查询后填入一次快照。快照有效五分钟，不可因模型重复读取延长；代扣撤销成功时失效该用户所有快照。资金执行不受模型控制。旧业务查询不传会话仍可执行，不自动给模型共享。

QueryIntentRequestDTO增加可选conversationId、expectedContextVersion，保留单参数构造兼容。绑定时锁定本人会话，核对有效期和版本；旧业务查询不绑定。UI已有会话时传入版本，无会话先建立一个空会话（新增创建接口，不收费），再显示原Action确认卡。确认完成后提示用户回到对话继续提问；不自动触发付费聊天。

新只读工具mandate_receipt args={}，只读当前grant的用户/会话已确认、未过期、未失效的快照。没有回执返回QUERY_CONFIRMATION_REQUIRED提示、不含代扣数据和事实；不是自动读取授权。已确认时以list_mandates证据产生既有mandates_latest/mandates_summary事实，同时附查询时刻/有效截止供事实来源说明。bank grants/effect hashes/配额和Java核验仍适用。Python允许该工具并展示安全固定的不可用原因，引导业务页确认。

实施：Java持久化及授权绑定；Python工具、mock及UI传会话；临时MySQL未确认/跨用户会话/确认快照/过期/重置/撤销及幂等回归；网页实际确认和追问测试；必要打包迁移运行候选；更新算法、安全、验收文档。新增表独立迁移，不改旧迁移。


审查修正：latest/findValid只对回执表作FOR UPDATE当前读，避免RR旧快照恢复已失效回执；网关先锁会话，银行查询/撤销先锁动作再写回执，回执读取不反向锁动作。两项真实MySQL交错事务回归已RED/GREEN验证。汇总事实同样附原查询时刻。首次网页聊天先创建当前标签页空对话，避免共享HTTP Session默认对话；原请求重试保留既有身份。

完成状态：V5已迁移现有8134免费候选；33项Java/MySQL、6项Python、5项前端绑定回归及桌面/390px移动网页实际流程通过。资金三表不变。验收与明确限制见docs/2026-10-11-已确认代扣对话接入.md。
