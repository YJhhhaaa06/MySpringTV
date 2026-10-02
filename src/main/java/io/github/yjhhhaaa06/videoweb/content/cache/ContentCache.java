package io.github.yjhhhaaa06.videoweb.content.cache;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheAside;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheKeys;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.config.ContentCacheProperties;
import io.github.yjhhhaaa06.videoweb.common.config.MediaProperties;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentMediaDao;
import io.github.yjhhhaaa06.videoweb.content.model.cache.ContentCacheDTO;
import io.github.yjhhhaaa06.videoweb.content.model.entity.ContentMedia;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentDetailVO;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 内容缓存（承接 TV {@code com.itheima.content.service.ContentCache}，739 行）。
 *
 * <h2>三态 + 索引，两类职责（TV 原样）</h2>
 * <ol>
 *   <li><b>内容详情</b>：{@code content:{id}} 走 {@link CacheAside} 的三态读
 *       （hit-data / hit-empty 空标记 60s / miss 回源回填）；</li>
 *   <li><b>类型分区索引</b>：{@code content:index:{type}:{category}}（Redis LIST），
 *       服务 {@code /start} 首页推荐；缺失时**懒重建**（见 {@link #ensureIndex}）。</li>
 * </ol>
 *
 * <h2>★ 它为什么不是"纯缓存层"</h2>
 * 《迁移参照系》§一 的警告在本类身上成立：它**承载业务语义**——
 * ① 媒体行的装配（{@code buildContentMedia}：按 type/sort 拼出 coverUrl / videoUrl / imageUrls，
 * 且**视频行缺失 = 内容不可用**）；
 * ② {@code /start} 的参数校验与随机抽样（{@code getRecommendByFilter}）；
 * ③ "媒体损坏 ⇒ 该内容视为不存在"这一**可观察行为**（详情 404、列表跳过）。
 * 故迁移时不能"只搬缓存不搬语义"。
 *
 * <h2>失败处置（G-4，逐字沿袭 TV）</h2>
 * <table>
 *   <caption>失败分类</caption>
 *   <tr><th>失败</th><th>处置</th></tr>
 *   <tr><td>Redis 失败（{@link CacheUnavailableException}）</td><td>内容读 → 降级直查 DB；
 *       索引读 → 降级为空推荐（TV 的 U-11 语义）</td></tr>
 *   <tr><td>DB 失败（{@code DataAccessException}）</td><td>内容读 → **上抛**（500）；
 *       索引懒重建的装载 → 记日志**跳过重建、保留旧索引**（见 {@link #ensureIndex} 的说明）</td></tr>
 * </table>
 *
 * <h2>相对 TV 有意去掉的（逐条记在决策表 G-6）</h2>
 * <b>不搬 {@code init()}</b>（启动全量重建）：其产物（内容 key、索引）**两条都有自愈路径**
 * （miss 回填 / 懒重建），只是预热；代价是启动期一次无上限全表查询。
 * <b>去掉索引重建的进程内冷却退避</b>（TV 10 秒）：当前无规模压力，收益为 0；
 * 补回位置见 G-6（{@code ensureIndex} 内加 {@code lastFailedAtMillis} + 窗口判定）。
 */
@Slf4j
@Component
public class ContentCache {

    /** 媒体类型代码（TV {@code UploadType} / {@code content_media.type}）。 */
    private static final int MEDIA_TYPE_VIDEO = 1;
    private static final int MEDIA_TYPE_IMAGE = 2;
    private static final int MEDIA_TYPE_COVER = 3;

    /** 内容类型（{@code content.type}）。 */
    private static final int CONTENT_TYPE_VIDEO = 1;
    private static final int CONTENT_TYPE_IMAGE = 2;

    private final ContentDao contentDao;
    private final ContentMediaDao contentMediaDao;
    private final CacheAside cacheAside;
    private final ContentRedisOps indexOps;
    private final ContentCacheProperties props;
    private final MediaProperties mediaProps;

    public ContentCache(ContentDao contentDao,
                        ContentMediaDao contentMediaDao,
                        CacheAside cacheAside,
                        ContentRedisOps indexOps,
                        ContentCacheProperties props,
                        MediaProperties mediaProps) {
        this.contentDao = contentDao;
        this.contentMediaDao = contentMediaDao;
        this.cacheAside = cacheAside;
        this.indexOps = indexOps;
        this.props = props;
        this.mediaProps = mediaProps;
    }

    // ========================================================================
    // 读：内容详情
    // ========================================================================

    /**
     * 三态读内容详情。
     *
     * @return 内容 DTO；**DB 无此内容 / 媒体损坏 / 未知类型 → {@code null}**
     *         （= 确认无数据，允许写空标记 → 详情端点 404）
     */
    public ContentCacheDTO getContent(long contentId) {
        return cacheAside.get(CacheKeys.content(contentId), ContentCacheDTO.class,
                () -> loadContentFromDb(contentId), props.contentTtl());
    }

    /**
     * 批量读内容（一趟 pipeline 拉多条三态，语义与单 key 完全一致；miss 走**批量装载**）。
     *
     * <p>供 {@code /start}、{@code /search}、{@code /profile} 复用——替代逐条 {@link #getContent} 的
     * 2×N 往返（TV 第五期 T2 的装载合并）。
     *
     * <p>返回 {@code contentId → DTO} 全量 Map（**null 值合法** = hit-empty / DB 无数据），
     * 调用方按键按原序收集并跳过 null。
     */
    public Map<Long, ContentCacheDTO> getContentsBatch(List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return Map.of();
        }
        List<String> keys = new ArrayList<>(contentIds.size());
        for (Long id : contentIds) {
            keys.add(CacheKeys.content(id));
        }
        Map<String, ContentCacheDTO> byKey = cacheAside.getBatch(keys, ContentCacheDTO.class,
                key -> loadContentFromDb(parseContentId(key)), this::loadContentsFromDb, props.contentTtl());
        Map<Long, ContentCacheDTO> byId = new HashMap<>(byKey.size());
        for (Map.Entry<String, ContentCacheDTO> entry : byKey.entrySet()) {
            byId.put(parseContentId(entry.getKey()), entry.getValue());
        }
        return byId;
    }

    // ========================================================================
    // 读：首页推荐（索引）
    // ========================================================================

    /**
     * 首页推荐（类型 / 分区过滤）。索引为 Redis LIST：读全量 id → 去重 → shuffle → limit。
     *
     * <p>{@code type}/{@code category} 的参数校验与旧 {@code buildQueryKey} 完全一致
     * （{@link ParamException} 同文案、同位置）——校验**留在缓存层内是 TV 的原样位置**，
     * 不"顺手"上移到 Controller（上移会改变"参数错但缓存未生效"这一可观察行为）。
     *
     * <p>惰性探测（TV T5/cache-05）：按 shuffle 序逐条读，凑满 limit 即止 ——
     * 探测量与候选总量解耦（原先是"批量探测全部候选"）。均匀性论证：
     * shuffle 仍在**全量去重 id 列表**上一次性执行，返回集 = "shuffle 序前 limit 个非 null"，
     * 与"批量读全量后按同一 shuffle 序收集"逐位一致；惰性只改"探测到第几个停止"。
     * null（hit-empty / DB 无数据）照旧消耗探测位——最坏退化到全探测。
     */
    public List<ContentVO> getRecommendByFilter(Integer type, Integer categoryId, int limit) {
        String indexKey = buildQueryKey(type, categoryId);
        ensureIndex(indexKey);

        List<Long> idList = readIndex(indexKey);
        if (idList.isEmpty()) {
            return List.of();
        }
        List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(idList));
        Collections.shuffle(distinctIds);

        List<ContentVO> result = new ArrayList<>();
        for (Long contentId : distinctIds) {
            ContentCacheDTO dto = getContent(contentId);
            if (dto == null) {
                continue;
            }
            result.add(toContentVO(dto));
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    // ========================================================================
    // 写（均应由 {@code ContentCacheChangedListener} 在**事务提交后**调用）
    // ========================================================================

    /**
     * 失效内容 key，让读路径自愈回填 DB 最新值。
     *
     * <p>TV 把它写成三个同名别名（{@code notifyLikeCountChanged} / {@code notifyCommentCountChanged} /
     * {@code updateCommentEnabled}）——三者做的事**逐字相同**（都是 DEL 内容 key + 空标记）。
     * S5 合并为一个方法，并保留下方三个旧名别名的**唯一理由**是让决策表 L-6/CM-3/G-3 里
     * "补回位置"的记载能被逐条对照（读代码时能 grep 到）。
     */
    public void invalidateContent(long contentId) {
        cacheAside.invalidate(CacheKeys.content(contentId));
    }

    /** TV 旧名别名：点赞数变化后失效内容 key。 */
    public void notifyLikeCountChanged(long contentId) {
        invalidateContent(contentId);
    }

    /** TV 旧名别名：评论增删后失效内容 key。 */
    public void notifyCommentCountChanged(long contentId) {
        invalidateContent(contentId);
    }

    /** TV 旧名别名：作者开关评论区后失效内容 key。 */
    public void updateCommentEnabled(long contentId) {
        invalidateContent(contentId);
    }

    /**
     * 重载内容 key 并顺带刷新索引（文案 / 媒体变了）。
     *
     * <p>DB 已删 ⇒ 走移除语义。装载失败（{@link DataAccessException}）⇒ **保留旧缓存让读自愈**，
     * 不做删除语义（TV 的三期 T3 结论：失败 ≠ 确无数据）。
     */
    public void refreshContent(long contentId) {
        ContentCacheDTO dto;
        try {
            dto = loadContentFromDb(contentId);
        } catch (DataAccessException e) {
            log.warn("刷新内容缓存装载失败（保留旧缓存，读自愈）: contentId={}", contentId, e);
            return;
        }
        if (dto == null) {
            removeContent(contentId);
            return;
        }
        cacheAside.writeOrInvalidate(CacheKeys.content(contentId), dto, props.contentTtl());
        addToIndex(dto);
    }

    /** 删除 / 下架后移除：失效内容 key（含空标记）+ 从**全部**索引 key 剔除该 id。 */
    public void removeContent(long contentId) {
        cacheAside.invalidate(CacheKeys.content(contentId));
        try {
            indexOps.removeIdFromAllIndexes(contentId);
        } catch (CacheUnavailableException e) {
            log.warn("内容索引移除失败（索引退化为「多一条脏 id」，读时被跳过）: contentId={}", contentId, e);
        }
    }

    /**
     * 用户改名后：失效该用户作为作者的**全部**内容 key，读自愈重新 JOIN users 回填新 authorName。
     *
     * <p>索引 {@code content:index:*} 只存 id 不含 authorName ⇒ 无需失效（TV 原注释）。
     *
     * <p>查询失败只记日志跳过（保留缓存 TTL 自愈）**不抛**——这是提交后的缓存维护动作，
     * 不能反过来让一次成功的改名变失败。TV 原样。
     */
    public void invalidateAuthorContentKeys(long userId) {
        List<Long> contentIds;
        try {
            contentIds = contentDao.findContentIdsByUser(userId);
        } catch (DataAccessException e) {
            log.warn("改名级联失效：查询内容 id 失败（保留缓存，TTL 自愈）: userId={}", userId, e);
            return;
        }
        if (contentIds == null || contentIds.isEmpty()) {
            return;
        }
        String[] keys = new String[contentIds.size()];
        for (int i = 0; i < contentIds.size(); i++) {
            keys[i] = CacheKeys.content(contentIds.get(i));
        }
        cacheAside.invalidate(keys);
    }

    // ========================================================================
    // VO 装配
    // ========================================================================

    public ContentVO toContentVO(ContentCacheDTO dto) {
        ContentVO vo = new ContentVO();
        copyCommon(vo, dto);
        return vo;
    }

    public ContentDetailVO toDetailVO(ContentCacheDTO dto) {
        ContentDetailVO vo = new ContentDetailVO();
        copyCommon(vo, dto);
        vo.setVideoUrl(dto.getVideoUrl());
        vo.setImageUrls(dto.getImageUrls());
        return vo;
    }

    /**
     * 字段拷贝（TV {@code copyToContentVO} / {@code copyToDetailVO} 的公共部分）。
     *
     * <p>手写而非 BeanUtils：**字段集是 JSON 契约**，显式列出来才能在评审里看见"哪个字段没被拷"。
     * TV 就是手写的。
     */
    private static void copyCommon(ContentCacheDTO target, ContentCacheDTO dto) {
        target.setId(dto.getId());
        target.setAuthorId(dto.getAuthorId());
        target.setType(dto.getType());
        target.setTitle(dto.getTitle());
        target.setDescription(dto.getDescription());
        target.setCategoryId(dto.getCategoryId());
        target.setCommentCount(dto.getCommentCount());
        target.setLikeCount(dto.getLikeCount());
        target.setCommentEnabled(dto.isCommentEnabled());
        target.setAuthorName(dto.getAuthorName());
        target.setCoverUrl(dto.getCoverUrl());
        target.setCreateTime(dto.getCreateTime());
    }

    // ========================================================================
    // 内部：DB 装载（loader 契约：null = 确认无数据；抛异常 = 加载失败）
    // ========================================================================

    /**
     * 单 key 装载。
     *
     * @return {@code null} = 确认无数据（无行 / 媒体损坏 / 未知类型）⇒ 允许写空标记
     * @throws DataAccessException DB 失败 ⇒ 上抛（不写空标记、不 DEL 数据 key）
     */
    private ContentCacheDTO loadContentFromDb(long contentId) {
        ContentCacheDTO dto = contentDao.findContent(contentId);
        if (dto == null) {
            return null;
        }
        Map<Integer, List<ContentMedia>> byType =
                groupMediaByType(contentMediaDao.findMediaByContentId(contentId));
        return buildContentMedia(dto, byType) ? dto : null;
    }

    /**
     * 批量装载（TV 第五期 T2）：一趟两次查询——批量内容 + 批量媒体，媒体按 contentId 分组后逐条构建。
     *
     * <p>契约（<b>必须为每个请求 key 给出条目</b>）：无行 / 已删除 / 媒体损坏 / 未知类型 ⇒
     * 该 key 的值给 {@code null}；整批 SQL 失败 ⇒ 抛 {@link DataAccessException}
     * （由 {@code CacheAside} 按"加载失败"处理：不写空标记、不写回）。
     */
    private Map<String, ContentCacheDTO> loadContentsFromDb(List<String> dataKeys) {
        List<Long> contentIds = new ArrayList<>(dataKeys.size());
        for (String key : dataKeys) {
            contentIds.add(parseContentId(key));
        }
        List<ContentCacheDTO> rows = contentDao.findContentsByIds(contentIds);
        Map<Long, ContentCacheDTO> byId = new HashMap<>(rows.size());
        for (ContentCacheDTO dto : rows) {
            byId.put(dto.getId(), dto);
        }
        Map<Long, Map<Integer, List<ContentMedia>>> mediaByContent =
                groupMediaByContent(contentMediaDao.findMediaByContentIds(contentIds));

        Map<String, ContentCacheDTO> byKey = new HashMap<>(contentIds.size());
        for (Long contentId : contentIds) {
            String key = CacheKeys.content(contentId);
            ContentCacheDTO dto = byId.get(contentId);
            if (dto == null) {
                byKey.put(key, null);          // 确认无数据（无行 / 已软删）
                continue;
            }
            boolean buildable = buildContentMedia(dto,
                    mediaByContent.getOrDefault(contentId, Map.of()));
            byKey.put(key, buildable ? dto : null);
        }
        return byKey;
    }

    /**
     * 媒体装配：把 {@code content_media} 行按 type 拼成对外的三种 URL 字段。
     *
     * <p>规则（TV {@code buildContentMedia} 逐条保留）：
     * <ul>
     *   <li>type=1（视频）：**必须有**视频行，否则"资源已丢失" ⇒ 内容不可用（返回 false）；</li>
     *   <li>type=2（图文）：图片行**可以没有**（TV 不报错，imageUrls 为 null）；</li>
     *   <li>type=3（封面）：可有可无（图文封面、视频封面）；</li>
     *   <li>其它 type：未知内容类型 ⇒ 不可用（返回 false）。</li>
     * </ul>
     *
     * @return {@code true} = 可构建；{@code false} = 该内容按"确认无数据"处理
     */
    private boolean buildContentMedia(ContentCacheDTO dto, Map<Integer, List<ContentMedia>> byType) {
        List<ContentMedia> covers = byType.get(MEDIA_TYPE_COVER);
        if (covers != null && !covers.isEmpty()) {
            dto.setCoverUrl(jointUrl(covers.get(0).getUrl()));
        }
        switch (dto.getType()) {
            case CONTENT_TYPE_VIDEO -> {
                List<ContentMedia> videos = byType.get(MEDIA_TYPE_VIDEO);
                if (videos == null || videos.isEmpty()) {
                    log.warn("内容媒体损坏（视频行缺失，按不可用处理）: contentId={}", dto.getId());
                    return false;
                }
                dto.setVideoUrl(jointUrl(videos.get(0).getUrl()));
            }
            case CONTENT_TYPE_IMAGE -> {
                List<ContentMedia> images = byType.get(MEDIA_TYPE_IMAGE);
                if (images != null && !images.isEmpty()) {
                    List<String> urls = new ArrayList<>(images.size());
                    for (ContentMedia media : images) {
                        urls.add(jointUrl(media.getUrl()));
                    }
                    dto.setImageUrls(urls);
                }
            }
            default -> {
                log.warn("未知内容类型（按不可用处理）: contentId={}, type={}", dto.getId(), dto.getType());
                return false;
            }
        }
        return true;
    }

    /**
     * 媒体 URL 前缀拼接。替代 TV 的 {@code RequestContext.getContextPath()}
     * （见 {@link MediaProperties} 的说明：context path 恒空，改为配置项）。
     */
    private String jointUrl(String url) {
        String base = mediaProps.baseUrl();
        return base == null ? url : base + url;
    }

    /** 扁平行按 {@code type} 分组（行已由 SQL 按 {@code type,sort} 排序 ⇒ 组内保序）。 */
    private static Map<Integer, List<ContentMedia>> groupMediaByType(List<ContentMedia> rows) {
        Map<Integer, List<ContentMedia>> byType = new HashMap<>();
        for (ContentMedia media : rows) {
            byType.computeIfAbsent(media.getType(), key -> new ArrayList<>()).add(media);
        }
        return byType;
    }

    /** 扁平行按 contentId → type 两级分组（批量装载用）。 */
    private static Map<Long, Map<Integer, List<ContentMedia>>> groupMediaByContent(List<ContentMedia> rows) {
        Map<Long, Map<Integer, List<ContentMedia>>> byContent = new HashMap<>();
        for (ContentMedia media : rows) {
            byContent.computeIfAbsent(media.getContentId(), key -> new HashMap<>())
                    .computeIfAbsent(media.getType(), key -> new ArrayList<>())
                    .add(media);
        }
        return byContent;
    }

    // ========================================================================
    // 内部：索引
    // ========================================================================

    /** 参数校验 + 归一为索引 key（TV {@code buildQueryKey} 逐字保留：同文案、同判定顺序）。 */
    private String buildQueryKey(Integer type, Integer categoryId) {
        if (type != null && type != 0 && (type < 1 || type > 2)) {
            throw new ParamException("不支持的内容类型: " + type);
        }
        if (categoryId != null && (categoryId < 0 || categoryId > 9)) {
            throw new ParamException("不支持的分区: " + categoryId);
        }
        int t = (type != null && type != 0) ? type : -1;
        int c = (categoryId != null) ? categoryId : -1;
        return CacheKeys.contentIndex(t, c);
    }

    /**
     * 索引懒重建：目标索引 key 不存在时按需从 DB 重建。
     *
     * <p><b>装载失败 ≠ 确无数据</b>（TV R-20 裁决 A / log3-14）：DB 装载失败时**跳过**重建
     * （**不清** {@code content:index:*}，既有索引原样保留供其它索引键继续服务），
     * 不把 DB 瞬时故障放大成"全站索引归零"。
     *
     * <p>⚠️ 与 TV 的差异：TV 还有 10 秒**进程内冷却退避**，把"停机期间每请求一次全表重建"
     * 收敛为"每冷却窗口一次"。本切片去掉（G-6：无规模压力时收益为 0）——
     * 但这条**有**正确性含义，补回位置见 G-6。
     */
    private void ensureIndex(String indexKey) {
        try {
            if (indexOps.keyExists(indexKey)) {
                return;
            }
        } catch (CacheUnavailableException e) {
            log.warn("索引检查失败（视为无索引，尝试重建）: key={}", indexKey, e);
        }
        List<ContentCacheDTO> all;
        try {
            all = contentDao.findAllContent();
        } catch (DataAccessException e) {
            log.warn("索引重建装载失败（跳过重建，保留旧索引）", e);
            return;
        }
        rebuildIndexes(all);
    }

    private List<Long> readIndex(String indexKey) {
        try {
            return indexOps.readIds(indexKey);
        } catch (CacheUnavailableException e) {
            log.warn("索引读取失败（降级为空推荐）: key={}", indexKey, e);
            return List.of();
        }
    }

    private void rebuildIndexes(List<ContentCacheDTO> all) {
        List<ContentRedisOps.IndexEntry> entries = new ArrayList<>(all.size());
        for (ContentCacheDTO dto : all) {
            entries.add(new ContentRedisOps.IndexEntry(dto.getId(), dto.getType(), dto.getCategoryId()));
        }
        try {
            indexOps.rebuildIndexes(indexOps.indexKeys(), entries);
        } catch (CacheUnavailableException e) {
            log.warn("索引重建失败（降级为空推荐）", e);
        }
    }

    /**
     * 写索引（新前序）。写失败 ⇒ best-effort DEL 本内容所属的索引 key，
     * 让下次推荐读触发懒重建自愈（TV 三期 T4/N4：否则索引 key 存在则懒重建永不触发，
     * 内容长期不进推荐）。
     */
    private void addToIndex(ContentCacheDTO dto) {
        String[] keys = ContentRedisOps.indexKeysOf(dto.getType(), dto.getCategoryId());
        try {
            indexOps.pushFront(keys, dto.getId());
        } catch (CacheUnavailableException e) {
            log.warn("内容索引写入失败，DEL 索引 key 让读路径懒重建自愈: contentId={}", dto.getId(), e);
            try {
                indexOps.deleteKeys(List.of(keys));
            } catch (CacheUnavailableException alsoFailed) {
                log.warn("索引自愈 DEL 也失败（疑似 Redis 持续异常），维持降级: firstKey={}", keys[0]);
            }
        }
    }

    /** {@code content:{id}} → id。 */
    private static long parseContentId(String dataKey) {
        return Long.parseLong(dataKey.substring("content:".length()));
    }
}
