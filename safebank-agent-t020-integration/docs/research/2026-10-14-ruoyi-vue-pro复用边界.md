# ruoyi-vue-pro复用边界

2026-10-14，用户询问后续后端改造能否复用。结论：可以按需借鉴/抽取，当前不迁整个底座。未下载、安装、复制其代码，也未改变SafeBank依赖。

官方README显示：默认master为JDK8/Spring Boot2.7；master-jdk17面向JDK17/21、Spring Boot3.5，接近本项目Java21/Boot3.5；master-jdk25为另一代。后端采用多模块、MyBatis-Plus、Redis/Redisson，并有Spring Security、RBAC、数据权限、系统日志、配置、监控、Flowable及AI模块。完整平台和精简版分开维护。[官方仓库说明](https://github.com/YunaiV/ruoyi-vue-pro)

实际master-jdk17根pom启用system/infra而将多种业务模块注释；说明可裁剪，但不等于单文件能脱离framework和依赖BOM直接复制运行。[分支pom](https://github.com/YunaiV/ruoyi-vue-pro/blob/master-jdk17/pom.xml)

许可证为MIT，允许使用、修改与分发，并要求复制或实质部分包含版权和许可声明。README宣传语不能替代LICENSE正文；若实际抽取，应记录固定commit、原路径、改动、依赖许可证，保留相应NOTICE/LICENSE。赛事原创内容需如实区分自研与开源复用。[LICENSE](https://github.com/YunaiV/ruoyi-vue-pro/blob/master/LICENSE)

| 候选 | 推荐方式 | SafeBank必须保留的约束 |
|---|---|---|
| 后台用户/角色、登录、配置管理 | 后续独立管理端优先评估 | 后台RBAC不替代客户对象归属及逐动作授权；不允许管理员直接编辑银行账本 |
| API错误、日志、监控、审计检索 | 先借鉴接口和模块拆分，再适配小组件 | 日志过滤key/验证码/金融个人信息；一般操作日志不等于账务审计原子提交 |
| Mapper组织、转换、测试规范 | 选择有实际收益的模式 | 现有MyBatis已满足要求，不为采用ruoyi强制迁MyBatis-Plus；锁顺序、受影响行数及幂等断言保持 |
| 短信登录/验证扩展 | 到真实认证阶段评估 | 通用短信模块不自动构成动作绑定MFA；保留证明有效期、参数hash、一次性消费 |
| Flowable、AI平台、Redis分布式锁 | 当前不引入 | 现有LangGraph继续负责Agent；Java工作流和动作终态唯一；不能以缓存锁替换持久幂等 |
| Vue管理界面 | 后续独立运维后台候选 | 客户Agent三栏工作台保持既定布局，不直接改成后台CRUD页面 |

复用前为每个模块写“解决的具体问题→最小依赖→行为契约→回归→上游版本来源”。先完成储蓄任务图，再根据队友维护需求选日志/配置或身份模块，避免平台迁移与新资金流程同时进行。是否满足赛题独立完成及开源使用口径仍以官方要求为准，不把MIT许可等同比赛审核结果。
