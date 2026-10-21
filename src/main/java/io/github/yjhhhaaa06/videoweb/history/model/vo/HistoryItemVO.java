package io.github.yjhhhaaa06.videoweb.history.model.vo;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 历史列表里的一条（{@code GET /history/list} 的条目，播放记录专项）。
 *
 * <h2>一个形状两个角色（同 {@code WatchLaterItemVO}）</h2>
 * 本类同时是 **Mapper 行类型**（{@code historyItemVoMap}）与 **API 载荷形状**。
 * SQL **只填本域三列**（{@code contentId} / {@code watchedTime} / {@code progress}）；
 * 展示字段（{@code title} / {@code coverUrl} / {@code authorName} / {@code durationSeconds}）
 * 由 {@code HistoryService#list} 经 {@code ContentService#loadContentVOs} **一趟批量**组装。
 * ⚠️ 判失效**不在 SQL 里**：装载契约"取不到"才是判据（见
 * {@code architecture/domain/history/history.md} §三 / §八）。
 *
 * <h2>失效占位（口径见 history.md §三）</h2>
 * <pre>
 * 正常：{invalid:false, title:"原标题", coverUrl:"/upload/cover/x.png", authorName:"原作者", durationSeconds:600}
 * 失效：{invalid:true,  title:"内容已失效", coverUrl:null,               authorName:null,        durationSeconds:null}
 * </pre>
 * ★ 失效行**保留 {@code progress}**（历史数据是真的：页面还能显示"看到第几秒"）——
 * 总时长取不到才置 null，前端据此选择不渲染进度条。
 *
 * <h2>⚠️ 为什么 {@code invalid} 用包装类型 {@code Boolean}</h2>
 * 同 {@code WatchLaterItemVO}：属性名 {@code invalid} 配 {@code boolean} 会让 Lombok 生成
 * {@code isInvalid()}；包装类型 ⇒ getter 恒为 {@code getInvalid()} ⇒ JSON 键名逐字是
 * {@code invalid}，不会漂移。
 *
 * <p>JSON 形状（{@code /history/list} 的 {@code data.list} 元素）：
 * <pre>
 * {"contentId":3,"watchedTime":"2026-10-21T00:00:00","invalid":false,
 *  "title":"标题","coverUrl":"/upload/cover/x.png","authorName":"作者",
 *  "progress":123,"durationSeconds":600}
 * </pre>
 */
@Data
@NoArgsConstructor
public class HistoryItemVO {

    /** 内容 id —— 打开内容的定位键；失效行**也保留**（历史里还要显示它）。 */
    private Long contentId;

    /** 最后观看 / 上报时间（{@code watch_history.update_time}），本列表的排序键。 */
    private LocalDateTime watchedTime;

    /** 是否已失效 —— 判据 = content 装载契约"取不到"（不是 {@code durationSeconds == null}）。 */
    private Boolean invalid;

    /** 标题；失效时是固定文案「内容已失效」（**不是**原标题）。 */
    private String title;

    /** 封面 URL；失效时恒为 {@code null}。 */
    private String coverUrl;

    /** 作者名；失效时恒为 {@code null}。 */
    private String authorName;

    /** 上次进度（秒）；**失效行保留** —— 它是历史数据，不是展示字段。 */
    private Integer progress;

    /** 总时长（秒）；失效时恒为 {@code null}（未知时长本来也可能是 null，两者由 {@code invalid} 区分）。 */
    private Integer durationSeconds;
}
