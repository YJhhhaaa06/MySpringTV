package io.github.yjhhhaaa06.videoweb.user.model;

import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;

/**
 * 头像选择的**存储口径**与**出口解析**（`R-27`）。
 *
 * <h2>DB 只存选择，不存最终 URL</h2>
 * {@code users.avatar_ref} 的取值只有两种：
 * <ul>
 *   <li>{@code default:1} … {@code default:6} —— 用户选了哪张默认头像；</li>
 *   <li>{@code /upload/avatar/{uuid}.jpg} —— 自定义头像。</li>
 * </ul>
 * 对外一律由 {@link #resolveUrl(long, String)} 解析成**生效 `avatarUrl`**：
 * 未选过（{@code NULL}）⇒ 按 {@code userId % 6} 回落默认，与前端 `UserAvatar` 的历史分配一致。
 *
 * <h2>为什么默认头像也走 URL</h2>
 * 消费端（顶栏 / 空间 / 播放页侧栏 / 评论 / 动态 / 关注列表）只需要一种逻辑：
 * "有 URL 就渲染 URL"。默认头像资源放在 `frontend/public/avatars/default-1..6.svg`，
 * 路径稳定；改名 / 迁移只需改这里的 {@link #DEFAULT_URL_PREFIX}，**不必改库**。
 *
 * <h2>纯函数</h2>
 * 本类不触盘、不查库，只做字符串解析与 ID 校验，可被 UserService / ProfileService /
 * AvatarService / 批量端点共用。自定义 URL 的磁盘存在性由写入路径保证（
 * {@code FileUploadService#saveFile} 先落盘再写库）。
 */
public final class AvatarRefs {

    /** 默认头像数量（与 `frontend/public/avatars/default-1..6.svg` 一一对应）。 */
    public static final int DEFAULT_COUNT = 6;

    private static final String DEFAULT_REF_PREFIX = "default:";
    private static final String CUSTOM_REF_PREFIX = "/upload/avatar/";
    private static final String DEFAULT_URL_PREFIX = "/avatars/default-";
    private static final String DEFAULT_URL_SUFFIX = ".svg";

    private AvatarRefs() {
    }

    /**
     * 校验默认头像编号（1..6），非法 / 缺失抛 400。
     *
     * <p>服务端与前端同一份编号：`1 = sunrise / 2 = planet / 3 = cat / 4 = bird / 5 = tv / 6 = cloud`，
     * 顺序与 `UserAvatar` 的 `Math.abs(userId) % 6` 历史分配逐位一致。
     */
    public static int validateDefaultId(Integer avatarId) {
        if (avatarId == null || avatarId < 1 || avatarId > DEFAULT_COUNT) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "头像编号不合法，应为 1..6");
        }
        return avatarId;
    }

    /** 默认头像的存储值（{@code default:N}）。调用方须先 {@link #validateDefaultId}。 */
    public static String defaultRef(int avatarId) {
        return DEFAULT_REF_PREFIX + avatarId;
    }

    /** 默认头像的稳定 URL（{@code /avatars/default-N.svg}）。 */
    public static String defaultUrl(int avatarId) {
        return DEFAULT_URL_PREFIX + avatarId + DEFAULT_URL_SUFFIX;
    }

    /** 从没选过头像时的回落 URL：{@code userId % 6 + 1}（与前端历史分配一致）。 */
    public static String fallbackUrl(long userId) {
        return defaultUrl(Math.floorMod(userId, DEFAULT_COUNT) + 1);
    }

    /**
     * 把 DB 里的 {@code avatar_ref} 解析成生效 URL。
     *
     * <p>非法 / 未知值（手工改库、旧数据）一律回落 {@link #fallbackUrl(long)}，不抛异常：
     * 头像不是关键业务字段，一个坏值不该让整个资料接口 500。
     */
    public static String resolveUrl(long userId, String avatarRef) {
        if (avatarRef == null || avatarRef.isBlank()) {
            return fallbackUrl(userId);
        }
        if (avatarRef.startsWith(DEFAULT_REF_PREFIX)) {
            try {
                int id = Integer.parseInt(avatarRef.substring(DEFAULT_REF_PREFIX.length()));
                return id >= 1 && id <= DEFAULT_COUNT ? defaultUrl(id) : fallbackUrl(userId);
            } catch (NumberFormatException ignored) {
                return fallbackUrl(userId);
            }
        }
        return isCustom(avatarRef) ? avatarRef : fallbackUrl(userId);
    }

    /**
     * 是否是自定义头像 URL。
     *
     * <p>只认应用内前缀 + 禁止 {@code ..}。精确文件名形状（UUID + 后缀）由
     * {@code FileUploadService#resolveAbsolutePath} 在删除 / 落盘时再校验；
     * 这里的职责只是"不让一个手工改库的怪值被当成自定义 URL 直接输出"。
     */
    public static boolean isCustom(String avatarRef) {
        return avatarRef != null
                && avatarRef.startsWith(CUSTOM_REF_PREFIX)
                && !avatarRef.contains("..");
    }
}
