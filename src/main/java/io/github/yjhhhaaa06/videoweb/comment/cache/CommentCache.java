package io.github.yjhhhaaa06.videoweb.comment.cache;

import io.github.yjhhhaaa06.videoweb.comment.dao.CommentDao;
import io.github.yjhhhaaa06.videoweb.comment.model.cache.CommentCacheDTO;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.JsonCodec;
import io.github.yjhhhaaa06.videoweb.common.config.CommentCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评论缓存（承接 TV {@code com.itheima.content.service.CommentCache}，744 行）。
 *
 * <h2>T10-A 的<b>两键组</b>（替换旧的"整树单 key"）</h2>
 * <pre>
 * content:comments:{id}:roots    LIST  主楼序列，元素 = 主楼 JSON（**children 不含**），comment_id 升序
 * content:comments:{id}:replies  HASH  field = 主楼 id，值 = 该主楼 children 的 JSON（**只存前 K=2 条**）
 * content:comments:{id}:count    String 真实主楼条数（首装时惰性 COUNT 一次）
 * empty:content:comments:{id}:roots      空标记（60s），承载"已确认无评论"
 * </pre>
 *
 * <h2>窗口装载（不一次性查全库）</h2>
 * 主楼 LIST 水位不足时按 DB keyset（{@code comment_id > lastId LIMIT}）追加；
 * 楼中楼 field 缺失时按主楼懒载（{@code parent_id IN (missingIds)} 一次取）。
 * 于是"{@code /comment/show} 第 3 页"的成本 ∝ 该页 + 已装载水位，而**与该内容的评论总量弱相关**
 * （治 U-20 的根因——TV 的 T10-A 就是为它做的）。
 *
 * <h2>失效重映射（DB 为源真理 + 失效自愈）</h2>
 * <ul>
 *   <li>增/删**主楼** → 失效 roots + count（读懒重建窗口）；</li>
 *   <li>增/删**回复**、评论点赞 → 定向 HDEL 该主楼的 replies field（懒载刷新）。</li>
 * </ul>
 * 调用点全部在**事务提交后**（{@code CommentCacheChangedListener} / {@code LikeChangedListener}），
 * 见决策表 G-3。
 *
 * <h2>★ 三态与两种失败（G-4）</h2>
 * <table>
 *   <caption>失败分类与处置</caption>
 *   <tr><th>失败</th><th>处置</th><th>理由</th></tr>
 *   <tr><td>Redis 失败（{@link CacheUnavailableException}）</td><td>降级：按调用形态直查 DB，
 *       **不写回**（TV 的 D4）</td><td>缓存只是加速器</td></tr>
 *   <tr><td>DB 失败（{@code DataAccessException}）</td><td><b>上抛</b>（→ 500）</td>
 *       <td>"无答案可答"——主楼列表就是本次请求的答案，降级成空会把"读不到"伪装成"没有"</td></tr>
 * </table>
 *
 * <h3>★ 一处**刻意的例外**（与上面的"DB 失败上抛"并列，别误当成疏忽）</h3>
 * <b>楼中楼（children 预览）的 DB 失败仍然降级为空数组</b>，理由是可表示的：
 * children 是**预览子集**，它的缺席可以如实表达（{@code children: []} + 顶层 {@code replyCount}
 * 仍说"N 条回复"），用户点开即走 {@code /comment/replies}（那里 DB 失败会照常 500）。
 * 而主楼列表若降级成空，响应会**声称"没有评论"**——那是另一回事，属于伪装。
 * TV 对两者都是降级；S5 只把"伪装"的那一半改成上抛。
 *
 * <h2>相对 TV 有意去掉的（G-6）</h2>
 * <b>单飞 SingleFlight</b>（同 key 并发 miss 各打一次 DB，正确性不变）、
 * <b>{@code CacheStats} 打点</b>。
 */
@Slf4j
@Component
public class CommentCache {

    /** 窗口装载单趟上限（每次 DB keyset 装载条数）。TV 原值 200。 */
    private static final int LOAD_BATCH_LIMIT = 200;

    /**
     * T10-B：每主楼缓存只存前 K 条楼中楼（命中路径的反序列化量与楼中楼总量解耦）。
     * 与 {@code CommentService.REPLY_PREVIEW_K} **同值**（TV 原注释要求两处同步）。
     */
    public static final int PREVIEW_REPLIES_PER_ROOT = 2;

    /** 空标记短 TTL（60s，与数据 key 的 TTL 不同；口径见 {@link CacheKeys#EMPTY_MARKER_TTL_SECONDS}）。 */
    private static final Duration EMPTY_MARKER_TTL =
            Duration.ofSeconds(CacheKeys.EMPTY_MARKER_TTL_SECONDS);

    private static final TypeReference<List<CommentCacheDTO>> COMMENT_LIST_TYPE = new TypeReference<>() {
    };

    private final CommentDao commentDao;
    private final CommentRedisOps ops;
    private final JsonCodec codec;
    private final CommentCacheProperties props;

    public CommentCache(CommentDao commentDao, CommentRedisOps ops,
                        JsonCodec codec, CommentCacheProperties props) {
        this.commentDao = commentDao;
        this.ops = ops;
        this.codec = codec;
        this.props = props;
    }

    // ========================================================================
    // 读：分页窗口（主入口）
    // ========================================================================

    /**
     * 分页窗口读：返回**该页主楼树**（children 随行，最多前 K 条）+ 真实主楼总数。
     *
     * <p>越界页返回空列表但 {@code rootTotal} 仍是真实主楼数——前端据此判末页（T8 契约）。
     */
    public PageWindow getRootPage(long contentId, int page, int pageSize) {
        // 兜底非法分页参数（Controller 已归一）：给空页而不是假装第 1 页（避免 subList 越界）；
        // total 仍取真实主楼数（对齐 T8 的 sliceRoots 语义）
        if (page < 1 || pageSize < 1) {
            return new PageWindow(new ArrayList<>(), loadRootTotal(contentId));
        }
        long from = (long) (page - 1) * pageSize;
        List<CommentCacheDTO> roots = prepareRoots(contentId, pageEnd(page, pageSize));
        if (roots == null) {
            return PageWindow.empty();          // hit-empty：已确认无评论
        }
        int total = loadRootTotal(contentId);
        return new PageWindow(attachReplies(contentId, sliceRoots(roots, from, pageSize)), total);
    }

    /**
     * 缺省**全量整树**（不传分页参数的兼容路径，T10-B）：DB 全量直取 + Java 侧上溯建树。
     *
     * <p>children 全量随行（缺省 = 全量语义，逐字节兼容旧数组响应）；
     * **不占两键组**——前端早已改传大 chunk，缺省调用方只剩兼容场景。
     *
     * @return {@code null} = 确认无评论（调用方返回空数组）
     */
    public List<CommentCacheDTO> getFullTree(long contentId) {
        List<CommentCacheDTO> rows = commentDao.getComments(contentId);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        return buildCommentTree(rows);
    }

    /** 展平评论树为全部评论 id（含 children），供批量查询点赞状态。 */
    public List<Long> collectCommentIds(List<CommentCacheDTO> tree) {
        List<Long> ids = new ArrayList<>();
        for (CommentCacheDTO node : tree) {
            collectIdsRecursive(node, ids);
        }
        return ids;
    }

    // ========================================================================
    // 写：失效（均应由监听器在**事务提交后**调用）
    // ========================================================================

    /**
     * 评论相关**整组失效**（增/删主楼、内容级联删除）：DEL roots + replies + count 及其空标记，
     * 读懒重建。
     */
    public void invalidateComments(long contentId) {
        invalidateQuietly(CacheKeys.contentCommentRoots(contentId),
                CacheKeys.contentCommentReplies(contentId),
                CacheKeys.contentCommentRootCount(contentId));
    }

    /**
     * 主楼序列失效（增/删主楼后）：DEL roots + count（下次读懒建窗口并重新 COUNT），
     * {@code replies} 保留——其它主楼的 children 没变。
     */
    public void invalidateRoots(long contentId) {
        invalidateQuietly(CacheKeys.contentCommentRoots(contentId),
                CacheKeys.contentCommentRootCount(contentId));
    }

    /**
     * 定向失效某主楼的 replies field（回复增/删、评论点赞后）：HDEL 该 field，下次读懒载刷新。
     *
     * @param rootId {@code null} = 无法定位主楼 ⇒ 整组失效兜底
     */
    public void invalidateReplyUnder(long contentId, Long rootId) {
        if (rootId == null) {
            invalidateComments(contentId);
            return;
        }
        String repliesKey = CacheKeys.contentCommentReplies(contentId);
        try {
            ops.hashDeleteField(repliesKey, String.valueOf(rootId));
        } catch (CacheUnavailableException e) {
            log.warn("评论 replies field 失效失败（缓存放任 TTL 自愈）: contentId={}", contentId, e);
        }
    }

    /**
     * 评论点赞/取消后：定位所属内容 + 所属主楼，定向 HDEL 该主楼的 replies field；
     * 定位失败 ⇒ 整组失效兜底。
     *
     * <p>这是 S3 的 {@code LikeChangedListener} 里那处 TODO 的落点
     * （L-6 的补回位置："S5 接入两个 Cache 时，在本监听器内一并补上这两次失效"）。
     */
    public void notifyCommentLikeChanged(long commentId) {
        CommentRef ref = locateCommentRef(commentId);
        if (ref == null) {
            return;     // 定位失败（评论已删）——缓存保持，靠 TTL 自愈
        }
        if (ref.rootId() == null) {
            invalidateComments(ref.contentId());   // 主楼上溯失败 ⇒ 整组失效兜底
            return;
        }
        invalidateReplyUnder(ref.contentId(), ref.rootId());
    }

    // ========================================================================
    // 内部：主楼窗口
    // ========================================================================

    /**
     * 主楼序列就绪（水位装载 + 全量读取）。
     *
     * @return 已装主楼列表（children 恒为 null）；{@code null} = 已确认无评论
     */
    private List<CommentCacheDTO> prepareRoots(long contentId, long needWatermark) {
        String rootsKey = CacheKeys.contentCommentRoots(contentId);
        try {
            if (ops.emptyMarkerExists(rootsKey)) {
                return null;                      // hit-empty
            }
            ensureRootsWindow(rootsKey, contentId, needWatermark);

            List<String> jsons = ops.readRootsAll(rootsKey, props.commentTtl());
            List<CommentCacheDTO> roots = new ArrayList<>(jsons.size());
            for (String json : jsons) {
                if (json != null && !json.isEmpty()) {
                    roots.add(codec.fromJson(json, CommentCacheDTO.class));
                }
            }
            // 空列表 = 已确认无评论（hit-empty / DB 无主楼），对齐旧的 null 语义
            return roots.isEmpty() ? null : roots;
        } catch (CacheUnavailableException e) {
            log.warn("评论主楼缓存读异常（降级走 DB）: contentId={}", contentId, e);
            return loadRootsFromDb(contentId, needWatermark);
        }
    }

    /**
     * 主楼 LIST 窗口装载：水位不足时按 keyset 轮次从 DB 追加。
     *
     * <p>首装（水位 0）时同时写 count key；DB 无主楼 → 写 roots 空标记（防穿透）。
     *
     * <p>★ <b>DB 装载失败一律向上抛</b>（{@link DataAccessException} 不在此捕获）：
     * 若在这里吞掉，调用方会继续读"还没装好的缓存"、得到空列表，于是**对外声称"没有评论"**
     * ——那正是 G-4 要禁的"把读不到伪装成没有"。
     * ⚠️ TV 在这里的行为不同：它 catch {@code DatabaseException} 后**继续往下读缓存**
     * （其注释写着"本次降级走 DB"，但代码并没有真的走 DB）——冷缓存下会返回"无评论"。
     * 本实现按 G-4 纠正（属有意改进，已记入决策表 G-4/G-6）。
     */
    private void ensureRootsWindow(String rootsKey, long contentId, long needWatermark) {
        if (needWatermark <= 0) {
            return;
        }
        while (true) {
            long watermark = ops.rootsLength(rootsKey);
            if (watermark >= needWatermark) {
                return;
            }
            long afterId = watermark > 0 ? lastRootId(rootsKey, watermark) : 0L;
            long batch = Math.min(LOAD_BATCH_LIMIT, needWatermark - watermark);

            List<CommentCacheDTO> mains =
                    commentDao.getMainCommentsAfter(contentId, afterId, (int) batch);   // DB 失败 ⇒ 上抛
            int total = watermark == 0 ? commentDao.countMainComments(contentId) : -1;
            if (mains.isEmpty()) {
                if (watermark == 0) {
                    markEmpty(rootsKey);          // 首装无主楼 ⇒ 空标记（防穿透）
                }
                return;                           // DB 尾部无更多
            }
            writeRootsAppend(rootsKey, contentId, watermark, mains, total);
        }
    }

    /** 追加装载结果到主楼 LIST（pipeline：清空标记[首装] + RPUSH + EXPIRE + count[首装]）。 */
    private void writeRootsAppend(String rootsKey, long contentId, long existedBefore,
                                  List<CommentCacheDTO> mains, int total) {
        List<String> jsons = new ArrayList<>(mains.size());
        for (CommentCacheDTO main : mains) {
            jsons.add(codec.toJson(main));
        }
        // 刻意**不吞**写失败：读路径会在下一轮/下次请求重试，吞掉只会让"没写进去"变成静默的半装载
        ops.appendRoots(rootsKey, CacheKeys.contentCommentRootCount(contentId),
                existedBefore, jsons, total, props.commentTtl());
    }

    /** 已装主楼列表**最后一条**的 comment_id（{@code LINDEX watermark-1}）。 */
    private long lastRootId(String rootsKey, long watermark) {
        String json = ops.rootsAt(rootsKey, watermark - 1);
        if (json == null || json.isEmpty()) {
            return 0L;
        }
        return codec.fromJson(json, CommentCacheDTO.class).getCommentId();
    }

    /**
     * 降级 DB 读取（Redis 不可用）：keyset 从 0 取到目标窗口。**不写回**（D4）。
     *
     * <p>{@code needWatermark == Long.MAX_VALUE} 代表"全量语义"（缺省路径的降级）——
     * 此时循环装完全部主楼，不能截断前 N 条。
     *
     * <p>⚠️ 与 TV 的差异：TV 在这里把 DB 失败**吞成 null**（对外表现为"没有评论"）。
     * 本实现按 G-4 让它**上抛**（500）——主楼列表就是答案，不能伪装成空。
     */
    private List<CommentCacheDTO> loadRootsFromDb(long contentId, long needWatermark) {
        List<CommentCacheDTO> all = new ArrayList<>();
        long afterId = 0L;
        long remaining = needWatermark;
        while (remaining > 0) {
            int batch = (int) Math.min(LOAD_BATCH_LIMIT, remaining);
            List<CommentCacheDTO> rows = commentDao.getMainCommentsAfter(contentId, afterId, batch);
            if (rows == null || rows.isEmpty()) {
                break;
            }
            all.addAll(rows);
            afterId = rows.get(rows.size() - 1).getCommentId();
            if (rows.size() < batch) {
                break;                            // 已到尾部
            }
            remaining -= rows.size();
        }
        return all;
    }

    /** 真实主楼总数：优先 count key；缺失 → DB 即时 COUNT（低频兜底）。 */
    private int loadRootTotal(long contentId) {
        String countKey = CacheKeys.contentCommentRootCount(contentId);
        try {
            String value = ops.getString(countKey);
            if (value != null) {
                return Integer.parseInt(value);
            }
        } catch (CacheUnavailableException e) {
            log.warn("评论主楼总数读取异常（DB 兜底）: contentId={}", contentId, e);
        } catch (NumberFormatException e) {
            log.warn("评论主楼总数缓存值非法（DB 兜底）: contentId={}", contentId);
        }
        // DB 失败上抛（同 loadRootsFromDb；TV 在这里吞成 0，会让"total=0 但列表非空"自相矛盾）
        return commentDao.countMainComments(contentId);
    }

    /** 主楼页切片（越界页空列表；信封另带真实 total）。 */
    private static List<CommentCacheDTO> sliceRoots(List<CommentCacheDTO> roots, long from, int pageSize) {
        if (from >= roots.size()) {
            return new ArrayList<>();
        }
        int to = (int) Math.min(from + pageSize, roots.size());
        return new ArrayList<>(roots.subList((int) from, to));
    }

    /** 页目标水位 = 该页末尾元素下标 + 1；用 long 防 {@code (page-1)*pageSize} 溢出。 */
    private static long pageEnd(int page, int pageSize) {
        if (page < 1 || pageSize < 1) {
            return 0;
        }
        return (long) (page - 1) * pageSize + pageSize;
    }

    // ========================================================================
    // 内部：楼中楼随行
    // ========================================================================

    /**
     * 为页内主楼装配 children（T10-A/T10-B）：HMGET 该页主楼 field；
     * field 缺失 → 懒载 DB 并按主楼分组一次取 → HSET 回填（无回复 → 空数组）。
     * Redis 异常 → 降级 DB 直取、不写回。
     */
    private List<CommentCacheDTO> attachReplies(long contentId, List<CommentCacheDTO> roots) {
        if (roots == null) {
            return null;
        }
        if (roots.isEmpty()) {
            return new ArrayList<>();
        }
        String repliesKey = CacheKeys.contentCommentReplies(contentId);
        List<Long> rootIds = new ArrayList<>(roots.size());
        for (CommentCacheDTO root : roots) {
            rootIds.add(root.getCommentId());
        }

        Map<Long, List<CommentCacheDTO>> childrenByRoot;
        try {
            List<String> values = ops.hashMultiGet(repliesKey, rootIds.stream().map(String::valueOf).toList());
            Map<Long, String> got = new HashMap<>();
            List<Long> missing = new ArrayList<>();
            for (int i = 0; i < rootIds.size(); i++) {
                String json = values.get(i);
                if (json == null || json.isEmpty()) {
                    missing.add(rootIds.get(i));
                } else {
                    got.put(rootIds.get(i), json);
                }
            }
            if (!missing.isEmpty()) {
                Map<Long, List<CommentCacheDTO>> loaded = lazilyLoadReplies(repliesKey, contentId, missing);
                for (Map.Entry<Long, List<CommentCacheDTO>> entry : loaded.entrySet()) {
                    got.put(entry.getKey(), codec.toJson(entry.getValue()));
                }
            }
            ops.expire(repliesKey, props.commentTtl());     // 命中续期

            childrenByRoot = new HashMap<>(rootIds.size());
            for (Long rootId : rootIds) {
                String json = got.get(rootId);
                childrenByRoot.put(rootId, json == null
                        ? new ArrayList<>()
                        : codec.fromJson(json, COMMENT_LIST_TYPE));
            }
        } catch (CacheUnavailableException e) {
            log.warn("评论楼中楼缓存读异常（降级走 DB）: contentId={}", contentId, e);
            childrenByRoot = loadRepliesFromDb(contentId, rootIds);
            if (childrenByRoot == null) {
                childrenByRoot = new HashMap<>();
            }
        }

        List<CommentCacheDTO> pageTree = new ArrayList<>(roots.size());
        for (CommentCacheDTO root : roots) {
            List<CommentCacheDTO> children = childrenByRoot.get(root.getCommentId());
            root.setChildren(children == null ? new ArrayList<>() : children);
            // T10-B replyCount 兜底：旧缓存主楼无 replyCount 字段 → 以已缓存 children 数自愈
            // （欠准但渐进正确，失效/重装后由 DB reply_count 纠正）
            if (root.getReplyCount() == 0 && !root.getChildren().isEmpty()) {
                root.setReplyCount(root.getChildren().size());
            }
            pageTree.add(root);
        }
        return pageTree;
    }

    /** 楼中楼懒载（field 缺失主楼）：按主楼批量 DB 取 → 截为前 K 条 → HSET 回填（空组存空数组）。 */
    private Map<Long, List<CommentCacheDTO>> lazilyLoadReplies(String repliesKey, long contentId,
                                                               List<Long> missing) {
        List<CommentCacheDTO> rows;
        try {
            rows = commentDao.getRepliesByRootIds(contentId, missing);
        } catch (DataAccessException e) {
            // ★ 刻意的例外：children 是**预览子集**，缺席可如实表达（children: [] + 顶层 replyCount 仍在），
            //   故这里降级而不是上抛（见类注释）。不写 field（保持"未装载"态，下次重试）。
            log.warn("评论楼中楼懒载失败（保持未装载态，下次重试）: contentId={}", contentId, e);
            return Map.of();
        }
        // 按主楼截断为前 K 条：**与本次组装同源**（保证缓存里存的与响应里给的一致）
        Map<Long, List<CommentCacheDTO>> preview = truncateToPreview(groupByParent(rows), missing);
        try {
            Map<String, String> fieldValues = new LinkedHashMap<>();
            for (Long rootId : missing) {
                fieldValues.put(String.valueOf(rootId), codec.toJson(preview.get(rootId)));
            }
            ops.hashPutAll(repliesKey, fieldValues, props.commentTtl());
        } catch (CacheUnavailableException e) {
            // 回填失败：不掩蔽——本次已拿到结果（调用方直接组装），下次读重试
            log.warn("评论楼中楼回填失败（本次已拿到结果，下次重试）: contentId={}", contentId, e);
        }
        return preview;
    }

    /** 按主楼截断为前 K 条（懒载返回与 HSET 内容一致，保证组装与缓存同源）。 */
    private static Map<Long, List<CommentCacheDTO>> truncateToPreview(
            Map<Long, List<CommentCacheDTO>> grouped, List<Long> rootIds) {
        Map<Long, List<CommentCacheDTO>> preview = new HashMap<>();
        for (Long rootId : rootIds) {
            List<CommentCacheDTO> children = grouped.getOrDefault(rootId, new ArrayList<>());
            preview.put(rootId, children.size() > PREVIEW_REPLIES_PER_ROOT
                    ? new ArrayList<>(children.subList(0, PREVIEW_REPLIES_PER_ROOT))
                    : children);
        }
        return preview;
    }

    /** 降级 DB：按主楼批量取楼中楼（不写回，D4）。返回按 parent_id 分组；DB 失败返回 null。 */
    private Map<Long, List<CommentCacheDTO>> loadRepliesFromDb(long contentId, List<Long> rootIds) {
        try {
            return groupByParent(commentDao.getRepliesByRootIds(contentId, rootIds));
        } catch (DataAccessException e) {
            log.warn("评论楼中楼降级查询失败（按无回复处理）: contentId={}", contentId, e);
            return null;
        }
    }

    /** 扁平行按 parent_id 分组成主楼 → children（组内升序由 SQL 的 ORDER BY 保证）。 */
    private static Map<Long, List<CommentCacheDTO>> groupByParent(List<CommentCacheDTO> rows) {
        Map<Long, List<CommentCacheDTO>> grouped = new HashMap<>();
        for (CommentCacheDTO row : rows) {
            Long parentId = row.getParentId() == null ? 0L : row.getParentId();
            grouped.computeIfAbsent(parentId, key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    // ========================================================================
    // 内部：建树 / 失效 / 定位
    // ========================================================================

    /** 楼中楼归一化建树（缺省全量路径用）：回复一律挂**主楼**（沿 parent 链上溯，防御存量多级链）。 */
    private static List<CommentCacheDTO> buildCommentTree(List<CommentCacheDTO> rows) {
        Map<Long, CommentCacheDTO> byId = new HashMap<>();
        List<CommentCacheDTO> roots = new ArrayList<>();
        for (CommentCacheDTO row : rows) {
            row.setChildren(new ArrayList<>());
            byId.put(row.getCommentId(), row);
        }
        for (CommentCacheDTO row : rows) {
            Long parentId = row.getParentId();
            if (parentId == null || parentId == 0) {
                roots.add(row);
                continue;
            }
            CommentCacheDTO parent = byId.get(parentId);
            if (parent == null) {
                continue;       // 父缺失（理论不可达，迁移前已归一）
            }
            while (parent.getParentId() != null && parent.getParentId() != 0) {
                CommentCacheDTO ancestor = byId.get(parent.getParentId());
                if (ancestor == null) {
                    break;
                }
                parent = ancestor;
            }
            parent.getChildren().add(row);
        }
        return roots;
    }

    /** 批量静默失效（数据 key + 空标记），best-effort **不抛出**。 */
    private void invalidateQuietly(String... dataKeys) {
        for (String dataKey : dataKeys) {
            try {
                ops.delete(dataKey, CacheKeys.empty(dataKey));
            } catch (CacheUnavailableException e) {
                log.warn("评论缓存失效失败（缓存放任 TTL 自愈）: key={}", dataKey, e);
            }
        }
    }

    /** 空标记（对齐 CacheAside.markEmpty 语义）：数据 key 已存在（并发回填）时跳过，防固化假空。 */
    private void markEmpty(String rootsKey) {
        try {
            ops.markEmptyIfAbsent(rootsKey, EMPTY_MARKER_TTL);
        } catch (CacheUnavailableException e) {
            log.warn("评论空标记写入失败: rootsKey={}", rootsKey, e);
        }
    }

    /** 定位评论所属内容与所属主楼 id（评论点赞失效路径）。定位失败返回 null（缓存保持，靠 TTL 自愈）。 */
    private CommentRef locateCommentRef(long commentId) {
        try {
            Long contentId = commentDao.getContentIdByCommentId(commentId);
            if (contentId == null) {
                return null;
            }
            return new CommentRef(contentId, commentDao.getRootIdByCommentId(commentId));
        } catch (DataAccessException e) {
            log.warn("评论所属内容/主楼查询失败（缓存保持，靠 TTL 自愈）: commentId={}", commentId, e);
            return null;
        }
    }

    private static void collectIdsRecursive(CommentCacheDTO node, List<Long> ids) {
        ids.add(node.getCommentId());
        if (node.getChildren() != null) {
            for (CommentCacheDTO child : node.getChildren()) {
                collectIdsRecursive(child, ids);
            }
        }
    }

    // ========================================================================
    // 结果载体
    // ========================================================================

    /** 分页窗口读结果：该页主楼树 + 真实主楼总数。 */
    public record PageWindow(List<CommentCacheDTO> roots, int rootTotal) {

        static PageWindow empty() {
            return new PageWindow(new ArrayList<>(), 0);
        }
    }

    /** 评论所属内容 + 所属主楼（{@code rootId == null} = 上溯失败）。 */
    private record CommentRef(long contentId, Long rootId) {
    }
}
