# tools/devclean/ —— 开发期数据清理（**不是运维脚本**）

> 共同语言约定（UTF-8 输出 / 路径从仓库根推导 / 会写的脚本默认只预演）见 **`tools/README.md` §三**，此处不重复。
>
> ★ 本目录同时承接**派生数据修复**（`recount.py` 重算计数 / `backfill_duration.py` 回填时长）：
> 它们只改**派生列**、可重复执行、默认 dry-run；§二 的删除红线**不适用于**这两类（它们不删行）。

---

## 一、定位：清"开发 / 测试造出来的数据"，不碰游玩数据

一句话：**删什么由人划定，脚本只顺悬挂引用清扫。**

你手工删掉测试用户行之后，凡是指向已不存在的 `users` / `content` / `comment` / `favorite_folder` 的行，
就是"根没了"的行，脚本把它们带走。这是"**不碰游玩数据**"的**结构性**保证：
脚本**永远没有"自己决定删什么"的权力** —— 它不判断"这条像不像测试数据"，只判断"它的根还在不在"。

---

## 二、红线（五条，改脚本前先读）

| # | 红线 | 为什么 |
|---|------|--------|
| 1 | **默认 `dry-run`**，`--apply` 才落库 / 落盘 | 与全仓"先看结论再执行"一致 |
| 2 | **不引用本仓目录之外的任何盘符** | 授权 agent 误操作时爆炸半径锁在本项目内；跨盘备份由人手动 |
| 3 | 破坏性谓词**一律 `NOT EXISTS`**，**禁 `NOT IN (子查询)`** | 后者遇到 NULL 会**静默地一行都不匹配**——该删的没删你却看不出来 |
| 4 | **根集由人划**（手工删测试用户），脚本只顺悬挂 | 见 §一 |
| 5 | **跨盘备份、`trash/` 清理都由人手动** | 这两步不可逆，脚本绝不代劳 |

---

## 三、脚本：清理 / 重算 / 回填 + 一个编排

| 脚本 | 干什么 | 默认 | `--apply` 做什么 |
|------|--------|------|------------------|
| `db_clean.py` | **DB 清理**：顺 15 类悬挂引用逐表清扫（`NOT EXISTS`） | 报「将删 N / 保留 M」 | 单事务执行删除 + 末尾复核残留 |
| `media_clean.py` | **媒体清理**：盘上文件若无任何 `content_media.url` 指向它 ⇒ 孤儿 ⇒ **移到** `<媒体根同级>/trash/` | 列孤儿（含大小 / 样例 20 个） | 有孤儿时搬走并写 `trash/manifest-<时间戳>.txt`（无孤儿则不写） |
| `recount.py` | **计数重算**：重算 7 个计数列，口径 = **不含软删行** | 逐列报"当前漂移行数"（= `F-07` 探针） | 单事务重算 + 复核并打印剩余漂移（**不改变退出码**，要判失败请自己看输出） |
| `backfill_duration.py` | **存量视频时长回填**（T46 的推翻条件；只填 `duration_seconds IS NULL`） | 逐行报 可回填 / 文件缺失 / 解析失败 | 单事务写 `CASE` 回填 + 复核目标行仍为 NULL 的数量 |
| `devclean.py` | **一键编排**：`备份(DB) → 媒体确认关卡 → DB → 媒体 → 重算` | **全链 dry-run** | 先备份 DB、含媒体步需 `--media-backed-up` |

> `backfill_duration.py` **不并入 `devclean.py` 编排**：它不是例行清理，是一次性数据修复
> （同 `recount.py` 也不在编排里）。跑它就是一条命令，别为一件事加第二套编排。

### 3.1 顺序是**依赖**，不是偏好

```
DB 清理  →  媒体清理  →  计数重算
```

- **DB 不先清**：被删内容的 `content_media` 行还在引用 URL ⇒ 磁盘文件不会被判成孤儿（媒体步骤白跑）；
- **重算不后置**：就还在给即将删掉的明细行计数（算完立刻又不一致）。

`devclean.py` 只做编排、**不复制任何逻辑**；三个子脚本各自可**单独跑**，
`devclean.py` 在 `--only` 跳过 DB 步时会**显式警告**这条依赖。

### 3.2 `recount.py` 覆盖的 7 列

```
content.comment_count   = COUNT(comment WHERE content_id=? AND is_deleted=0)      （含楼中楼）
content.like_count      = COUNT(content_like WHERE content_id=?)
content.favorite_count  = COUNT(DISTINCT user_id) favorite_item WHERE content_id=?  （按人去重）
users.follow_count      = COUNT(follow WHERE user_id=?)
users.follower_count    = COUNT(follow WHERE followed_user_id=?)
comment.like_count      = COUNT(comment_like WHERE comment_id=?)
comment.reply_count     = COUNT(comment WHERE parent_id=? AND is_deleted=0)
```

★ 口径必须与**运行时写路径**逐字一致（来源：`mapper/ContentMapper.xml` / `CommentMapper.xml` / `UserMapper.xml`），
否则重算当天就会重新制造 `F-07` 那类"计数 vs 明细不一致"。

### 3.3 为什么 `db_clean` 的 dry-run 也真跑 SQL

dry-run 与 apply **走同一段 SQL**：dry-run = 在同一事务里**真跑删除再 `ROLLBACK`**。这不是图省事：

- 若 dry-run 只做静态 `COUNT`，就**算不出级联**（删主楼会连带带走它的楼中楼 / 点赞 / 媒体），
  **系统性少报**"将删 N"、并把"保留 M"抬得虚高 —— 护栏反被引向"看起来更安全"那一边；
- `ROLLBACK` 保证一个字节都不留，所以"**报的和干的一致**"。

`recount.py` 同理：dry-run 的漂移查询与 apply 的聚合**共用同一段派生表 SQL**（`AGG`），口径只有一处。

### 3.4 `backfill_duration.py` 的口径（一次性，2026-10-27）

| 项 | 口径 |
|---|---|
| 解析 | 与 `upload/service/Mp4DurationReader` 逐条一致：只读 box 头，moov 可在文件尾，v0 / v1，`size==0/1`；`timescale==0` / duration 全 F / 截断 / 非 mp4 ⇒ **保持 NULL**（不写 0） |
| 写什么 | **只填 `duration_seconds IS NULL`**；已有值一个都不覆盖（可重复执行） |
| 路径 | `/upload/{video|image|cover}/...` → 媒体根；拒绝 `..` 与越界（与 `FileUploadService` 同源） |
| 文件缺失 | 跳过并登记 —— **不写 0**（`file_exists=0` 是审计快照，不作为"跳过"依据；确实读不到文件时绝不猜） |
| 缓存 | **不刷缓存**；`content:{id}` 在 TTL 内仍可能读到 null（既有口径，见 `history.md` §六.3） |

⚠️ Python 侧解析器是 `Mp4DurationReader` 的**镜像**：改 Java 规则必须同步改它。
落地时已用 Java 解析器对媒体根下 **44 个真实 mp4** 逐一比对（结果全等）。

---

## 四、用法

```bash
# 编排（推荐：先看全链预演）
python tools/devclean/devclean.py                            # 全链 dry-run
python tools/devclean/devclean.py --only media               # 只预演媒体步骤
python tools/devclean/devclean.py --skip media               # 跳过媒体步骤（可重复）
python tools/devclean/devclean.py --apply --media-backed-up  # 真跑（自动先备份 DB）
python tools/devclean/devclean.py --apply --no-backup        # 跳过 DB 备份（自担风险）
python tools/devclean/devclean.py --apply --out backups      # 备份输出目录（仅 --apply 且未 --no-backup 时生效）

# 单跑（三步都可独立执行）
python tools/devclean/db_clean.py                            # 报「将删 N / 保留 M」
python tools/devclean/db_clean.py --apply
python tools/devclean/media_clean.py                         # 列孤儿
python tools/devclean/media_clean.py --apply
python tools/devclean/recount.py                             # = F-07 探针
python tools/devclean/recount.py --apply

# 存量时长回填（一次性；先备份再跑）
python tools/devclean/backfill_duration.py                   # 预演：可回填 / 文件缺失 / 解析失败
python tools/devclean/backfill_duration.py --apply
```

`devclean.py --apply` 的两道关卡，**按这个顺序执行**：① **先备份 DB**（调 `tools/backup.py`，**DB-only**，媒体不备份）；
② 然后才检查**含媒体步时必须显式 `--media-backed-up`** —— 缺这个开关会**中止清理**，
但 ① 的备份目录**已经落下了**（不是白跑，别以为它没执行）。

---

## 五、退出码

| 脚本 | `0` | 非 `0` |
|------|-----|--------|
| `db_clean.py` | 成功（dry-run 与 apply 都返回 0） | `1` 解析 / 连接 / SQL 失败，或 apply 后仍有残留 / 护栏表被改动；`2` 未找到 `mysql` 客户端 |
| `media_clean.py` | 成功 | `1` 配置 / 连接 / 扫描失败；`2` 未找到 `mysql` 客户端 |
| `recount.py` | 成功 | `1` 连接 / SQL 失败；`2` 未找到 `mysql` 客户端 |
| `backfill_duration.py` | 成功（dry-run 与 apply 都返回 0） | `1` 连接 / SQL / 回填后复核失败；`2` 未找到 `mysql` 客户端 |
| `devclean.py` | 成功 | `1` 中止（备份失败 / 缺确认关卡 / 子脚本失败） |

---

## 六、刻意不做（**如实声明，不是漏项**）

1. **不删 `users` 行** —— 删用户由人手工做（本仓无"删除用户行"业务，未来也不会有）；
2. **不碰 `coupon` 表** —— 清 `coupon_order` 会让 `coupon.stock`（只在抢券时扣、无重算路径）与订单脱钩，
   `stock` 怎么办由人在手动清 `coupon` 时一并定；
3. **不管 `video` / `videoinfo`** —— TV 遗留表，**在本仓无任何消费者**（用户 2026-10-17 确认），
   不属"开发期造的数据"；
4. **`comment_media` 只登记、不据此判断** —— 本仓无消费者 ⇒ 它的 URL 一律按孤儿处理；
   若将来启用它，其图片会被误判为孤儿（媒体步的 dry-run 输出里**显式登记**了这一行）；
5. **不做统一 CLI** —— `devclean.py` 只是编排壳，子脚本必须能单跑（本仓"脚本少，直接调即可"的口径）。

---

## 七、与 `tools/backup.py` 的关系

**连接参数只有一个来源**：`common.py` 直接 `import backup`，复用它的 `application.yaml` 解析
（`spring.datasource.{url,username,password}` + `DB_*` 环境变量覆盖）—— 不复制一份，
免得同一个库出现两套连接逻辑。`mysql` 客户端由 `common.mysql_exe()` 探测（PATH → 常见安装目录），
口令走 `MYSQL_PWD`，**不出现在命令行 / 进程表**里。

---

## 八、已知边界

- **单趟收敛依赖 `comment.parent_id` 深度 ≤ 2**（楼中楼只有一层，app 口径）。出现更深链时**不会静默漏掉**：
  apply 末尾的复核把残留**响亮报出**并以非零码退出，再跑一次即收敛。
- **`media_clean` 只扫 `media/{video,image,cover}` 三个子目录**（不递归）；URL → 磁盘路径的解析
  **逐字复用** `FileUploadService` 的口径（`/upload/(video|image|cover)/名称` + `..` 守卫）。
- **"被引用但盘上缺失"只登记、不据此判断**：它含"从未扫描"误报（见 `F-05` / `E-2`）。
