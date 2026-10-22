package io.github.yjhhhaaa06.videoweb.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 全站唯一错误码源：**字符串码 + HTTP 状态 + 默认文案**三元。
 *
 * <p>契约见 {@code architecture/tech/api/接口契约.md} §2.1–§2.2。三条要点：
 * <ol>
 *   <li><b>码是字符串</b>（= 枚举名 {@link #name()}，不另设字段 —— 两个事实源迟早漂移）。形如
 *       {@code CONTENT_NOT_FOUND}，前端拿它翻字典做精细分支；HTTP status 只表**类别**，
 *       前端据此做通用分支（401 清态、403 提示无权限、429 退避）。</li>
 *   <li><b>码与 HTTP 不再同值</b>。旧实现 {@code code = 401} 与 status 完全重复 ⇒ body 零信息增量，
 *       前端能判别的只有中文 {@code msg}。这是本类存在的原因。</li>
 *   <li><b>状态由本枚举携带</b>，{@code GlobalExceptionHandler} / 两个过滤器都不再 {@code HttpStatus.resolve(code)}。</li>
 * </ol>
 *
 * <h2>★ 粒度判据（为什么不是「每个抛出点一个码」）</h2>
 * 判据是**前端能不能据此做出不同决定**，三条任一成立才独立成码：
 * <ol>
 *   <li><b>要改流程</b>：清登录态 / 跳登录页 / 重拉资源 / 隐藏入口 / 按 {@code Retry-After} 退避；</li>
 *   <li><b>要定位目标</b>：哪个字段不合法、哪个资源没了（`field` 由 `errors[]` 给，资源种类由码给）；</li>
 *   <li><b>要按码统计</b>：限流、依赖不可用、DB 唯一键竞态（运维面判据）。</li>
 * </ol>
 * 只是「同一个 toast 换一句中文」的差异 ⇒ **同码**，差异进 {@code msg}（`T57` 起进 `errors[]`）。
 * 例：「标题不能为空」「简介不超过 5000 字」「enabled 格式错误」全部落 {@link #PARAM_INVALID} ——
 * 前端对它们的处置完全一致（把字段错误标出来），分码只会让字典膨胀而没人翻。
 *
 * <h2>★ 唯一的「同因」「多因」例外：登录失败</h2>
 * 登录时「账号不存在」与「密码错误」**必须同码同文案**（{@link #AUTH_BAD_CREDENTIALS}）：
 * 分开回答等于给出「这个手机号注册过没有」的探测口 —— 一个脚本就能批量筛出全站已注册手机号。
 * 这与收藏域「收藏夹不存在 / 收藏夹不公开 ⇒ 同 404 同文案」是同一条原则，不是疏漏。
 * ⚠️ 因此**不要**把 {@code AUTH_BAD_CREDENTIALS} 拆回两个码，也不要给 {@link #AUTH_USER_NOT_FOUND}
 * 用在登录路径上（那个码只用于「持有有效令牌但账号已被删」这种自查场景）。
 *
 * <p>代码里出现过的旧名与去向：{@code PARAM_ERROR→PARAM_INVALID}、{@code UNAUTHORIZED→AUTH_UNAUTHORIZED}、
 * {@code WRONG_PASSWORD}（拆成 {@link #AUTH_BAD_CREDENTIALS} 与 {@link #AUTH_OLD_PASSWORD_WRONG}）、
 * {@code TOKEN_EXPIRED→AUTH_TOKEN_EXPIRED}、{@code FORBIDDEN/ACCESS_DENIED→AUTH_FORBIDDEN}、
 * {@code NOT_FOUND}（泛码，删：每个抛出点必须指名资源种类）、
 * {@code CONFLICT}（泛码，拆成具体原因）、{@code DUPLICATE_LIKE→LIKE_ALREADY_LIKED}、
 * {@code SERVER_ERROR→SYSTEM_INTERNAL}、{@code DATABASE_ERROR→SYSTEM_DATABASE}、
 * {@code SERVICE_UNAVAILABLE→SYSTEM_UNAVAILABLE}、
 * {@code INVALID_PHONE}/{@code CACHE_ERROR}（删，理由见各自位置）。
 */
public enum ErrorCode {

    // ========================================================================
    // 400 参数（字段缺失 / 格式 / 取值不合法 / @Valid 失败）
    // ========================================================================

    /**
     * 参数不合法（400）。
     *
     * <p>★ 这一族**刻意**只用一个码：前端对它的处置恒为「把字段错误展示出来」，
     * 字段级定位由 {@code T57} 的 {@code errors[].field} 给，不靠码。
     *
     * <p>⚠️ 旧 {@code INVALID_PHONE}（手机号格式错误）**删除**：注册/登录的手机号格式由
     * {@code @Valid} + {@code errors[]} 承接，单独一个码只会让前端多写一条永远走同一分支的 switch。
     * <p>⚠️ 密码格式（{@code ^[a-zA-Z0-9]{6,16}$}）同理：注册与改密的 {@code *Request} 都带 {@code @Pattern}，
     * 实际由 {@code @Valid} 拦下 —— 若再给一个 {@code USER_PASSWORD_INVALID}，同一个原因会因
     * "被哪一层拦下"而给出两个码（{@code @Valid} 走 {@code PARAM_INVALID}、Service 内部守卫走另一个），
     * 前端字典里出现同义两条。故 {@code InvalidPasswordException} 也携带本码。
     */
    PARAM_INVALID(HttpStatus.BAD_REQUEST, "参数错误"),

    // ========================================================================
    // 401 认证（未登录 / 凭证无效 / 凭证过期）
    // ========================================================================

    /** 未带令牌或令牌无法解析（401）—— 前端唯一正确处置：清登录态 + 跳登录页。 */
    AUTH_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "请先登录"),

    /** 令牌已过期（401）—— 与「未登录」分开：前端要提示「已过期」而不是「请登录」，双 Token 方案落地时按它判断刷新。 */
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录"),

    /**
     * 登录失败（401）—— **账号不存在与密码错误共用此码，且文案也共用**。
     *
     * @see ErrorCode 类注释「唯一的「同因」「多因」例外」
     */
    AUTH_BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "账号或密码错误"),

    /**
     * 修改密码时「原密码不匹配」（401）。
     *
     * <p>与 {@link #AUTH_BAD_CREDENTIALS} **必须分开**：操作者此时已通过令牌证明身份，
     * 报「原密码错」不构成任何探测口，而前端要据此把错误挂到「原密码」输入框上。
     */
    AUTH_OLD_PASSWORD_WRONG(HttpStatus.UNAUTHORIZED, "原密码错误"),

    /**
     * 令牌有效但账号已不存在（401）。
     *
     * <p>只出现在「用持有者自己的令牌查自己」（{@code /user/me}、改密）的路径上 ——
     * 请求者本来就持有该 userId 的令牌，报出来不增加任何信息量；前端处置 = 清态重新登录。
     * ⚠️ **不许**用在登录路径（那会变成手机号探测口，见 {@link #AUTH_BAD_CREDENTIALS}）。
     */
    AUTH_USER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "用户不存在"),

    // ========================================================================
    // 403 授权（已认证但无权）
    // ========================================================================

    /**
     * 无权操作该资源（403）。
     *
     * <p>合并了旧的 {@code FORBIDDEN} 与 {@code ACCESS_DENIED}（两者文案相同、语义相同，是同一原因的两个名字）。
     * 具体是哪条规则拒绝（不是自己的评论 / 作品 / 收藏夹）只影响文案，前端处置恒为「提示无权限」。
     */
    AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "无权访问"),

    // ========================================================================
    // 404 资源不存在（按资源种类分码：前端要能区分「内容没了」与「夹没了」）
    // ========================================================================

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "用户不存在"),
    CONTENT_NOT_FOUND(HttpStatus.NOT_FOUND, "内容不存在"),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "评论不存在"),
    FAVORITE_FOLDER_NOT_FOUND(HttpStatus.NOT_FOUND, "收藏夹不存在"),
    CONTENT_MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND, "媒体资源不存在"),

    // ========================================================================
    // 409 状态冲突（参数合法，但当前状态不允许）
    // ========================================================================

    /** 注册时手机号已被占用（409）。⚠️ 这是**已知**的手机号探测面之一，见 §2.2 的「已知代价」。 */
    USER_PHONE_TAKEN(HttpStatus.CONFLICT, "手机号已被使用"),

    USER_NAME_TAKEN(HttpStatus.CONFLICT, "用户名已被占用"),

    /** 不可重复点赞（409）—— 旧名 {@code DUPLICATE_LIKE}（死码）。前端据此纠正本地按钮态，必须独立成码。 */
    LIKE_ALREADY_LIKED(HttpStatus.CONFLICT, "不可重复点赞"),

    /** 未点赞却取消（409）—— 同上，前端据此纠正本地态。 */
    LIKE_NOT_LIKED(HttpStatus.CONFLICT, "未点赞，无法取消"),

    /** 不能关注/取关自己（409）。 */
    FOLLOW_SELF(HttpStatus.CONFLICT, "不能关注自己"),

    /** 已关注（409）—— 前端纠正本地态。 */
    FOLLOW_ALREADY(HttpStatus.CONFLICT, "已关注，不可重复操作"),

    /** 未关注却取关（409）—— 前端纠正本地态。 */
    FOLLOW_NOT_FOLLOWING(HttpStatus.CONFLICT, "未关注，不可取消"),

    /** 对该稿件投币枚数已用完（409）—— 前端要把投币入口置灰。 */
    COIN_ALREADY_FULL(HttpStatus.CONFLICT, "对本稿件投币枚数已用完"),

    /** 评论区已关闭（409）—— 前端要隐藏输入框。 */
    COMMENT_DISABLED(HttpStatus.CONFLICT, "评论区已关闭"),

    /** 被回复的评论不属于该内容（409）—— 与「不存在」分开：这里目标存在，是关系不合法。 */
    COMMENT_REPLY_TARGET_INVALID(HttpStatus.CONFLICT, "被回复评论不属于该内容"),

    /** 默认收藏夹不可删除（409）—— 前端要隐藏该夹的删除入口。 */
    FAVORITE_FOLDER_DEFAULT(HttpStatus.CONFLICT, "默认收藏夹不可删除"),

    /**
     * 内容状态不允许该操作（409）：已删除无法下架 / 已下架 / 已删除无法恢复 / 未下架。
     *
     * <p>四种原因共用一码的理由：处置相同（**重拉列表再判断**），差异只在文案；
     * 它们全是后台运营动作，前端不需要按原因分支。
     */
    CONTENT_STATUS_CONFLICT(HttpStatus.CONFLICT, "内容当前状态不允许该操作"),

    /** 优惠券不可领（409）：库存不足 / 活动未开始 / 已结束。 */
    COUPON_UNAVAILABLE(HttpStatus.CONFLICT, "库存不足或活动未开始/已结束"),

    /**
     * DB 唯一键竞态兜底（409）。
     *
     * <p>业务层已先查重，并发下仍可能撞唯一键（{@code users.phone} / {@code users.username} /
     * 点赞、收藏、关注、优惠券的联合唯一键）。{@code GlobalExceptionHandler} 拿到的是
     * {@code DuplicateKeyException}，**无法知道撞的是哪个键** —— 硬猜会把「用户名占用」错报成「手机号占用」。
     * 故用 SYSTEM 前缀如实表达「基础设施层冲突，请换值或重试」。
     *
     * <p>判据 ③（运维统计）：出现本码说明「先查后插」的窗口被穿越了，值得单独统计。
     */
    SYSTEM_DUPLICATE_KEY(HttpStatus.CONFLICT, "数据已存在，请重试"),

    // ========================================================================
    // 400 域内约束（参数合法但业务上不允许；与 PARAM_INVALID 分开是因为前端要给出**不同**引导）
    // ========================================================================

    /** 硬币不足（400）—— 前端可引导「去签到领币」（签到端点幂等、可重复调用）。 */
    COIN_INSUFFICIENT(HttpStatus.BAD_REQUEST, "硬币不足"),

    /** 该内容不支持投币（400）—— 图文帖不能投币，前端要隐藏入口。 */
    COIN_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "该内容不支持投币"),

    /**
     * 不能给自己投币（400）。
     *
     * <p>★ 状态沿用 coin 域既有口径（400），**不**对齐 follow 域的「不能关注自己」（409）：
     * coin 的拒因七级顺序是域文档冻结的契约（{@code architecture/domain/coin/coin.md} §五.1），
     * 码表任务只负责「说清是哪条原因」，不动状态类别。
     */
    COIN_SELF_FORBIDDEN(HttpStatus.BAD_REQUEST, "不能给自己投币"),

    /** 收藏夹数量已达上限（400）—— 前端引导「先删一个夹」。 */
    FAVORITE_FOLDER_LIMIT(HttpStatus.BAD_REQUEST, "收藏夹数量已达上限"),

    /** 单个收藏夹已满（400）—— 与「夹数量上限」分开：前者引导清理**条目**，后者引导删**夹**。 */
    FAVORITE_FOLDER_FULL(HttpStatus.BAD_REQUEST, "收藏夹已满"),

    /** 稍后再看已满（400）。 */
    WATCHLATER_FULL(HttpStatus.BAD_REQUEST, "稍后再看已满"),

    /** 稍后再看只收视频（400）—— 前端要按内容类型禁用入口。 */
    WATCHLATER_TYPE_INVALID(HttpStatus.BAD_REQUEST, "稍后再看只支持视频"),

    /** 只能记录视频的播放进度（400）。 */
    HISTORY_TYPE_INVALID(HttpStatus.BAD_REQUEST, "只能记录视频的播放进度"),

    /** 内容类型不合法（400）。 */
    CONTENT_TYPE_INVALID(HttpStatus.BAD_REQUEST, "不支持的内容类型"),

    /** 分区不存在（400）。 */
    CONTENT_CATEGORY_INVALID(HttpStatus.BAD_REQUEST, "分区不存在"),

    /** 上传文件类型不支持（400）。 */
    UPLOAD_TYPE_INVALID(HttpStatus.BAD_REQUEST, "文件类型不支持"),

    /** 改密时填的手机号不是本人（400）—— 与「旧密码错」严格分开，顺序是冻结契约（见 {@code UserService#changePassword}）。 */
    USER_PHONE_MISMATCH(HttpStatus.BAD_REQUEST, "手机号不匹配"),

    // ========================================================================
    // 429 限流
    // ========================================================================

    /**
     * 请求过于频繁（429），响应带 {@code Retry-After}。
     *
     * <p>⚠️ 实际出口**不看本枚举的 status**：限流发生在过滤器链上（{@code DispatcherServlet} 之前），
     * {@code @RestControllerAdvice} 覆盖不到 ⇒ 由 {@code RateLimitFilter} 自写信封 + 头。
     * 本项是**对外契约的声明**与过滤器取值的唯一来源。
     */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),

    // ========================================================================
    // 5xx
    // ========================================================================

    /**
     * 依赖不可用 / 服务繁忙（503），响应带 {@code Retry-After}。
     *
     * <p>与「缓存挂了就降级直查 DB」的常态**刻意不同**：写路径（播放记录上报缓冲）在 Redis 不可用时
     * 降级成直写 DB，会在故障瞬间把全部心跳压进数据库 —— 正是该方案要避免的事 ⇒ 快速失败 + 让客户端退避。
     */
    SYSTEM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "服务繁忙，请稍后重试"),

    /** 未预期异常（500）。**不**把内部细节泄露到响应体。 */
    SYSTEM_INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误"),

    /**
     * 数据访问异常（500）。
     *
     * <p>⚠️ 旧 {@code CACHE_ERROR}（缓存操作失败）**删除**：本仓缓存故障一律「降级直查 DB」，
     * Redis 出错从不走到对外响应（见《缓存与数据一致性》）。留一个永远触发不了的码，
     * 只会让下一个人以为存在「缓存错误对外可见」这条路径。
     */
    SYSTEM_DATABASE(HttpStatus.INTERNAL_SERVER_ERROR, "数据库操作失败");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    /**
     * 对外错误码 = 枚举名。
     *
     * <p>不另设 {@code String code} 字段：两个名字迟早会漂移，而漂移只会在前端翻不到字典时才被发现。
     * 形态与白名单由 {@code ErrorCodeContractTests} 钉住。
     */
    public String getCode() {
        return name();
    }

    /** 对应的 HTTP 状态类别（前端通用分支用）。 */
    public HttpStatus getStatus() {
        return status;
    }

    /** 默认中文文案：`msg` 的兜底值，可被业务侧更具体的文案覆盖。 */
    public String getMessage() {
        return message;
    }
}
