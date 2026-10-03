package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * 自动大V（滞回判定）参数 + 大V名单。
 *
 * <h2>三个字段的来源</h2>
 * TV 的取值层是 {@code config.FeedBigVConfig}（**236 行**：外置 Properties 文件 + 节流惰性热更 + 名单），
 * 它产出的快照有三个字段 {@code (threshold, userIds, downgradeRatio)}。本记录承接全部三项：
 * <ul>
 *   <li>{@code threshold} / {@code downgradeRatio} —— **写侧**（{@code AutoBigVStateService}）的滞回判定输入
 *       （S4 迁入）；</li>
 *   <li>{@code userIds}（**名单**）—— S9 迁入：只服务**读侧** {@code feed.service.FeedBigVRouter}
 *       〚"名单 ∪ 自动大V状态表 ∪ 粉丝数 ≥ 阈值"三路并集〛的**第①路**。</li>
 * </ul>
 * <b>外置文件热更不搬</b>（S4 已裁、S9 沿裁）：那是"配置载体"的可替换实现，
 * 其收益（改文件不重启）在无生产流量时不成立，而成本（节流 + 双检锁 + 整批换入 + 失败告警按状态迁移）
 * 是实打实的复杂度。补回位置见《遗留台账》B 类 / 《决策留痕表》C-8。
 *
 * <p>TV 的 {@code feed.bigv.threshold} 默认 10000、{@code feed.bigv.userIds} 默认空、
 * {@code feed.bigv.downgradeRatio} 默认 0.8——此处照搬为默认值（写在 {@code application.yaml}）。
 *
 * @param threshold      升级线：粉丝数 {@code >= threshold} ⇒ 状态表插入该行（"存在即自动大V"）
 * @param downgradeRatio 滞回降级系数：粉丝数 {@code < ratio × threshold} ⇒ 状态表删行；
 *                       两线之间为"带内"，**状态不翻转**（这就是滞回）
 * @param userIds        大V名单（显式指定；读侧命中即大V，不经阈值、也不进 SQL 的 IN 列表）。空 = 无名单
 */
@ConfigurationProperties(prefix = "video.bigv")
public record BigVProperties(int threshold, double downgradeRatio, Set<Long> userIds) {

    /**
     * 校验口径照搬 TV {@code AppConfig.validateFeedBigVDowngradeRatio}（即滞回判定的一份规格）：
     * 合法区间 {@code (0, 1]}——{@code > 1} 会让降级线高于升级线（带内必翻转、滞回反向失效）；
     * {@code <= 0} 会让降级永不发生（状态表只增不减）。
     *
     * <p>⚠️ <b>一处有意差异</b>：TV 在**调用期**校验（首次关注/取关请求抛
     * {@code IllegalArgumentException} → 500），本实现改为**绑定期**校验（应用启动即拒）。
     * 理由：本项目配置一律绑定期校验（{@link JwtProperties} / {@link LikeCacheProperties} 同款），
     * 而"配置错就出事且难发现"的一类宁可启动即拒——TV 自己在批量尺寸参数上是同一口径
     * （{@code validatePositiveBatch} 的注释："宁可启动即拒"）。配置正确时两者行为完全一致。
     *
     * <p><b>名单缺失的归一</b>：{@code video.bigv.user-ids} 未配置时 Spring 可能传入 {@code null}，
     * 归一为空集（TV 对空白名单也返回空集——"无名单"是合法常态，不是配置错误，故**不** fail-fast）。
     */
    public BigVProperties {
        if (threshold <= 0) {
            throw new IllegalArgumentException("video.bigv.threshold 必须为正数: " + threshold);
        }
        if (!(downgradeRatio > 0.0) || downgradeRatio > 1.0) {
            throw new IllegalArgumentException("video.bigv.downgrade-ratio 必须在 (0, 1] 区间: " + downgradeRatio);
        }
        userIds = (userIds == null) ? Set.of() : Set.copyOf(userIds);
    }
}

