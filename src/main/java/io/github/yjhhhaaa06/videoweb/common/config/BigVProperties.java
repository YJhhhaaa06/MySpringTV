package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自动大V（滞回判定）参数。
 *
 * <h2>为什么只有两个字段</h2>
 * TV 的取值层是 {@code config.FeedBigVConfig}（**236 行**：外置 Properties 文件 + 节流惰性热更 + 名单），
 * 它产出的快照有三个字段 {@code (threshold, userIds, downgradeRatio)}。其中：
 * <ul>
 *   <li>{@code threshold} / {@code downgradeRatio} —— **写侧**（{@code AutoBigVStateService}）唯一的判定输入；</li>
 *   <li>{@code userIds}（**名单**）—— 只服务**读侧** {@code feed.service.FeedBigVRouter}
 *       （"名单 ∪ 状态表 ∪ 阈值"三路并集），属 feed 域，本批不搬。</li>
 * </ul>
 * 故本记录只承接前两项。**外置文件热更不搬**：那是"配置载体"的可替换实现，
 * 其收益（改文件不重启）在无生产流量时不成立，而其成本（节流 + 双检锁 + 整批换入 + 失败告警按状态迁移）
 * 是实打实的复杂度。补回位置见《事务边界决策表》§二·E 盘点 B。
 *
 * <p>TV 的 {@code feed.bigv.threshold} 默认 10000，{@code feed.bigv.downgradeRatio} 默认 0.8，
 * 两者都在 {@code app.properties} 中显式存在，此处照搬为其默认值（写在 {@code application.yaml}）。
 *
 * @param threshold      升级线：粉丝数 {@code >= threshold} ⇒ 状态表插入该行（"存在即自动大V"）
 * @param downgradeRatio 滞回降级系数：粉丝数 {@code < ratio × threshold} ⇒ 状态表删行；
 *                       两线之间为"带内"，**状态不翻转**（这就是滞回）
 */
@ConfigurationProperties(prefix = "video.bigv")
public record BigVProperties(int threshold, double downgradeRatio) {

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
     */
    public BigVProperties {
        if (threshold <= 0) {
            throw new IllegalArgumentException("video.bigv.threshold 必须为正数: " + threshold);
        }
        if (!(downgradeRatio > 0.0) || downgradeRatio > 1.0) {
            throw new IllegalArgumentException("video.bigv.downgrade-ratio 必须在 (0, 1] 区间: " + downgradeRatio);
        }
    }
}
