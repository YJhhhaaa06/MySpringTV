# 前端 GAP 台账（CURRENT_FRONTEND_GAPS）

> **定位**：本仓的**唯一接口差异清单**（原从 [CURRENT_FRONTEND.md](./CURRENT_FRONTEND.md) §三 拆出，2026-10-27）。
> **入口** = [CURRENT_INTEGRATION.md](./CURRENT_INTEGRATION.md)（联调期登记台）；本文件只承接「GAP 表 + 实测补充」，
> 新增 GAP 只加在这里，ID 不回收。⚠️ 联调期**新发现**的差异也进本表（不开新编号空间）。
> 类型 / 临时表现 / 编号纪律与原 §三 完全一致（下面保留原文）。

`类型` 四分类：`M1 字段缺` / `M2 形状异` / `M3 端点缺` / `M4 语义异`。
`临时表现` **必填**（隐藏？占位？假数据？）—— 缺这格，日后没人分得清「没做」还是「假装做了」。
定位用 `文件#函数`（如 `frontend/src/api/adapters/videoCard.ts#toVideoCard`），**GAP 编号不进代码注释**
（不新开无人管辖的编号空间）。
> ★ **2026-10-27 更正**：`GAP-01/05/07` 已进入 `src/main` 约 30 处（作交叉引用），`GAP-10` 沿用 ——
> 编号可作"为什么有这段代码"的锚点，但**不得**据此新开编号空间（新编号只能加在本表）。

| ID | 类型 | 页面·组件 | 期望 | 现状 | 谁适配 | 临时表现 | 后端改动点 | 状态 |
|---|---|---|---|---|---|---|---|---|
| GAP-01 | M1 字段缺 | 首页 / VideoCard | 列表带 `playCount`（动态卡片另带 `likeCount` / `commentCount`） | `ContentVO` 已按面平铺三个可空计数 | ✅ 前后端均已接 | ✅ 封面左下播放量角标（图文不显示，见 GAP-05）；like/comment 由**动态页**（`/feed`）消费（2026-10-27） | ✅ 后端 2026-10-26 已交付（矩阵见 [缓存与数据一致性 §5.3](../architecture/tech/cache/缓存与数据一致性.md)）；前端 2026-10-27 全部接完 | **已销账** |
| GAP-02 | M3 端点缺 | 首页 / ChannelWall | 分区列表 | 无 `GET /categories` | 前端适配 | **常量表 18 项** | 无（不补） | 长期 |
| GAP-03 | M4 语义异 | 播放页 / DanmakuLayer | 弹幕流 | 无弹幕域（无表/无端点） | 前端适配 | **不做 + 占位角标** | 无 | 不做 |
| GAP-04 | M1 字段缺 | 首页 / VideoCard | 弹幕数角标 | 同 GAP-03 | 前端不做 | **不显示该数字** | 无 | 不做 |
| GAP-05 | M4 语义异 | 首页·播放页 / NumberText | 图文 `play_count` 语义 | 列叫 play，图文实为「浏览」，且对图文**结构性恒 0**（GAP-10） | 前端适配 | **图文不渲染计数**（画「浏览 0」＝把"没接管道"说成"没人看"）；视频按「播放」 | 无 | 长期 |
| GAP-06 | M3 端点缺 | 首页 / VideoGrid | 推荐流分页（无限加载） | `GET /contents/recommended` **固定 12 条、无 page 参数**；`GET /contents` 是**搜索专用**（`keyword` 必填，空白即 400 `输入不能为空`） | 后端补（**已拍板：等后端**） | 只渲染 12 条；**不做无限滚动** | 给 recommended 加分页参数，或新增推荐分页端点 | 待补（等后端） |
| GAP-07 | M2 形状异 | 全部页面 / `api/adapters/*` | 生成类型与运行时一致 | spec 已用 3.1 的 `anyOf: [原类型, {type: "null"}]` 标出已登记的可空字段（`coverUrl` / `durationSeconds` / `interaction` / 三态 / 失效占位等） | ✅ 前后端均已接 | adapter 仍按 `null` 兜底（运行时真会显式 null） | ✅ 已落地：`OpenApiConfig#nullableVoFieldsCustomizer` + `NULLABLE_VO_FIELDS` 注册表（只登记**确有 null 分支**的字段）；spec 快照已重生成 | **已销账（2026-10-27）** |
| GAP-08 | M1 字段缺 | 顶栏 / 播放页侧栏 / 头像 | 头像 URL | `UserInfoVO` / `ProfileVO` 都**没有头像字段**，也没有头像上传域 | 前端适配（不做头像域） | **6 张本地 SVG 默认头像**（`UserAvatar` 按 `userId` 稳定分配；无身份才用首字兜底；2026-10-31） | 无（不补）。**自定义头像上传 = 新头像域，本窗不做（2026-10-31 拍板）** | 长期 |
| GAP-09 | M3 端点缺 | 首页 / HeroBanner | 运营 banner 位（B 站首页大轮播是广告/活动位） | 无任何 banner/运营端点 | 前端适配 | 用 `recommended` 前 3 条**有封面的**做「推荐橱窗」轮播（真封面真标题，非假图）；无内容时不渲染轮播 | 无（不补） | 长期 |
| GAP-10 | M3 端点缺（参数） | 搜索页 / tabs·排序条 | `type` 筛选（视频/图文）+ 排序（播放/最新） | 原只有 `keyword/page/pageSize` | **前后端均已补** | —（已可筛可排） | ✅ 已落地（2026-10-27）：`?type=VIDEO\|POST&order=LATEST\|PLAY`，两个**可选枚举**（大小写敏感，非法值 400 + `errors[field]`）；类型过滤与 `count` **共用 mapper 片段**（total 同源）；`PLAY` = `play_count DESC, id DESC`。⚠️ 图文 `play_count` 结构性恒 0 ⇒ 前端**图文 tab 不渲染**「最多播放」，后端**不设特例**（照收不报错）。⚠️ 与 `recommended?type=1\|2` 的**数字**口径刻意不一致（§2.5 要求语义字符串；那套是 TV 遗留、前端未消费） | **已销账（2026-10-27）** |
| GAP-11 | M3 端点缺 | 首页 / ChannelWall「热门」 | 热门榜 | 没有排行榜端点（`recommended` 是推荐流，不是排行） | 前端不做 | 灰态占位 → `/placeholder/popular` | 无 | 不做 |
| GAP-12 | M1 字段缺 | 播放页·图文详情 / 评论区 | 评论发布时间 | 原：`CommentVO` 没有 `createTime`（只有 `commentId` 可推先后）—— **已补（2026-10-31）** | ✅ 前后端均已接 | —（已可显示） | ✅ 三处同步：`CommentCacheDTO` 字段 + `commentCacheMap` 映射 + `convertToCommentVO` 手写拷贝；spec 已标 nullable 并重生成。展示口径见 [前端.md §六](../architecture/frontend/前端.md) | **已销账（2026-10-31）** |
| GAP-13 | M3 参数缺 | 播放页 / 评论排序 | 热度 / 时间排序 | `GET /contents/{id}/comments` 只有 `page/pageSize`，固定按 `comment_id` 升序 | 前端适配 | **不渲染排序条**；发布后跳到末页并高亮 | 加 `order` 参数 | 长期 |
| GAP-14 | M1 字段缺 | 空间页 / 头部 | banner 图 / 签名 / 生日 / 地区 | `ProfileVO` 只有 id、username、两个计数、`contentPage` | 前端适配（不做资料域） | **低饱和渐变 banner**；资料卡只放真计数 | 无 | 长期 |
| GAP-15 | M3 端点缺 | 空间页 / tabs | 动态 / 合集和系列 / 最近点赞 | 无对应端点（`/feed` 是「我关注的动态」，不是某人的） | 前端不做 | **不渲染这三个 tab**（不摆点不动的） | 无 | 不做 |
| GAP-16 | M1 字段缺 | 空间页 / 收藏条目卡 | 条目要带 `authorId` / `type` / `durationSeconds` / 发布时间 | `FavoriteItemVO` 只有 contentId/title/coverUrl/authorName/favoriteTime/invalid | 前端适配（方案 A） | 作者渲染成**纯文本**（不做死链）、不显示时长与日期、`invalid` 打「已失效」灰角标；**没有 `type` ⇒ 链接先落 `/video/{id}`，播放页取到详情是图文后重定向 `/opus/{id}`（2026-10-27）** | 可选：`FavoriteItemVO` 加 `type`（下一批契约；补上后删重定向、卡面补「图文」角标） | 长期 |
| GAP-17 | M1 字段缺 | 空间页 / 收藏夹卡 | 收藏夹封面（B 站是四宫格缩略图） | `PublicFavoriteFolderVO` 只有 id/name/itemCount | 前端适配 | 纯文字卡片（名字 + 条目数） | 无 | 长期 |
| GAP-18 | M4 语义异 | 播放页 / 播放进度 | 匿名观看也计播放量（B 站口径） | `/play`、`/progress`、`/flush` **全部 `@RequiresLogin`** | 前端适配 | 未登录**整段不接**进度上报（不打一串 401） | 若要做需匿名计数端点 | 长期 |
| GAP-19 | M3 端点缺 | 历史页 / 卡片 | 删除单条历史 / 清空历史（B 站历史页有） | **✅ 前后端均已交付（2026-10-31，`R-26`）**：`DELETE /api/v1/history`（`contentIds` ≤500 / `all=true`，204 幂等、不校验内容存在性、不动 `play_count`）+ 播放量口径改 per-day（`V10` 账本）；前端管理态多选 / 单卡垃圾桶 / 两个确认框 | ✅ 前后端均已接 | —（已可删） | ✅ 已落地：`V10` + `/play` 改账本判首播 + 删除端点 + 缓冲遗忘；历史页管理态（全选=**已加载**条目）/ 清空 / 删后清 `history:pending` / 末页回退。⚠️ 删除与播放会话并发时那条可能复活（窄窗口，B站 同行为）⇒ 见 [history-已知代价](../architecture/domain/history/history-已知代价.md) ③ | **已销账（2026-10-31）** |
| GAP-20 | M3 参数缺 | 历史页 / 工具栏 | B 站历史页的 tabs（综合 / 视频 / 直播 / 专栏）+「搜索标题/up 主」+ 更多筛选 | `GET /history` 只有 `page/pageSize`；`HistoryItemVO` **连 `type` 都没有**（前端筛选也做不了） | 后端补（P3） | **不渲染 tabs / 搜索框 / 更多筛选**（只按最后观看倒序） | `/history` 加 `type`（视频/图文）与 `keyword`；⚠️ 直播 / 专栏**无对应域，永远不做** | 待补（等后端） |
| GAP-21 | M3 端点缺 | 历史页 / 记录开关 | 「记录浏览历史」开关（B 站可暂停记录） | 原：无用户设置域（无 `user_settings` 表 / 端点）。**2026-10-31 已交付**：`users.history_recording`（用户级，`V9`）+ `GET /users/me` 回带 + `PUT /users/me/history-recording`（`R-25`） | ✅ 前后端均已接 | 标题行右侧开关；**关闭弹确认框**（遮罩/×/取消 = 取消），打开不确认；乐观生效、失败不提示（断网期间的切换不算数，刷新回原值）。关闭后不 `/play`、不报进度、**不计播放量**（D1），已有记录保留 | ✅ 已落地（`T73` 后端 / `T74` 前端，2026-10-31）。⚠️ **服务端不校验写端点 = 刻意偏离**（用户实测 B站 是服务端强制）；理由/推翻条件见 [history-已知代价](../architecture/domain/history/history-已知代价.md) | **已销账（2026-10-31）** |
| GAP-22 | M3 端点缺 | 创作中心 / 图文编辑 | 「把原图文取回、改完**原位覆盖**」（尤其增删多张图） | **✅ 前后端均已交付（2026-10-29）**：后端 `POST /api/v1/uploads/{type}`（单文件上传、不写库）+ `POST /api/v1/contents`（JSON 整份清单创建）+ `PUT /api/v1/contents/{id}` 的 `media` 子对象**整份覆盖**；前端 `/creator/manage`（稿件列表）+ `/creator/edit/{id}`（取回 → 换源 / 增删图 / 长按换序 → 整份覆盖），投稿页也已改走 upload + 清单 | ✅ 前端 2026-10-29 补齐 | 原内容预填 + 原位覆盖（**不**新建内容 —— 新建会换 id、丢评论/点赞）；未改的媒体 URL 不重传 | 无（旧 multipart 端点保留为兼容面，前端已不再调用） | **已销账（2026-10-29）** |
| GAP-23 | M3 参数缺 | 动态页 / tabs | 动态流按内容类型筛选（B 站：全部 / 视频投稿 / 追番追剧 / 专栏） | `GET /feed` 只有 `page/pageSize`；`ContentVO.type` 有，但端点不收 `type` | 后端补（P3） | **不渲染 tabs**（只按时间倒序；不拿"只筛当前页"冒充筛选） | `/feed?type=VIDEO\|POST`（与搜索的 GAP-10 同口径；追番/专栏无域，永远不做） | 待补（等后端） |
| GAP-24 | M3 端点缺 | 动态页 / 右栏 | 「bilibili 热搜」榜 + 社区中心运营位 | 没有热搜 / 运营端点（与 GAP-09 的 banner 同类） | 前端不做 | 右栏换成**我关注的人**（真接口 `/users/{me}/following`），顺带接上新交付的关注列表页 | 无（热搜属运营域，本仓不做） | 不做 |
| GAP-25 | M1 字段缺 | 空间·关注/粉丝 | 用户卡的**头像 / 签名 / 等级 / 分组** | `FollowUserVO` 只有 `userId / username / isFollowed / isSelf` | 前端适配（不做资料/头像域） | 默认 SVG 头像（按 `userId` 分配）+ 名字 + 关注按钮；**不渲染**签名 / 分组 / 搜索 / 排序 / 批量操作（B 站那句「这个人很神秘」是 B 站自己的默认签名，写上去就是编） | 无（头像域见 GAP-08） | 长期 |
| GAP-27 | M3 端点缺 | 图文详情 / 「赞与转发」tab | 列出点赞人 / 转发人 | 无「列出点赞人」端点；转发域无表 | 前端不做 | **不渲染该 tab**（只留「评论 N」；转发永远不做） | 若要做点赞人列表：新端点 + 分页 | 不做 |
| GAP-28 | M1 字段缺 | 列表 / 图文卡 | 图文无显式封面时用**首图**当卡面 | `ContentVO` 只有 `coverUrl`（媒体装配的 type=3），列表里 `imageUrls` 被屏蔽 ⇒ **有图但无封面**的图文只能显示文字卡面（实测 POST 59 / 测试夹具 260–265） | 前端适配（不逐卡回源详情） | **文字卡面**（真简介；空简介 → 中性「图文动态」） | 可选：`buildContentMedia` 对 POST 在 `coverUrl==null` 时回填首图；或列表加 `imageCount` | **本窗不做**（2026-10-29 用户拍板；保持文字卡面，不用"上传时复制首图为封面"的写侧降级） |
| GAP-29 | M4 语义异 | 首页 / 推荐流 | B 站 **web 首页不推图文**（移动端推） | `GET /contents/recommended` 无 `type` 过滤 ⇒ 含图文 | 前端适配（保留图文，与移动端一致） | 图文按 `contentHref` 进 `/opus`；纯文字 / 无封面走文字卡面 | 无（若要严格对齐 web：加 `type=VIDEO` 过滤——**产品上不做**） | 长期/有意为之 |
| GAP-30 | M4 语义异 | 投稿页 / 分区 | 投稿分区用的 `categoryId` 体系 | 后端只接受 **0~9 单个数字字符**（旧项目 10 项：其他/游戏/音乐/资讯/动画/娱乐/动物/体育/鬼畜/绘画）；首页 18 频道是另一套视觉导航，没有 id 映射 | 前端适配 | 投稿页用 `constants/categories.ts` 固定 10 项；**不把 18 频道写进 `categoryId`** | 无（分类端点已拍板不补，GAP-02） | 长期 |
| GAP-31 | M4 语义异 | 创作中心 / 编辑 | 简介上限按内容类型（视频 ≤1000 / 图文 ≤10000） | 旧缺陷：`POST /contents/posts` 允许 10000，`PUT /contents/{id}` 只允许 **5000** ⇒ 5001~10000 字的图文**发布后永远改不了** | **✅ 已修（2026-10-29）** | `ContentService.updateContent` 按内容类型取上限（VIDEO 1000 / POST 10000），编辑页可直接支持长图文 | 已交付；⚠️ 视频侧由旧 5000 收紧到 1000，与发布同源 | ✅ 已交付 |

> ★ **稍后再看没有"是否已在池中"的查询端点**（[favorite.md §六](../architecture/domain/favorite/favorite.md) 红线）：
> 本仓**不登记 GAP**、也不逐卡回源 —— 前端用 URL 作用域的内存记忆复刻 B 站实测行为
> （见 [CURRENT_FRONTEND.md](./CURRENT_FRONTEND.md)「客户端行为口径」）。
> 这是**刻意不做**，不是缺口。

> 实测补充（2026-10-25 起后端 curl，非文档推断）：
> ① `GET /api/v1/contents?page=1&pageSize=3` → **400 `PARAM_INVALID / 输入不能为空`** —— 该端点只服务关键词搜索，不是通用列表；
> ② `coverUrl` 是**相对路径**（`/upload/cover/xxx.png`），前端按同源拼即可，**别写死 localhost**；
> ③ 匿名请求 `interaction: null`（不是 `{}`、也不是四个 `false`）；
> ④ **spec 漏标 nullable**：匿名响应里 `coverUrl` / `durationSeconds` / `interaction` 会出现**显式 `null`**，
> 而 spec 只声明"可缺省" ⇒ `api.d.ts` 生成 `string | undefined`，与运行时不一致（GAP-07）。
> 前端已按 `null` 兜底，但**别拿生成类型当运行时真相**。
> ⑤ ★ `CommentVO.children` 运行时是**带 `isLiked` 的 `CommentVO`**（spec 声明成缩水的 `CommentCacheDTO`）
> ⇒ 楼中楼点赞态可以直接用；按 spec 写会以为"回复没有点赞态"。
> ⑥ ★ 三态是**字段级**的：`GET /users/{id}` 的 `contentPage.list[].interaction` 返回
> `{coin:null, isFavorited:null, isFollowed:null, isLiked:false}` —— 只有整块 `null` 才表示"匿名、本面不查"。
> ⑦ 限流会**真的咬到正常操作**：2026-10-25 实测"首屏 + 连续三个写操作就 429"（当时登录桶 `20 枚 / 2 枚每秒`，
> 缓存 miss `×3` 放大单次成本）；**2026-10-26 用户已把登录桶提到 `200 / 10`、匿名 `600 / 30`**
> （数值以 `application.yaml` 的 `video.rate-limit.user.*` 为准，本表不复制配置）。前端的 429 处理
> （吃 `Retry-After` 说清等多久 / 页面级「重试」/ **不**自动重试）不因数值变化而失效；
> 前端必须能说清"等多久"（吃 `Retry-After`），**这不是 bug**。

> 只**做一页列一页**，不预演全站；ID 不回收。
