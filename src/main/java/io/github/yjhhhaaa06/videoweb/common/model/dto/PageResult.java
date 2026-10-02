package io.github.yjhhhaaa06.videoweb.common.model.dto;

import java.util.List;

/**
 * 分页信封（跨域共享）。
 *
 * <p>承接 TV {@code com.itheima.common.model.dto.PageResult}（T14 从 content 上移到公共包，
 * 成为**全项目唯一**的分页信封）。
 *
 * <h2>字段与推导公式逐字不变（冻结契约）</h2>
 * JSON 形状 = {@code {list, total, page, pageSize, totalPages}}。
 * {@code totalPages} 由 {@code total} 与 {@code pageSize} 推导（{@code pageSize <= 0} 时为 0）。
 * 旧 pytest {@code TestFollowListPagination} 对键集合做了**逐字断言**
 * （{@code set(data.keys()) == {list,total,page,pageSize,totalPages}}），
 * 且对"缺省（不传分页参数）== 显式 {@code page=1&pageSize=100} 响应**逐字节一致**"有断言 ⇒
 * 字段名、类型、推导公式都不得改动（见《事务边界决策表》F-3）。
 *
 * <p>用 {@code record} 而非 TV 的可变 DTO：本类只被构造一次、随即被序列化，没有可变需求；
 * 不可变也让"信封在事务外组装"这一事实（F-3）在类型上成立。
 *
 * @param list       当前页的条目
 * @param total      条目总数（与页内容**同源**，不得来自另一次快照计数）
 * @param page       当前页码（已归一，≥1）
 * @param pageSize   页大小（已归一，1..域级上限）
 * @param totalPages 总页数 = {@code ceil(total / pageSize)}；{@code pageSize <= 0} 时为 0
 */
public record PageResult<T>(List<T> list, int total, int page, int pageSize, int totalPages) {

    /** 便捷构造：{@code totalPages} 按 TV 公式推导。 */
    public PageResult(List<T> list, int total, int page, int pageSize) {
        this(list, total, page, pageSize, pageSize > 0 ? (total + pageSize - 1) / pageSize : 0);
    }
}
