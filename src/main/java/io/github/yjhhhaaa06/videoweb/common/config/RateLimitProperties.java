package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 限流配置（限流专项 <b>T23</b>；口径的唯一事实源 = {@code architecture/tech/rate-limit/限流.md}）。
 *
 * <p>与 {@link FavoriteLimitProperties} / {@link RedisBreakerProperties} 同款：标准配置绑定 +
 * 环境变量可覆盖，放在 {@code common.config}（本项目的 {@code @ConfigurationProperties} 记录
 * 都收在这里，与域代码分离），由 {@code @ConfigurationPropertiesScan} 自动注册。
 *
 * <h2>结构：一个 record + 三段嵌套（与 YAML 层级一一对应）</h2>
 * <pre>{@code
 * video.rate-limit:
 *   server: capacity / refill-per-sec / task-cost      →  Server
 *   user:   authed / anon : capacity / refill-per-sec  →  User{authed,anon: Bucket}
 *   cost:   base / default-base / miss-multiplier / reject-discount  →  Cost
 * }</pre>
 * ★ <b>嵌套 record 是能绑的</b>（Boot 4.1.1 实测：{@code Binder} 直绑与真实
 * {@code @ConfigurationProperties} 两条路径都绑上，层层非 {@code null}）——
 * 本仓 {@code application.yaml} 里 feed 段那句"record 只认本前缀下的扁平键、嵌套会被静默忽略
 * 并绑成 0"**经实测不成立**（登记为 {@code I-15}，见 {@code architecture/tech/rate-limit/限流.md} §二）。
 * 这里选择嵌套而非拆成四个类：它与 YAML 层级**字面对应**，"哪个键属于哪一段"不用靠命名去猜。
 *
 * <h2>★ 数值全是<b>拍的</b>（本仓无真实流量）</h2>
 * 每个数值的「推导锚点」与「推翻条件」登记在 {@code architecture/tech/rate-limit/限流.md}。
 * 三条量级锚点（别当成实测基线读）：
 * <ul>
 *   <li>{@code server.capacity = 200} ≈ 容器默认最大工作线程数（"瞬时在途请求上限"），
 *       {@code refill-per-sec = 50} = capacity / 4（空桶 4 秒补满）；</li>
 *   <li>{@code user.authed = 20/2}（"正常人远达不到每分钟 120 次"）；{@code anon = 60/6}
 *       —— IP 共享（NAT / 内网）⇒ <b>刻意比登录桶宽 3 倍</b>；</li>
 *   <li>{@code cost} 三段按"是否触达 DB 写 + 是否重活"排序：登录 / 注册 5、上传 10、写路径 3、
 *       未登记路由落 {@code default-base = 1}。</li>
 * </ul>
 * 改数**不动代码**（同 {@code video.favorite.*} 的纪律）——除了下面两条与结构绑定的口径。
 *
 * <h2>★ 绑定期校验（非法即拒绝启动）</h2>
 * 原生类型（{@code int} / {@code double}）**键名写错时不会报错，只会绑成 0 / 0.0**
 * ⇒ 所有"必须为正"的量在构造期响亮失败（同 {@link FavoriteLimitProperties} 的纪律）。
 * 三条<b>不是</b>简单"必须为正"的口径，各自的理由：
 * <ol>
 *   <li>{@code cost.base} 的 <b>key 形态</b>必须是「{@code <方法> <以 / 开头的路径模式>}」——
 *       ⚠️ <b>这条不是洁癖，是实测到的静默陷阱</b>：Spring 绑 {@code Map} 时，键里除小写字母数字
 *       与 {@code -} 之外的字符会被**删掉**，于是 YAML 里若忘了用 {@code [ ]} 括键，
 *       {@code POST /api/v1/favorites} 会**静默变成** {@code POSTapiv1favorites}（无任何报错，
 *       只是那条权重永远命不中、全落 {@code default-base}）。把形态钉成断言后，
 *       这类"漏写方括号"会在**启动时**炸出来。</li>
 *   <li>{@code cost.miss-multiplier} 必须 {@code >= 1.0}：它是"缓存 miss 更贵"的乘数，
 *       {@code 1.0 = 关闭}（沿用本仓"0 / 1 = 关闭"惯例）；小于 1 等于"miss 更便宜"，
 *       与信号②的语义相反。</li>
 *   <li>{@code cost.reject-discount} 必须落在 {@code [0,1]}：{@code 0} = 被拒<b>免费探路</b>、
 *       {@code 1} = 用户为"服务器过载"**全额买单**；两端都是设计上明确不取的边界。</li>
 * </ol>
 *
 * @param server 服务器桶（全局单桶，"单实例整体还能接多少活"）
 * @param user   用户桶（per-key 配额：登录态 {@code user:{id}} / 匿名态 {@code anon:{ip}}）
 * @param cost   成本信号①（端点权重表）+ 信号②（缓存 miss 乘数）+ 被拒折扣
 */
@ConfigurationProperties(prefix = "video.rate-limit")
public record RateLimitProperties(Server server, User user, Cost cost) {

    public RateLimitProperties {
        require(server, "video.rate-limit.server");
        require(user, "video.rate-limit.user");
        require(cost, "video.rate-limit.cost");
    }

    /**
     * 服务器桶（§三 的记账主体之一；<b>唯一</b>会被"任务主体"扣的桶）。
     *
     * @param capacity      容量（瞬时在途请求上限；首版 200，锚点 = 容器默认最大工作线程数）
     * @param refillPerSec  每秒补充速率（首版 50 = capacity / 4）
     * @param taskCost      单个昂贵后台任务在<b>执行处</b>无条件扣给服务器桶的量（首版 10；
     *                      口径 = "一次收件箱重建 ≈ 十次普通读"）。⚠️ 任务的成本**不属于任何用户**
     *                      （多个用户被去抖合并成一次重建）⇒ 只扣服务器桶、不碰用户桶。
     */
    public record Server(int capacity, int refillPerSec, int taskCost) {
        public Server {
            requirePositive(capacity, "video.rate-limit.server.capacity");
            requirePositive(refillPerSec, "video.rate-limit.server.refill-per-sec");
            requirePositive(taskCost, "video.rate-limit.server.task-cost");
        }
    }

    /**
     * 单个用户桶的两个参数。
     *
     * @param capacity     容量（首版：登录 20 / 匿名 60）
     * @param refillPerSec 每秒补充速率（首版：登录 2 / 匿名 6）
     */
    public record Bucket(int capacity, int refillPerSec) {
        public Bucket {
            requirePositive(capacity, "video.rate-limit.user.*.capacity");
            requirePositive(refillPerSec, "video.rate-limit.user.*.refill-per-sec");
        }
    }

    /**
     * 用户桶（两种 key 形态各自一套：登录态按 {@code userId}、匿名态按客户端 IP）。
     *
     * <p>★ 匿名桶**必须比登录桶宽**（首版 3 倍）：IP 是**共享**的（NAT / 内网 / 校园网下几十个用户
     * 共用一个出口 IP），按登录桶的口径会**误伤整片人**。这是"匿名单独建桶"相对"只吃服务器桶"的代价面，
     * 已知代价见 {@code architecture/tech/rate-limit/限流.md}。
     *
     * @param authed 登录态用户桶（key = {@code user:{userId}}）
     * @param anon   匿名态用户桶（key = {@code anon:{ip}}）
     */
    public record User(Bucket authed, Bucket anon) {
        public User {
            require(authed, "video.rate-limit.user.authed");
            require(anon, "video.rate-limit.user.anon");
        }
    }

    /**
     * 成本信号与折扣（口径见 {@code architecture/tech/rate-limit/限流.md} §二 / §四）。
     *
     * @param base           权重表：key = 「{@code <方法> <路径模式>}」（Spring 路由模式，非具体 id，
     *                       如 {@code POST /api/v1/favorites}、{@code PUT /api/v1/favorite-folders/{id}}
     *                       —— 模式里的 {@code {id}} **原样写**），value = 该端点一次的令牌数。
     *                       ★ YAML 里**必须**写成 {@code "[PUT /api/v1/favorite-folders/{id}]": 3}（方括号！）——
     *                       理由见类注释校验清单第 1 条（漏写会被静默改写）。
     *                       <p>⚠️ <b>空表是合法的</b>（等价于"全部走 default-base"）——
     *                       故意不拒空，是为了保留"只按兜底单位限"这条合法配置；而"漏配某个路由"
     *                       本就有 {@code default-base} 兜底，不会变成"不限"。
     * @param defaultBase    未登记路由的兜底权重（首版 1）。★ **不是 0** —— "漏配 = 不限"正是要防的。
     * @param missMultiplier 信号②：本次请求发生缓存 miss 时的乘数。**T26 起已启用 3.0**
     *                       （锚点"miss 一次 ≈ 三次命中"）；{@code 1.0 = 关闭}（此时连一次往返都不花）；
     *                       落地为**门后差额** {@code base × (m−1)} 补扣（见 {@code architecture/tech/rate-limit/限流.md} §四）
     * @param rejectDiscount 被拒请求仍扣的"询问费"比例（首版 0.3；口径见 {@code architecture/tech/rate-limit/限流.md} §二）：
     *                       {@code 0} 则被拒请求可零成本探路、{@code 1} 则用户为服务器过载全额买单。
     *                       ⚠️ {@code 0} 是**合法值**（故本键键名写错会静默绑成 0.0 而绕过下面的区间校验）
     *                       —— 该洞由 {@code RateLimitPropertiesBindingTests#被拒折扣落在开区间} 兜住
     *                       （口径取开区间 {@code (0,1)}：被拒既不能免费、也不能全款；改键名或改成 0 必红，已实测）。
     */
    public record Cost(Map<String, Integer> base, int defaultBase, double missMultiplier, double rejectDiscount) {

        /** 权重表 key 的合法形态：HTTP 方法 + 一个空格 + 以 {@code /} 开头的路径模式。 */
        private static final Pattern ROUTE_KEY = Pattern.compile("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS) /.+$");

        public Cost {
            // 整段缺失 ⇒ null；此处归一成空表（合法），避免后续 NPE。
            // ⚠️ 先校验再 copyOf：Map.copyOf 对 null key/value 抛裸 NPE，会把"哪条配错了"一起吞掉。
            Map<String, Integer> raw = base == null ? Map.of() : base;
            requirePositive(defaultBase, "video.rate-limit.cost.default-base");
            raw.forEach((key, weight) -> {
                if (key == null || !ROUTE_KEY.matcher(key).matches()) {
                    throw new IllegalArgumentException(
                            "video.rate-limit.cost.base 的 key 必须是「<方法> <以 / 开头的路径模式>」，当前值 ["
                                    + key + "]：若 YAML 里写的是 POST /xxx 而这里没有空格，"
                                    + "说明**漏用了方括号**（Spring 绑 Map 时会静默删掉键里的空格与斜杠，"
                                    + "见 RateLimitProperties 类注释）");
                }
                if (weight == null || weight <= 0) {
                    throw new IllegalArgumentException(
                            "video.rate-limit.cost.base[" + key + "] 必须为正（0 等于对该端点不限流）");
                }
            });
            base = Map.copyOf(raw);
            if (Double.isNaN(missMultiplier) || missMultiplier < 1.0) {
                throw new IllegalArgumentException(
                        "video.rate-limit.cost.miss-multiplier 必须 ≥ 1.0（1.0 = 关闭信号②）："
                                + missMultiplier);
            }
            if (Double.isNaN(rejectDiscount) || rejectDiscount < 0 || rejectDiscount > 1) {
                throw new IllegalArgumentException(
                        "video.rate-limit.cost.reject-discount 必须落在 [0,1]（0 = 被拒免费探路、"
                                + "1 = 用户全额买单）：" + rejectDiscount);
            }
        }
    }

    private static void requirePositive(int value, String key) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " 必须为正（当前值 " + value + "）");
        }
    }

    private static void require(Object segment, String key) {
        if (segment == null) {
            throw new IllegalArgumentException(key + " 整段缺失：请检查 application.yaml 里的键名与缩进"
                    + "（原生类型键名写错不会报错，只会绑成默认值）");
        }
    }
}
