# 共享集群与 RBAC 实施报告

## 阶段 1：共享集群数据模型（2026-11-05）

本阶段已实现集群数据模型、历史节点归属迁移与默认关闭的一次性管理员绑定。统一 RBAC、成员 API、Token 权限、SQL 查询范围和前端管理尚属阶段 2–6，不能把本阶段视为完整权限隔离已经上线。

### 授权与工作区

开始时只有上一阶段的未跟踪审计报告 `docs/shared-cluster-rbac-audit.md`，已保留。用户明确确认“现有 ops_node 全部属于同一个默认共享集群”。本次据此编写 V8 回填；没有获得指定账号的管理员绑定信息，因此没有执行真实账号提权，采用受控配置且默认关闭。未自动提交/推送。

### 数据库变更

新增 `V8__create_shared_cluster_tables.sql`，V1–V7 完全不变：

- ops_cluster：唯一 cluster_name、description、status、created_at/updated_at，以及 bootstrap_completed_at/bootstrap_user_id 一次性初始化标记。创建 default-cluster，但不使用写死的 ID 查询它。
- ops_cluster_member：cluster_id/user_id 外键、角色 ADMIN/OPERATOR/VIEWER、状态 ACTIVE/DISABLED、时间，UNIQUE(cluster_id,user_id)，数据库 CHECK 校验角色/状态；按 user/status/cluster 建索引。
- ops_node：新增 cluster_id，按唯一名称取得默认集群 ID 回填所有历史 Node，再建立 NOT NULL、外键及 (cluster_id,status,id) 索引。原节点 ID、owner_user_id 和所有关联记录不变。
- ops_agent_enroll_token：增加可空 cluster_id 及外键/索引，映射已有 issued_by_user_id。历史 Token 不捏造集群/签发人，不改变原消费/有效期状态。此字段可空用于保留无归属历史 Token；阶段 4 新签发接口必须强制可信 clusterId 和签发人。

MySQL DDL 不是普通业务事务，正式应用应先备份并验证副本。Flyway 在下次后端启动时会应用 V8；本次测试只用独立 H2，没有启动实际 MySQL 后端或修改真实数据。正式库应确保迁移连接使用 UTC 时间，V8 新建集群的时间使用数据库 CURRENT_TIMESTAMP(6)。

关系：

```text
sys_user --< ops_cluster_member >-- ops_cluster --< ops_node --< ops_agent
                         role/status                    |            |
                                                      host/GPU samples
ops_agent_enroll_token --cluster_id--> ops_cluster
                      --issued_by_user_id--> sys_user
```

一台 Node 只关联一个 Cluster。一个 Agent 继续绑定唯一 Node；同 Node 多 Agent 的既有结构未改动。用户注册不会自动建立成员关系。知识库仍以原 owner_user_id 私有授权，未改 RAG/Qdrant。

### 关键实现与阶段兼容

- ClusterEntity/ClusterMemberEntity、Mapper 和 Repository 沿用现有 MyBatis-Plus 模式和 UTC Instant TypeHandler。
- NodeEntity 增加 clusterId 和历史 ownerUserId；assignCluster 拒绝改绑已有集群，更新时不覆盖 cluster/历史 owner 字段。
- NodeRepository.save 对当前旧注册流程创建的、尚未显式指定集群的新 Node，按名称查 ACTIVE 默认集群后绑定。已有集群的 Node 不会被覆盖。此逻辑为阶段 1 单集群兼容；阶段 4 将在 Service 依据可信 Enrollment Token 绑定目标集群，不能由 Agent 请求体指定。
- AgentEnrollTokenEntity 增加 clusterId/issuedByUserId 构造和读取；FOR UPDATE 的自定义 Mapper 同步映射新字段，消费更新不会擦掉审计字段。
- ClusterBootstrapService.bootstrap(confirmedUserId) 为受控部署内部服务，无公开 HTTP 入口。事务锁定默认集群；只接受明确、存在且 ACTIVE 的 userId；不允许在已有成员时静默提权。首次插入 ADMIN 与写入完成标记在同一事务中。
- 完成标记存在后直接返回 false，不恢复已降级、禁用或已删除成员，不会每次启动重新授予 ADMIN。没有显式配置时不会调用 bootstrap。
- DataInitializer 保留原 aiops.admin.username/password 的账号创建行为，额外接入受控 bootstrap。已有同名注册账号不会仅凭名字自动得到 ADMIN。

### 初始管理员操作

先确认实际 sys_user.id 的账号身份。这里的 userId 不是用户名，不能凭默认值猜测。

1. 普通首次启动仍可用原 `aiops.admin.username/password` 创建账号。若尚未存在账号，先创建再核对 ID；不自动授权新注册用户。
2. 将明确核验的 userId 配入环境 `AIOPS_CLUSTER_BOOTSTRAP_USER_ID`，并设置 `AIOPS_CLUSTER_BOOTSTRAP_ENABLED=true`。
3. 通过新增的 YAML profile 加载配置，例如：

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=dev,cluster"
```

`application-cluster.yml` 仅提供这两个受控参数，默认 false / 0，不含密码或密钥。可由部署配置提供等价 aiops.cluster.bootstrap-enabled / bootstrap-user-id。

4. 成功后移除启用标志或改为 false。检查默认集群的 bootstrap 标记及对应 ADMIN 成员。后续启动不会重置成员角色；初始 ADMIN 恢复需独立人工流程。

本次没有用户指定的真实管理员 ID，以上操作未执行。尚未建立成员 API 和统一 RBAC，因此绑定完成本身不会改变旧接口授权规则；不能据此将系统当作已完成权限改造部署。

### API 与未完成项

本阶段没有新增 HTTP API，不改变 Python Agent/GPU JSON 协议、AI Tool、SSE 或 Vue 页面。现有全局 ROLE_USER 授权、任意 USER 签发 Token、旧 agents/metrics 写接口等审计问题仍待后续阶段修复。

Token 的 clusterId/issuedByUserId 目前为数据模型能力；旧签发流程仍使用历史构造方法。这不意味着已满足“只有 ADMIN 可签发目标集群 Token”；该校验依赖阶段 2 权限服务并在阶段 4 接入。本阶段没有撤销旧 Token，也没有替用户选择其兼容策略。

### 文件清单

| 文件 | 作用 |
|---|---|
| src/main/resources/db/migration/V8__create_shared_cluster_tables.sql（新增） | 新表、历史节点回填、外键和索引 |
| src/main/java/com/aiops/backend/Entity/ClusterEntity.java（新增） | 集群和一次性 bootstrap 标记 |
| src/main/java/com/aiops/backend/Entity/ClusterMemberEntity.java（新增） | 成员与角色/状态 |
| src/main/java/com/aiops/backend/mapper/ClusterMapper.java（新增） | 集群 MyBatis-Plus Mapper |
| src/main/java/com/aiops/backend/mapper/ClusterMemberMapper.java（新增） | 成员 Mapper |
| src/main/java/com/aiops/backend/repository/ClusterRepository.java（新增） | 按名称查默认集群、事务加锁 |
| src/main/java/com/aiops/backend/repository/ClusterMemberRepository.java（新增） | 成员查询与持久化 |
| src/main/java/com/aiops/backend/service/ClusterBootstrapService.java（新增） | 一次性、受控、事务化 ADMIN 绑定 |
| src/main/java/com/aiops/backend/Entity/NodeEntity.java（修改） | clusterId 和历史 owner 映射、禁止静默改绑 |
| src/main/java/com/aiops/backend/repository/NodeRepository.java（修改） | 当前单集群注册兼容 |
| src/main/java/com/aiops/backend/Entity/AgentEnrollTokenEntity.java（修改） | cluster/issuer 字段及兼容构造 |
| src/main/java/com/aiops/backend/mapper/AgentEnrollTokenMapper.java（修改） | 加锁查询字段映射 |
| src/main/java/com/aiops/backend/config/DataInitializer.java（修改） | 启动参数启用一次性 bootstrap |
| src/main/resources/application-cluster.yml（新增） | 默认关闭的 YAML 初始化配置 |
| src/test/java/com/aiops/backend/SharedClusterMigrationTests.java（新增） | 空库、V7 升级和历史记录逐行保留 |
| src/test/java/com/aiops/backend/SharedClusterModelTests.java（新增） | 成员约束、默认归属、bootstrap、Token 映射 |
| docs/shared-cluster-rbac-implementation-report.md（新增） | 本阶段报告 |

无删除文件；阶段 0 审计报告保留原样，不将其算作本阶段新写业务代码。

### 测试命令与结果

```powershell
mvn -s data/local-run/mybatis-maven-settings.xml "-Dtest=SharedClusterModelTests,SharedClusterMigrationTests" test -B
mvn -s data/local-run/mybatis-maven-settings.xml test -B
```

新增 9 项测试已通过，包含：空库 V1–V8、带历史数据的 V7→V8、重复 migrate 幂等、12 张业务表原列逐行对比（含 GPU/知识库/旧模型）、Node NOT NULL 与外键、成员唯一性、普通注册不入群、明确 ID 才能 bootstrap、降级/禁用/删除后重启不补回、Token 消费保存 cluster/issuer。

完整 Java 回归 **88 项通过，0 失败、0 错误、0 跳过**，包含新增 9 项与既有认证、Agent、指标、GPU、知识库测试；`git diff --check` 通过，V1–V7 无修改。真实 MySQL 副本迁移、实际管理员绑定没有执行；不将 H2 测试等同于真实环境升级通过。Python/前端未改动，本阶段未重复它们的无关测试。

### 初学者源码阅读顺序

1. V8 SQL：认识外键、非空、唯一性与历史数据回填，理解保留节点 ID 的意义。
2. ClusterEntity / ClusterMemberEntity：集群和成员不同于用户账号；成员角色是某个集群内的角色。
3. Mapper → Repository：MyBatis-Plus BaseMapper 执行 SQL，Repository 封装按集群名称/成员身份查询。
4. NodeRepository.save：输入 NodeEntity，输出保存后的实体；未给新节点显式集群时查默认集群，避免硬编码 ID。
5. ClusterBootstrapService.bootstrap：输入核验过的 userId；返回 true 表示首次完成，false 表示已初始化；事务/行锁保护成员插入和标记一起完成。
6. DataInitializer → bootstrap：仅部署配置显式启用才调用；普通注册走 AuthService，与此无关。
7. 两个测试类：对照迁移前后数据与初始化状态，区分数据模型已建立和 RBAC 尚未接入。

### 下一阶段

阶段 2 将增加统一 ClusterPermissionService、权限映射、集群/成员 API、管理审计与最后一个有效 ADMIN 的并发保护。阶段 3 再将所有服务器/指标/GPU/统计查询纳入集群范围，阶段 4 接入可信 Token 集群归属与旧接口收口。
