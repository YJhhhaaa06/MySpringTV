package io.github.yjhhhaaa06.videoweb.favorite.model.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 稍后再看清单里的一条（{@code GET /watchlater/list} 的条目，收藏三期 T17）。
 *
 * <h2>★ 与 {@link FavoriteItemVO} 同形，但用 {@code addedTime}（用户 2026-10-14 决策 D3）</h2>
 * 两者渲染一张卡片所需的字段完全相同：{@code {contentId, 「时间」, invalid, title, coverUrl, authorName}}。
 * 差别有两条：
 * <ol>
 *   <li>时间是 {@code addedTime}（"加入稍后再看的时间"），**不借收藏语义的 {@code favoriteTime}**
 *       —— 字段名直接表达语义，免得下游把"稍后再看时间"读成"收藏时间"；</li>
 *   <li><b>不含 {@code id}</b>：移出按 {@code contentId} 定位（{@code /watchlater/remove} 收
 *       {@code contentIds}），且 {@code uk_user_content} 保证「同一用户同一内容」只有一条
 *       ⇒ {@code contentId} 本就是稳定的行键，不需要再给一个记录 id。</li>
 * </ol>
 * ⚠️ 两条清单**刻意不共用 VO**：字段名与定位键不同，硬凑一个只会得到"两处都别扭"的形状。
 *
 * <h2>失效占位（与收藏列表**同口径**，R-03 / R-11）</h2>
 * <pre>
 * 正常：{invalid:false, title:"原标题",   coverUrl:"/upload/cover/x.png", authorName:"原作者"}
 * 失效：{invalid:true,  title:"内容已失效", coverUrl:null,                authorName:null}
 * </pre>
 * ⚠️ 失效条目**不返回原标题 / 封面 / 作者**；且**保留 {@code contentId}**（移出要它）。
 * 判据 = content 装载契约（{@code ContentService.loadContentVOs}）"取不到"（不存在 / 已删下架 /
 * 媒体损坏 / 未知类型统一折叠，口径"看不了 ≈ 失效"，R-11）。
 * ⚠️ 与 {@code FavoriteService.toItemVO} 的占位分支口径相同、代码**各自一份**（两处 VO 类型不同）
 * ⇒ 改动一处必须同步另一处，由两组对角用例守着。
 *
 * <h2>⚠️ 为什么 {@code invalid} 用包装类型 {@code Boolean}</h2>
 * 同 {@code FavoriteItemVO}：属性名 {@code invalid} 配 {@code boolean} 会让 Lombok 生成
 * {@code isInvalid()}；包装类型 ⇒ getter 恒为 {@code getInvalid()} ⇒ JSON 键逐字是 {@code invalid}。
 *
 * <h2>一个形状两个角色</h2>
 * 本类同时是 **Mapper 行类型**（{@code watchLaterItemVoMap}）与 **API 载荷形状**（同 {@code FavoriteItemVO}）。
 * SQL 只填**本域两列**（{@code contentId} / {@code addedTime}）；其余四列由
 * {@code WatchLaterService.list} 组装 —— 正常行从 {@code loadContentVOs} 抄出，失效行构造占位。
 *
 * <p>JSON 形状（{@code /watchlater/list} 的 {@code data.list} 元素）：
 * <pre>
 * {"contentId":3,"addedTime":"2026-10-14T18:00:00","invalid":false,
 *  "title":"标题","coverUrl":"/upload/cover/x.png","authorName":"作者"}
 * </pre>
 */
@Data
@NoArgsConstructor
public class WatchLaterItemVO {

    /** 内容 id —— 打开内容、**移出**与「移入收藏夹」的定位键（失效条目也必须带，否则清不掉占位）。 */
    private Long contentId;

    /** 加入稍后再看的时间（{@code watch_later.create_time}），本列表的排序键。 */
    private LocalDateTime addedTime;

    /** 内容是否已失效 —— 判据 = content 装载契约"取不到"（同收藏列表口径，R-11）。 */
    private Boolean invalid;

    /** 标题；失效时是固定文案「内容已失效」（**不是**原标题）。 */
    private String title;

    /** 封面 URL；失效时恒为 {@code null}。 */
    private String coverUrl;

    /** 作者名；失效时恒为 {@code null}。 */
    private String authorName;
}
