# SafeBank v0.7 M2：Java 只读 Agent 网关

当前候选入口 http://127.0.0.1:8132/。Java 与 MyBatis、资金和权限服务保留；Python stdlib 服务在 8133 模拟协议，未接入正式 LangGraph，也未调用 DeepSeek。页面明确显示“网关协议模拟”。此阶段验证跨服务安全与恢复，不能证明自然语言回答质量提升。

## 实现范围

- 内部服务随机 256 位凭证，双方验证。银行工具另核验随机 256 位 grant，只保存 token hash，绑定原 user/conversation/turn/request。
- statement、statement_compare、recurring_analysis、account_query 四种只读工具；参数严格白名单，不接受用户身份或资金执行参数。TTL 最长5分钟且受原会话限制，边界到期即拒绝。
- 每轮8次新逻辑查询，权威为原轮唯一 receipt 数；续签同事务撤销旧 grant，不重置总额度。相同 operationKey/inputHash 返回原快照、原观察时间，不再查询或计数。
- Java conversation version 是唯一权威。先核验结构化回答和已登记证据，原轮 complete、展示/证据、不可变 resultHash/outputVersion、grant 撤销同一事务。相同结果重复 ACK 返回原版本，不重复增加；不同结果拒绝。
- ACK 未确认时，新轮暂缓；重复原请求仍能读取已保存的回答。HTTP 未确定时查询原 turn，不重发 start；Java到Python运行/ACK通道的正文与整个请求具有5秒截止时间和64KiB响应上限。fake到Java银行事实回调受3秒截止时间及256KiB读取上限限制；两月完整事实目录实测超过64KiB，按通道区别限制，仍有界。
- 每30秒扫描到期批次，最多100条，按 next_check_at/turn_id 排序并推进；网络在事务外。确认 NO_RUN 中断原轮；UNKNOWN/冲突暂停核对；连续3次不可达记录 PAUSED 并告警，恢复后重新核对原 run。
- 独立 M2 库 `safebank_bank_v07_m2`，受限运行账号与迁移账号分开；V3 仅 agent-gateway profile 生效。V1/V2 不改，M1 仍为原26表。M2 为27表，按真实 MySQL 导出并审阅的 manifest 校验，不自动修复坏库。

## 有限验证与证据

最终22项网关/HTTP检查全部通过（20项实际MySQL及2项HTTP传输测试），0失败/错误。MySQL/HTTP 覆盖：同请求重放/不同内容冲突、归属和版本、TTL边界、错误输入摘要/资金工具/附加身份拒绝、旧快照复用、跨续签8次额度、两连接并发续签与同key回执、grant/receipt插入故障回滚、ACK重复/下一轮后的旧ACK、未ACK新轮屏障、丢响应/丢ACK、不可达3次和恢复、NO_RUN中断、内部服务与grant双重认证。

旧版 H2 定向回归：ContextApiTest、ContextConcurrencyTest、ContextLifecycleTest、GroundedAnswerTest，共46项通过；没有重复全套 M1 测试。独立审查新增并发 begin 和响应正文停滞的失败回归，修复结果以 [最终检查摘要](evidence/2026-10-10-v07-gateway/verification.json) 为准。

真实候选合成账单的 2026-09 支出为536933分。相同 requestId 重放保留原回答和版本。Edge +现有 Playwright 检查页面标题、模拟状态、查询依据、导航点击、桌面左导航/中业务/右对话、390×844视口，0控制台错误；Browser 插件在本次会话未提供，未安装浏览器依赖。见 [UI证据](evidence/2026-10-10-v07-gateway/ui-check.json)、[桌面图](evidence/2026-10-10-v07-gateway/desktop.png)、[手机图](evidence/2026-10-10-v07-gateway/mobile.png)。

## 独立审查处理

一次审查：0 Critical、2 Important、1 Minor候选。

1. 外层事务使 ContextService.begin 在会话锁前建立 RR 快照，两个 requestId 可同时开始。实际 MySQL 第三连接锁屏障回归复现两请求都受理；修复为 gateway 先取得会话锁，再调用原 begin，不改变全局隔离级别或原 native 行为。
2. HttpClient 的 InputStream handler 只保证头部已收到，随后正文读取可能永久等待。假服务先发头与首字节后停滞，失败回归复现；修复为有界 BodySubscriber 和覆盖整个 body 的异步结果截止时间，超时取消请求。
3. “余额别名重复”未成立：tool 接口使用 factCatalog，已过滤 eN.* 别名；实际候选查询两个账户返回两个金额。按源码与实际 HTTP 证据拒绝此项，未盲目改变原事实目录。

本阶段只进行一次全阶段审查与一次重要问题修复，未再次派发审查。候选两月对比额外发现 JsonNode/Map 事实合同差异：普通 JSON 节点不能提供原 Map 专属月份前缀，导致月份事实覆盖；真实HTTP失败后在网关证据边界转换为原普通对象，新增两月份独立事实/金额回归。最终合并检查22项通过。

## 当前运行与恢复边界

MySQL 程序与数据在 D:/SafeBankRuntime/mysql，继续共用一个3307实例。仅当前 Java 网页8132和fake服务8133运行；8130及8096/8110/8111停止。M1原JAR的SHA校验副本保留于本机忽略目录，代码在 `archive/v07-mysql-m1`，M1数据未升级或重导入。早期原版本也继续保留。

`start-agent-gateway-demo.ps1` 校验独立数据库、账号、64位hex内部凭证与空闲端口后启动，不读取模型密钥。数据库凭证和服务密钥只留本机忽略配置/环境；首次导入种子独立执行，重复导入拒绝。进程启动输出不等于就绪，需检查 /api/session 的 FAKE_AGENT 模式。

fake run 只存在内存，不保存 grant。fake 重启后无法恢复其运行记录，Java已完成回答仍可原请求重放；未确认运行按 NO_RUN/暂停核对处理，不自动再执行。正式持久化、租约、interrupt/resume、模型预算/effect 和 LangGraph 图属于 M3。多 Java 实例资金协调、生产 TLS/真实登录/MFA/银行及Flyway标称支持范围问题仍开放。

未合并、推送或提交比赛；作品简介、技术文档、安全自评阶段性更新，PPT/视频与最终发布包继续暂缓。
