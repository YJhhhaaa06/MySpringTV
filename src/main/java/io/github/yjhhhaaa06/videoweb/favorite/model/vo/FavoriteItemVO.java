package io.github.yjhhhaaa06.videoweb.favorite.model.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 收藏夹**内**的一条内容（{@code GET /favorite/list} 的条目，T6）。
 *
 * <h2>★ 一条记录两种渲染：正常卡片 / 失效占位</h2>
 * 需求篇 §五「失效内容」+ R-03：内容被作者删除（{@code is_deleted = 1}）或管理员下架
 * （{@code is_deleted = 2}）、乃至内容行真没了，收藏记录**仍在**、仍占分页位置，
 * 但对外只能看到一张**脱敏占位卡**：
 * <pre>
 * 正常：{invalid:false, title:"原标题",   coverUrl:"/upload/cover/x.png", authorName:"原作者"}
 * 失效：{invalid:true,  title:"内容已失效", coverUrl:null,                authorName:null}
 * </pre>
 * ⚠️ 失效条目**不返回原标题 / 封面 / 作者** —— 这是 R-03 的安全侧理由：
 * 一旦把标题带出去，第三方客户端就能捞出作者已删的内容（"删了"就不该再被检索到）。
 * 判据与"能不能收进来"同源（T4 的 {@code ContentDao.isContentExist} 带 {@code is_deleted = 0}），
 * 故不会出现"列表说失效、{@code add} 却收得进"这类自相矛盾（详见 {@code FavoriteItemDao} 口径 2）。
 *
 * <h2>字段为什么是这七个（键集即契约，加字段前先回需求篇）</h2>
 * <ul>
 *   <li>{@code id} —— 收藏记录行 id（前端的稳定行键；与 {@code contentId} 同在本 VO 里，
 *       是因为"移出"按 {@code (folderId, contentId)} 定位，而"渲染一行"按记录定位）；</li>
 *   <li>{@code contentId} —— ★ 打开内容 + **移出**都要它（T4 的 {@code /favorite/remove}
 *       收的就是 {@code contentIds}，失效条目**必须**移得掉 ⇒ 失效时**照常返回**本字段）；</li>
 *   <li>{@code favoriteTime} —— 收藏时间（＝本页的排序键，{@code create_time DESC, id DESC}）；
 *       失效条目也返回（记录还在，收藏时间仍是事实）；</li>
 *   <li>{@code invalid} —— 前端据此渲染占位样式；★ 有它才不必靠"标题等于某文案"反推状态；</li>
 *   <li>{@code title} / {@code coverUrl} / {@code authorName} —— 渲染一张卡片所需的最小集；
 *       失效时分别是固定文案 / {@code null} / {@code null}。</li>
 * </ul>
 * 刻意**不含** {@code type} / {@code authorId} / {@code description} / 点赞评论数 ——
 * 键集一旦扩大就是对外契约，一期只给"渲染一张卡片 + 移出"所必需的字段；
 * T7 若确需（如按作者名跳个人空间要 {@code authorId}），先回来改本注释与键集断言。
 *
 * <h2>⚠️ 为什么 {@code invalid} 用包装类型 {@code Boolean}</h2>
 * 同 {@code FavoriteFolderVO}：属性名 {@code invalid} 配 {@code boolean} 会让 Lombok 生成
 * {@code isInvalid()}，Jackson 对 {@code isXxx()} 的推导虽在本例恰好得到 {@code invalid}，
 * 但一旦改名就静默漂（{@code ContentVO.isLiked} 就踩成 {@code liked}）。用包装类型 ⇒ getter
 * 恒为 {@code getInvalid()} ⇒ JSON 键逐字是 {@code invalid}，不需要 {@code @JsonProperty} 兜底。
 *
 * <h2>一个形状两个角色</h2>
 * 本类同时是 **Mapper 行类型**（{@code favoriteItemVoMap}）与 **API 载荷形状** ——
 * 与 {@code FavoriteFolderVO} 同款处置。⚠️ 差别是：SQL 里查出来的 {@code title} /
 * {@code coverUrl} / {@code authorName} 是**原值**（失效内容也查得到），**脱敏发生在 Java 侧**
 * （{@code FavoriteService.listFolderItems}）——"判失效"归 SQL、"失效长什么样"归 Java，
 * 两件事不在一层做，各自的注入点也就各自独立（反向验证两个点都能红）。
 *
 * <p>JSON 形状（{@code /favorite/list} 的 {@code data.list} 元素）：
 * <pre>
 * {"id":11,"contentId":3,"favoriteTime":"2026-10-09T18:00:00","invalid":false,
 *  "title":"标题","coverUrl":"/upload/cover/x.png","authorName":"作者"}
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteItemVO {

    /** 收藏记录行 id（{@code favorite_item.id}）。 */
    private Long id;

    /** 内容 id —— 打开内容与**移出**的定位键（失效条目也必须带，否则清不掉占位）。 */
    private Long contentId;

    /** 收藏时间（{@code favorite_item.create_time}），即本列表的排序键。 */
    private LocalDateTime favoriteTime;

    /** 内容是否已失效（{@code is_deleted != 0} 或内容行不存在）。 */
    private Boolean invalid;

    /** 标题；失效时是固定文案「内容已失效」（**不是**原标题）。 */
    private String title;

    /** 封面 URL；失效时恒为 {@code null}。 */
    private String coverUrl;

    /** 作者名；失效时恒为 {@code null}。 */
    private String authorName;
}
