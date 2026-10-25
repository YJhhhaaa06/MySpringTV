package io.github.yjhhhaaa06.videoweb.common.exception;

import io.github.yjhhhaaa06.videoweb.common.web.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码表**结构契约**（`T55`）：形态 / 白名单 / 状态合法 / 默认文案 / **无死码**。
 *
 * <p>这类断言为什么值得单写一个类：码表是"全站唯一码源"，四个出口（advice、两个过滤器、
 * 访问日志）都从这里取值 —— 一个新码写错形态或永远没人产，**不会有任何编译期报错**，
 * 只有前端翻不到字典时才被发现，而那时已经上线了。
 *
 * <h2>「无死码」为何用**源码扫描**而不是逐个跑用例</h2>
 * 旧码表 20 项里有 6 项从未被引用（{@code INVALID_PHONE} / {@code ACCESS_DENIED} /
 * {@code CONTENT_NOT_FOUND} / {@code COMMENT_NOT_FOUND} / {@code DUPLICATE_LIKE} / {@code CACHE_ERROR}），
 * 它们之所以活着只是因为"枚举里放着不碍事"。死码的真正判据是**有没有产码点**：
 * 本用例扫 {@code src/main/java} 全量源码，要求每个码在 {@link ErrorCode} 之外至少被引用一次。
 *
 * <p>⚠️ 边界（如实写明，别当它证明了更多）：它证明的是"**生产代码会产生这个码**"，
 * **不是**"该码在运行期可达/有用例触发"。运行期触发的代表性子集由各域 HTTP 用例覆盖
 * （登录 / 点赞 / 关注 / 投币 / 收藏 / 评论 / 限流 / 访问日志），清单见 `T55` 回写。
 */
class ErrorCodeContractTests {

    /** 码形态：至少两段、全大写、下划线分隔（{@code architecture/tech/api/接口契约.md} §2.2）。 */
    private static final java.util.regex.Pattern SHAPE =
            java.util.regex.Pattern.compile("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)+$");

    /**
     * 域前缀白名单（§2.2）。新域先加白名单，再造码。
     *
     * <p>`T55` 补了两项（登记 `R-22`）：
     * <ul>
     *   <li>{@code FOLLOW} —— §2.2 的定稿表**漏了既有关注域**（14 项里有 WATCHLATER / HISTORY 却没有 FOLLOW），
     *       本用例第一次跑就红了，属于该护栏挣到的第一分；</li>
     *   <li>{@code PARAM} —— **横切命名空间**，不是业务域：{@code PARAM_INVALID} 覆盖全站
     *       "字段缺失 / 格式不合法"这一族，前端处置恒为「把字段错误标出来」。</li>
     *   <li>{@code IDEMPOTENCY}（`T66`）—— **横切命名空间**：幂等键的三个结果
     *       （不合法 / 处理中 / 载荷冲突）与任何业务域无关，且三者的客户端处置各不相同
     *       （修 bug / 按 Retry-After 重试 / 换 key）。</li>
     * </ul>
     */
    private static final Set<String> PREFIXES = Set.of(
            "AUTH", "USER", "CONTENT", "COMMENT", "LIKE", "FAVORITE", "WATCHLATER", "COIN",
            "HISTORY", "COUPON", "UPLOAD", "ADMIN", "RATE", "SYSTEM", "FOLLOW", "PARAM",
            "IDEMPOTENCY");

    @Test
    @DisplayName("码形态 = 大写多段 + 域前缀在白名单内（写成 401 或 camelCase 都能编译，只有前端会翻不到）")
    void 码形态与白名单() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(SHAPE.matcher(code.getCode()).matches())
                    .as("%s 形态不合格：要求 %s（§2.2）", code.name(), SHAPE.pattern())
                    .isTrue();

            String prefix = code.getCode().substring(0, code.getCode().indexOf('_'));
            assertThat(PREFIXES)
                    .as("%s 的前缀 %s 不在白名单（§2.2：新域先加白名单再造码）", code.name(), prefix)
                    .contains(prefix);
        }
    }

    @Test
    @DisplayName("码不是 HTTP 状态的数字副本（否则 body 零信息增量，退回 T55 之前的老形态）")
    void 码不是数字副本() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.getCode())
                    .as("%s 的码不能是纯数字 —— 那就是旧实现 code == status 的形态", code.name())
                    .doesNotMatch("^\\d+$");
        }
    }

    @Test
    @DisplayName("每个码都有合法 4xx/5xx 状态与非空默认文案")
    void 状态与默认文案() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.getStatus().is4xxClientError() || code.getStatus().is5xxServerError())
                    .as("%s 的状态必须是 4xx/5xx（成功码是 %s，不在这张表里）", code.name(), ApiResponse.OK)
                    .isTrue();
            assertThat(code.getMessage()).as("%s 的默认文案不能为空（msg 兜底用它）", code.name()).isNotBlank();
        }
    }

    @Test
    @DisplayName("★ 无死码：每个码在 src/main 里至少有一个产码点（反向验证见类注释）")
    void 无死码() {
        String sources = readAllMainSources();

        Set<String> dead = new LinkedHashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            // 排除枚举自身的声明/注释；只要在别处出现过就说明有产码点或出口引用
            if (!sources.contains("ErrorCode." + code.name())) {
                dead.add(code.name());
            }
        }
        assertThat(dead)
                .as("这些码没有任何产码点（死码）。要么接线，要么删除 —— 留一个永远触发不了的码，"
                        + "只会让下一个人以为存在那条错误路径")
                .isEmpty();
    }

    @Test
    @DisplayName("码表 = 一份：同一状态下的原因不共用码（抽样钉住 T55 明确拆开的几组）")
    void 同状态不同原因不共用码() {
        // 401 组的四个原因必须可分（旧实现四项共用 code=401）
        List<String> auth401 = List.of(
                ErrorCode.AUTH_UNAUTHORIZED.getCode(), ErrorCode.AUTH_TOKEN_EXPIRED.getCode(),
                ErrorCode.AUTH_BAD_CREDENTIALS.getCode(), ErrorCode.AUTH_OLD_PASSWORD_WRONG.getCode(),
                ErrorCode.AUTH_USER_NOT_FOUND.getCode());
        assertThat(auth401).doesNotHaveDuplicates();
        assertThat(auth401).allSatisfy(c -> assertThat(c).startsWith("AUTH_"));

        // 404 组按资源种类分码（旧实现只有一枚泛码 NOT_FOUND）
        assertThat(List.of(ErrorCode.CONTENT_NOT_FOUND.getCode(), ErrorCode.COMMENT_NOT_FOUND.getCode(),
                        ErrorCode.USER_NOT_FOUND.getCode(), ErrorCode.FAVORITE_FOLDER_NOT_FOUND.getCode(),
                        ErrorCode.CONTENT_MEDIA_NOT_FOUND.getCode()))
                .doesNotHaveDuplicates();

        // 409 组：手机号占用 / 用户名占用 必须分开（前端提示不同；`T59` 起点赞/关注的冲突码已删）
        assertThat(ErrorCode.USER_PHONE_TAKEN.getCode()).isNotEqualTo(ErrorCode.USER_NAME_TAKEN.getCode());
    }

    /** 读 {@code src/main/java} 全量源码（surefire 的 user.dir = 模块根）。 */
    private static String readAllMainSources() {
        Path root = Path.of("src", "main", "java");
        assertThat(Files.isDirectory(root)).as("源码目录不存在：%s（工作目录=%s）", root, Path.of("").toAbsolutePath()).isTrue();
        StringBuilder sb = new StringBuilder(1 << 20);
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    // 枚举自身的注释里会出现所有码名，排除掉才谈得上"产码点"
                    .filter(p -> !p.getFileName().toString().equals("ErrorCode.java"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            sb.append(Files.readString(p)).append('\n');
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sb.toString();
    }
}
