package io.github.yjhhhaaa06.videoweb.content.dao;

import io.github.yjhhhaaa06.videoweb.content.model.entity.ContentMedia;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 内容媒体数据访问（承接 TV {@code com.itheima.content.dao.ContentMediaDao}）。
 *
 * <h2>只搬本切片端点在用的方法（决策表 §二·G 盘点 B）</h2>
 * 读：{@link #findMediaByContentId}（内容详情的媒体聚合）、
 * {@link #findMediaByContentIds}（批量装载的媒体聚合）、
 * {@link #findMediaByContentTypeSort}（作者**换源**的前置定位）、
 * {@link #findMediaByContentTypeUrl}（作者**删图**的前置定位，`T63`）。
 * 写：{@link #deleteMediaById}（删单条图，按查出来的代理键删，`T63`）、
 * {@link #compactImageSort}（删图后重排 sort 保持 1..n 连续）、
 * {@link #deleteByContentId}（删作品时级联清媒体记录）。
 *
 * <h2>`T63`：删图的两条 SQL 为什么按 {@code id} 而不是 {@code (type,sort)}</h2>
 * {@code sort} 是**可变位置**（`compactImageSort` 会把它前移），删完第 N 张后再发一次
 * "删 sort=N"会命中**原来的第 N+1 张** ⇒ 重试删掉另一个对象（接口契约 §2.4① 的反例）。
 * 故删图改为：按稳定标识（{@code url}）**查出代理键**{@code id} → **按 id 删** → 判 affected rows。
 * {@code findMediaByContentTypeSort} 仍保留（换源 `PUT /contents/{id}/media` 按 `type+sort`，
 * 那是既存形态、`T63` 不修）。
 *
 * <p>（S7 起 {@code addMedia} / {@code updateMediaUrl} 已由 upload 批次补搬；
 * S8 起 {@code findAllMedia} / {@code findMediaById} / {@code updateFileExists}
 * 已由 admin 媒体审计批次补搬——见文末。）
 *
 * <h2>一处与原实现的形态差异（等价，非行为改动）</h2>
 * TV 的 {@code findMedia} 直接在 DAO 内把行按 {@code type} 分组返回
 * {@code Map<Integer, List<ContentMedia>>}。新实现返回**扁平行列表**（SQL 的
 * {@code order by type,sort} 保证同 type 内有序），分组由 {@code ContentCache.groupMediaByType} 完成
 * ——这样 DAO 只做"取行"，分组逻辑与批量路径**共用同一份实现**（TV 的批量路径本就在 Java 侧分组）。
 */
@Mapper
public interface ContentMediaDao {

    /**
     * 某内容的全部媒体行，按 {@code type, sort} 升序。
     *
     * <p>TV: {@code select id,content_id,url,type,sort from content_media where content_id=? order by type,sort}
     */
    List<ContentMedia> findMediaByContentId(@Param("contentId") long contentId);

    /**
     * 按 contentId 集合批量取媒体行（TV 第五期 T5：启动/批量装载消 N+1）。
     *
     * <p>TV: {@code ... where content_id IN (?…) order by content_id,type,sort}
     * ——{@code StringBuilder} 拼 IN 改 {@code <foreach>}（机械改动）。
     */
    List<ContentMedia> findMediaByContentIds(@Param("ids") Collection<Long> ids);

    /**
     * 按 {@code (contentId, type, sort)} 定位单条媒体（作者换源/删图的前置）。
     * 查不到返回 {@code null} ⇒ 调用方抛 404「媒体资源不存在」。
     */
    ContentMedia findMediaByContentTypeSort(@Param("contentId") long contentId,
                                            @Param("type") int type,
                                            @Param("sort") int sort);

    /**
     * 按 {@code (contentId, type, url)} 定位单条媒体（`T63`：作者**删图**的前置定位）。
     * 查不到返回 {@code null} ⇒ 调用方抛 404「媒体资源不存在」。
     *
     * <p>⚠️ <b>必须 {@code order by id limit 1}</b>：{@code content_media} 只有主键与
     * {@code idx_content_id}，**没有** {@code (content_id,url)} 唯一键；存量数据的 url 也不是 UUID
     * （可能同内容内重复）⇒ 命中多行时返回 {@code List} 会抛 {@code TooManyResultsException}（500）。
     * 取最小 id 那一行，与本方法"单条"的语义一致。
     *
     * @param url 已由调用方归一化（剥掉 {@code video.media.base-url} 前缀）的**库内原值**
     */
    ContentMedia findMediaByContentTypeUrl(@Param("contentId") long contentId,
                                           @Param("type") int type,
                                           @Param("url") String url);

    /**
     * 按主键删除单条媒体（`T63`：作者删图）。
     * TV: {@code delete from content_media where id=?}（TV 里无消费者，`T63` 起才有）
     *
     * <p>★ 与 {@code findMediaByContentTypeUrl} 配对使用：**查出来的 id** 即删除条件，
     * 中途别处的 {@code compactImageSort} 改了 {@code sort} 也不会删错行。
     * 返回 0 表示"这一行已被别的请求删掉"⇒ 调用方抛 404（并发双删）。
     */
    int deleteMediaById(@Param("mediaId") long mediaId);

    /**
     * 删图后重排剩余图片的 {@code sort}，保持 {@code 1..n} 连续
     * （前端用 {@code index+1} 定位图片，空洞会让"第 3 张"指错）。
     *
     * <p>TV: {@code update content_media set sort=sort-1 where content_id=? and type=2 and sort>?}
     * ——{@code type=2}（图片）是**写死在 SQL 里**的，与作者删图只允许 type=2 一致。
     */
    int compactImageSort(@Param("contentId") long contentId, @Param("deletedSort") int deletedSort);

    /**
     * 删除某内容的**全部**媒体记录（作者删作品的级联）。
     * TV: {@code delete from content_media where content_id=?}
     */
    int deleteByContentId(@Param("contentId") long contentId);

    // ========================================================================
    // S7：upload（发布写路径）
    // ========================================================================

    /**
     * 新增一条媒体行（发布视频/图文时插视频、封面、图片）。
     *
     * <p>TV: {@code insert into content_media(content_id,url,type,sort) VALUES (?, ?,?,?)}
     *
     * @param type            1 视频 / 2 图片 / 3 封面（调用方按上传类型给出，TV {@code UploadType.mediaType} 口径）
     * @param sort            同 type 内序号（视频/封面恒 1；图片从 1 递增）
     * @param durationSeconds 视频时长（秒）；**只有视频行给值**，封面/图片传 {@code null}
     *                        （列可空，{@code null} = 未知，见 {@code history.md} §六.2）
     */
    int addMedia(@Param("contentId") long contentId,
                 @Param("url") String url,
                 @Param("type") int type,
                 @Param("sort") int sort,
                 @Param("durationSeconds") Integer durationSeconds);

    /**
     * 换源：更新单条媒体的 url，并立即标记"文件存在" + 记录校验时间。
     *
     * <p>TV: {@code update content_media set url=?, file_exists=?, last_verify_time=? where id=?}
     * ——{@code exists=true} 与时间戳由 Service 传入（刚写入的新文件必然存在）。
     */
    int updateMediaUrl(@Param("mediaId") long mediaId,
                       @Param("url") String url,
                       @Param("exists") boolean exists,
                       @Param("lastVerifyTime") java.sql.Timestamp lastVerifyTime);

    /**
     * 换源后刷新**该媒体行**的时长（T46；发布路径不走这里——那里是 {@code addMedia} 直接写入）。
     *
     * <p>★ 刻意与 {@link #updateMediaUrl} 分成两条语句：换图片（type=2）/ 封面（type=3）时
     * 调用方**根本不调用本方法** ⇒ "换非视频不清空视频行的时长"由调用点保证，
     * 而不是靠 SQL 里判 type（那种隐式约定在评审里看不见）。
     *
     * @param durationSeconds 新时长（秒）；解析失败传 {@code null} ⇒ 时长回到"未知"
     */
    int updateMediaDuration(@Param("mediaId") long mediaId,
                            @Param("durationSeconds") Integer durationSeconds);

    // ========================================================================
    // S8：admin（媒体审计）
    // ========================================================================

    /**
     * **全部**媒体行，按 {@code id} 升序（运维全量扫描的装载源）。
     *
     * <p>TV: {@code select id,content_id,url,type,sort from content_media order by id}
     *
     * <p>⚠️ 这是**无上限**全表查询（TV 原样）——它只被 {@code GET /api/v1/admin/media} 与
     * {@code POST /api/v1/admin/media/scan} 触发（管理员手工运维动作，非热路径）。
     */
    List<ContentMedia> findAllMedia();

    /**
     * 按 media id 查单条媒体行（运维恢复的前置：据此拿到 {@code url} 反解磁盘路径）。
     * 查不到返回 {@code null} ⇒ 调用方抛 404「媒体资源不存在: mediaId=…」。
     *
     * <p>TV: {@code select id,content_id,url,type,sort from content_media where id=?}
     */
    ContentMedia findMediaById(@Param("mediaId") long mediaId);

    /**
     * 回写单条媒体的"文件存在"状态（扫描 / 恢复后）。
     *
     * <p>TV: {@code update content_media set file_exists=?, last_verify_time=? where id=?}
     *
     * <p>⚠️ 与 {@link #updateMediaUrl} 的区别：后者**同时改 url**（换源），本方法**只改校验列**。
     * 两者都写 {@code file_exists}，别合并——换源语义与"扫描回写"是两件事。
     */
    int updateFileExists(@Param("mediaId") long mediaId,
                         @Param("exists") boolean exists,
                         @Param("lastVerifyTime") java.sql.Timestamp lastVerifyTime);
}
