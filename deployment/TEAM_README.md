# SafeBank 可信银行智能体 · 团队测试版

深金赛开发候选，支持账单分析、银行代扣咨询与受控操作预览。Java21/Spring Boot/MyBatis负责业务权限、金额、工具和逐动作执行；官方Python LangGraph负责理解、规划、对话及恢复；双MySQL分别持久化银行业务与Agent状态。

**用户、账户、交易、银行及身份验证均为合成演示，不是已接入微众银行的产品。不要输入真实银行卡密码、验证码或个人金融资料。**

## 队友启动

需要Docker Desktop（Linux containers）或Docker Engine+Compose，以及Python3.10+（仅生成本机配置）。首次构建需联网下载镜像、Maven和Python依赖。

**Windows队友：**安装并启动Docker Desktop，使用Linux containers模式；另安装Git与Python3.10+。在PowerShell执行下方命令即可。Java21、Maven、Python Agent依赖和MySQL都在容器中安装，无需在Windows上分别搭建这四套运行环境。Docker Desktop安装过程中若提示启用WSL2或虚拟化，按其安装向导完成并重启。

```sh
git clone https://github.com/GreysonXie/safebank-agent.git
cd safebank-agent
python deployment/configure.py
docker compose up --build -d
docker compose ps
```

macOS/Linux可能使用`python3`。打开 **http://127.0.0.1:8134/**，首次等待数据库初始化。日志：`docker compose logs --tail=80 app`。

默认mock为免费有限规则模拟，适合页面/流程测试，不能评价模型智能。服务密码独立生成；首次导入合成数据，后续重启保留状态。

## 使用自己的 DeepSeek Key

编辑生成的`.env`（不要提交或发送给他人）：

Windows可在项目目录运行`notepad .env`，修改以下配置后保存；修改的是`.env`，不是`.env.example`。不要把文件另存为`.env.txt`。

```dotenv
SAFEBANK_MODEL_MODE=deepseek
DEEPSEEK_API_KEY=填写你自己的密钥
SAFEBANK_MODEL_NAME=deepseek-flash
SAFEBANK_MODEL_SMOKE_CAP=20
```

运行`docker compose up -d --force-recreate app`。**20是模型请求上限，不是20轮或人民币额度**，一轮通常2次，重写可能再加1次；用量账本保存在运行卷中。也请在自己的模型账户设置额度并检查余额。本仓库没有作者密钥或免费模型额度。

模型只能查询、准备预览，聊天确认不能代替逐动作授权。页面显示mock时不要把结果当作真实模型生成。

## 试用三场景

1. `分析2026-09账单` → `核对最大的一笔支出` → `这笔用途能确定吗？需要哪些依据？`
2. 在代扣业务页确认查询本人授权，再问`季度安全服务180元是每月扣吗？` → `先不撤销，只解释合同边界，不要重复金额。`
3. `从日常账户给备注同学的收款人转500元，先看预览` → `改成600元`；核对原卡失效、新卡金额，正常试用不提交转账。

记录问题顺序、实际与期望回答、版本、模式和截图，不附密钥、token或本机数据库密码。见[团队部署与试用](docs/团队部署与试用.md)。

## 开发文档

- [开发状态与文档导航](docs/团队开发状态.md)
- [数据库历史设计](docs/database/2026-10-08-database-design.md)，最新结构以迁移SQL为准
- [系统架构](docs/competition/技术文档-系统架构.md)
- [核心算法](docs/competition/技术文档-核心算法.md)
- [安全设计](docs/competition/技术文档-安全设计.md)
- [安全自评及已知风险](docs/competition/安全自评报告.md)
- [开发规范](CODING_STANDARDS.md)

docs保留阶段设计、计划、验收和比赛材料；旧端口、启动命令及版本状态为历史记录，以本README为准。原始会话、调试账本、截图及凭据不上传，见[证据说明](docs/evidence/README.md)。

## 停止与开发检查

`docker compose down`停止但保留数据。再次`docker compose up -d`继续。不要随意使用`down -v`，它会删除演示数据、操作和用量记录。端口冲突可修改`.env`中SAFEBANK_WEB_PORT，再重建app，内部端口保持不变。

Java开发需要Java21与Maven3.9+，运行`mvn test`。Python需要Python3.12，虚拟环境安装`agent-service/requirements.lock.txt`后，在agent-service目录运行免费单元检查：

```sh
python -m unittest tests.test_live_contract_recovery tests.test_answer_contract tests.test_preview_planning tests.test_credential_output tests.test_transfer_safety -v
```

MySQL集成与Java Oracle测试需额外独立配置，见团队部署文档。不要把真实数据库配置交给测试。

## 验证边界

有部分账单、代扣真实模型完整查询链路和最新95项本地相关测试证据；最终提示与规范化尚未重新完成真实模型全场景验收，工具注入风险R03仍开放。真实银行/MFA未接入。

团队Docker入口已在GitHub Actions的Linux环境通过镜像构建、MySQL初始化、双服务健康检查及mock账单对话验证；自动测试结果见仓库Actions。各队友仍需记录自己电脑上的首次部署结果。仅用于个人电脑演示，不直接暴露公网。
