# TV → Spring 迁移：选型决策记录

> 生成时间：2026-10-01（逐项讨论产出的决策快照）
> 用途：新仓库（Spring 生态 / 类 B 站后端）立项与实现期的对照依据。
> 关联：《TV-无Spring踩坑清单.md》《TV-历史债与迁移处置.md》（同目录）
> 基调：迁移期控制变量——"一次只搬一个变量"，新能力后置。

## 一、决策总表

| # | 决策项 | 拍板结果 |
|---|--------|---------|
| ① | 版本与运行时基线 | Spring Boot 4.1.x（当前 4.1.1）+ JDK 25 + Maven + 可执行 jar（内嵌 Tomcat 11）；依赖联网自由拉取 |
| ② | 数据访问层 | MyBatis 原生（不引 MyBatis-Plus/PageHelper）+ `@Transactional` + HikariCP；mapper 以 XML 为主 |
| ③ | 认证鉴权 | Spring Security 7 + 自定义 JWT Filter；单 Token 起步；角色起步 RBAC（ROLE_USER / ROLE_ADMIN） |
| ④ | 消息与异步 | RabbitMQ + Spring AMQP；消费重试 = RetryTemplate 退避 + DLQ（拓扑保留三队列）；`@Scheduled` + `@Async`（虚拟线程开、背压场合显式有界） |
| ⑤ | 缓存 | Jedis + Spring Data Redis 底座；混合抽象（复杂缓存显式封装 / 简单只读 `@Cacheable`）；熔断降级交 Resilience4j；锁手写 Lua；多级缓存暂不做 |
| ⑥ | Schema 管理 | Flyway（SQL-first）；先搬后改（V1 baseline = TV 现结构） |
| ⑦ | 测试策略 | Testcontainers + JUnit 一套（单元 + 集成/全栈）；pytest 平行体系不保留；可选极薄冒烟；每测自建数据 |
| ⑧ | API 风格与文档 | 保留 code/msg/data 信封 + HTTP 状态码正确化；路径沿袭 TV 风格；springdoc-openapi + Swagger UI |
| ⑨ | 本地/部署形态 | 无部署计划（Dockerfile 留后）；Docker Compose 管基础设施；`java -jar` 跑应用；媒体本地磁盘（配置化） |
| ⑩ | 限流与防护 | **暂缓**（专项时再定；底座 Redis / Resilience4j / AOP 已随其他决策就位） |

## 二、各项理由与实现注意

### ① 版本与运行时基线

- 理由：3.5.x 免费支持已终止（3.5.16 为最后 OSS 版）；4.1.x 支持期最长（OSS 至 2027-07）；JDK 25 与 TV 一致且受一流支持。
- 实现注意：Boot 4 = **Jackson 3**（TV 的 Jackson 2 序列化代码/测试需适配）；Spring Framework 7 内置重试（`@Retryable`，需自加 `@EnableResilientMethods`）与并发限流（`@ConcurrencyLimit`）；版本取当时最新 patch。

### ② 数据访问层

- 理由：SQL 是可移植资产（TV 手写 SQL 全部原样搬）；类 B 站复杂查询密集，MyBatis 掌控度匹配；后续分库分表生态最顺。
- 实现注意：DAO 签名从 `(Connection conn, ...)` 改为 Mapper；`SQLException → DatabaseException` 手工包装退役（Spring `DataAccessException` 接管）；MyBatis starter 4.0.0 支持 Boot 4.0+（同一代 4.x）；MyBatis-Plus 将来 CRUD 密集时按需插件式引入。

### ③ 认证鉴权

- 理由：Security 过滤器链 + 自定义 JWT 是单体自签场景最直白路线；授权单一配置源 + `@PreAuthorize` 根治 TV"两份硬编码清单"。
- 实现注意：**Security 7 已移除 `authorizeRequests` 与 `and()`**（6.x 教程写法不可用）；单 Token 的已知边界 = 到期前无法即时踢下线、封禁不即时生效（可接受；将来用 Redis token 版本号兜底）；CORS 纳入安全配置；密码继续 BCrypt（spring-security-crypto）。

### ④ 消息与异步

- 理由：TV 拓扑/消息/业务语义直接迁移；TV 的四个 MQ 坑（无重试/无恢复/confirm 串行/参数硬化）在 Spring AMQP 全有开箱对应物；Kafka 留作演进。
- 实现注意：重试计数语义差异——Resilience4j `maxAttempts` 含首次调用，Framework `@Retryable(maxRetries)` 是首次之外的重试数；**虚拟线程 ≠ 背压**：投递类场景保留有界队列 TaskExecutor（或 Semaphore）；`@Scheduled` 解锁 TV 被"判定不做"的巡检/清理/补偿能力。

### ⑤ 缓存

- 理由：Jedis 控制变量（减少排查盲区）；TV 的 CacheAside 三态/续期/降级语义比 Spring Cache 丰富——保留显式封装层是"换实现不换语义"。
- 实现注意：用 Jedis 需**排除 Lettuce 再引入**（版本由 Boot 托管，勿钉 TV 的 5.1.0）；序列化保留"JSON + 未知字段兼容"语义；熔断降级用 `resilience4j-spring-boot4`（2.4.0，已验证 Boot 4.1.1 + JDK 25），手写 RedisCircuitBreaker 退役；多级缓存（Caffeine）暂不做。

### ⑥ Schema 管理

- 理由：SQL-first 与掌控哲学一致；现有结构可做 baseline；DDL 入 git 顺带解掉"文档与真库不符""G9 手工闭环"两条债。
- 实现注意：**Flyway 管结构演进，不是备份**——业务数据不进 git，备份仍靠 dump；已有库用 baseline 对齐，空库（新环境/测试容器）执行 V1 建结构；"代码性质的数据"（字典、基线管理员）可写成迁移脚本进 git。

### ⑦ 测试策略

- 理由：环境即代码根治重资产（T1/E2 债）；一套语言一条链（T4 债根因是双体系并行）。
- 实现注意：集成测试 = 新的端到端（`@SpringBootTest(RANDOM_PORT)` 真容器 + 真 HTTP + Testcontainers + Flyway 自动建 schema）；防"测试迎合"——断言可观察行为（HTTP/DB 终态/**独立 oracle 复算**——TV pytest 精华模式保留），单测只覆盖分支密集逻辑；用例做"语义翻译"而非机械复制。

### ⑧ API 风格与文档

- 理由：信封是既有生态习惯 + 国内前端惯例；HTTP 状态码正确化抹平"协议层恒 200"的模糊态（TV 现状：语义码在 body、协议层不设 status）；路径沿袭控制迁移变量。
- 实现注意：错误出口统一（`@ControllerAdvice` + Security 的 `AuthenticationEntryPoint`/`AccessDeniedHandler`），两层语义对齐（401→401、403→403…）；springdoc 3.1.1（已支持 Boot 4.1）；API 版本化暂不加（需要时用 Framework 7 内置能力）。

### ⑨ 本地/部署形态

- 理由：compose 管 infra = 环境即代码（E2 债清）；开发期 jar 直跑循环最快。
- 实现注意：分工——compose 服务"本地开发运行"，Testcontainers 服务"自动化测试"，不重复维护；`application.yml` + profiles，敏感值走环境变量（AppConfig 覆盖链退役）；Dockerfile/镜像化有部署计划时再补；对象存储（MinIO/S3）留后，保持"存储接口 + 路径配置化"以便低成本替换。

### ⑩ 限流与防护（暂缓）

- 结论：**不定不影响迁移**——限流是新增能力（TV 零限流），且其底座（Redis、Resilience4j、Spring AOP）已随其他决策就位。
- 专项时再拍：Resilience4j RateLimiter（进程内底座）/ Redis 计数（登录防爆破）/ Redis 原子操作（抢券）；Sentinel 因控制台与概念偏重暂不考虑。

## 三、暂缓项与触发条件（留后清单）

| 项 | 触发条件 |
|----|---------|
| 限流与防护实现 | 专项启动（公网部署 / 防刷需求出现） |
| Dockerfile / 镜像化 | 有服务器 / 公网部署计划 |
| 对象存储 | 公网视频服务、多实例或大流量 |
| 双 Token（刷新/吊销） | 需要"改密立即失效 / 踢下线"时（轻量方案 = Redis token 版本号） |
| 多级缓存（Caffeine） | 实测热点问题出现 |
| API 版本化 | 破坏性变更需求出现 |
| Kafka | 弹幕量级 / 行为埋点 / 推荐数据管线出现 |

## 四、跨项技术备忘（实现期防坑）

1. **Security 7**：`authorizeHttpRequests`（无 `and()`）——查教程以 7.x 官方为准。
2. **Boot 4 = Jackson 3**：包名迁到 `tools.jackson.*`——TV 的 Jackson 2 代码适配时注意。
3. 三个"对齐 Boot 4"的第三方件：MyBatis starter 4.0.0、springdoc 3.1.1、resilience4j-spring-boot4 2.4.0——以实施时最新 patch 为准。
4. 重试计数语义（`maxAttempts` vs `maxRetries`）与"虚拟线程 ≠ 背压"（见 ④）。
5. Jedis 切换需排除 Lettuce（见 ⑤）；**Flyway ≠ 备份**（见 ⑥）。