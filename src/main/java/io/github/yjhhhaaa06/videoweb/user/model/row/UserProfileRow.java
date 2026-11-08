package io.github.yjhhhaaa06.videoweb.user.model.row;

import lombok.Data;

import java.time.LocalDate;

/**
 * {@code GET /api/v1/users/me} 的**端点专属读模型**（2026-11-08 合并读路径时新增）。
 *
 * <h2>它解决什么</h2>
 * 该端点原来在一个 {@code @Transactional(readOnly = true)} 里发 4 条主键点查
 * （资料 / 硬币 / 签到日 / 记录开关）。基线实测（MySQL 8.4 · 10 万 users · 5000 次单连接循环）：
 * 每次请求 = <b>7 次数据库往返</b>（{@code SET autocommit=0} + 4 × SELECT + {@code COMMIT}
 * + {@code SET autocommit=1}），p50 <b>2917 µs</b>；合并成一条后 p50 <b>444 µs</b>（−85%）。
 * 同一轮实测里"多读一列"的服务端代价是 0（8 列 71.6 µs vs 7 列 76.1 µs，同一行同一页）
 * ⇒ 成本在**往返数**，不在列数。完整分解见 {@code UserService#getProfile} 的注释。
 *
 * <h2>★ 为什么是新类型，而不是给 {@link io.github.yjhhhaaa06.videoweb.user.model.entity.User} 加列</h2>
 * {@code User} 的列集被 {@code findByIdForLogin}（{@code select *}）、{@code findUsersByIds}、
 * {@code findAvatarRefsByIds} 共用，且 {@code findByIdForProfile} 还被
 * {@code ProfileService}（个人主页）与 {@code AvatarService}（存在性校验，两处）调用 ——
 * 给它加列 = 同时改这三处调用点的列集契约。本类的字段集**只由 {@code UserInfoVO} 决定**，
 * 端点要什么就映什么，"这个端点需要知道签到日 / 开关"这件事因此仍留在实现里。
 *
 * <h2>★ 它是投影，不是实体</h2>
 * 没有 {@code hashed_password}、没有 {@code phone}、没有计数列 —— 不是"查得少"，是**本来就不该读**。
 * 不要把它当成 {@code User} 的替代品加字段：它是 {@code /users/me} 的响应源，加列前先问
 * "VO 里有没有这个字段"。
 */
@Data
public class UserProfileRow {

    /** 主键（同时是 {@code avatar_ref} 回落默认头像时的取模基数）。 */
    private Long id;

    private String username;

    /** 头像**选择**（{@code default:N} / {@code /upload/avatar/...} / {@code NULL}），不是生效 URL。 */
    private String avatarRef;

    /** 0=普通 1=管理员（口径同 {@code User#role}）。 */
    private Integer role;

    /** 硬币余额的**十分位整数**（值 = 币 × 10）；列 {@code NOT NULL}。 */
    private Integer coinDeci;

    /** 最近签到日；从未签到 ⇒ {@code null}（这是**正常的**读结果，不是缺列）。 */
    private LocalDate lastSignDate;

    /** 是否记录观看历史（{@code R-25}）；列 {@code NOT NULL DEFAULT 1}。 */
    private Boolean historyRecording;

    /** 管理员判定。与 {@code User#isAdmin()} **同口径**（{@code role == 1}）。 */
    public boolean isAdmin() {
        return role != null && role == 1;
    }
}
