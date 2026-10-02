# 迁移工作流（切片 SOP）

> 生成时间：2026-10-01
> 用途：**新窗口执行切片迁移的操作手册**。目标读者是没有历史上下文的执行者（人或 AI）。
> 依据：切片 0（`user` 模块）的实际执行经验，含 4 个已踩过的坑与验证命令。
> 关联：《迁移参照系.md》《事务边界决策表.md》《测试策略与阶段验收.md》《切片计划.md》

---

## 〇、开始之前（环境前置）

| 项 | 值 |
|----|-----|
| 工作目录 | `D:\javaproject\SpringVideo\MySpringTV` |
| 构建 | `.\mvnw.cmd`（Maven 3.9.16，wrapper，无需本机装 Maven） |
| JDK | 25（`JAVA_HOME=D:\dev\DevTools\jdk\openjdk-25.0.2`，**不在 PATH**，用 wrapper 即可） |
| 老项目（只读参照） | `old-project\TVhomework1`（**已 gitignore**，仅本地；不在 git 里） |
| 测容器 | Docker 必须运行。测试**自带** mysql/rabbitmq/redis 容器，**不需要**本机手工起容器 |
| 开发库 | 宿主原生 mysqld **8.0.45 @ 3306**，库 `TVDatabase`，root/MySQL |

**铁律**：老项目是**行为规格书**，不是源码来源。所有移植都按"读旧代码 → 用新写法实现"进行，
不做整份复制（详见《迁移参照系》§二）。

---

## 一、标准流程（按序执行，不要跳步）

### 步骤 1：界定切片范围

- 选定模块（见《切片计划.md》），明确**本切片要交付的可观察能力**
- 判断是否需要"薄依赖"（如 content-thin）：只搬下游必需的方法，不搬整模块
- **不要**以"文件为单位"横向搬运，要以"能力闭环"为单位

### 步骤 2：盘点事务边界（**开工前必做**）

```powershell
# 列出本切片相关的所有事务调用点
Select-String -Path "old-project\TVhomework1\src\main\java\com\itheima\<模块>\service\*.java" `
  -Pattern "transactionTemplate\.execute" | ForEach-Object { "$(Split-Path $_.Path -Leaf):$($_.LineNumber)" }
```

逐条填《事务边界决策表.md》，状态必须从 🔴 变为 ✅/⚠️。
**未表态的事务边界 = 未完成的迁移准备**，不得开工。

判定顺序（决策表 §三 模式 C）：
1. 旧行为是不是刻意设计？→ **先读原注释**（TV 注释质量高，常直接写明理由）
2. 合成原子是否违反产品语义？→ 违反就 ✅ 保持
3. 都不明确 → 挂起问人，**不要自行决定**

**顺手一并确认（S1 实测：这两件预判时漏了，都是读代码才发现的，且都会实际影响实现）**：

- **端点鉴权归属** → 读 TV `filter/AuthFilter` 的 `PROTECTED_PREFIXES` / `PROTECTED_EXACT` 两份清单，
  逐条记下本切片每个端点是否需要登录。
  （S1 实例：`/coupon/list` 公开；`/coupon/grab`、`/coupon/my` 需登录。
  清单本身**不迁移**，但它是鉴权口径的**事实来源**，要逐条固化成测试。）
- **旧异常出口的对外文案** → 读各 Service 的 `catch` 与 `ExceptionFilter`，确认删掉手工包装后
  **对外 `msg` 会不会变**。
  （S1 实例：删掉 1062 判断后，重复抢券的 msg 由 `"您已抢过该优惠券"` 变成全局 409 文案。
  状态码/业务码通常不变，但这属"契约差异"，必须写进决策表与提交信息，否则会被当成回归。）

### 步骤 3：盘点跨模块依赖

```powershell
Select-String -Path "old-project\TVhomework1\src\main\java\com\itheima\<模块>\**\*.java" `
  -Pattern "import com\.itheima\.(\w+)\.dao\." |
  ForEach-Object { ($_.Line -replace '.*import com\.itheima\.','') -replace '\.dao\..*','' } | Sort-Object -Unique
```

确认被依赖的模块**已存在**或本切片会一并交付（薄依赖）。

### 步骤 4：搬 DAO（SQL 复制，签名重写）

机械改动清单（《迁移参照系》§2.1）：

| 旧 | 新 |
|----|-----|
| `Connection conn` 首参 | 删掉 |
| `throws SQLException` | 删掉 |
| `?` + `setXxx(n, v)` | `#{name}` |
| `ResultSet` 手工遍历 | `<resultMap>` 显式声明 |
| `StringBuilder` 拼 `IN (?,?)` | `<foreach>` |
| `@Component` | `@Mapper` |
| `Statement.RETURN_GENERATED_KEYS` 手工取键 | `useGeneratedKeys="true" keyProperty="id"` |

**SQL 文本、表名、列名、COUNT 口径、索引使用方式——原样保留。**

新文件放：`src/main/java/.../<模块>/dao/XxxDao.java` + `src/main/resources/mapper/XxxMapper.xml`

### 步骤 5：重写 Service（学语义，删骨架）

- 删：`transactionTemplate.execute`、`conn` 穿透、`catch (SQLException)` 手工包装
- 留：**校验顺序**、**异常类型**、**幂等口径**、**失效时机**
- 加：`@Transactional`（按步骤 2 的决策）
- "提交后副作用"（缓存失效 / MQ 投递）→ `@TransactionalEventListener(phase = AFTER_COMMIT)`

参考实现：`user/service/UserService.java`（含 U-1 刻意不加事务的范例）

### 步骤 6：搬 Controller + 鉴权

| 旧 | 新 |
|----|-----|
| `@WebServlet("/x/*")` + `switch(pathInfo)` | `@RestController` + `@PostMapping` |
| `RequestParser.parse(req, DTO.class)` | `@RequestBody @Valid` |
| 逐个人工 if 校验 | 声明式约束注解 |
| `(Long) req.getAttribute("userId")` + null 检查 | `@CurrentUserId` + `@RequiresLogin` |
| `CommandConverter.xxxToCommand(dto)` | 删（双层 DTO/Command 无必要） |

**鉴权声明用 `@RequiresLogin`，不要去改任何路径清单**——那正是决策③要根治的债。

### 步骤 7：写测试（防"测试迎合"）

三条纪律（《测试策略与阶段验收》§四）：
1. **断言可观察行为**（HTTP 状态 + 信封 + DB 终态），不断言内部调用
2. **独立 oracle 复算**：关键计数不信任接口回显，直接 `SELECT COUNT(*)` / 直接查列
3. **语义翻译而非机械复制**：保留旧测试的**意图**，形式随架构重写

测试类继承：
- 要发 HTTP → `extends AbstractHttpIntegrationTest`
- 只用 bean → `extends AbstractIntegrationTest`

### 步骤 8：验收（DoD 全绿才算完成）

见下方 §三。

### 步骤 9：回填文档 + 提交

- 更新《事务边界决策表》状态
- 更新《切片计划.md》进度
- 提交信息记录 `git diff` 看不见的决策（为什么这样定、踩了什么坑）

---

## 二、本项目已踩过的坑（**务必先读，避免重踩**）

> 这些坑的共同特征：**不报错、症状离病因很远**。

| # | 坑 | 症状 | 正解 |
|---|----|------|------|
| 1 | **Jackson 3 包名迁移** | `程序包 com.fasterxml.jackson.databind 不存在` | `ObjectMapper` 在 `tools.jackson.databind`；但**注解仍在** `com.fasterxml.jackson.annotation`（`JsonInclude` 等不用改） |
| 2 | **MyBatis 配置前缀** | 运行期 `Invalid bound statement (not found)` | 前缀是 **`mybatis.`（顶层）**，不是 `spring.mybatis.`。写错会被**静默忽略** |
| 3 | **Security 默认保护链** | 所有请求 401，日志有 `Using generated security password` | `spring-boot-starter-security` 在类路径即自动装配默认链。已由 `SecurityPassthroughConfig` 临时放行；**⚠️ 接入决策③时必须删除该类** |
| 4 | **`Duration` 裸数字** | 刚登录就 401，token 的 `iat` == `exp` | `expire-hours: 2` 被解析为 **PT2S（2 秒）**。`Duration` 属性**必须带单位后缀**（`2h`） |
| 5 | **Testcontainers 容器写成基类 static 字段** | 每个测试类起新容器 → 端口漂移 → 缓存上下文连旧端口 → `Communications link failure` | 容器必须放**进程级单例**（`support/Containers.java`） |
| 6 | **`RestClient.retrieve()`** | 想断言 4xx 却抛 `HttpClientErrorException` 拿不到 body | 用 **`exchange()`** 拿原始 `ResponseEntity`（见 `AbstractHttpIntegrationTest.post/get`） |
| 7 | **Lombok 与 JDK 25** | 编译告警 `sun.misc.Unsafe ... lombok.permit.Permit` | 当前 Lombok 1.18.46 可用（>1.18.42 门槛）。降级 Boot 会导致编译失败 |
| 8 | **`@MockitoBean`/`@MockitoSpyBean` 位置** | 找不到类 | 在 `org.springframework.test.context.bean.override.mockito`（Boot 旧的 `boot.test.mock.mockito` 已移除） |
| 9 | **`TestRestTemplate` 已移除** | 找不到类 | 用 `RestClient`（Boot 4） |
| 10 | **测试里用 JVM 时间造时间窗** | 用例**偶发**失败（时好时坏）——"活动未开始却抢到了""未过期却查不到" | 时间窗一律交给**数据库时钟**：SQL 里写 `begin_time = NOW() - INTERVAL 1 HOUR, end_time = NOW() + INTERVAL 1 DAY`，**不要**用 `LocalDateTime`/`Timestamp` 绑参。根因：Testcontainers 的 MySQL 容器默认 **UTC**，JVM 是 **Asia/Shanghai**，绑参会引入偏移。属"不报错、只是偶发"的坑（S1 实测） |
| 11 | **`-o`（离线）跑 `verify`** | 测试 **全绿** 但构建失败：`Cannot access aliyun ... in offline mode`，缺 `maven-archiver` / `plexus-archiver` / `xz` / `zstd-jni` | 离线只适用于 `test`；`verify` 会走到 `maven-jar-plugin`，其依赖通常未被缓存 ⇒ **必须联网**。与代码无关，别去查业务代码（S1 实测） |

### 配置前缀速查（已实测）

| 用途 | 正确前缀 |
|------|---------|
| 数据源 | `spring.datasource.*` |
| Redis | `spring.data.redis.*`（Jedis 池在 `spring.data.redis.jedis.pool.*`） |
| RabbitMQ | `spring.rabbitmq.*`（vhost 是 `virtual-host`） |
| Flyway | `spring.flyway.*` |
| **MyBatis** | **`mybatis.*`**（特例：不在 spring 下） |
| 本项目 JWT | `video.jwt.*` |

---

## 三、验收清单（DoD）

一个切片**全部满足**才算完成：

| # | 条件 | 怎么验 |
|---|------|--------|
| 1 | 事务边界全部表态（无 🔴） | 对照《事务边界决策表》 |
| 2 | 旧可观察行为已固化为测试 | 测试清单可执行 |
| 3 | 无旧模式残留 | §三.1 反模式 grep 零命中 |
| 4 | 测试走 Testcontainers，不依赖本机手工容器 | 停掉宿主容器仍通过（§三.2） |
| 5 | 端点契约与旧版一致 | 契约测试 / 手工对照 |
| 6 | `mvnw.cmd -B test` 全绿 | 见命令 |

### 3.1 反模式自检（复制即用，排除注释行）

```powershell
$files = Get-ChildItem "src\main\java" -Recurse -Filter *.java
$hits = @()
foreach ($f in $files) {
  $lines = Get-Content $f.FullName
  for ($i = 0; $i -lt $lines.Count; $i++) {
    $t = $lines[$i].TrimStart()
    if ($t.StartsWith("*") -or $t.StartsWith("//") -or $t.StartsWith("/*")) { continue }
    if ($lines[$i] -match "Connection conn|throws SQLException|TransactionTemplate|com\.itheima\.ioc|catch \(SQLException") {
      $hits += "$($f.Name):$($i+1): $($lines[$i].Trim())"
    }
  }
}
if ($hits) { $hits } else { "✅ 零命中" }
```

> 说明：Javadoc 里**允许**出现这些词（用于说明"为什么删掉它"），所以必须排除注释行。

### 3.2 环境隔离验证（证明 T1「环境即代码」）

```powershell
docker stop redis rabbitmq          # 停掉宿主容器
.\mvnw.cmd -B test                  # 必须仍然全绿
docker start redis rabbitmq         # 用完恢复
```

### 3.3 全量验证命令

```powershell
.\mvnw.cmd -B test      # 编译 + 测试
.\mvnw.cmd -B verify    # 切片验收（含所有插件绑定）
.\mvnw.cmd -B dependency:tree   # 改过 pom 后必跑
```

---

## 四、事务边界记录模板

每迁移一条，在《事务边界决策表》按此格式补一段：

```markdown
### <模块>-<序号> `<方法名>` —— <一句话结论>

**位置**：`<旧项目相对路径>:<行号>`

**旧行为**：<引用原注释原文，TV 注释常直接写明理由>

**决策**：✅ 保持 / ⚠️ 有意改进 / 🔴 待决策

**新实现**：<关键差异点，尤其"什么不能改">

**迁移时必须固化的测试**：
- <用例> → <断言要点>
```

**特别提醒**：遇到"看起来像缺陷、实际是规格"的地方（如切片 0 的 U-1：`registerAndLogin`
刻意不加 `@Transactional`），必须写成**反模式警告**并配一条专门测试——
否则后来者（包括未来的自己）会"顺手优化"掉它。

---

## 五、扩展基线（缺什么补什么）

当前基线已就位（编译期 + 测试依赖齐备，`user` 切片已通）。后续切片可能需要：

| 需求 | 动作 |
|------|------|
| 缓存读写 | Spring Data Redis 已就位（Jedis）。复杂语义按决策⑤做**显式封装层**，不用 `@Cacheable` |
| MQ 投递/消费 | `spring-boot-starter-amqp` 已就位。重试用 `max-retries`（**首次之外**的次数） |
| 熔断降级 | 需引入 `resilience4j-spring-boot4`（决策⑤，尚未加） |
| API 文档 | 需引入 `springdoc-openapi-starter-webmvc-ui`（决策⑧，尚未加） |
| 并发抢购类 | 决策⑩限流**暂缓**；coupon 切片先做单机语义，限流专项时再补 |

**加依赖的纪律**：依赖表已冻结，加新依赖 = 改共享状态。
若必须加，**单独提交**并说明理由，不要混在业务切片里。

---

## 六、切片执行的最小上下文

新窗口开工时，读这四份即可，无需其它历史：

1. 本文件（流程 + 坑 + 验收）
2. 《切片计划.md》（做什么、顺序、阻塞关系）
3. 《迁移参照系.md》（每层怎么搬、什么不搬）
4. 《事务边界决策表.md》（事务怎么定）

**参照实现**（照抄结构最省事）：`user` 模块
- DAO：`user/dao/UserDao.java` + `resources/mapper/UserMapper.xml`
- Service：`user/service/UserService.java`
- Controller：`user/controller/UserController.java`
- 测试：`user/UserFlowTests.java`、`user/TransactionBoundaryTests.java`
- 地基：`support/Containers.java`、`support/AbstractIntegrationTest.java`、`support/AbstractHttpIntegrationTest.java`
