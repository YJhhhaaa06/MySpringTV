# TV「无 Spring」踩坑清单

> 生成时间：2026-09-30（调查窗口产出，未改动业务代码）
> 用途：新仓库迁移 Spring 生态的立项输入；也可作 TV 本体后续维护时"哪些债值得投入"的参照。

**收录口径**：只收录「若在 Spring（Boot）生态下开发则不必踩 / 不易踩」的坑。
**不收录**：与框架无关的业务坑与通用工程坑——如缓存一致性设计、消息幂等、SQL/表设计、测试脆弱性等（例：U-11 降级语义选择、U-35 测试守卫、U-36 表膨胀本体，均属此类，不在本清单）。

**标注**：**【已爆】**= 实际发生过并有治理动作；**【妥协】**= 被迫的设计让步（长期成本）；**【隐患】**= 结构性风险，尚未爆。

---

## 一、依赖注入与生命周期

### 1. Servlet/Filter 与 IoC 容器是两套生命周期，只能手工缝合【妥协】

- 现象：Tomcat 负责创建 Servlet/Filter，IoC 容器管不到它们；Servlet 靠 `init` 反射注入，Filter 更彻底——运行时从容器里"捞"Bean。
- 证据：`controller/BaseServlet.java:11-13`（`injectInto(this)`）；`filter/AuthFilter.java:58`（`IocContainer.getInstance().getBean(UserService.class)` 现取现用）。
- 无 Spring 为什么躲不开：没有 DispatcherServlet 统一托管组件，容器（Tomcat）与 IoC 是两套世界，只能手工桥接。
- Spring 对照：一切组件皆 Bean，控制器/过滤器由容器创建装配，无缝合层。

### 2. 反射注入的失败可见性只能靠"事后治理"【已爆】

- 现象：`@Inject` 字段在容器里取不到时**静默跳过**，问题拖到请求运行期才暴露；后来专门加了 fail-fast + 未注入清单汇总才治住。
- 证据：`ioc/IocContainer.java:174-178、199-210`（T17 注释："不再静默跳过，收集后统一上报"）。
- 无 Spring 为什么躲不开：字段反射注入没有编译期/启动期契约，全靠自己补校验。
- Spring 对照：构造器注入在启动期即失败，错误发生在部署而非请求。

### 3. 销毁/关停钩子靠接口约定 + 反射方法名【妥协】

- 现象：销毁要实现 `Disposable` 接口，兜底逻辑甚至用**反射找 `shutdown()` 方法名**；停机编排（容器 shutdown → 缓存 flush → 连接池关闭）手写在监听器里，顺序即正确性。
- 证据：`ioc/IocContainer.java:92-110`（`getMethod("shutdown")`）；`controller/AppShutDownListener.java:39-48`。
- Spring 对照：`@PreDestroy`/`DisposableBean` 标准钩子 + 容器统一编排关闭顺序。

---

## 二、事务

### 4. 无传播/嵌套语义：组合业务只能拆事务【妥协】

- 现象：内层事务取新连接、与外层互不可见，跨 Service 组合操作要么塞进一个大 lambda，要么拆成多个独立事务（原子性丢失）。
- 证据：`user/service/UserService.java:100-120`（注释明写"注册与自动登录仍是两个独立事务"）；`util/TransactionTemplate.java:26-33`（每次 `getConnection()` 新连接）。
- Spring 对照：`@Transactional` 传播行为（REQUIRED/NESTED 等），组合操作可原子化。

### 5. 无事务同步回调："提交后动作"全靠人肉编排【妥协】

- 现象：缓存失效、MQ 投递等副作用必须写在事务 lambda **之后**的代码行里；写错位置就是"事务回滚但副作用已发出"。全靠约定与评审兜住，且该模式在多处重复出现。
- 证据：`like/service/LikeService.java:72-75`（"缓存更新放在事务提交后"）；`content/service/ContentService.java:211-219`；`follow/service/FollowService.java:60-92`。
- Spring 对照：`TransactionSynchronization` / `@TransactionalEventListener(phase = AFTER_COMMIT)` / `@Async`。

---

## 三、Web 层

### 6. 手写路由分发【妥协】

- 现象：每个 Controller 是 `@WebServlet("/xxx/*")` + 在 doGet/doPost 里按 pathInfo 写 if/else 分发；**加一个接口 = 改分发代码块**。
- 证据：`content/controller/ContentController.java:28-49`（手动分发 `/commentEnabled`、`/update`、`/delete` 等）。
- Spring 对照：`@GetMapping/@PostMapping` 注解路由，方法即端点。

### 7. 手写参数解析的持续维护成本【已爆】

- 现象：分页参数归一化被**反复重构四轮**（T5 收敛公共 → T10-B 上限参数化 → T11-A 域级信封 → T19 下沉为 request 无关方法）；请求体解析只覆盖 JSON。
- 证据：`controller/BaseServletUtil.java:49-67`（四轮演变注释链）；`controller/RequestParser.java:16-23`。
- Spring 对照：`Pageable` + HandlerMethodArgumentResolver 一次成型。

### 8. 参数校验散落、无声明式约束【隐患】

- 现象：校验逻辑在各 Controller 手写（读参数 + if + 抛 `ParamException`），新增接口时复制扩散。
- 证据：`content/controller/ContentController.java:52-83`（逐个人工校验）。
- Spring 对照：Bean Validation（`@Valid`/`@NotNull`），约束声明在模型上、框架统一执行。

### 9. 过滤器链顺序靠人工排序 + 注释红线【隐患】

- 现象：5 个 filter 的先后顺序是正确性要素（AccessLog 最外层、Encoding 内层），只能靠 web.xml 排列 + 注释警示"顺序一律不动"，无自动校验；加 filter 要人工判断插在哪。
- 证据：`webapp/WEB-INF/web.xml:7-9、10-53`。
- Spring 对照：`@Order`/`FilterRegistrationBean` 显式排序。

### 10. 无 CORS 处理【隐患】

- 现象：仅放行 OPTIONS 预检（LoginFilter），无任何 `Access-Control-*` 响应头；与独立前端/多端对接时必然要补。
- 证据：`filter/LoginFilter.java:22-25`；全项目无 CORS 相关代码。
- Spring 对照：`@CrossOrigin` / `CorsFilter` / WebMvcConfigurer 标配。

---

## 四、安全

### 11. 鉴权清单硬编码 + 多份清单同步【隐患】

- 现象：受保护路径 = 代码里两份静态清单（前缀清单 + 精确清单，`Set.of` 硬编码）；前缀匹配语义还被外部工具（覆盖率守卫）依赖、需跨文件对齐；漏改一处 = 权限口子或误拦。
- 证据：`filter/AuthFilter.java:20-40、68-76`。
- Spring 对照：Spring Security `requestMatchers` 单一源 + 方法级注解（`@PreAuthorize`）就近声明。

### 12. 认证/授权无扩展点，"登出、踢下线、多端"无处安放【隐患】

- 现象：JWT 只有"签发/校验"，无刷新、吊销/黑名单；权限只有 isAdmin 一档，写死在 Filter 里。
- 证据：`util/JwtUtil.java:10-25`；`filter/AuthFilter.java:56-63`。
- Spring 对照：Spring Security 过滤器链 + UserDetails/权限模型扩展点齐全（注：JWT 吊销策略本身两边都要自行设计，但挂载点是现成的）。

---

## 五、消息与异步

### 13. 消费失败无重试/退避——能力被"热循环风险"逼掉【妥协】

- 现象：消费失败直接 nack 进 DLQ，不重试；注释明说"一期不做有限重试/退避，避免消费失败热循环"——手写重试极易造成热循环，于是干脆不做这个能力。
- 证据：`mq/MqConsumerContainer.java:17-33`；`mq/MqTopology.java:40-41`（DLQ 无 TTL）。
- Spring 对照：Spring AMQP RetryTemplate 内置退避，重试开箱且不会热循环。

### 14. 消费者拉起失败无自动恢复/定时重试【隐患】

- 现象：`basicConsume` 失败只记 WARNING 关 channel，要等下次连接建立/手动 init 才重拉；"连接可用但拉起偶发失败" = 消费停摆且无感知。
- 证据：`mq/MqConsumerContainer.java:128-166`；`.docs/目标与任务/UNPLANNED_ISSUES.md` U-32。
- Spring 对照：SimpleMessageListenerContainer 自动恢复（recovery interval）。

### 15. publisher confirm 串行（吞吐上限）【妥协】

- 现象：单 confirm channel + `synchronized`「publish + `waitForConfirms(5s)`」，多线程发布被串行化。
- 证据：`mq/MqPublisher.java:16-20、72-94`；`UNPLANNED_ISSUES.md` U-34。
- Spring 对照：Spring AMQP 提供异步确认（CorrelationData + ConfirmCallback）。

### 16. 异步投递：自研线程池 + 上下文手工传递【妥协】

- 现象：为让 Web 线程不等 confirm，自研了"单 worker + 有界队列 + 拒绝丢弃 + 关停 drain"的投递器；reqId 上下文要手工 `wrap` 才不丢。
- 证据：`mq/MqDeliveryDispatcher.java`（T31 落地）；`util/LogContext.java`（wrap 约定）。
- Spring 对照：`@Async` + 容器线程池 + TaskDecorator 统一上下文传递。

---

## 六、调度

### 17. 没有任何定时调度设施——一批能力被"判定不做"【妥协】

- 现象：项目零定时任务；巡检、积压清理、补偿重试、热点刷新这类需求都只能"先自研调度再谈方案"。已登记的 U-31（MQ 巡检）、U-32（消费者定时重试）、U-36（表侧清理）被"不取"的深层原因都是它。
- 证据：全项目无 `ScheduledExecutorService`/定时任务设施；`UNPLANNED_ISSUES.md` U-31/U-32。
- Spring 对照：`@Scheduled`/Quartz，挂载成本≈零。

---

## 七、配置

### 18. 配置硬化：调参 = 改代码重部署【妥协】

- 现象：MQ 的 confirm 超时/重连冷却/消费线程数/prefetch 全是包内常量；配置系统只有 properties + 环境变量覆盖，无 profiles、无类型绑定、无刷新。
- 证据：`UNPLANNED_ISSUES.md` U-33（`mq/MqPublisher.java:32`、`mq/MqConnectionManager.java:58、64`、`mq/MqConsumerContainer.java:41` 均为常量）；`config/AppConfig.java:9-14、34-48`。
- Spring 对照：`spring.rabbitmq.*` 自动配置 + `@ConfigurationProperties` + profiles。

---

## 八、可观测与运维

### 19. 无健康检查/指标端点【隐患】

- 现象：MQ 队列深度、消费状态无观测；缓存统计是私有自研（CacheStats），无标准指标导出；运维排障只能靠日志检索。
- 证据：`UNPLANNED_ISSUES.md` U-31；`cache/CacheStats.java`。
- Spring 对照：Actuator + Micrometer（health/metrics/Prometheus）。

### 20. 自研了一整套"MDC + 访问日志"等价物【妥协】

- 现象：reqId 生成算法、thread-local 上下文、结果码三处收口、日志格式化器全部手写（质量高，但属重复造轮子，规则要靠文档长期维持）。
- 证据：`util/LogContext.java:7-29、44-47、62-65`；`filter/AccessLogFilter.java`；`controller/BaseServletUtil.java`（结果码写入）。
- Spring 对照：logback + MDC 惯例，Spring Boot 自动配置默认就有。

---

## 九、数据访问

### 21. DAO 全手写 JDBC + 连接显式传参【妥协】

- 现象：每个查询手写 PreparedStatement + 结果集映射；`(Connection conn, ...)` 显式贯穿全层；无统一 DataSource 抽象（连接池与事务硬耦合）；无 SQL 挂载点（慢 SQL/审计/分页/读写分离都要自研）。
- 证据：`user/dao/UserDao.java:14-16`；`util/MyConnectionPool.java`；`util/TransactionTemplate.java:29`。
- Spring 对照：JdbcTemplate/MyBatis + DataSource + 事务管理器。

---

## 附：使用建议

- **新仓库**：本清单可当"迁移验收清单"——每条在 Spring 版里应有对应"默认解法"（见 Spring 对照行），立项时逐条确认。
- **TV 本体**：这些坑的修复以"教学价值"为准，不必追击（TV 既定形态就是手写基础设施）。