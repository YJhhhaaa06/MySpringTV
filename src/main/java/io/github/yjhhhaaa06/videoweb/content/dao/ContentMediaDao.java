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
 * {@link #findMediaByContentTypeSort}（作者删单条图的前置定位）。
 * 写：{@link #deleteMediaByContentIdAndTypeSort}（删单条图）、
 * {@link #compactImageSort}（删图后重排 sort 保持 1..n 连续）、
 * {@link #deleteByContentId}（删作品时级联清媒体记录）。
 *
 * <p><b>不搬</b>（各有归属）：
 * <ul>
 *   <li>{@code deleteMediaById} — **无消费者**：全量实测无一端点/服务调用它（TV 的死代码），
 *       按"不搬无主代码"丢弃</li>
 * </ul>
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
     * 按 {@code (contentId, type, sort)} 删除单条媒体（作者删错图）。
     * TV: {@code delete from content_media where content_id=? and type=? and sort=?}
     */
    int deleteMediaByContentIdAndTypeSort(@Param("contentId") long contentId,
                                          @Param("type") int type,
                                          @Param("sort") int sort);

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
     * @param type 1 视频 / 2 图片 / 3 封面（调用方按上传类型给出，TV {@code UploadType.mediaType} 口径）
     * @param sort 同 type 内序号（视频/封面恒 1；图片从 1 递增）
     */
    int addMedia(@Param("contentId") long contentId,
                 @Param("url") String url,
                 @Param("type") int type,
                 @Param("sort") int sort);

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

    // ========================================================================
    // S8：admin（媒体审计）
    // ========================================================================

    /**
     * **全部**媒体行，按 {@code id} 升序（运维全量扫描的装载源）。
     *
     * <p>TV: {@code select id,content_id,url,type,sort from content_media order by id}
     *
     * <p>⚠️ 这是**无上限**全表查询（TV 原样）——它只被 {@code /api/admin/media/list} 与
     * {@code /scan} 触发（管理员手工运维动作，非热路径）。
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
