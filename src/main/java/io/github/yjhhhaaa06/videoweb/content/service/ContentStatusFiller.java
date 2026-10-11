package io.github.yjhhhaaa06.videoweb.content.service;

import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.interaction.model.InteractionTarget;
import io.github.yjhhhaaa06.videoweb.interaction.model.vo.InteractionStatusVO;
import io.github.yjhhhaaa06.videoweb.interaction.service.InteractionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内容**列表**的互动态回填适配器（T10 起为薄壳；R-10，见 {@code 收藏功能-分期与设计.md} §3.6）。
 *
 * <h2>T10 之前 / 之后</h2>
 * 原实现（承接 TV {@code ContentStatusFiller}，S5）自己持有 {@code LikeService} /
 * {@code FollowService}，**直接**填列表与详情的两个状态字段。T10 把"数据怎么取"整体搬进
 * **互动域**（{@link InteractionService}）：本类只剩一件事——把 {@code List<ContentVO>}
 * 翻译成互动域目标（{@link InteractionTarget}）、调它的**批量**契约、把结果写回 VO 顶层
 * （{@code isLiked} / {@code isFollowed}）。详情路径（单条）不经本类：它要的是完整子对象，
 * 由 {@code ContentService.getContentDetailVO} 直调互动域。
 *
 * <h2>★ 为什么这段映射留在 content 而不是搬进互动域</h2>
 * 互动域**不得依赖 content**（否则 {@code content → interaction → content} 成环，R-10 §3.6）
 * ⇒ 它不认识 {@code ContentVO}、也不反查"这条内容的作者是谁 / 什么类型"（作者与类型由调用方
 * 随 {@link InteractionTarget} 传入）。故"ContentVO ↔ 目标"的映射必须留在 content 侧；
 * 留在本类还让 {@code /start} 与 {@code /search/keywordSearch} 共享**同一份**映射。
 *
 * <h2>★ 批量而非逐条（T9 红线的同一纪律）</h2>
 * 本类只调互动域的**批量**契约；**不得**改成逐条调 {@link InteractionService#getStatus} ——
 * 一个 100 条的推荐页会从一趟批量退化为 100 次往返（原类注释的既有纪律）。
 * 判别力见 {@code ContentReadTests} 的批量透传用例（spy 断言调用次数）。
 *
 * <p>匿名（{@code userId == null}）短路：两个状态保持 Java 默认 {@code false}
 * （旧 pytest 对匿名场景的断言即此）。
 */
@Component
public class ContentStatusFiller {

    private final InteractionService interactionService;

    public ContentStatusFiller(InteractionService interactionService) {
        this.interactionService = interactionService;
    }

    /**
     * 列表页批量回填 {@code isLiked} + {@code isFollowed}
     * （{@code /start}、{@code /search/keywordSearch} 使用）。
     *
     * <p>作者未知（{@code authorId <= 0}）的条目由互动域按 {@code false} 作答
     * （与既有跳过口径一致），本类不做分支。
     */
    public void fillLikeAndFollowBatch(List<ContentVO> list, Long userId) {
        if (userId == null || list == null || list.isEmpty()) {
            return;
        }
        Map<Long, InteractionStatusVO> statuses = interactionService.batchGetStatus(userId, targets(list));
        for (ContentVO vo : list) {
            InteractionStatusVO status = statuses.get(vo.getId());
            vo.setIsLiked(status.getIsLiked());
            vo.setIsFollowed(status.getIsFollowed());
        }
    }

    /** 页内 VO → 互动域目标（content 侧知识：id 与作者来自 VO，类型原样传入）。 */
    private static List<InteractionTarget> targets(List<ContentVO> list) {
        List<InteractionTarget> targets = new ArrayList<>(list.size());
        for (ContentVO vo : list) {
            targets.add(new InteractionTarget(vo.getId(), vo.getAuthorId(), vo.getType()));
        }
        return targets;
    }
}