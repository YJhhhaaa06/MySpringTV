#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""反面盘点（"迁移完成"判据**腿 2**）：老项目 `src/main` 的每个资产 → 本仓的"去向"。

## 为什么需要它（这条腿本仓从未有过）

判据（K-2）四条腿里，**腿 3 与腿 2 是对偶的**：

- 腿 3 = "**改了**的地方都说了"（《决策留痕表》D 类）；
- 腿 2 = "**没改**的地方都还在"（本脚本）。

**缺腿 3 ⇒ 到处是静默回归；缺腿 2 ⇒ 删了没人知道。**
`endpoint_matrix.py` 只覆盖"端点"一种资产；除端点以外的老资产（缓存 / MQ / 配置 /
过滤器 / IoC / 工具 / 异常 / 前端）此前**没有任何复算依据**。

## ★ 做法：按**域**声明覆盖，**不是**"文件 × 文件对照表"

若按文件/符号一一对，会产出两类噪声：

- **伪缺口**：老项目 150+ 行手写 IoC 在 Spring 里**本就没有对应物**（它是"不迁移"的表态，
  不是缺口）；
- **伪闭合**：同名类 ≠ 同行为。

故本脚本的机制是 —— **声明覆盖**（`DECLARATIONS`）：

1. 扫描老侧资产（`src/main` 下全部文件 + 老 `pom.xml`）；
2. 每条声明 = `glob（覆盖哪些老文件）+ 去向类型 + 新仓落点 + 证据指针 + 说明`；
3. 判定 = **每个老文件都必须被 ≥1 条声明覆盖**；未被覆盖即 **"未表态"**。
   ⇒ 手写 IoC 是**显式的"不迁移"表态**；将来老仓新增文件也会**自动变红**。

在覆盖率之上再叠加**域级对账**（§B），每行判定都落到**可观察行为**。

## 用法

    python tools/migration_matrix.py                 # 全部：覆盖率 + 11 个域 + 发现项
    python tools/migration_matrix.py --coverage      # 只看覆盖率与未表态项
    python tools/migration_matrix.py --domain config # 只看某一域（可多次）
    python tools/migration_matrix.py --list-domains  # 列出域名
    python tools/migration_matrix.py --strict        # 把「未登记的口径差异」也计入退出码

退出码：**未表态 > 0**、**声明重叠**、**域内对账有差异** ⇒ 非 0。
⚠️ 子集运行（`--coverage` / `--domain`）**不会**给出"腿 2 成立"的整体结论 ——
结论句会写明本次实际跑了哪些域，避免把子集结果当作整体证据。

## 局限（诚实声明）

- **正则/glob 启发式，不是编译器**。命中数会打印；与直觉不符时**以人核为准**，
  然后改本脚本的声明——**脚本是校验器，不是事实源**（与 `endpoint_matrix.py` 同口径）。
- **覆盖率只证明"被某条声明显式覆盖"，不证明声明内容为真**——声明本身要人核。
  （首次交付时由独立 reviewer 核对过一轮，改了 8 处事实错：审计点 4→1、依赖 20→16、
  `@ConfigurationProperties` 16→13、前端 19→18、`DuplicateLikeException` 判定、
  `TimeUtil` 实为死代码等。**这类错误不会有任何测试变红，只能靠人核。**）
- 老侧 SQL 是**内联字符串**（老仓**没有任何 mapper XML**），语句计数是正则近似。
- 值一致性只覆盖**能机械比对的那部分**（见 §B-config）；口径不同的项**显式登记为
  `口径不同` 并给出证据指针**，不由脚本替人下结论。
- 「发现项」（`FINDINGS`）是**人工维护**的清单：脚本只能核对"已声明的口径"，**发现不了
  自己没想到的口径差异**。默认不阻塞退出码（腿 2 只问"有没有表态"），`--strict` 才卡。
- `_old_props()` / `tvconf.flat_yaml()` 用"遇 `#` 即注释"剥行内注释 ⇒ **值本身含 `#` 会被截断**。
  已核本仓两份配置文件当前**均无**此情形，但换配置时要留意。
"""

from __future__ import annotations

import argparse
import fnmatch
import re
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import endpoint_matrix  # noqa: E402
import tvconf  # noqa: E402

OLD = tvconf.OLD_ROOT
OLD_POM = OLD / "pom.xml"


# ══════════════════════════════════════════════════════════════════════════
# A. 声明覆盖表（反面盘点的门禁）
# ══════════════════════════════════════════════════════════════════════════

@dataclass
class Decl:
    glob: str  # 相对 OLD 的 glob（`*` 亦匹配 `/`，故包级前缀即可覆盖整棵子树）
    domain: str
    dest: str  # 去向类型
    target: str  # 新仓落点 / 替代物
    evidence: str  # 证据指针
    note: str = ""


DECLARATIONS: list[Decl] = [
    Decl("src/main/java/com/itheima/admin/*", "admin 域", "语义重写",
         "admin/（3 controller + 1 service + 3 audit 模型）", "B-6 / B-16",
         "8 端点；审计点老 7 → 新 6——唯一丢弃的 1 处随死代码 changePhone（B-18 已被 G-5 更正）"),
    Decl("src/main/java/com/itheima/cache/*", "缓存基建", "替代物（重写）",
         "common/cache（8）+ 各域 */cache（每域 RedisOps+Cache）", "C-1 / C-3 / C-5",
         "不搬 TV cache 包；多域共性抽 common/cache"),
    Decl("src/main/java/com/itheima/comment/*", "comment 域", "语义重写",
         "comment/（含 cache/event/dao/service）", "B-3",
         "写路径 S2 交付；读路径在 ContentService（B-3）"),
    Decl("src/main/java/com/itheima/common/*", "公用模型", "语义重写",
         "common/model/dto/PageResult", "PageResult 类注释",
         "唯一文件：分页 DTO（同名同路径逐字重写）"),
    Decl("src/main/java/com/itheima/config/*", "配置装载", "替代物",
         "common/config（16 文件，其中 13 个 @ConfigurationProperties）+ application.yaml", "决策⑨ / B-12 / B-13",
         "AppConfig 多级覆盖链退役，交 Spring 标准机制"),
    Decl("src/main/java/com/itheima/content/*", "content 域", "语义重写",
         "content/（FeedService 有意迁 feed 域）", "B-1 / B-3 / B-22",
         "thin/full 拆分；/feed 读编排迁 feed"),
    Decl("src/main/java/com/itheima/controller/*", "Servlet 基座", "不迁移（管道删掉）",
         "Spring MVC + 内嵌容器；common/web", "迁移参照系 §一/§三",
         "BaseServlet/RequestParser/BaseServletUtil = 手写请求管道；"
         "AppShutDownListener 的启动校验退役（B-12）"),
    Decl("src/main/java/com/itheima/coupon/*", "coupon 域", "语义重写", "coupon/", "S1"),
    Decl("src/main/java/com/itheima/exception/*", "异常体系", "语义重写（收敛）",
         "common/exception（12）+ common/web/GlobalExceptionHandler", "迁移参照系 §三",
         "20 → 12：3 族（缓存/DB/服务端）并入框架异常与唯一出口，另 5 项收敛到父类（403/404/409/400）"),
    Decl("src/main/java/com/itheima/feed/*", "feed 域", "语义重写",
         "feed/（service 11 + mq 13 + event 2 + model/message 3）", "B-7 / B-20 / B-22"),
    Decl("src/main/java/com/itheima/filter/*", "过滤器", "替代物",
         "common/log/AccessLogFilter + common/security/JwtAuthFilter + Spring", "B-17 / 迁移参照系 §三",
         "5 个 filter 的能力分别落到日志/鉴权/编码/异常出口"),
    Decl("src/main/java/com/itheima/follow/*", "follow 域", "语义重写", "follow/", "S4"),
    Decl("src/main/java/com/itheima/ioc/*", "自研 IoC", "不迁移",
         "Spring IoC（@Component / @Autowired / @PostConstruct / @PreDestroy）", "迁移参照系 §三",
         "手写基建不迁移——**声明为「不迁移」而非缺口**"),
    Decl("src/main/java/com/itheima/like/*", "like 域", "语义重写", "like/", "S3"),
    Decl("src/main/java/com/itheima/mq/*", "MQ 基建", "替代物",
         "feed/mq（13）+ Spring AMQP；FeedTopology/FeedRabbitConfig", "C-7 / B-21 / J-1 / J-2",
         "拓扑/连接恢复/重试+DLQ/预取交 Spring AMQP；投递线程池与补偿缓冲补回（T5）"),
    Decl("src/main/java/com/itheima/upload/*", "upload 域", "语义重写",
         "upload/（+ WebMvcConfig 静态 /upload/**）", "B-5 / B-10 / B-11"),
    Decl("src/main/java/com/itheima/user/*", "user 域", "语义重写",
         "user/（含 AutoBigVStateService）", "切片 0 / S4"),
    Decl("src/main/java/com/itheima/util/*", "工具层", "替代物（逐件，见 §B-8）",
         "各落点见 §B-8", "迁移参照系 §三 / B-18"),
    Decl("src/main/webapp/*", "前端", "语义重写",
         "src/main/resources/static/（18 文件，含 index.html）", "D28 / D-13~D-15 / B-10",
         "容器描述符 web.xml / context.xml 不迁移（能力由 Spring/WebMvcConfig 承接）"),
    Decl("src/main/resources/app.properties", "配置（老档）", "逐键对照",
         "src/main/resources/application.yaml", "B-13 / C-8；本脚本 §B-6 逐键"),
    Decl("pom.xml", "依赖清单", "替代物",
         "本仓 pom.xml（22 条：Boot 托管 18 + 显式版本 4）", "决策②③④⑤⑥ / B-11",
         "老仓 16 条依赖；新仓显式版本 = resilience4j / java-jwt / mybatis-starter / archunit，"
         "amqp-client 与 jedis 走 Boot 托管。"
         "⚠️ 原 23 条中的 spring-rabbit-test 已删（第四批 T7-2 / 账 B11 → D30：声明而未启用）"),
]


# ══════════════════════════════════════════════════════════════════════════
# B. 域级对账数据
# ══════════════════════════════════════════════════════════════════════════

# B-6 配置逐键：`cmp` = int / ms / minutes / hours / float / bool / str / none
# `none` = 口径本身不同或有意裁剪 ⇒ **必须给 evidence**，由脚本打印出来给人核。
@dataclass
class Cfg:
    old: str
    new: str | None
    cmp: str
    note: str = ""
    evidence: str = ""


CONFIG_MAP: list[Cfg] = [
    Cfg("db.driver", "spring.datasource.driver-class-name", "str", "com.mysql.cj.jdbc.Driver"),
    Cfg("db.url", "spring.datasource.url", "none", "形状不同：新档加 allowPublicKeyRetrieval/characterEncoding", "决策②"),
    Cfg("db.username", "spring.datasource.username", "str"),
    Cfg("db.password", "spring.datasource.password", "none", "值同；走环境变量 ${DB_PASSWORD}", "决策⑨"),
    Cfg("db.pool.initSize", "spring.datasource.hikari.minimum-idle", "int", "自研池 → Hikari"),
    Cfg("db.pool.maxSize", "spring.datasource.hikari.maximum-pool-size", "int", "自研池 → Hikari"),
    Cfg("db.pool.timeoutMs", "spring.datasource.hikari.connection-timeout", "ms"),
    Cfg("redis.host", "spring.data.redis.host", "str"),
    Cfg("redis.port", "spring.data.redis.port", "int"),
    Cfg("redis.maxTotal", "spring.data.redis.jedis.pool.max-active", "int"),
    Cfg("redis.maxIdle", "spring.data.redis.jedis.pool.max-idle", "int"),
    Cfg("redis.minIdle", "spring.data.redis.jedis.pool.min-idle", "int"),
    Cfg("redis.connectTimeoutMs", "spring.data.redis.connect-timeout", "ms"),
    Cfg("redis.soTimeoutMs", "spring.data.redis.timeout", "ms"),
    Cfg("redis.pool.maxWaitMs", "spring.data.redis.jedis.pool.max-wait", "ms"),
    Cfg("redis.breaker.failureThreshold", "video.cache.redis-breaker.failure-rate-threshold", "none",
        "★ 口径不同：老=连续 5 次失败；新=失败率 50% / 滑窗 10 / 最少 5 次调用", "H-2 / B4"),
    Cfg("redis.breaker.cooldownMillis", "video.cache.redis-breaker.wait-duration-in-open-state", "ms"),
    Cfg("rabbitmq.host", "spring.rabbitmq.host", "str"),
    Cfg("rabbitmq.port", "spring.rabbitmq.port", "int"),
    Cfg("rabbitmq.username", "spring.rabbitmq.username", "str"),
    Cfg("rabbitmq.password", "spring.rabbitmq.password", "str"),
    Cfg("rabbitmq.vhost", "spring.rabbitmq.virtual-host", "none", "键名不同：virtual-host（不是 vhost）", "决策④"),
    Cfg("rabbitmq.connection.timeoutMs", "spring.rabbitmq.connection-timeout", "ms"),
    Cfg("jwt.secret", "video.jwt.secret", "none", "值有意改：STONE → dev-only-change-me（部署前必换）",
        "application.yaml 注释（D 类无此条）"),
    Cfg("jwt.expireHours", "video.jwt.expire-hours", "hours"),
    Cfg("upload.path", "video.upload.root", "str", "落盘根；与 media.base-url 严格分家", "B-11 / B-12"),
    Cfg("upload.maxSize", None, "none", "死配置（无消费者）——有意不搬；限额改 spring.servlet.multipart", "B-13"),
    Cfg("cache.content.ttlMinutes", "video.cache.content-ttl", "minutes"),
    Cfg("cache.comment.ttlMinutes", "video.cache.comment-ttl", "minutes"),
    Cfg("cache.like.ttlMinutes", "video.cache.like-ttl", "minutes"),
    Cfg("cache.follow.ttlMinutes", "video.cache.follow-ttl", "minutes"),
    Cfg("cache.content.indexRebuildCooldownMillis", "video.cache.content-index-rebuild-cooldown", "ms"),
    Cfg("feed.inbox.ttlMinutes", "video.feed.inbox-ttl", "minutes"),
    Cfg("feed.inbox.windowPerAuthor", "video.feed.inbox-window-per-author", "int"),
    Cfg("feed.inbox.windowMax", "video.feed.inbox-window-max", "int"),
    Cfg("feed.bigv.threshold", "video.bigv.threshold", "int"),
    Cfg("feed.bigv.userIds", "video.bigv.user-ids", "str"),
    Cfg("feed.bigv.queryBatch", "video.feed.bigv-query-batch", "int", "执行参数（非判定输入）"),
    Cfg("feed.bigv.configFile", None, "none", "★ 外置热更文件有意裁剪（无生产流量时收益为零）", "C-8"),
    Cfg("feed.bigv.refreshMillis", None, "none", "同上（热更节流窗口随 C-8 一并裁剪）", "C-8"),
    Cfg("feed.bigv.downgradeRatio", "video.bigv.downgrade-ratio", "float", "滞回系数，合法区间 (0,1]"),
    Cfg("feed.readWindowMax", "video.feed.read-window-max", "int"),
    Cfg("feed.outbox.windowSize", "video.feed.outbox-window-size", "int"),
    Cfg("feed.outbox.ttlMinutes", "video.feed.outbox-ttl", "minutes"),
    Cfg("feed.fanout.batch", "video.feed.fanout-batch", "int"),
    Cfg("feed.rebuild.authorBatch", "video.feed.rebuild-author-batch", "int"),
    Cfg("feed.delivery.queueCapacity", "video.feed.delivery.queue-capacity", "int", "B8 补回（T5）"),
    Cfg("feed.delivery.drainTimeoutMillis", "video.feed.delivery.drain-timeout", "ms", "B8 补回（T5）"),
    Cfg("feed.consume.retry.maxRetries", "spring.rabbitmq.listener.simple.retry.max-retries", "int",
        "口径同：均为「首次之外」的重试次数", "决策④"),
    Cfg("feed.consume.retry.backoffMillis", "spring.rabbitmq.listener.simple.retry.initial-interval", "ms"),
    Cfg("feed.dlq.ttlMillis", "video.feed.dlq-ttl", "ms", "★ 溢出潜伏项（B13，T7-2 处置）"),
    Cfg("feed.rebuild.debounceMillis", "video.feed.rebuild-debounce", "ms"),
    Cfg("feed.compensate.bufferCapacity", "video.feed.compensate.buffer-capacity", "int", "B9 补回（T5）"),
    Cfg("log.level", "logging.level.root", "str"),
    Cfg("log.file", "video.log.dir", "none", "文件名 → 目录：文件名交 logback-spring.xml", "G 类"),
    Cfg("log.error.file", "(logback-spring.xml)", "none", "专属输出端", "G 类"),
    Cfg("log.error.level", "(logback-spring.xml)", "none", "JUL SEVERE → logback ERROR 语义对映", "G 类"),
    Cfg("log.maxBytes", "(logback-spring.xml)", "none", "按大小轮转", "G 类"),
    Cfg("log.fileCount", "(logback-spring.xml)", "none", "轮转保留数", "G 类"),
    Cfg("log.access.file", "(logback-spring.xml)", "none", "access 专属 logger", "G 类"),
    Cfg("log.slowRequestMs", "video.log.slow-request-ms", "int", "纯数字毫秒，不是 Duration"),
    Cfg("log.audit.file", "(logback-spring.xml)", "none", "audit 专属 logger", "G 类"),
    Cfg("ioc.failFast", None, "none", "自研 IoC 整体不迁移 ⇒ 该键无处可去", "迁移参照系 §三"),
]

# 反向：新档有、老档没有。**第一列是「键或键前缀」**（`k == e` 或 `k.startswith(e + ".")` 命中）；
# 未命中且未在 CONFIG_MAP 里 ⇒ 报"既无老对应、也未登记新增"（防静默新增）。
NEW_ADDITIONS: list[tuple[str, str]] = [
    ("spring.application.name", "Spring 应用名"),
    ("spring.data.redis.client-type", "Jedis 显式指定（Boot 默认 Lettuce）"),
    ("video.cache.redis-breaker", "Resilience4j 滑动窗口参数（老侧无）；TV 只配了阈值与冷却"),
    ("video.media.base-url", "替代 TV RequestContext.getContextPath()（内嵌容器下恒空串）"),
    ("video.feed.compensate.probe-interval", "TV 是包内常量 30s；入配置以便驱动（T5 / B9）"),
    ("spring.servlet.multipart", "替代 @MultipartConfig 两处（B-13）"),
    ("spring.rabbitmq.listener.simple", "prefetch / concurrency / acknowledge-mode 等交 Spring AMQP（C-7）"),
    ("spring.datasource.hikari.pool-name", "连接池名（新档自定，老侧无此概念）"),
    ("spring.flyway", "Flyway 结构演进机制——老侧完全没有（决策⑥ / K-1）"),
    ("mybatis", "MyBatis 接入（老侧是手写 DAO）"),
    ("management", "Actuator 观测面（T2 / B3）"),
]


# ★ **发现项**：未登记的**对外可观察口径差异**。腿 2 只问"有没有去向声明"，故它不阻塞腿 2；
# 但按 K-3「每条存量异常都要被发现、登记、分类、表态」，每条**必须**在 T7-3 收口前
# 落进《决策留痕表》D 类（或明确判为"有意收紧"）。默认只打印；`--strict` 时计入退出码。
@dataclass
class Finding:
    old: str
    new: str
    detail: str


# 曾记于此的唯一一条 FINDINGS-1（老 `phoneCheck` 宽口径 vs 新 `RegisterRequest` 严正则）
# 已于第四批 T7-3（2026-10-06）**表态并登记**：见《决策留痕表》**D-16**（注册收紧）与
# **D-17**（脱敏采用真实号段口径、有意窄于老侧）。登记完必须从这里删掉，
# 否则文档说"已登记"而脚本仍判"未登记" ⇒ 又一处"没有任何测试会变红"的事实错。
FINDINGS: list[Finding] = []


# B-7 过滤器 / 监听器 / 启动守卫：老侧每一项的"能力由谁承担"
FILTER_MAP: list[tuple[str, str, str]] = [
    ("filter/AccessLogFilter", "common/log/AccessLogFilter（专属 logger access）", "T2 / G 类"),
    ("filter/AuthFilter", "common/security/JwtAuthFilter + AdminChecker", "S6 / B-17"),
    ("filter/EncodingFilter", "Spring 内建（server.servlet.encoding，UTF-8）", "迁移参照系 §三"),
    ("filter/ExceptionFilter", "common/web/GlobalExceptionHandler（唯一出口）", "迁移参照系 §三"),
    ("filter/LoginFilter", "common/security/JwtAuthFilter + @RequiresLogin", "S6 / 契约测试"),
    ("controller/AppShutDownListener", "启动硬校验退役（落盘与 ResourceHandler 读同一属性）+ UploadProperties 空值 fail-fast", "B-12"),
]

# B-8 工具层 13 件：逐件去向（"指针"输出，但不许有文件无去向）
UTIL_MAP: list[tuple[str, str, str]] = [
    ("AuditLog", "common/log/AuditLog（专属 logger audit）；审计点老 7 → 新 6，唯一丢弃的 1 处随死代码 changePhone", "B-18 / G-5"),
    ("JwtUtil", "common/security/JwtService", "决策③"),
    ("LogContext", "common/log/RequestIdFilter（MDC reqId）", "T2 / G 类"),
    ("LogFormatter", "logback-spring.xml 的 pattern", "T2 / G 类"),
    ("LogUtil", "logback-spring.xml（输出端规格表 → Spring 配置）", "T2 / G 类"),
    ("MyConnectionPool", "HikariCP（application.yaml spring.datasource.hikari）", "决策②"),
    ("MyRedisPool", "Spring Data Redis + Jedis 池（application.yaml）", "决策⑤"),
    ("PasswordUtil", "UserService 内 BCryptPasswordEncoder（spring-security-crypto）", "决策③"),
    ("RequestContext", "video.media.base-url（替代上下文路径 ThreadLocal）", "B-11"),
    ("ResultUtil", "common/web/ApiResponse", "迁移参照系 §三"),
    ("StringUtil", "拆分：校验类内联到参数/DAO；脱敏 maskPhone/maskForLog → common/log/LogMasker。"
                   "★ 手机号口径有意收紧（已登记《决策留痕表》**D-16**；"
                   "脱敏侧口径见 **D-17**）", "G 类"),
    ("TimeUtil", "不迁移（**死代码**：老侧 0 调用；且返回 `yyyy_MM-dd HH:mm:ss` 格式化串，新仓无语义等价物）", "迁移参照系 §三"),
    ("TransactionTemplate", "Spring @Transactional（手写事务管道删掉）", "SOP / 事务边界决策表"),
]

# B-8 异常 20 件：逐件去向
EXCEPTION_MAP: list[tuple[str, str, str]] = [
    ("AccessDeniedException", "不迁移（**死子类**：老侧 0 调用；新仓直接用 ForbiddenException）", "迁移参照系 §三"),
    ("AuthException", "同名保留", "—"),
    ("BusinessException", "同名保留", "—"),
    ("CacheException", "→ common/cache/CacheUnavailableException", "H-1"),
    ("CommentNotFoundException", "→ NotFoundException（收敛）", "迁移参照系 §三"),
    ("ConflictException", "同名保留", "—"),
    ("ContentNotFoundException", "→ NotFoundException（收敛）", "迁移参照系 §三"),
    ("DatabaseException", "不迁移（DataAccessException → GlobalExceptionHandler 记堆栈 → 500）", "迁移参照系 §三"),
    ("DuplicateLikeException", "不迁移（**死类**：老侧 0 调用；老/新实际抛 ConflictException(不可重复点赞) → 409）", "迁移参照系 §三"),
    ("DuplicatePhoneException", "同名保留", "—"),
    ("ErrorCode", "同名保留", "—"),
    ("ForbiddenException", "同名保留", "—"),
    ("InvalidPasswordException", "同名保留", "—"),
    ("InvalidPhoneException", "不迁移（唯一调用点在**死代码** changePhone；注册侧改用 Bean Validation @Pattern）", "迁移参照系 §三"),
    ("NotFoundException", "同名保留", "—"),
    ("ParamException", "同名保留", "—"),
    ("PasswordIncorrectException", "同名保留", "—"),
    ("ServerException", "不迁移（→ 500 由唯一出口记录）", "迁移参照系 §三"),
    ("TokenExpiredException", "同名保留", "—"),
    ("UserNotFoundException", "同名保留", "—"),
]

# B-8 CoC IoC 8 件
IOC_MAP: list[tuple[str, str]] = [
    ("Component", "@Component / @Service / @Repository / @RestController"),
    ("Inject", "@Autowired / 构造器注入"),
    ("InjectConstructor", "构造器注入（唯一构造器免注解）"),
    ("PostConstruct", "@PostConstruct"),
    ("ClassScanner", "Spring 组件扫描（@SpringBootApplication）"),
    ("IocContainer", "Spring ApplicationContext"),
    ("Initializable", "@PostConstruct / InitializingBean"),
    ("Disposable", "@PreDestroy / DisposableBean"),
]


# ══════════════════════════════════════════════════════════════════════════
# 工具函数
# ══════════════════════════════════════════════════════════════════════════

_RE_DURATION = re.compile(r"^(\d+)(ms|s|m|h|d)$")
_DUR_MS = {"ms": 1, "s": 1000, "m": 60_000, "h": 3_600_000, "d": 86_400_000}


def to_ms(text: str | None) -> int | None:
    """`"30m" / "7d" / "1000ms" / "5000"` ⇒ 毫秒；无法解析返回 None。"""
    if text is None:
        return None
    t = text.strip()
    m = _RE_DURATION.match(t)
    if m:
        return int(m.group(1)) * _DUR_MS[m.group(2)]
    return int(t) if t.isdigit() else None


def scan_assets() -> list[str]:
    """老侧被盘点的资产（相对 OLD 的 posix 路径）。"""
    out: list[str] = []
    if tvconf.OLD_SRC_MAIN.is_dir():
        for p in tvconf.OLD_SRC_MAIN.rglob("*"):
            if p.is_file():
                out.append(p.relative_to(OLD).as_posix())
    if OLD_POM.is_file():
        out.append("pom.xml")
    return sorted(out)


def match_decls(rel: str) -> list[Decl]:
    """命中该老侧路径的**全部**声明（多于 1 条即声明重叠 ⇒ 报错，见 `report_coverage`）。"""
    return [d for d in DECLARATIONS if fnmatch.fnmatchcase(rel, d.glob)]


def match_decl(rel: str) -> Decl | None:
    hits = match_decls(rel)
    return hits[0] if hits else None


# ══════════════════════════════════════════════════════════════════════════
# 报告：A 覆盖率
# ══════════════════════════════════════════════════════════════════════════

def report_coverage(assets: list[str]) -> list[str]:
    print("═" * 78)
    print("A. 覆盖率（腿 2 的门禁：每个老资产都必须有去向声明）")
    print("═" * 78)
    print(f"\n老侧资产 {len(assets)} 个"
          f"（{sum(1 for a in assets if a.endswith('.java'))} java"
          f" + {sum(1 for a in assets if '/webapp/' in a)} webapp"
          f" + {sum(1 for a in assets if a.endswith('.properties'))} properties"
          f" + {sum(1 for a in assets if a == 'pom.xml')} pom）")
    print("★ 范围与工单 §二 一致 = `src/main/**` + 老 `pom.xml`；**有意排除**：老侧 `src/test`、"
          "老仓 `.docs/`、`tools/`（自研测试链）、pom 的 build 插件\n")

    by_decl: dict[str, list[str]] = {}
    undeclared: list[str] = []
    multi_hit: list[tuple[str, list[str]]] = []
    for rel in assets:
        hits = match_decls(rel)
        if not hits:
            undeclared.append(rel)
        else:
            by_decl.setdefault(hits[0].glob, []).append(rel)
            if len(hits) > 1:
                multi_hit.append((rel, [h.domain for h in hits]))

    print(f"  {'域':<12} {'文件':>4}  去向 / 新仓落点")
    print("  " + "-" * 74)
    for d in DECLARATIONS:
        files = by_decl.get(d.glob, [])
        if not files:
            print(f"  ⚠ {d.domain:<12} {0:>4}  ← 该声明 0 命中（老仓结构变了？）")
            continue
        print(f"  ✓ {d.domain:<12} {len(files):>4}  {d.dest} → {d.target}   [{d.evidence}]")
        if d.note:
            print(f"    {'':<12}      · {d.note}")

    print()
    if undeclared:
        print(f"✗ 未表态 {len(undeclared)} 个：")
        for rel in undeclared[:40]:
            print(f"    {rel}")
        if len(undeclared) > 40:
            print(f"    … 另有 {len(undeclared) - 40} 个")
    else:
        print("✓ 未表态 = 0")
    zero_hit = [d.domain for d in DECLARATIONS if not by_decl.get(d.glob)]
    if zero_hit:
        print(f"✗ 零命中声明 {len(zero_hit)} 条（声明漂移）：{', '.join(zero_hit)}")
    if multi_hit:
        print(f"✗ 多头命中 {len(multi_hit)} 个（**声明重叠 ⇒ 未表态会漏报**，必须去歧义）：")
        for rel, domains in multi_hit[:20]:
            print(f"    {rel}  ← {domains}")
    return (
        undeclared
        + [f"零命中声明：{x}" for x in zero_hit]
        + [f"声明重叠：{rel} 同时命中 {d}" for rel, d in multi_hit]
    )


# ══════════════════════════════════════════════════════════════════════════
# 报告：B 各域
# ══════════════════════════════════════════════════════════════════════════

def dom_endpoints() -> list[str]:
    olds = endpoint_matrix.old_endpoints()
    news = endpoint_matrix.new_endpoints()
    missing = [o for o in olds if not endpoint_matrix.match(o, news)]
    print(f"  老端点 {len(olds)} · 已迁 {len(olds) - len(missing)} · 剩余 {len(missing)}"
          f"（复算：python tools/endpoint_matrix.py）")
    if missing:
        return [f"端点未迁：{o.path}" for o in missing]
    return []


def dom_business() -> list[str]:
    old_cases = 0
    for p in OLD.rglob("test_*.py"):
        old_cases += len(re.findall(r"^\s*def\s+test_", p.read_text(encoding="utf-8", errors="replace"), re.M))
    print(f"  老 pytest 用例 {old_cases} 例（老侧是**行为规格**；映射关系见《测试策略》§六）")
    print("  新仓用例数由 surefire 给出：python tools/run_tests.py（本脚本不解析控制台）")
    print("  口径：同一行为**不必映射两次**（《测试策略》§6.2）⇒ 本行是**指针**，不比 1:1")
    return []


def dom_sql() -> list[str]:
    print("  ★ 老侧**没有 mapper XML**（工单 §二 该行的前提不成立）：SQL 是内联字符串")
    old_total = 0
    for p in sorted((OLD / "src/main/java/com/itheima").rglob("*Dao*.java")):
        n = len(re.findall(r"String\s+sql\s*=", p.read_text(encoding="utf-8", errors="replace")))
        old_total += n
        print(f"    老 {p.name:<24} {n:>3} 条")
    new_total = 0
    for p in sorted((tvconf.ROOT / "src/main/resources/mapper").glob("*.xml")):
        n = len(re.findall(r"<(select|insert|update|delete)\b[^>]*\bid=", p.read_text(encoding="utf-8"), re.I))
        new_total += n
        print(f"    新 {p.name:<24} {n:>3} 条")
    print(f"  合计：老 {old_total} 条（内联） vs 新 {new_total} 条（XML 重写）")
    print("  ★ **语句数下降不是缺口**：连接参数/事务管道删掉、批量与判定类语句是新拆的")
    print("    ⇒ 判据是「每条老语句的**意图**在新仓有落点」，不是数量相等（本行需人核，脚本只报数）")
    return []


def dom_cache() -> list[str]:
    print("  TTL / 冷却窗口（逐键，值与老档对照）：")
    problems: list[str] = []
    for c in CONFIG_MAP:
        if c.old.startswith("cache."):
            problems += _check_cfg(c)
    print("  失效矩阵属**行为**，不在此机械比对 ⇒ 指针：《决策留痕表》I 类 + 各域 *Cache 失效集")
    print("  口径：失效集 = 数据 key + 空标记(empty:) + partial 标记（三件套，见 I 类）")
    return problems


def dom_mq() -> list[str]:
    print("  拓扑（老仓 MqTopology vs 新仓 FeedTopology —— **逐名相同**）：")
    names = [
        ("交换机", "feed.push.exchange / feed.rebuild.exchange / feed.dlx"),
        ("队列", "feed.push.queue / feed.rebuild.queue / feed.dlq"),
        ("路由键", "feed.push.content / feed.push.backfill / feed.rebuild.inbox / feed.dlq"),
        ("绑定", "feed.push.# / feed.rebuild.#"),
        ("DLQ 参数", "x-dead-letter-exchange=feed.dlx + x-message-ttl（值见下）"),
    ]
    for kind, val in names:
        print(f"    {kind:<8} {val}")
    print("  DLQ TTL（值对照）：")
    problems: list[str] = []
    for c in CONFIG_MAP:
        if c.old == "feed.dlq.ttlMillis":
            problems += _check_cfg(c)
    print("  消费重试：老固定退避 2 次 → spring.rabbitmq.listener.simple.retry（multiplier=1 保固定）")
    print("  ★ 补回项：异步投递线程池（J-1）+ 内存补偿缓冲（J-2）——T5 已交付")
    return problems


def _norm(v: str | None) -> str | None:
    return None if v is None else v.replace("\\", "/")


def _check_cfg(c: Cfg) -> list[str]:
    flat = tvconf.flat_yaml(tvconf.NEW_YAML)
    if c.cmp == "none":
        tail = f"[{c.evidence}]" if c.evidence else "⚠ 未给证据"
        label = c.new or "（不迁移）"
        print(f"    · {c.old:<44} → {label:<42} 口径不同/有意 {tail}")
        return [] if c.evidence else [f"配置 {c.old}：标注口径不同但**未给证据指针**"]
    if c.new is None:
        return [f"配置 {c.old}：cmp={c.cmp} 却无新键"]
    old_raw = _old_props().get(c.old)
    new_raw = tvconf.yaml_value(flat, c.new)
    if old_raw is None:
        return [f"配置 {c.old}：老档里找不到该键（声明漂移）"]
    if new_raw is None:
        print(f"    ✗ {c.old:<44} → {c.new:<42} 新档**缺键**")
        return [f"配置 {c.old}：新档缺键 {c.new}"]
    ok, detail = _cmp_values(c, old_raw, new_raw)
    print(f"    {'✓' if ok else '✗'} {c.old:<44} {_norm(old_raw):<12} vs {_norm(new_raw):<12} {detail}")
    return [] if ok else [f"配置值不一致 {c.old}: {old_raw} vs {c.new}={new_raw}（{detail}）"]


def _cmp_values(c: Cfg, old_raw: str, new_raw: str) -> tuple[bool, str]:
    if c.cmp == "int":  # 数值比较（莫用字符串比：`05` vs `5` 会假报不一致）
        try:
            return int(old_raw.strip()) == int(new_raw.strip()), ""
        except ValueError:
            return False, "非整数（声明漂移？）"
    if c.cmp == "float":
        try:
            return abs(float(old_raw) - float(new_raw)) < 1e-9, ""
        except ValueError:
            return False, "非浮点（声明漂移？）"
    if c.cmp == "str":
        return _norm(old_raw.strip()) == _norm(new_raw.strip()), ""
    if c.cmp in ("ms", "minutes", "hours"):
        factor = {"ms": 1, "minutes": 60_000, "hours": 3_600_000}[c.cmp]
        want = int(old_raw.strip()) * factor
        got = to_ms(new_raw)
        return got == want, f"({want} ms vs {got} ms)"
    return False, f"未知 cmp={c.cmp}"


_PROPS_CACHE: dict[str, str] = {}


def _old_props() -> dict[str, str]:
    if not _PROPS_CACHE:
        path = OLD / "src/main/resources/app.properties"
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.split("#", 1)[0].strip()
            if "=" in line:
                k, _, v = line.partition("=")
                _PROPS_CACHE[k.strip()] = v.strip()
    return _PROPS_CACHE


def dom_config() -> list[str]:
    print("  方向 1／2：老档 `app.properties` **逐键** → 新 `application.yaml`（值一致性机械比对）\n")
    problems: list[str] = []
    for c in CONFIG_MAP:
        problems += _check_cfg(c)

    old_keys = set(_old_props())
    declared = {c.old for c in CONFIG_MAP}
    missing_decl = sorted(old_keys - declared)
    if missing_decl:
        print(f"\n  ✗ 老档有 {len(missing_decl)} 个键**未被映射声明**（= 静默丢失）：")
        for k in missing_decl:
            print(f"      {k}")
        problems += [f"配置 {k}：老档有键但未在 CONFIG_MAP 表态" for k in missing_decl]

    print("\n  方向 2／2：新档 `application.yaml` 的键 → 老档对应（或声明「有意新增」）\n")
    flat = tvconf.flat_yaml(tvconf.NEW_YAML)
    reachable = {c.new for c in CONFIG_MAP if c.new}
    addition_prefixes = [k for k, _ in NEW_ADDITIONS]

    def handled(key: str) -> bool:
        if key in reachable:
            return True
        if any(key == p or key.startswith(p + ".") for p in addition_prefixes):
            return True
        return any(r != key and key.startswith(r + ".") for r in reachable)

    orphan = [k for k in sorted(flat) if not handled(k)]
    if orphan:
        print(f"  ✗ 新档 {len(orphan)} 个键既无老档对应、也未登记为「有意新增」：")
        for k in orphan:
            print(f"      {k}")
        problems += [f"配置 {k}：新档有键但既无老对应也未登记新增" for k in orphan]
    else:
        print(f"  ✓ 新档 {len(flat)} 个键全部可追溯到老档或已登记「有意新增」")

    print("\n  【有意新增（老档无对应）】")
    for k, why in NEW_ADDITIONS:
        print(f"    + {k}\n        {why}")
    return problems


def dom_filters() -> list[str]:
    print("  web.xml 里 5 个 filter 的注册顺序由 Spring 过滤器链承担；逐项能力去向：\n")
    for old, new, ev in FILTER_MAP:
        print(f"    {old:<34} → {new}")
        print(f"    {'':<34}   [{ev}]")
    return []


def dom_pointer(rows: list[tuple[str, str, str]] | list[tuple[str, str]]) -> list[str]:
    for row in rows:
        if len(row) == 3:
            print(f"    {row[0]:<26} → {row[1]:<58} [{row[2]}]")
        else:
            print(f"    {row[0]:<26} → {row[1]}")
    print(f"  （共 {len(rows)} 件，全部有去向；明细理由见指针列）")
    return []


def dom_frontend() -> list[str]:
    """机械核对：老 webapp 每个资产在新仓 static/ 下的落点**存在性**。"""
    new_static = tvconf.ROOT / "src/main/resources/static"
    declared_drop = {
        "src/main/webapp/WEB-INF/web.xml": "容器描述符不迁移；能力交 Spring 过滤器链",
        "src/main/webapp/META-INF/context.xml": "容器描述符不迁移；/upload/** 改由 WebMvcConfig 挂（B-10）",
    }
    problems: list[str] = []
    old_files = sorted(
        p.relative_to(OLD).as_posix()
        for p in (OLD / "src/main/webapp").rglob("*")
        if p.is_file()
    )
    for rel in old_files:
        if rel in declared_drop:
            print(f"    · {rel:<40} 不迁移（{declared_drop[rel]}）")
            continue
        sub = rel[len("src/main/webapp/"):]
        expected = new_static / sub
        if expected.is_file():
            print(f"    ✓ {rel:<40} → resources/static/{sub}")
        else:
            print(f"    ✗ {rel:<40} → resources/static/{sub} **不存在**")
            problems.append(f"前端资产未落位：{rel} → {expected.relative_to(tvconf.ROOT)}")
    print(f"  小计：老 {len(old_files)} 文件 = 迁移 {len(old_files) - len(declared_drop)}"
          f" + 显式不迁移 {len(declared_drop)}")
    return problems


def report_findings(strict: bool) -> list[str]:
    """发现项：**未登记的口径差异**。不阻塞腿 2，但必须被看见（`--strict` 时计入退出码）。"""
    if not FINDINGS:
        print("  ✓ 无未登记的口径差异")
        return []
    print(f"  ★ {len(FINDINGS)} 条**未登记**的对外可观察差异"
          f"（不阻塞腿 2；T7-3 收口前必须表态{'；当前 --strict 下计入退出码' if strict else ''}）：\n")
    for i, f in enumerate(FINDINGS, 1):
        print(f"    FINDINGS-{i}  老：{f.old}")
        print(f"                 新：{f.new}")
        print(f"                 判定依据：{f.detail}\n")
    return [f"未登记的口径差异 FINDINGS-{i}：{f.new}" for i, f in enumerate(FINDINGS, 1)] if strict else []


DOMAINS: dict[str, tuple[str, object]] = {
    "endpoints": ("端点（指针 → 《端点对照表》）", dom_endpoints),
    "business": ("业务行为（指针 → 《测试策略》§六）", dom_business),
    "sql": ("SQL / DAO（计数 + 一致性）", dom_sql),
    "cache": ("缓存（TTL 逐键 + 失效矩阵指针）", dom_cache),
    "mq": ("MQ（拓扑逐项 + DLQ TTL）", dom_mq),
    "config": ("★ 配置逐键对照（老档 ↔ 新档，双向）", dom_config),
    "filters": ("过滤器 / 监听器 / 启动守卫", dom_filters),
    "util": ("工具层（13 件逐件去向）", lambda: dom_pointer(UTIL_MAP)),
    "exception": ("异常体系（20 件逐件去向）", lambda: dom_pointer(EXCEPTION_MAP)),
    "ioc": ("自研 IoC（8 件 → Spring）", lambda: dom_pointer(IOC_MAP)),
    "frontend": ("前端（webapp 落位存在性）", dom_frontend),
}


# ══════════════════════════════════════════════════════════════════════════
# main
# ══════════════════════════════════════════════════════════════════════════

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="反面盘点（判据腿 2）：老项目 src/main 的每个资产 → 本仓的「去向」。",
        epilog="未表态 > 0 或域内对账有差异 ⇒ 退出码非 0。**只读**：不写任何库与磁盘。",
    )
    parser.add_argument("--coverage", action="store_true", help="只看覆盖率与未表态项")
    parser.add_argument("--domain", action="append", default=[], help="只看某一域（可多次；见 --list-domains）")
    parser.add_argument("--list-domains", action="store_true", help="列出可用的域名")
    parser.add_argument("--strict", action="store_true",
                        help="把「未登记的口径差异」（FINDINGS）也计入退出码（T7-3 收口时用）")
    args = parser.parse_args(argv)

    if args.list_domains:
        for key, (title, _) in DOMAINS.items():
            print(f"  {key:<12} {title}")
        return 0

    if not tvconf.OLD_SRC_MAIN.is_dir():
        print(f"✗ 老项目源码不存在（已 gitignore，仅本地）：{tvconf.OLD_SRC_MAIN}", file=sys.stderr)
        print("  本仓禁止删除老项目——它是判据腿②的唯一事实源（K-4）。", file=sys.stderr)
        return 2

    assets = scan_assets()
    coverage_problems = report_coverage(assets)

    run_domains: list[str] = []
    domain_problems: list[str] = []
    if not args.coverage:
        run_domains = args.domain or list(DOMAINS)
        unknown = [d for d in run_domains if d not in DOMAINS]
        if unknown:
            print(f"\n✗ 未知域名：{unknown}（见 --list-domains）", file=sys.stderr)
            return 2
        for key in run_domains:
            title, fn = DOMAINS[key]
            print()
            print("═" * 78)
            print(f"B. 域级对账 —— {title}")
            print("═" * 78)
            print()
            domain_problems += fn()

    print()
    print("═" * 78)
    print("C. 发现项（未登记的口径差异）")
    print("═" * 78)
    print()
    finding_problems = report_findings(args.strict)

    all_problems = list(dict.fromkeys(coverage_problems + domain_problems + finding_problems))
    full_run = not args.coverage and not args.domain
    scope = ("本次仅跑覆盖率（**未跑任何域级对账**）" if args.coverage
             else f"本次跑了 {len(run_domains)}/{len(DOMAINS)} 个域"
                  + ("" if full_run else f"：{', '.join(run_domains)}（**非全量**）"))
    print("═" * 78)
    print(f"结论：覆盖率问题 = {len(coverage_problems)}（含未表态），域级差异 = {len(domain_problems)}"
          f"，发现项 = {len(FINDINGS)}（--strict 下未登记项计入退出码）")
    print(f"      范围：{scope}")
    if all_problems:
        print("✗ 腿 2 未成立 —— 逐条如下（exit != 0）：\n")
        for p in all_problems:
            print(f"  · {p}")
        print("═" * 78)
        return 1
    if full_run:
        print("✓ 腿 2 成立：老侧每个资产都有去向声明，且**全部 11 个域**对账无差异。")
    else:
        print(f"✓ 本次所选范围无差异，但**腿 2 的完整结论需要全量跑**（{scope}）⇒ 见上。")
    print("注：覆盖率只证明「被某条声明显式覆盖」，声明本身仍需人核（见文件头「局限」）。")
    print("═" * 78)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
