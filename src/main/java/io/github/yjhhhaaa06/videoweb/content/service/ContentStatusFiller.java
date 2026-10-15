package io.github.yjhhhaaa06.videoweb.content.service;

import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.interaction.model.InteractionDimension;
import io.github.yjhhhaaa06.videoweb.interaction.model.InteractionTarget;
import io.github.yjhhhaaa06.videoweb.interaction.model.vo.InteractionStatusVO;
import io.github.yjhhhaaa06.videoweb.interaction.service.InteractionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内容**列表**的互动态回填适配器（T10 起为薄壳；R-10 的 §六，T13 起按 R-13 带维度集合）。
 *
 * <h2>T10 之前 / 之后</h2>
 * 原实现（承接 TV {@code ContentStatusFiller}，S5）自己持有 {@code LikeService} /
 * {@code FollowService}，**直接**填列表与详情的两个状态字段。T10 把"数据怎么取"整体搬进
 * **互动域**（{@link InteractionService}）：本类只剩一件事——把 {@code List<ContentVO>}
 * 翻译成互动域目标（{@link InteractionTarget}）、调它的**批量**契约、把结果写进 VO 的
 * {@code interaction} 子对象。详情路径（单条）不经本类：它要的是完整子对象，
 * 由 {@code ContentService.getContentDetailVO} 直调互动域。
 *
 * <h2>★ 为什么这段映射留在 content 而不是搬进互动域</h2>
 * 互动域**不得依赖 content**（否则 {@code content → interaction → content} 成环，R-10 §六）
 * ⇒ 它不认识 {@code ContentVO}、也不反查"这条内容的作者是谁 / 什么类型"（作者与类型由调用方
 * 随 {@link InteractionTarget} 传入）。故"ContentVO ↔ 目标"的映射必须留在 content 侧；
 * 留在本类还让 {@code /start} 与 {@code /search/keywordSearch} 共享**同一份**映射。
 * ⚠️ {@code FeedService} 有一份**同形**的私有映射（3 行胶水）—— feed 是独立的一个"面"，
 * 维度常量由它自持；刻意**不**抽公共（抽了会让 feed 依赖 content 的装配组件，
 * 且 {@code feed → content} 的边会再宽一层）。
 *
 * <h2>★ 批量而非逐条（T9 红线的同一纪律）</h2>
 * 本类只调互动域的**批量**契约；**不得**改成逐条调 {@link InteractionService#getStatus} ——
 * 一个 100 条的推荐页会从一趟批量退化为 100 次往返（原类注释的既有纪律）。
 * 判别力见 {@code ContentReadTests} 的批量透传用例（spy 断言调用次数）。
 *
 * <h2>★ 维度集合由调用方给（R-13）</h2>
 * 本类**不持有**"哪个面带哪几维"的知识 —— 那是调用点的**编译期常量**
 * （见 {@link InteractionDimension}）。本类只负责透传。
 *
 * <p>匿名（{@code userId == null}）短路：互动域返回 {@code null}，VO 的 {@code interaction}
 * 保持 {@code null}（= "没有登录态"，不是三个 {@code false}）。
 */
@Component
public class ContentStatusFiller {

    private final InteractionService interactionService;

    public ContentStatusFiller(InteractionService interactionService) {
        this.interactionService = interactionService;
    }

    /**
     * 列表页批量回填互动态（{@code /start}、{@code /search/keywordSearch} 使用）。
     *
     * <p>作者未知（{@code authorId <= 0}）的条目由互动域按 {@code false} 作答
     * （与既有跳过口径一致；且该维若被选中，仍会给出 {@code false} 而不是 {@code null}），
     * 本类不做分支。
     *
     * <p>⚠️ {@code dimensions} 为**空集**（{@code /search/keywordSearch} 的取值）时
     * 本方法**仍要调用**互动域 —— 它一趟都不查、但会返回"对象在、三维全 {@code null}"的对象。
     * ★ **直接跳过调用会得到 {@code interaction: null} ⇒ 与"匿名"不可区分**（这是错的）。
     *
     * @param dimensions 要填的维度（编译期常量；空集 ⇒ 只产出一个"三维全 null"的对象）
     */
    public void fillInteractionBatch(List<ContentVO> list, Long userId,
                                     Set<InteractionDimension> dimensions) {
        if (userId == null || list == null || list.isEmpty()) {
            return;
        }
        // 互动域批量契约保证"每个入参 id 恰好一条"（不重不漏，见 InteractionService#batchGetStatus），
        // 故此处不判空、缺失也不可能发生 —— 该前提由 InteractionStatusContractTests 钉住。
        // ⚠️ 下面的 null 判定**当前不可达**（userId 非 null 时互动域不会返回 null）；
        //    保留它是为了"互动域将来若新增返回 null 的分支"时不静默 NPE —— 别读成"这里有条匿名路径"。
        Map<Long, InteractionStatusVO> statuses =
                interactionService.batchGetStatus(userId, targets(list), dimensions);
        if (statuses == null) {
            return;
        }
        for (ContentVO vo : list) {
            vo.setInteraction(statuses.get(vo.getId()));
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
