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
 *   <li>{@code updateFileExists} — admin/媒体审计批次（文件校验状态回写；换源路径用的是
 *       {@link #updateMediaUrl} 自带的 file_exists 参数，不经过它）</li>
 *   <li>{@code deleteMediaById} — admin/运维（按媒体 id 删）</li>
 *   <li>{@code findAllMedia} / {@code findMediaById} — 运维扫描/恢复（无端点）</li>
 * </ul>
 *
 * <p>（S7 起 {@code addMedia} / {@code updateMediaUrl} 已由 upload 批次补搬，见文末。）
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
}
