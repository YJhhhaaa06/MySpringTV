package io.github.yjhhhaaa06.videoweb.common.cache;

/**
 * 统一缓存 key 命名规范 —— **本仓唯一源**（承接 TV {@code cache.CacheKeys}，T1 定稿）。
 *
 * <h2>为什么本切片才抽它（S5，rule of three 落地）</h2>
 * S3（like）与 S4（follow）各自在 {@code *Cache} 内写了私有 key 工厂，理由写在决策表 L-7 / F-7：
 * 「第 2 个使用方，等 S5 出现第 3 个使用方再抽」。S5 同时引入 {@code ContentCache}（内容 key +
 * 索引 LIST）与 {@code CommentCache}（两键组）**两个**新使用方 ⇒ 本类落地。
 *
 * <h2>★ 键字符串是冻结契约，不得改动</h2>
 * 测试里**硬编码**了 key 形状（{@code AbstractLikeIntegrationTest.CONTENT_LIKE_COUNT_KEY} 等），
 * 目的是让"key 改名"这种静默的缓存失联无法悄悄发生。故本类只做**搬迁**，一个字符都不改。
 *
 * <h2>空标记（{@code empty:{dataKey}}）</h2>
 * 不碰业务容器，另起独立 String key 标记"已确认无数据"，短 TTL
 * （{@link #EMPTY_MARKER_TTL_SECONDS}）。它同时服务**防穿透**与**写路径判定**：
 * 集合类缓存（follow）的条件写要知道"这一侧是'已加载的空'而不是'没加载'"。
 */
public final class CacheKeys {

    /** 空标记的固定值（仅需 EXISTS 判断，值语义不重要）。TV 原样。 */
    public static final String EMPTY_MARKER_VALUE = "1";

    /** 部分装载标记的固定值（仅需 EXISTS 判断）。TV 原样。 */
    public static final String PARTIAL_MARKER_VALUE = "1";

    /** 空标记短 TTL（秒）：TV NEEDS 4.4 约定约 30s~5min，取 60s。 */
    public static final long EMPTY_MARKER_TTL_SECONDS = 60;

    /** 内容索引 key 前缀：{@code content:index:}（生成与 SCAN 匹配同源，防漂移）。 */
    public static final String CONTENT_INDEX_PREFIX = "content:index:";

    private CacheKeys() {
    }

    // ==================== 内容（ContentCache） ====================

    /** 内容详情：{@code content:{id}}（JSON，Cache-Aside 数据 key）。 */
    public static String content(long contentId) {
        return "content:" + contentId;
    }

    /**
     * 评论缓存 key 公共前缀：{@code content:comments:{id}}。
     *
     * <p>TV 的 {@code contentComments} 是**旧整树单 key**（T10-A 起停用）。新实现里它只作为
     * 两键组的公共前缀存在，**不再单独作为数据 key 使用**——保留方法只为让三个派生 key
     * 的字符串拼装来源唯一（防"前缀改了但派生的没改"）。
     */
    public static String contentComments(long contentId) {
        return "content:comments:" + contentId;
    }

    /** 评论主楼序列（T10-A 两键组①）：{@code content:comments:{id}:roots}（LIST，窗口读 + 尾追加）。 */
    public static String contentCommentRoots(long contentId) {
        return contentComments(contentId) + ":roots";
    }

    /** 评论楼中楼（T10-A 两键组②）：{@code content:comments:{id}:replies}（HASH，field=主楼 id）。 */
    public static String contentCommentReplies(long contentId) {
        return contentComments(contentId) + ":replies";
    }

    /** 评论主楼总数（T10-A）：{@code content:comments:{id}:count}（String int，首装时惰性 COUNT 一次）。 */
    public static String contentCommentRootCount(long contentId) {
        return contentComments(contentId) + ":count";
    }

    /**
     * 内容类型分区索引：{@code content:index:{type}:{categoryId}}（LIST）。
     *
     * <p>{@code -1} 表示通配维度（推荐接口 type/category 为空时的归一）。
     * 一条内容同时进 **4 个**索引 key（本 type/cid + 两个通配 + 全通配），见
     * {@code ContentCache#indexKeysOf}。
     */
    public static String contentIndex(int type, int categoryId) {
        return CONTENT_INDEX_PREFIX + type + ":" + categoryId;
    }

    // ==================== 空标记 ====================

    /**
     * 空标记：{@code empty:{dataKey}}（String），与数据 key 一一对应，短 TTL。
     *
     * @param dataKey 数据 key（如 {@link #content(long)}、{@link #userFollowing(long)}）
     */
    public static String empty(String dataKey) {
        return "empty:" + dataKey;
    }

    /**
     * 部分装载标记：{@code partial:{dataKey}}（String），与数据 key 一一对应，TTL **与数据 key 同步**。
     *
     * <h2>它断言什么（T11-C 的"集合不变量")</h2>
     * 没有本标记 ⇒ 数据 key（若存在）**就是全集**（T7 口径，成员集 = DB 全部）；
     * 有本标记 ⇒ 数据 key 只是 DB 按序的**前缀** {@code [0, W)}（{@code W = ZCARD}）。
     * 于是"分页读一个百万粉丝的博主"才不会在冷 key 上退化成"任何一页都装载百万行"。
     *
     * <h2>★ 本仓的来去（第三批 T4 / 账 B7）</h2>
     * S4 曾按 F-7 回到 T7 口径（"数据 key 存在 ⇒ 完整"）并**整条去掉**本标记与配套的四处特判；
     * T4 按工单把 T11-C 口径**搬回来**（改动理由与代价见《决策留痕表》I 类）。
     * <p>⚠️ 与它配套的四处必须一起存在，缺一处就是**静默错答案**（不是性能退化）：
     * ① 判定（{@code isFollowing}/{@code batchIsFollowing}）在部分态下**未命中必须回落 DB**
     * （前缀里查不到 ≠ 不是成员）；
     * ② 全量读（{@code getFollowingIds}）遇部分态**必须先补齐**（调用方依赖"返回全部"）；
     * ③ 写路径任一侧部分态 ⇒ **整体失效**（取关会在前缀里留"洞"、关注会插入非前缀成员，
     * 两者都破坏 {@code ZRANGE offset} 语义）；
     * ④ 部分态 / 降级态的 {@code total} 走**域级计数口径**（ZCARD 只是已知前缀大小，会低估）。
     *
     * <p>⚠️ 目前**只有 follow 域**用它：feed 的收件箱键写者只有读路径（fanout/重建只 DEL 不写）
     * ⇒ 永不产生前缀态，保持"两件套"（《决策留痕表》C-9）。
     *
     * @param dataKey 数据 key（如 {@link #userFollowing(long)}）
     */
    public static String partial(String dataKey) {
        return "partial:" + dataKey;
    }

    // ==================== 点赞（LikeCache） ====================

    /** 内容点赞计数：{@code content:likeCount:{id}}（String int）。 */
    public static String contentLikeCount(long contentId) {
        return "content:likeCount:" + contentId;
    }

    /** 评论点赞计数：{@code comment:likeCount:{id}}（String int）。 */
    public static String commentLikeCount(long commentId) {
        return "comment:likeCount:" + commentId;
    }

    /**
     * 我点赞过的内容：{@code user:likeSet:{userId}}（Set&lt;contentId&gt;）。
     *
     * <p>成员 key 取**用户维度**（TV 第四期 T4 的装载反转）：装载量 = 该用户点赞的内容数，
     * 与内容热度解耦。
     */
    public static String userLikeSet(long userId) {
        return "user:likeSet:" + userId;
    }

    /** 我点赞过的评论：{@code user:commentLikeSet:{userId}}（Set&lt;commentId&gt;，同 T4 反转）。 */
    public static String userCommentLikeSet(long userId) {
        return "user:commentLikeSet:" + userId;
    }

    // ==================== 关注（FollowCache） ====================

    /**
     * 我关注了谁：{@code user:following:{userId}}（**ZSet**，score = followedUserId）。
     *
     * <p>为什么是 ZSet 而不是 Set：分页要"按 id 升序取第 N 页"，ZSet 的 {@code ZRANGE by rank}
     * 天然给出该页成员且成本与列表总量弱相关（TV T7 A1 的性质）。
     */
    public static String userFollowing(long userId) {
        return "user:following:" + userId;
    }

    /** 谁关注了我：{@code user:follower:{userId}}（ZSet，score = userId）。 */
    public static String userFollower(long userId) {
        return "user:follower:" + userId;
    }

    /**
     * 我的关注数：{@code user:followCount:{userId}}（String int）。
     *
     * <p>★ **S5 补回**（决策表 G-8）：S4 曾在 F-7 里去掉它与 {@link #userFollowerCount(long)}，
     * 理由是该切片只剩"降级路径取 total"一个用途；F-7 同时写明补回位置 = S5 迁
     * {@code ProfileService} 时。S5 兑现：{@code /profile} 直接需要这两个计数。
     */
    public static String userFollowCount(long userId) {
        return "user:followCount:" + userId;
    }

    /** 我的粉丝数：{@code user:followerCount:{userId}}（String int，同 G-8 补回）。 */
    public static String userFollowerCount(long userId) {
        return "user:followerCount:" + userId;
    }

    // ==================== feed（S9） ====================

    /**
     * 收件箱窗口：{@code feed:inbox:{userId}}（**ZSet**，score = contentId ⇒ ZRANGE 升序 = 内容倒序的反序）。
     *
     * <p><b>写者只有读路径</b>：fanout / 重建**只 DEL 不写**（单写 DB 真相 {@code feed_inbox} +
     * 写后失效 + 读 miss 回源回填）。故本键**不产生"前缀态"**——"全量读即等于完整集合"成立。
     *
     * <p><b>失效集 = 两件套</b>（数据 key + {@code empty:}）：TV 的 {@code feedInboxCacheKeys} 是
     * **三件套**（多一个 {@code partial:} 前缀窗口标记）。{@code partial:} 只在"窗口装载"型读路径
     * 才产生（follow 域，见 {@link #partial(String)}），而本键的写者**只有读路径**（fanout/重建只 DEL
     * 不写、读路径也不做前缀装载）⇒ {@code partial:} 永不产生，留它反而制造"残留 partial 把完整集
     * 误判成前缀"的风险，故失效集降为两件套（《决策留痕表》C-9）。
     */
    public static String feedInbox(long userId) {
        return "feed:inbox:" + userId;
    }

    /**
     * 大V发件箱窗口：{@code feed:outbox:{authorId}}（ZSet，score = contentId，存该作者最近 N 条）。
     *
     * <p>真相源始终是 {@code content} 表；本键是**可降级读缓存**（miss → 回源 → 回填；
     * Redis 异常 → DB 直查、不写回）。失效集 = 两件套（数据 key + {@code empty:}，TV 原样）。
     */
    public static String feedOutbox(long authorId) {
        return "feed:outbox:" + authorId;
    }

    /**
     * 收件箱重建去重锁：{@code feed:rebuild:lock:{userId}}（String token，SET NX EX）。
     *
     * <p>锁只是**去重优化**，不承担正确性：TTL 到点后若真有并发重建交叉执行，两次都是同一份
     * {@code content} 真相的窗口快照（{@code INSERT IGNORE} 幂等）⇒ 并集、只多不丢。
     * 释放用 Lua CAS（值等于自己的 token 才删，避免误删他人已获得的锁）。
     */
    public static String feedRebuildLock(long userId) {
        return "feed:rebuild:lock:" + userId;
    }
}
