package io.github.yjhhhaaa06.videoweb.feed.service;

import io.github.yjhhhaaa06.videoweb.common.config.BigVProperties;
import io.github.yjhhhaaa06.videoweb.common.config.FeedProperties;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 大V路由单点（承接 TV {@code feed.service.FeedBigVRouter}）：判定"该作者是否大V"的**唯一判定点（读侧）**
 * ——**名单 ∪ 自动大V状态表 ∪ 粉丝数 ≥ 阈值**。
 *
 * <h2>为什么单点</h2>
 * 同一判定被三处消费——fanout 写扩散（大V内容**不进**粉丝收件箱）、窗口重建（排除大V作者）、
 * 两路读的"大V发件箱"腿（读谁的发件箱）；三处若各自实现阈值 / 名单，口径必然漂移。
 * **写侧**的滞回判规则只在 {@code AutoBigVStateService} 表达（两处，不重叠）。
 *
 * <h2>判定口径（三路并集，TV 逐字保留）</h2>
 * <ol>
 *   <li><b>名单命中</b>（{@code BigVProperties.userIds}）⇒ 大V（显式指定，不经阈值、也不进 SQL 的 IN 列表）；</li>
 *   <li>否则<b>自动大V状态表命中</b>（{@code auto_bigv}，滞回判定的产物——作者曾达线升为大V、
 *       掉粉后仍在带内未降级）⇒ 大V（同样是显式事实，不进粉丝数 IN 列表）；</li>
 *   <li>否则<b>粉丝数</b>（DB 真值 {@code users.follower_count}，按 {@code feed.bigv.query-batch} 分块）
 *       {@code >=} 阈值 ⇒ 大V。</li>
 * </ol>
 * 第③路<b>必须保留</b>：冷启动 / 历史数据未入状态表时，已达标作者不得被"没有状态行"突然降级。
 * 状态表只负责"曾达线、现处带内"的作者不翻转（滞回收益 = 带内作者仍按大V早退 ⇒ fanout 写扩散归零）。
 *
 * <h2>为什么批量要分块</h2>
 * {@link #isBigVBatch(List)} 的 IN 列表长度 ∝ 关注数（无上限：重建 / 读侧都带上整个关注集）⇒
 * 按 {@code feed.bigv.query-batch}（默认 200）切分为多条 SQL。切分发生在**同一个只读事务内**：
 * 一次连接借还 + **同一 read view**（MySQL 默认 REPEATABLE READ）⇒ **跨块无时序偏差**。
 * 这是本方法**保持事务**的唯一理由（不是"取连接的手段"）——见《事务边界决策表》§四·S9。
 *
 * <h2>失败面（fail-open）</h2>
 * 任一 SQL 失败 ⇒ 结论行 WARNING（**不带栈**——源头 {@code DataAccessException} 已由连接池 / 框架记录）
 * + **整批只返回名单命中项**（当作"其余都是普通作者"）。**不做"成功块部分合并"**。
 * 写路径失败方向取"宁可多写不少写"（上行穿越残影由读路径按 contentId 去重兜底）；
 * 读路径少显示一批大V内容、**不 500**（下次请求自愈）。
 */
@Slf4j
@Service
public class FeedBigVRouter {

    private final UserDao userDao;
    private final BigVProperties bigVProperties;
    private final FeedProperties feedProperties;

    public FeedBigVRouter(UserDao userDao, BigVProperties bigVProperties, FeedProperties feedProperties) {
        this.userDao = userDao;
        this.bigVProperties = bigVProperties;
        this.feedProperties = feedProperties;
    }

    /**
     * 判定作者是否大V（名单命中 OR 状态表命中 OR 粉丝数 ≥ 阈值）。
     *
     * <p>委派批量入口（口径唯一实现）；粉丝数 = DB 真值。
     *
     * <p>⚠️ 这是**同类自调用**：{@link #isBigVBatch(List)} 上的 {@code @Transactional} 在经此路径时
     * **不生效**（Spring AOP 不拦自调用）。**无害**：单作者 ⇒ 只有一块 SQL ⇒ 无"跨块 read view"问题
     * （那正是批量入口要事务的唯一理由）。批量入口被外部（fanout / 重建 / 读）**经代理**调用，事务生效。
     */
    public boolean isBigV(long authorId) {
        return isBigVBatch(List.of(authorId)).contains(authorId);
    }

    /**
     * **批量**判定：返回 {@code authorIds} 中属于大V的子集（fanout / 重建 / 读三处共用）。
     *
     * @param authorIds 待判定作者（空 / null → 空集，且**不读任何配置、不发 SQL**）
     * @return 其中的大V子集（**不含**非大V）
     */
    @Transactional(readOnly = true)
    public Set<Long> isBigVBatch(List<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> listed = bigVProperties.userIds();
        Set<Long> bigVs = new LinkedHashSet<>();
        List<Long> toQuery = new ArrayList<>(authorIds.size());
        for (Long authorId : authorIds) {
            if (listed.contains(authorId)) {
                bigVs.add(authorId);      // 名单命中：不经阈值、也不必进 IN 列表
            } else {
                toQuery.add(authorId);
            }
        }
        if (toQuery.isEmpty()) {
            return bigVs;                  // 全部名单命中 ⇒ 无需任何 SQL
        }
        int threshold = bigVProperties.threshold();
        int batch = feedProperties.bigvQueryBatch();
        if (batch <= 0) {
            // 非正批量会让下面的分块循环无法前进 ⇒ fail-fast。**必须在 try 之外**：
            // 进了 try 会被 fail-open 捕获吞掉、静默按普通作者判定。
            // （FeedProperties 已在绑定期拒，此处是第二道守卫。）
            throw new IllegalArgumentException("video.feed.bigv-query-batch 必须为正数: " + batch);
        }
        try {
            for (int from = 0; from < toQuery.size(); from += batch) {
                int to = Math.min(from + batch, toQuery.size());
                List<Long> chunk = toQuery.subList(from, to);
                // ③-① 自动大V状态表（滞回：曾达线升为大V、掉粉后仍在带内者）
                bigVs.addAll(userDao.findAutoBigVUserIdsIn(chunk));
                // ③-② 粉丝数 ≥ 阈值（DB 真值；冷启动 / 历史数据未入状态表时的兜底路径）
                bigVs.addAll(userDao.findUserIdsByMinFollowerCount(chunk, threshold));
            }
        } catch (DataAccessException e) {
            // fail-open：整批只保留名单项（见类注释）；结论行不带栈
            log.warn("大V判定降级（状态表/粉丝数查询失败，按普通作者处理，名单项保留）, authorCount={}",
                    toQuery.size());
        }
        return bigVs;
    }
}
