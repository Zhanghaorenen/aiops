# 资源保护实现与验证

实现位置：Linux采集端resource_guard.py、Agent启动钩子、Java ResourceGuardService/Controller和V15、AI ServerTool/PlatformClient、服务器详情ResourceGuardPanel。原有未提交业务修改保留，未部署或启用真实服务器的终止功能，未对真实训练进程发送信号。

验证：
- 采集端41项通过，其中19项资源保护定向测试：持续/瞬时压力、PSI缺失、空闲内存、模式与本地开关、UID交集、过期策略、持久熔断、审计失败、扫描上限、凭据更换、平台保护、PID复用、恢复取消、同一pidfd的TERM/KILL升级等。
- AI服务49项通过，其中2项新增工具协议测试；检查请求无PID/命令参数、沿用JWT、只返回受理不代表执行，403不重试，并回归GraphAgent/SSE。
- Java37项通过（ResourceGuardTests 6、ResourceMemoryTests 16、SharedClusterModelTests 7、AgentAccessPersistenceTests 8）。使用H2验证迁移V15、角色/Agent凭据隔离、默认关闭、ROOT UID拒绝、请求限流、事件幂等和跨节点事件冲突、UTC同步状态读取。
- 前端npm run build通过，含vue-tsc类型检查；保留已有大体积chunk提示。没有进行浏览器视觉验收。

合计127项自动化测试通过。日志位于Git忽略目录data/local-run/resource-guard-python-tests.log、resource-guard-ai-tests.log、resource-guard-java-final.log及resource-guard-frontend-build.log；JUnit在target/surefire-reports。

真实Linux信号、实际OOM恢复、线上MySQL V15迁移及生产启用均尚未验证。测试替身没有制造真实资源耗尽，不把上述测试数当作线上成功率。部署/权限/限制见resource-guard.md。
