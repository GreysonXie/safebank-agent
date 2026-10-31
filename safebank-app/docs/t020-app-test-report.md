# t020 版 App 功能验收报告

- 日期：2026-10-31
- App：`safebank-app`（Kotlin/Compose，APK 13.74 MB，10-31 19:14 构建）
- 后端：`safebank-agent-t020-integration\safebank-agent-t020-integration`（Spring Boot 3.5.6，profile `risk-demo,context-demo`，H2 内存库）
- 设备：Android 37 模拟器 `SafeBank_API37`（AEHD 硬件加速），App 经 `adb reverse tcp:8080 tcp:8080` 连接 `127.0.0.1:8080`
- 测试方式：模拟器 UI 实际操作（adb tap/uiautomator dump）+ 同源 HTTP 对照后端原始响应

## 一、结论

**不完善。** 5 个原有 tab（对话/账单/代扣/储蓄/更多）的业务链路在新后端下**全部正常**；但本批新增的两个二级页（金融推荐、卡片管理）**程度不同地不可用**：

- 「金融推荐」能调通、能出结果，但关键字段失真（起投 ¥0.00、目录/生成时间空白、排除方向产品名空白）。
- 「卡片管理」基本不可用：卡片余额/限额/卡号/开关全部失真，且**所有卡片操作都走不完**（无确认按钮）、在线/境外开关**必定报错**、没有申卡/挂失/改限额入口。

根因不在后端：t020 新增模块（cards/finance）的 VO 是**裸 camelCase record**，而 App 沿用了旧契约「银行侧响应 snake_case」的假设建模；此外还有状态常量、必填参数、缺失入口三处实现问题。**全部可在 App 端修复，后端不用改。**

## 二、分项测试结果

| 模块 | 结果 | 证据 / 说明 |
|---|---|---|
| 账单分析 | ✅ 通过 | 账户 2 个、收入 ¥11,700.00 / 支出 ¥5,369.33 / 内部转账 ¥1,000.00、9 类目、19 笔交易，与后端一致（`t020-shot-2.png`） |
| 对话助手 | ✅ 通过 | 点「分析账单」→ 意图=账单分析、记忆 2026-09、分类金额、2 笔大额候选、2 条轨迹（`t020-shot-13-chat.png`） |
| 代扣管理 | ✅ 通过 | 「确认查询范围」→ 生成 QUERY_MANDATES 卡（黄风险）→ 勾选 → 确认并查询 → 返回 3 条代扣（云空间 ¥12/周期未确认、音乐服务 ¥15/按月、季度安全服务 ¥180/按季度）（`t020-shot-12-mandates-result.png`） |
| 转账（常用工具） | ✅ 通过 | 查询收款人（重名手选 zhang-a）→ 金额 500 → 预览（黄风险、手续费 ¥0）→ 勾选 → 确认并执行 → **银行已确认成功**、回执 `08eac682-504d-4baa-ab24-1e5ea00491e3`（`t020-shot-17-transfer-result.png`） |
| 底部导航 / 更多 | ✅ 结构正常 | 5 tab 未变；更多页新增「金融推荐」「卡片管理」两个入口（`t020-shot-3-more.png`） |
| 卡片管理 | ❌ 不可用 | 见 P0-1/2/3/4 |
| 金融推荐 | ⚠️ 半可用 | 见 P1-1/2；接口筛选逻辑本身正常（1 候选/6 排除/7 评估/4 规则与后端一致） |
| 设置 | ✅ 正常 | 显示模型 `deepseek-v4-pro`、服务器地址、连通性自检正常（`t020-shot-3-more.png`） |

## 三、缺陷清单（App 端）

### P0（功能不可用）

**P0-1 卡片 VO 字段名不匹配（snake_case ↔ camelCase）**
后端 `BankCardVO` 输出 `accountId/accountLabel/balanceMinor/cardType/maskedNumber/perTransactionLimitMinor/dailyLimitMinor/onlineEnabled/overseasEnabled`；App `Models.kt` 的 `BankCardVo` 用 `@SerializedName("account_id")` 等。
实测：同一张卡后端 `balanceMinor=5154480`、`dailyLimitMinor=1000000`、`perTransactionLimitMinor=200000`、`onlineEnabled=true`，App 显示 **`余额 ¥0.00 · 日限 ¥0.00 · 单笔 ¥0.00`、`在线✗`、卡号空白**（`t020-shot-8-cards.png`）。
同类问题在 `CardOperationVo`（`payloadHash/expectedVersion/createdAt/expiresAt/executedAt/receiptId`）与 `CardChangeDto`。

**P0-2 卡片操作「确认」按钮永不出现**
后端操作状态只有 `AWAITING_CONFIRMATION / COMPLETED / CANCELLED`（schema.sql 有 CHECK 约束）；App 只在 `status == "AWAITING_AUTH"` 时渲染确认/取消。
实测：点「锁定」→ 生成 `LOCK / AWAITING_CONFIRMATION`，页面只有历史记录、**没有确认或取消按钮**；连点多次堆积了 4 个悬挂操作（`t020-shot-9-card-lock.png`）。卡片操作永远无法完成。

**P0-3 在线/境外开关必定失败**
后端 `UPDATE_CONTROLS` 要求 `onlineEnabled` 与 `overseasEnabled` 同时非 null；App 每次只传一个。
实测点「开启在线」→ 红色错误 **「请明确选择线上与境外交易开关」**（`t020-shot-10-card-toggle.png`）。四个开关按钮全部不可用。

**P0-4 卡片缺少关键入口 + 空库无数据源**
后端支持 6 种操作（APPLY / UPDATE_LIMITS / UPDATE_CONTROLS / LOCK / UNLOCK / REPORT_LOST），App 只有锁定/解锁/两个开关；**没有申卡、挂失、改限额**。干净库无任何卡片种子数据，唯一造卡途径是 APPLY（App 无入口）→ 全新环境里卡片页只能显示空态，无法自证可用（本次测试卡是用 curl 造的）。

### P1（体验/完整性）

**P1-1 金融推荐字段失真**
后端 `FinanceProduct` 输出 `minimumAmountMinor/riskLevel/riskLabel/lockMonths/redemptionBusinessDays/capitalProtected/periodReturnBps/maxDrawdownBps/annualFeeBps/liquidityNote/riskNote`；`FinanceRecommendationVO` 输出 `catalogVersion/asOf/generatedAt/summary` 等 camelCase。App 按 snake_case 读取。
实测：`起投 ¥0.00`（后端 100 分=¥1.00）、目录行 **「目录  · SafeBank 本地合成产品库 · 生成 」**（catalogVersion/generatedAt 空白）、「未匹配方向」5 条**产品名全空白**（`t020-shot-18-finance-exclusions.png`）。

**P1-2 金融展示缺失/硬编码**
不展示 `liquidityNote/riskNote/redemptionBusinessDays/annualFeeBps`；"随时可取" 是写死文案，与后端 `redemptionBusinessDays=0`、`liquidityNote` 不一致，存在误导风险。

**P1-3 空闲后首个请求偶发 `unexpected end of stream`（稳定性）**
`SafeBankHttp` 设置 `retryOnConnectionFailure(false)`，Tomcat keep-alive 过期后复用死连接，偶发失败；再点一次即成功。本次在「卡片首次加载」「查询收款人」各复现一次。建议：GET/只读请求允许 OkHttp 自动重试（或对幂等请求自动重建连接一次），authorize/submit 仍保持"不自动重试"。

**P1-4 后端新增接口大面积未覆盖**
t020 的 `/api/**` 从 35 → 66 个：新增卡片 6、用户与联系人 5、模型设置 6、账单扩展 5（`/statement/year`、`filter`、`query`、`months`、`years`）、动作列表 1、`ACTIVATE_MANDATE`/`MANAGE_SCHEDULE` 两类新动作等。App 仅接入卡片 5 + 金融 1 条。用户管理、模型设置、账单扩展、新动作类型在 App 中完全不可见。

**P1-5 储蓄与旧模型的新变化未适配**
后端 `day_of_month` 已放宽 1..31（短月取月末），App 仍限 1..28；`ScheduleVO.status` 新增 `PAUSED/CANCELLED`，App 文案未覆盖。

**P1-6 金融金额单位易误填**
输入框标注「金额（整数分）」默认 100000；用户按"元"理解会差 100 倍，建议改为「元」并在内部换算。

### 附：一致性问题

两个新页面同时有 `DetailTopBar` + `PageHeader`（双标题）；新页用旧 `ui/components`，其它二级页用 `ToolkitComponents`，视觉两套；动作卡实现已有 3 份（actions/42KB、savings/28KB、PendingActionFlow），后续契约变化要改三处。

## 四、未测/未覆盖

1. 储蓄任务 tab 的定时储蓄、联合任务（本次时间未跑；后端 workflow 逻辑未变，但有 P1-5 的边界差异）。
2. 卡片 UPDATE_LIMITS / REPORT_LOST / 挂失不可逆等后端能力（App 无入口）。
3. 新后端用户/联系人、模型设置、账单扩展接口（App 未实现，无法在 UI 验证）。
4. 真机网络与弱网场景（仅模拟器 + adb reverse）。

## 五、建议修复顺序（App 端，预计 1 天内可完成）

1. `Models.kt`：把 cards/finance 相关 8 个模型的 `@SerializedName` 去掉（或改成 camelCase），同时 `SafeBankJson` 注册表无需改（类别已在）。
2. `CardsScreen`：状态常量改 `AWAITING_CONFIRMATION`；确认请求 `confirmed=true` + `payloadHash`；确认后刷新列表。
3. `CardsScreen`：`UPDATE_CONTROLS` 同时传两个开关（保留另一个的当前值）。
4. `CardsScreen`：补 APPLY（申卡）、REPORT_LOST（挂失）、UPDATE_LIMITS（改限额）入口；空态引导申卡。
5. `FinanceScreen`：金额改"元"输入并换算；补 `liquidityNote/riskNote/redemptionBusinessDays/annualFeeBps`；"随时可取"改为后端实际天数文案。
6. `SafeBankHttp`：GET 允许自动重试一次，POST 维持不重试。
7. 视需要：统一 `DetailTopBar`（删一条标题）、把新页迁到 `ToolkitComponents`。

## 六、截图索引

| 文件 | 内容 |
|---|---|
| `t020-shot-2.png` | 账单分析（新后端数据正常） |
| `t020-shot-3-more.png` | 更多页（新增金融推荐/卡片管理入口） |
| `t020-shot-5-finance-result.png` / `-6-finance-detail.png` | 金融推荐表单与结果（起投 ¥0.00、目录空白） |
| `t020-shot-18-finance-exclusions.png` | 未匹配方向（产品名空白） |
| `t020-shot-8-cards.png` | 卡片页（余额/限额 ¥0.00、在线✗） |
| `t020-shot-9-card-lock.png` | 锁定后停在 AWAITING_CONFIRMATION、无确认按钮 |
| `t020-shot-10-card-toggle.png` | 开启在线报「请明确选择线上与境外交易开关」 |
| `t020-shot-12-mandates-result.png` | 代扣查询授权提交后返回 3 条 |
| `t020-shot-17-transfer-result.png` | 转账授权提交成功 + 回执 |
| `t020-shot-13-chat.png` | 对话一轮结果 |

> 说明：卡片测试卡由宿主 curl 通过 `POST /api/cards/preview(APPLY)` + `confirm` 创建（label 在控制台显示为 `?????` 是终端编码问题，不影响 App 验证）。

## 七、修复与复验（2026-10-31 22:00–22:25）

### 修复内容

| # | 文件 | 修复 |
|---|---|---|
| 1 | `data/model/Models.kt` | 卡片/金融 8 个模型去掉 `@SerializedName`，改为与 t020 后端一致的 camelCase（`BankCardVo / CardChangeVo / CardOperationVo / FinanceProductVo / FinancePreferenceVo / FinanceExclusionVo / FinanceRecommendationVo`） |
| 2 | `data/network/SafeBankHttp.kt` | 只读 GET 遇到网络错误时丢弃连接池旧连接并重试一次；authorize/submit 等写操作仍不自动重试（修复 P1-3 偶发 `unexpected end of stream`） |
| 3 | `data/util/Formatters.kt` | 新增 `cardStatusLabel / cardTypeLabel / cardOperationStatusLabel / cardOperationKindLabel / scheduleStatusLabel`；`actionKindLabel` 增加 `ACTIVATE_MANDATE / MANAGE_SCHEDULE` |
| 4 | `ui/screens/savings/SavingsUi.kt` | 定时储蓄/联合任务 `day_of_month` 从 1..28 放宽到 1..31（短月按月末，与后端一致） |
| 5 | `ui/screens/cards/CardsScreen.kt`（重写）+ `CardsForms.kt` + `CardsState.kt`（新增） | 状态改 `AWAITING_CONFIRMATION`；待确认变更卡与历史行提供「确认变更 / 取消」；`UPDATE_CONTROLS` 双开关同时提交；新增 APPLY / UPDATE_LIMITS / REPORT_LOST 入口；去掉重复标题；错误/成功横幅；修正原布局重叠 |
| 6 | `ui/screens/finance/FinanceScreen.kt`（重写） | 金额改「元」输入；展示 catalogVersion/asOf/generatedAt/summary；候选卡展示起投/类别/风险/本金保障/存期或赎回 T+n/收益/回撤/年费/流动性/风险提示；排除项显示产品名；去掉重复标题 |

构建结果：`assembleDebug` + `test` **BUILD SUCCESSFUL**，35 个单元测试 0 失败；APK 13.83 MB（22:13 重新生成）。

### 复验证据（模拟器 UI 实际操作）

| 项 | 结果 | 截图 |
|---|---|---|
| 卡片空态 + 申卡表单 | ✅ 单一标题；账户余额 ¥51,544.80 正确；三类表单齐全 | `fix-shot-1-cards-empty.png` |
| APPLY 预览 → 确认 | ✅ 预览卡显示账户/卡名/借记卡/单笔 ¥5,000/日限 ¥20,000/在线开启/境外关闭；确认后卡片出现：`DEMO •••• 1019`、`余额 ¥51,544.80 · 单笔 ¥5,000.00 · 日限 ¥20,000.00`、`在线支付已开启 · 境外支付已关闭 · v1` | `fix-shot-4-card-created.png` |
| 锁定 → 确认（P0-2） | ✅ 「已完成：锁定卡片」，状态变「已锁定」 | `fix-shot-5-card-lock-unlock.png` |
| 解锁 → 确认 | ✅ 状态恢复「正常」 | 同上 |
| 开启境外（P0-3 双开关） | ✅ 预览显示「在线支付 开启 / 境外支付 开启」，确认后卡片 `在线支付已开启 · 境外支付已开启 · 版本 v4` | `fix-shot-6-card-toggle.png` |
| 调整限额（新能力） | ✅ 单笔 5,000→8,000、日限 20,000→30,000，确认后卡片行更新为 ¥8,000 / ¥30,000 | `fix-shot-11-limits-updated.png` |
| 金融推荐（P1-1/2） | ✅ 输入 1000 元 → 起投 **¥1.00**、赎回 T+0、收益/回撤/年费「暂无数据」、流动性说明与风险提示可见；元信息行 `目录 demo-finance-v1 · SafeBank 本地合成产品库 · 数据日 2026-09-30 · 生成 10-31 14:18 · 模式 DEMO`；排除项产品名完整（零钱管理/定期积累/稳步积累…） | `fix-shot-7-finance-result.png`、`fix-shot-8-finance-detail.png` |
| 回归：账单 | ✅ 收入 ¥11,700.00 / 支出 ¥5,369.33 正常 | `fix-shot-10-regression.png` |
| 回归：对话 | ✅ 「分析账单」→ 意图=账单分析、回答正常 | 同上 |

### 仍未完成/未复验

1. **REPORT_LOST 挂失**：UI 的 AlertDialog → 预览 → 确认 未走完（本轮时间原因）；实现与其他操作共用同一 preview/confirm 路径。
2. **后端新增能力未接入 App**：t020 的 66 个端点里，用户/联系人（5）、模型设置（6）、账单扩展（5）、动作列表（1）、`ACTIVATE_MANDATE` / `MANAGE_SCHEDULE` 两类新动作在 App 仍不可用。
3. **文档滞后**：`docs/api-contract.md` 仍按旧版 35 端点；建议后续按 `t020-backend-changes.md` 更新。
4. 新页视觉仍复用 `ui/components`（与 `ToolkitComponents` 两套风格）；卡片/金融操作历史仅展示最近 10 条。
5. `unexpected end of stream` 已加 HTTP 层缓解（GET 重试一次），本次复验期间未再出现；因其为偶发问题，建议后续做一次弱网/长空闲专项观察。

## 八、新后端能力接入（2026-10-31 22:30–23:00）

### 新增页面（更多 → 二级页）

| 页面 | 接口 | 能力 |
|---|---|---|
| 账单扩展 `StatementExtrasRoute` | `GET /api/statement/year`、`/statement/filter`、`/statement/months`、`/statement/years` | 年度账单（每月一行 + 合计 + 覆盖率提示）；单月「类别 + 收支方向 + 单笔金额下限」组合筛选（合计由服务端重算） |
| 用户与联系人 `UsersRoute` | `GET/POST /api/users`、`POST /api/users/{id}`、`GET/POST /api/users/profile` | 个人资料查看/保存；联系人列表；新增/编辑联系人（姓名/手机/性别/备注/归属账户/初始余额/储蓄目标） |
| 模型设置 `ModelSettingsRoute` | `GET /api/models/providers`、`GET/POST /api/models/configuration`、`POST /api/models/disable`、`POST /api/models/test` | 供应商与模型选择、Base URL/模型/密钥编辑（留空=保持、可清空）、停用模型、连接测试（明确提示会真实外网调用，未在验收中点按） |

配套改动：`Models.kt` 新增 15 个模型；`SafeBankRepository`/`AndroidBankRepository` 新增 14 个方法；`Destinations`/`SafeBankApp`/`MoreScreen` 增加 3 个路由与入口。

### 复验证据

| 项 | 结果 | 截图 |
|---|---|---|
| 年度账单 | ✅ 2026 年合计：支出 ¥29,315.20 / 收入 ¥54,200.00 / 内部转账 ¥6,000.00 / 113 条；12 个月逐行展示；提示「业务日 2026-10-31 · 本年度进行中」 | `ext-shot-1-year.png` |
| 单月筛选 | ✅ 2026-09 / 支出 / 下限 ¥0 → 「匹配 15 笔 · 支出 ¥5,369.33 · 收入 ¥0.00」，含服务端合计口径说明 | `ext-shot-2-filter.png` |
| 用户与联系人 | ✅ 个人资料读取（演示用户）；联系人列表含「本人」与 3 位联系人；创建 `TestContact`（无手机/未说明/备注 demo-note）后列表变 4 条 | `ext-shot-3-users.png`、`ext-shot-4-users-created.png` |
| 模型设置 | ✅ 当前配置读取（provider=deepseek、baseUrl、模型、密钥未保存、启用否、未验证、来源 environment）；供应商 chips（DeepSeek/通义千问/智谱 GLM/Kimi）与模型 chips 正常；保存空密钥时后端返回「请填写有效的 API Key…（MODEL_NOT_CONFIGURED）」并由页面中文展示 | `ext-shot-5-models.png`、`ext-shot-6-models-saved.png` |

### 边界说明

1. **模型保存成功路径未验证**：演示环境没有有效 API Key，保存空密钥时后端按设计拒绝（MODEL_NOT_CONFIGURED）；需真实密钥才能验证「保存 → 启用 → 连接验证」成功链路。
2. **连接测试未执行**：`POST /api/models/test` 会真实请求外部模型服务，按约定未在验收中点按。
3. 用户编辑采用「编辑」按钮回填表单再保存（调用 `POST /api/users/{id}`）；手机号校验沿用后端规则（空或 11 位数字）。
4. 仍未接入：`/api/statement/query`（通用区间查询）、`/api/actions`（动作列表）、`ACTIVATE_MANDATE` / `MANAGE_SCHEDULE` 两类新动作、`/api/model-budget`（仅 langgraph-agent profile）。

## 九、决策调整：移除 App 内的「模型设置」页（2026-10-31 22:38）

### 原因

t020 后端确实提供 `/api/models/*`（按会话保存模型连接，`ModelConfigurationService` 加密存 `data/model-settings`，`DeepSeekModelClient` 按 `userId` 取连接，未配置时回退 `environment`），属于**演示用的 BYOK 能力**。但让银行 App 的终端用户选择模型在架构上是错误的：

1. 模型/供应商属于机构控制项（合规、审计、成本、数据流向）；
2. 用户可填任意 `baseUrl` → 服务端替用户请求任意地址（SSRF/密钥泄露/中转风险）；
3. `/api/models/*` 目前只有 `SessionGuard` 的“会话 + CSRF”校验，**没有管理员/运维角色校验**。

### 处理（方案 A：移除页面）

- 删除 `ui/screens/models/ModelSettingsScreen.kt`
- 移除 `AppDestination.ModelSettings`、`SafeBankApp` 路由分支、`MoreScreen` 入口
- 数据层 `SafeBankRepository` / `AndroidBankRepository` 中的 `/api/models/*` 方法**保留但不暴露**，留作后续运维/管理员工具
- 构建 + 35 单测通过，APK 14.02 MB（22:37）；模拟器验证「更多」页已无「模型设置」入口，App 无崩溃（`remove-models-more.png`）

### 后续建议（后端）

1. 给 `/api/models/*` 增加管理员/运维角色校验（当前任何会话都可读改自己的模型配置）；
2. 保存时对 `baseUrl` 做白名单（或仅允许内置供应商域名），禁止任意主机；
3. 若要保留 BYOK 演示能力，用服务端开关（如 `demo.model-settings.enabled=false` 默认关闭）+ 仅 debug/内部入口暴露。
