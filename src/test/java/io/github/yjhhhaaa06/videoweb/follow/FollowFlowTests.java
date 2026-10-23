package io.github.yjhhhaaa06.videoweb.follow;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4 的**功能闭环 / HTTP 契约**测试（对应《事务边界决策表》F-1 / F-3 的"必须固化的测试"清单）。
 *
 * <p>本类钉的是"**用户看到什么**"：HTTP 状态码 + 信封形状 + DB 终态（独立 oracle 直查）。
 * 它**不**证明缓存时序（那由 {@code FollowCacheTests} 负责）——两种测试不可互相替代
 * （SOP §2.5：「断言通过 ≠ 机制生效」）。
 *
 * <p>契约要点（全部来自旧 pytest {@code test_follow_list.py} 的翻译，形式随架构重写、意图保留）：
 * <ul>
 *   <li>4 个端点全部需登录（TV 的 {@code /follow} 是**前缀**保护项）→ 无 token 一律 401；</li>
 *   <li>{@code followedUserId} / {@code userId} 进路径后（`T58`）：可判定的参数档只剩"非法 id → 400"；
 *       旧用例"缺参 → 400"因"少了路径段就没有路由匹配"而**整条退役**（不改成 404 硬凑）；</li>
 *   <li>关注成功 {@code data="关注成功"}、取关成功 {@code data="已取关"}（TV {@code writeSuccess} 文案）；</li>
 *   <li>列表返回**分页信封**（T11-A 起缺省不再是全量数组），键集合 = {@code {list,total,page,pageSize,totalPages}}；</li>
 *   <li>顺序 = **按 id 升序**（TV 的 T7 注释："关注/粉丝列表顺序由此处决定"）；</li>
 *   <li>条目字段 = {@code {userId, username, isFollowed, isSelf}}。</li>
 * </ul>
 */
class FollowFlowTests extends AbstractFollowIntegrationTest {

    private static final String PHONE_A = "13800001001";
    private static final String PHONE_B = "13800001002";
    private static final String PHONE_C = "13800001003";

    // ========================================================================
    // 写路径：关注 / 取关（F-1 / F-2）
    // ========================================================================

    @Test
    @DisplayName("关注成功：follow 新增 1 行，双方计数各 +1（独立 oracle 直查列）")
    void 关注成功_关系行与双方计数各加一() {
        TestUser a = register(PHONE_A, "flow-a");
        TestUser b = register(PHONE_B, "flow-b");

        var resp = follow(a.id(), b.token());

        assertThat(resp.getStatusCode().value()).as("HTTP 状态码应与 body code 一致（200）").isEqualTo(200);
        assertThat(Envelope.code(resp)).isEqualTo("OK");
        assertThat(Envelope.data(resp).asString()).as("TV writeSuccess 文案").isEqualTo("关注成功");

        assertThat(countFollowRows(b.id(), a.id())).as("关系行").isEqualTo(1L);
        assertThat(followCountColumn(b.id())).as("关注者的 follow_count").isEqualTo(1);
        assertThat(followerCountColumn(a.id())).as("被关注者的 follower_count").isEqualTo(1);
    }

    @Test
    @DisplayName("取关成功：follow 行删除，双方计数各 -1")
    void 取关成功_关系行与双方计数各减一() {
        TestUser a = register(PHONE_A, "flow-a");
        TestUser b = register(PHONE_B, "flow-b");
        assertThat(follow(a.id(), b.token()).getStatusCode().value()).isEqualTo(200);

        var resp = unfollow(a.id(), b.token());

        assertThat(Envelope.code(resp)).isEqualTo("OK");
        assertThat(Envelope.data(resp).asString()).isEqualTo("已取关");
        assertThat(countFollowRows(b.id(), a.id())).isZero();
        assertThat(followCountColumn(b.id())).isZero();
        assertThat(followerCountColumn(a.id())).isZero();
    }

    @Test
    @DisplayName("关注自己 → 409「不能关注自己」，且不产生关系行、计数不变")
    void 关注自己_409且无副作用() {
        TestUser a = register(PHONE_A, "flow-a");

        var resp = follow(a.id(), a.token());

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(Envelope.code(resp)).isEqualTo("FOLLOW_SELF");
        assertThat(Envelope.msg(resp)).as("TV 原文案").isEqualTo("不能关注自己");
        assertThat(countFollowRows()).isZero();
        assertThat(followCountColumn(a.id())).isZero();
        assertThat(followerCountColumn(a.id())).isZero();
    }

    @Test
    @DisplayName("取关自己 → 409「不能取关自己」（文案与关注方向**不同**，TV 原文如此）")
    void 取关自己_409() {
        TestUser a = register(PHONE_A, "flow-a");

        var resp = unfollow(a.id(), a.token());

        assertThat(Envelope.code(resp)).isEqualTo("FOLLOW_SELF");
        assertThat(Envelope.msg(resp)).isEqualTo("不能取关自己");
        assertThat(countFollowRows()).isZero();
    }

    @Test
    @DisplayName("★重复关注 → 409「已关注，不可重复操作」，且计数**只加一次**（证明校验在读侧生效）")
    void 重复关注_409且计数只加一次() {
        TestUser a = register(PHONE_A, "flow-a");
        TestUser b = register(PHONE_B, "flow-b");
        assertThat(follow(a.id(), b.token()).getStatusCode().value()).isEqualTo(200);

        var resp = follow(a.id(), b.token());

        assertThat(Envelope.code(resp)).isEqualTo("FOLLOW_ALREADY");
        assertThat(Envelope.msg(resp)).isEqualTo("已关注，不可重复操作");
        assertThat(countFollowRows(b.id(), a.id())).as("不得产生第二行（唯一键亦兜底）").isEqualTo(1L);
        assertThat(followCountColumn(b.id())).as("计数只加一次 ⇒ 第二次被拦在写之前").isEqualTo(1);
        assertThat(followerCountColumn(a.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("取关未关注 → 409「未关注，不可取消」，且**不产生负计数**")
    void 取关未关注_409且不产生负计数() {
        TestUser a = register(PHONE_A, "flow-a");
        TestUser b = register(PHONE_B, "flow-b");

        var resp = unfollow(a.id(), b.token());

        assertThat(Envelope.code(resp)).isEqualTo("FOLLOW_NOT_FOLLOWING");
        assertThat(Envelope.msg(resp)).isEqualTo("未关注，不可取消");
        assertThat(countFollowRows()).isZero();
        assertThat(followCountColumn(b.id())).isZero();
        assertThat(followerCountColumn(a.id())).isZero();
    }

    @Test
    @DisplayName("未登录：4 个端点全部 401（TV 的 `/follow` 是**前缀**保护项，`T58` 后改由类级注解表达）")
    void 未登录全部401() {
        TestUser a = register(PHONE_A, "flow-a");

        assertThat(Envelope.code(put("/api/v1/follows/" + a.id(), null, null))).isEqualTo("AUTH_UNAUTHORIZED");
        assertThat(Envelope.code(delete("/api/v1/follows/" + a.id(), null))).isEqualTo("AUTH_UNAUTHORIZED");
        assertThat(Envelope.code(getFollowing(a.id(), null))).isEqualTo("AUTH_UNAUTHORIZED");
        assertThat(Envelope.code(getFollowers(a.id(), null))).isEqualTo("AUTH_UNAUTHORIZED");
    }

    @Test
    @DisplayName("参数非法：路径 id 非数字 → 400（关注 / 取关 / 关注列表 / 粉丝列表四个端点同口径）")
    void 路径id非数字400() {
        TestUser a = register(PHONE_A, "flow-a");

        // ★ `T58` 契约变更（接口契约.md §2.3/§2.4）：followedUserId / userId 从 form/query 搬进路径。
        //   旧用例"缺 followedUserId / 缺 userId ⇒ 400"**整条退役**——少了路径段就没有任何路由匹配，
        //   已无法用"合法 URL + 少一个参数"表达（同 T58-2 like 域与 T58-4 favorite 域的口径）；
        //   剩下的可判定档是"id 不可转换为 long" ⇒ 400。
        assertThat(put("/api/v1/follows/abc", null, a.token()).getStatusCode().value())
                .as("关注：路径 id 非数字").isEqualTo(400);
        assertThat(delete("/api/v1/follows/abc", a.token()).getStatusCode().value())
                .as("取关：路径 id 非数字").isEqualTo(400);
        assertThat(get("/api/v1/users/abc/following", a.token()).getStatusCode().value())
                .as("关注列表：路径 id 非数字").isEqualTo(400);
        assertThat(get("/api/v1/users/abc/followers", a.token()).getStatusCode().value())
                .as("粉丝列表：路径 id 非数字").isEqualTo(400);

        // 不得产生任何副作用（参数绑定失败在 Service 之前）
        assertThat(countFollowRows()).isZero();
    }

    /**
     * ★ F-04 的尖锐场景（follow 侧，unsigned）：{@code follow} 有关系行、但两侧计数都停在 0。
     * 取关时若不设守卫，`0 + (-1)` 会因 UNSIGNED 下溢报 1690 → 500。
     * 断言 200（不回滚、不失败）+ 关系行照删 + 两侧计数停在 0（守卫 block）。
     *
     * <p><b>判别力</b>：去掉守卫 ⇒ 本用例因 500 变红；"正常 −1→0" 由 {@link #取关成功_关系行与双方计数各减一} 作对照。
     */
    @Test
    @DisplayName("★取关：计数为 0 但关系行仍在 ⇒ 守卫拦住下溢，返回 200 而非 500（T34）")
    void 取关时计数为零不报500() {
        TestUser a = register(PHONE_A, "flow-a");
        TestUser b = register(PHONE_B, "flow-b");
        // 夹具不自洽：关系行存在，但两侧计数停在 0（不走 insertFollowRow 的联动 +1）
        jdbcTemplate.update("INSERT INTO follow (user_id, followed_user_id) VALUES (?, ?)", b.id(), a.id());
        assertThat(followCountColumn(b.id())).as("夹具：b 的 follow_count 停在 0").isZero();
        assertThat(followerCountColumn(a.id())).as("夹具：a 的 follower_count 停在 0").isZero();

        var resp = unfollow(a.id(), b.token());

        assertThat(resp.getStatusCode().value()).as("守卫拦住下溢；不得 500").isEqualTo(200);
        assertThat(countFollowRows(b.id(), a.id())).as("关系行照删").isZero();
        assertThat(followCountColumn(b.id())).as("守卫 block：停在 0").isZero();
        assertThat(followerCountColumn(a.id())).as("守卫 block：停在 0").isZero();
    }

    // ========================================================================
    // 读路径：关注 / 粉丝列表（F-3）
    // ========================================================================

    @Test
    @DisplayName("关注列表为空 → 仍返回**分页信封**（空数组，不是 null），total/totalPages 为 0")
    void 关注列表_空集合返回空信封() {
        TestUser a = register(PHONE_A, "flow-a");

        var resp = getFollowing(a.id(), a.token());

        assertThat(Envelope.code(resp)).isEqualTo("OK");
        JsonNode data = Envelope.data(resp);
        assertThat(data.isObject()).as("缺省应返回分页信封（T11-A 起不再是全量数组）").isTrue();
        assertThat(data.path("list").isArray()).isTrue();
        assertThat(data.path("list").size()).isZero();
        assertThat(data.path("total").asInt()).isZero();
        assertThat(data.path("totalPages").asInt()).isZero();
        assertThat(data.path("page").asInt()).isEqualTo(1);
        assertThat(data.path("pageSize").asInt()).isEqualTo(PAGE_SIZE_MAX);
    }

    @Test
    @DisplayName("关注列表条目口径：isFollowed=当前用户是否关注了条目本人；isSelf=条目本人是否当前用户")
    void 关注列表_条目字段与isFollowed_isSelf口径() {
        TestUser a = register(PHONE_A, "flow-a");
        TestUser b = register(PHONE_B, "flow-b");
        assertThat(follow(a.id(), b.token()).getStatusCode().value()).isEqualTo(200);

        // b 查自己的关注列表 → 应含 a；b 已关注 a ⇒ isFollowed=true；a≠b ⇒ isSelf=false
        var resp = getFollowing(b.id(), b.token());

        assertThat(Envelope.code(resp)).isEqualTo("OK");
        JsonNode item = singleItemOf(resp);
        assertThat(item.path("userId").asLong()).isEqualTo(a.id());
        assertThat(item.path("username").asString()).isEqualTo("flow-a");
        assertThat(item.path("isFollowed").asBoolean()).isTrue();
        assertThat(item.path("isSelf").asBoolean()).isFalse();
        assertThat(item.propertyNames()).as("字段形状是契约，不得多/少字段")
                .containsExactlyInAnyOrder("userId", "username", "isFollowed", "isSelf");
    }

    @Test
    @DisplayName("粉丝列表条目口径：b 查 a 的粉丝列表（b 自己是粉丝）⇒ isFollowed=false、isSelf=true")
    void 粉丝列表_条目口径() {
        TestUser a = register(PHONE_A, "flow-a");
        TestUser b = register(PHONE_B, "flow-b");
        assertThat(follow(a.id(), b.token()).getStatusCode().value()).isEqualTo(200);

        var resp = getFollowers(a.id(), b.token());

        assertThat(Envelope.code(resp)).isEqualTo("OK");
        JsonNode item = singleItemOf(resp);
        assertThat(item.path("userId").asLong()).isEqualTo(b.id());
        assertThat(item.path("username").asString()).isEqualTo("flow-b");
        assertThat(item.path("isFollowed").asBoolean()).as("b 不关注自己").isFalse();
        assertThat(item.path("isSelf").asBoolean()).as("条目本人就是当前用户").isTrue();
    }

    @Test
    @DisplayName("★缺省（不传分页参数）与显式 page=1&pageSize=100 响应**逐字节一致**")
    void 缺省与显式第一页逐字节一致() {
        TestUser a = register(PHONE_A, "flow-a");
        TestUser b = register(PHONE_B, "flow-b");
        assertThat(follow(a.id(), b.token()).getStatusCode().value()).isEqualTo(200);

        JsonNode byDefault = Envelope.data(getFollowers(a.id(), b.token()));
        JsonNode explicit = Envelope.data(
                get("/api/v1/users/" + a.id() + "/followers?page=1&pageSize=" + PAGE_SIZE_MAX, b.token()));

        assertThat(byDefault.path("list")).as("本用例需要非空列表，否则比对退化为恒真").isNotEmpty();
        assertThat(byDefault).as("缺省与显式第一页应完全一致").isEqualTo(explicit);
    }

    @Test
    @DisplayName("半参数：只传 page 或只传 pageSize 亦返回信封，未传一侧取域级归一值")
    void 半参数归一() {
        TestUser a = register(PHONE_A, "flow-a");

        JsonNode onlyPage = Envelope.data(get("/api/v1/users/" + a.id() + "/following?page=2", a.token()));
        assertThat(onlyPage.path("page").asInt()).isEqualTo(2);
        assertThat(onlyPage.path("pageSize").asInt()).isEqualTo(PAGE_SIZE_MAX);

        JsonNode onlySize = Envelope.data(get("/api/v1/users/" + a.id() + "/following?pageSize=5", a.token()));
        assertThat(onlySize.path("page").asInt()).isEqualTo(1);
        assertThat(onlySize.path("pageSize").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("参数归一：page<1→1、pageSize 超上限→100、51 原样回显；越界页空数组但**保留 total**")
    void 参数归一与越界页() {
        TestUser a = register(PHONE_A, "flow-a");
        TestUser b = register(PHONE_B, "flow-b");
        assertThat(follow(a.id(), b.token()).getStatusCode().value()).isEqualTo(200);

        JsonNode norm = Envelope.data(
                get("/api/v1/users/" + a.id() + "/followers?page=-1&pageSize=999", b.token()));
        assertThat(norm.path("page").asInt()).isEqualTo(1);
        assertThat(norm.path("pageSize").asInt()).isEqualTo(PAGE_SIZE_MAX);

        JsonNode mid = Envelope.data(get("/api/v1/users/" + a.id() + "/followers?pageSize=51", b.token()));
        assertThat(mid.path("pageSize").asInt()).as("上限以内原样回显").isEqualTo(51);

        JsonNode beyond = Envelope.data(
                get("/api/v1/users/" + a.id() + "/followers?page=9999&pageSize=10", b.token()));
        assertThat(beyond.path("list").size()).as("越界页应为空数组").isZero();
        assertThat(beyond.path("total").asInt()).as("越界页仍返回 total（前端据此判末页）").isEqualTo(1);
    }

    @Test
    @DisplayName("★粉丝列表分页：页间不重不漏、与缺省第一页前缀一致、**按 id 升序**")
    void 粉丝列表_跨页不重不漏且升序() {
        TestUser a = register(PHONE_A, "flow-a");
        TestUser b = register(PHONE_B, "flow-b");
        TestUser c = register(PHONE_C, "flow-c");
        assertThat(follow(a.id(), b.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(follow(a.id(), c.token()).getStatusCode().value()).isEqualTo(200);

        // 全量（缺省第一页，信封 100 > 2）
        JsonNode full = Envelope.data(getFollowers(a.id(), a.token()));
        List<Long> allIds = idsOf(full);
        assertThat(allIds).as("应有 2 个粉丝").hasSize(2);
        assertThat(allIds).as("顺序口径 = 按 id 升序（TV T7：顺序由此处决定）").isSorted();
        assertThat(allIds).containsExactly(b.id(), c.id());
        assertThat(full.path("total").asInt()).isEqualTo(2);
        assertThat(full.path("totalPages").asInt()).isEqualTo(1);

        // 小信封跨页：pageSize=1 ⇒ 两页各 1 条，与全量前缀一致、页间不重复
        JsonNode page1 = Envelope.data(get("/api/v1/users/" + a.id() + "/followers?page=1&pageSize=1", a.token()));
        JsonNode page2 = Envelope.data(get("/api/v1/users/" + a.id() + "/followers?page=2&pageSize=1", a.token()));
        assertThat(idsOf(page1)).containsExactly(allIds.get(0));
        assertThat(idsOf(page2)).containsExactly(allIds.get(1));
        assertThat(page1.path("total").asInt()).as("total 与成员集同源").isEqualTo(2);
        assertThat(page1.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page1.path("list").get(0).path("username").asString()).isNotBlank();
    }

    @Test
    @DisplayName("关注列表同样按 id 升序，且只含自己关注的人")
    void 关注列表_升序且只含自己关注的() {
        TestUser a = register(PHONE_A, "flow-a");
        TestUser b = register(PHONE_B, "flow-b");
        TestUser c = register(PHONE_C, "flow-c");
        // b 关注 a 与 c；a 不关注任何人
        assertThat(follow(a.id(), b.token()).getStatusCode().value()).isEqualTo(200);
        assertThat(follow(c.id(), b.token()).getStatusCode().value()).isEqualTo(200);

        List<Long> bFollowing = idsOf(Envelope.data(getFollowing(b.id(), b.token())));
        assertThat(bFollowing).containsExactly(a.id(), c.id());

        assertThat(idsOf(Envelope.data(getFollowing(a.id(), a.token())))).as("a 未关注任何人").isEmpty();
    }

    // ==================== 断言辅助 ====================

    /** 取列表中唯一一条条目（不唯一则失败——比 {@code get(0)} 更能暴露夹具/实现问题）。 */
    private static JsonNode singleItemOf(org.springframework.http.ResponseEntity<String> resp) {
        JsonNode list = Envelope.data(resp).path("list");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).as("本用例期望恰好 1 条：%s", list).isEqualTo(1);
        return list.get(0);
    }

    private static List<Long> idsOf(JsonNode envelopeData) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : envelopeData.path("list")) {
            ids.add(item.path("userId").asLong());
        }
        return ids;
    }
}
