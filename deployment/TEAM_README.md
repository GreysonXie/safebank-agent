# SafeBank 可信银行智能体 · 团队测试版

深金赛开发候选，支持账单分析、银行代扣咨询与受控操作预览。Java21/Spring Boot/MyBatis负责业务权限、金额、工具和逐动作执行；官方Python LangGraph负责理解、规划、对话及恢复；双MySQL分别持久化银行业务与Agent状态。

**用户、账户、交易、银行及身份验证均为合成演示，不是已接入微众银行的产品。不要输入真实银行卡密码、验证码或个人金融资料。**

## 队友启动

需要Docker Desktop（Linux containers）或Docker Engine+Compose，以及Python3.10+（仅生成本机配置）。首次构建需联网下载镜像、Maven和Python依赖。

**Windows队友：**安装并启动Docker Desktop，使用Linux containers模式；另安装Git与Python3.10+。在PowerShell执行下方命令即可。Java21、Maven、Python Agent依赖和MySQL都在容器中安装，无需在Windows上分别搭建这四套运行环境。Docker Desktop安装过程中若提示启用WSL2或虚拟化，按其安装向导完成并重启。

```powershell
git clone https://github.com/GreysonXie/safebank-agent.git
cd safebank-agent
python deployment/configure.py
python deployment/doctor.py --report .team-local/preflight.json
docker compose up --build -d --wait --wait-timeout 300
python deployment/smoke.py --report .team-local/smoke.json
docker compose ps
```

macOS/Linux可能使用`python3`。打开 **http://127.0.0.1:8134/**，首次等待数据库初始化。日志：`docker compose logs --tail=80 app`。

默认mock为免费有限规则模拟，适合页面/流程测试，不能评价模型智能。服务密码独立生成；首次导入合成数据，后续重启保留状态。

## Windows自检与自定义端口

`doctor.py`只检查Python、Docker/Compose/Linux daemon、Compose最终有效配置和端口；不创建、重建或删除容器，不连接模型。它检查包含环境变量覆盖后的Compose配置，但不打印配置、密钥或Docker原始错误。出现FAIL先按提示处理，成功也不代表三业务已经验收。

PowerShell入口可替代上述doctor命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File deployment/check-windows.ps1 -Report .team-local/preflight.json
```

这里的执行策略只作用于这一次进程。应用已经启动后使用`python deployment/doctor.py --running`，跳过“端口必须空闲”检查。配置自己的`.env`后再启动，不覆盖已有文件。

若8134被占用，修改`.env`的`SAFEBANK_WEB_PORT=8138`，重新运行自检、启动，并执行：

```powershell
python deployment/smoke.py --base-url http://127.0.0.1:8138 --report .team-local/smoke.json
```

冒烟只接受显式127.0.0.1 HTTP端口、拒绝重定向和付费模式，验证免费账单及同一原requestId重放，不授权或提交操作。每次冒烟创建独立合成对话。报告不含聊天正文、密钥、CSRF或内部grant；仍请只共享脱敏报告，不共享.env。

停止并再次启动后可重做冒烟，保留数据卷。按[验收记录模板](../docs/验收结果记录模板.md)记录自己电脑的结果；本机工具检查、Linux Docker CI与另一台Windows首次部署是三种不同证据。

## 使用自己的 DeepSeek Key

编辑生成的`.env`（不要提交或发送给他人）：

Windows可在项目目录运行`notepad .env`，修改以下配置后保存；修改的是`.env`，不是`.env.example`。不要把文件另存为`.env.txt`。

```dotenv
SAFEBANK_MODEL_MODE=deepseek
DEEPSEEK_API_KEY=填写你自己的密钥
SAFEBANK_MODEL_NAME=deepseek-flash
SAFEBANK_MODEL_SMOKE_CAP=20
```

运行`docker compose up -d --force-recreate app`。**`SAFEBANK_MODEL_SMOKE_CAP` 是项目累计模型请求上限，默认 20 次，可设 1–100000**，不是对话轮数或人民币额度。一轮通常 2 次，重写可能再加 1 次；已预留的失败或结果未知调用也计数。用量账本保存在运行卷中，重启、更换密钥和新建对话都不会清零。调整上限时保留原调用记录；降到低于已用次数会阻止新调用，仍可核对原请求和回执。本仓库没有作者密钥或免费模型额度。

页面显示累计已用、上限和剩余次数。耗尽时会说明限制原因，未发送的问题保留在输入框；处理中达到限制的原请求会保留供核对。可点击“新建对话”离开暂停的旧对话，再从“已保留的对话”返回，不会自动重发。切换入口保存在当前浏览器标签页的 sessionStorage，关闭标签页后不提供跨标签恢复；银行确认项和回执仍保存在服务端。额度摘要只返回汇总数字，不公开内部调用标识。

模型只能查询、准备预览，聊天确认不能代替逐动作授权。页面显示mock时不要把结果当作真实模型生成。

## 使用 OpenAI 兼容中转模型

编辑项目根目录的 `.env`，不要把真实密钥写入代码、示例或提交：

```dotenv
SAFEBANK_MODEL_MODE=openai
SAFEBANK_MODEL_URL=https://your-gateway.example/v1
SAFEBANK_MODEL_API_KEY=填写你自己的中转密钥
SAFEBANK_MODEL_NAME=填写服务商实际提供的模型ID
SAFEBANK_MODEL_SMOKE_CAP=20
```

地址填写到 `/v1`（以服务商为准），程序追加 `/chat/completions`，使用 Bearer 密钥认证与 JSON 回答格式，不发送 DeepSeek 专用的 `thinking` 参数。只接受 HTTPS 地址。模型 ID 必须来自中转站提供的列表；中转站的命名不证明上游模型身份。

修改后执行 `docker compose up --build -d --wait --wait-timeout 300`。页面会显示实际配置的模型名和 `LANGGRAPH_MODEL` 模式。只读查询也会消耗中转账户额度；保留原有请求次数限制和逐动作授权机制。`deployment/smoke.py` 仍只用于 mock，拒绝真实模型，避免日常启动意外消耗额度。

## 试用三场景

1. `分析2026-09账单` → `核对最大的一笔支出` → `这笔用途能确定吗？需要哪些依据？`
2. 在代扣业务页确认查询本人授权，再问`季度安全服务180元是每月扣吗？` → `先不撤销，只解释合同边界，不要重复金额。`
3. `从日常账户给备注同学的收款人转500元，先看预览` → `改成600元`；核对原卡失效、新卡金额，正常试用不提交转账。

记录问题顺序、实际与期望回答、版本、模式和截图，不附密钥、token或本机数据库密码。见[团队部署与试用](../docs/团队部署与试用.md)。

## 开发文档

- [开发状态与文档导航](../docs/团队开发状态.md)
- [数据库历史设计](../docs/database/2026-10-08-database-design.md)，最新结构以迁移SQL为准
- [系统架构](../docs/competition/技术文档-系统架构.md)
- [核心算法](../docs/competition/技术文档-核心算法.md)
- [安全设计](../docs/competition/技术文档-安全设计.md)
- [安全自评及已知风险](../docs/competition/安全自评报告.md)
- [开发规范](../CODING_STANDARDS.md)

docs保留阶段设计、计划、验收和比赛材料；旧端口、启动命令及版本状态为历史记录，以本README为准。原始会话、调试账本、截图及凭据不上传，见[证据说明](../docs/evidence/README.md)。

## 停止与开发检查

`docker compose down`停止但保留数据。再次`docker compose up -d`继续。不要随意使用`down -v`，它会删除演示数据、操作和用量记录。端口冲突可修改`.env`中SAFEBANK_WEB_PORT，再重建app，内部端口保持不变。

Java开发需要Java21与Maven3.9+，运行`mvn test`。Python需要Python3.12，虚拟环境安装`agent-service/requirements.lock.txt`后，在agent-service目录运行免费单元检查：

```sh
python -m unittest tests.test_live_contract_recovery tests.test_answer_contract tests.test_preview_planning tests.test_credential_output tests.test_transfer_safety -v
```

MySQL集成与Java Oracle测试需额外独立配置，见团队部署文档。不要把真实数据库配置交给测试。

## 验证边界

本次交付进度与检查范围见[Windows自检验收](../docs/2026-10-15-Windows交付自检验收.md)。旧批次计数仅对应当时范围，以分列[验收矩阵](../docs/2026-10-15-下一阶段验收矩阵.md)为准。

有部分账单、代扣真实模型完整查询链路和最新95项本地相关测试证据；最终提示与规范化尚未重新完成真实模型全场景验收，工具注入风险R03仍开放。真实银行/MFA未接入。

团队Docker入口已在GitHub Actions的Linux环境通过镜像构建、MySQL初始化、双服务健康检查及mock账单对话验证；自动测试结果见仓库Actions。各队友仍需记录自己电脑上的首次部署结果。仅用于个人电脑演示，不直接暴露公网。
