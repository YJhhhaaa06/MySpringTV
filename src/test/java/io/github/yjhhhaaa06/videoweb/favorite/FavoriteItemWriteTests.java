package io.github.yjhhhaaa06.videoweb.favorite;

import io.github.yjhhhaaa06.videoweb.support.AbstractHttpIntegrationTest;
import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收藏一期 <b>T4：收藏 / 取消（批量移出）/ 移动</b> 的契约测试（{@code 能力 一期-4}）。
 *
 * <h2>覆盖什么（期望值全部出自业务口径，盖住实现照样写得出来）</h2>
 * <ul>
 *   <li><b>收藏</b>：落库终态（folder_id / user_id / content_id）、★ 重复收藏 409 且
 *       **无副作用**（行数不变）、★ 同一内容进多夹是合法状态（需求篇 §三，与 like 的
 *       单行模型不同）、★ 不带夹收藏 → 默认夹**懒建**且**不重复建**（R-01 +
 *       {@code uk_user_default}）；</li>
 *   <li><b>失效不可移入</b>（R-07）：{@code is_deleted} 三态里的 1（作者删）与
 *       2（下架）收藏都 404，且**不留任何行**（内容校验先行 ⇒ 懒建也不发生）；</li>
 *   <li><b>★ 失效条目能移出</b>（G11 验收）：占位记录（内容行被物理删的"幽灵 id"与
 *       软删内容两种形态）都能从夹里删掉 —— 这条与 add 的 404 是**同一条业务规则的两面**；</li>
 *   <li><b>移出幂等</b>：记录不存在照常 200（0 行不算错）；**别的夹不受影响**
 *       （需求篇 §三「那些夹里的不受影响」）；</li>
 *   <li><b>移动</b>：终态 = 目标夹有一行、源夹没有、第三个夹不动；★ 失效内容 move 被拒
 *       且**留在原位**（R-07「不可移动但保留原位」）；★ 目标夹已有同内容 → 409 且
 *       **两边都保留**（先插后删 + 同事务的可观察结果）；</li>
 *   <li>错误线：404（夹/内容不存在）、403（不是我的夹）、400（空 contentIds）、401（未登录）。</li>
 * </ul>
 *
 * <h2>为什么"计数以 DB 终态为准"在本类逐处体现</h2>
 * 全部断言走独立 oracle（直查 {@code favorite_item} / {@code favorite_folder} 行），
 * 不信接口回显（写端点的 {@code data} 只是文案字符串，本来也没有计数可抄）；
 * 行存在性用 {@code (folder_id, content_id)} 精确对查，而不是数总数 —— 后者会把
 * "删错了别的行"误判成正确。
 *
 * <h2>顺序契约：内容存在性判在夹归属之前（add / move）</h2>
 * 对齐 {@code /like/*} 写路径「先校验存在性」的参照（T4 入口线索指定的形态），
 * 且它有懒建路径特有的实质后果：幽灵内容 + 新用户 ⇒ 404 且**不建默认夹**。
 * 故用「幽灵内容收进**他人的**夹 → 404 而非 403」把顺序钉死（同 T2 钉"400 先于 403"
 * 的理由：不钉，将来谁先谁后会随手漂移）。
 *
 * <h2>反向验证清单（实测记录见 T4 回写；T8 收口复核）—— 逐条都**实测过能红**</h2>
 * <ul>
 *   <li>给 {@code FavoriteService.removeItems} 注入内容存在性校验 ⇒
 *       {@code 批量移出_失效条目能移出} 变红（G11 契约的判别力所在）；</li>
 *   <li>删掉 {@code addItem} / {@code moveItem} 的 {@code isContentExist} 调用 ⇒
 *       {@code 失效内容不可收藏} / {@code 失效内容不可移动} 变红；</li>
 *   <li>把 {@code moveItem} 改成**先删后插**并去掉 {@code @Transactional}（"两段式"形态）⇒
 *       {@code 移动目标重复409} 变红 —— 409 时源夹行已被删掉且无人回滚。
 *       ⚠️ 只换顺序、保留事务**不会红**（回滚兜住了），这正好说明该用例的红**依赖事务在场**：
 *       它钉的是"409 ⇒ 什么都不变"这条可观察契约；</li>
 *   <li>删掉 {@code removeItems} 的空列表守卫 ⇒ MyBatis 生成 {@code IN ()} 语法错 500 ⇒
 *       {@code 移出错误线} 变红；</li>
 *   <li>把 {@code insertDefaultFolderIfAbsent} 的 {@code is_default} 改成 0（建出自建夹）⇒
 *       {@code 不带夹收藏} 变红（500，懒建回查不到 {@code is_default=1} 的行）。</li>
 * </ul>
 * ⚠️ <b>本类覆盖不到的机制</b>：{@code moveItem} 的 {@code @Transactional} 在
 * **删除步失败**时的回滚语义 —— 先插后删的顺序让"插入失败"发生在删除之前，
 * HTTP 侧造不出"插入成功、删除失败"的注入点。与 T2 的 I-04 同款，标注给 T8 处置。
 */
class FavoriteItemWriteTests extends AbstractHttpIntegrationTest {

    private static final String PASSWORD = "abc123456";

    /** 刻意**不**在 content 表插入对应行的"幽灵内容 id"（失效条目形态之一：内容行真没了）。 */
    private static final long GHOST_CONTENT_ID = 990001L;

    @BeforeEach
    void 清空本域相关表() {
        // 在基类的 DELETE FROM users 之后执行（JUnit5 父类 @BeforeEach 先跑）。
        jdbcTemplate.update("DELETE FROM favorite_item");
        jdbcTemplate.update("DELETE FROM favorite_folder");
        jdbcTemplate.update("DELETE FROM content");
    }

    // ========================================================================
    // 1. 收藏（add）
    // ========================================================================

    @Test
    @DisplayName("收藏到指定夹：200 + 落库终态；重复收藏 409 无副作用；同内容进另一夹合法")
    void 收藏落库_重复409_多夹合法() {
        TestUser me = register("13800002001", "收藏人甲");
        long folderA = insertFolderRow(me.id, "夹A");
        long folderB = insertFolderRow(me.id, "夹B");
        long content = insertContent(me.id);

        ResponseEntity<String> resp = addItem(me.token, content, folderA);

        assertThat(resp.getStatusCode().value()).as("首次收藏成功").isEqualTo(200);
        assertThat(Envelope.code(resp)).isEqualTo(200);
        // ⚠️ data 是纯字符串（I-03）：Envelope.str 走 data.<field> 路径取不到，必须取整个 data 节点
        assertThat(Envelope.data(resp).asString()).isEqualTo("收藏成功");

        // 独立 oracle：行存在且三列精确对上（user_id 是冗余列，必须等于收藏人）
        assertThat(itemRowExists(folderA, content)).as("(folder_id, content_id) 落库").isOne();
        assertThat(itemUserId(folderA, content)).as("冗余列 user_id = 收藏人").isEqualTo(me.id);

        // 重复收藏：409（幂等拒绝），且**不产生第二行**
        assertThat(addItem(me.token, content, folderA).getStatusCode().value())
                .as("同夹重复收藏 → 撞 uk_folder_content 的幂等拒绝（409）").isEqualTo(409);
        assertThat(itemRowCount(folderA, content)).as("409 后行数仍是 1（无副作用）").isOne();

        // 同一内容进另一夹：合法（需求篇 §三「同一视频可以同时躺在我的多个夹里」）
        assertThat(addItem(me.token, content, folderB).getStatusCode().value())
                .as("另一夹收同一内容不是重复（唯一键是 (folder_id, content_id)）").isEqualTo(200);
        assertThat(itemRowCount(folderA, content)).isOne();
        assertThat(itemRowCount(folderB, content)).isOne();
    }

    @Test
    @DisplayName("★不带夹收藏：默认夹懒建（R-01）；第二次仍只有**一个**默认夹（唯一键兜住）")
    void 不带夹收藏_默认夹懒建且不重复建() {
        TestUser fresh = register("13800002002", "新用户乙");
        long content1 = insertContent(fresh.id);
        long content2 = insertContent(fresh.id);

        assertThat(defaultFolderCount(fresh.id)).as("前提：收藏前一个默认夹都没有").isZero();

        ResponseEntity<String> first = addItem(fresh.token, content1, null);
        assertThat(first.getStatusCode().value()).as("没选夹也能收藏（自动进默认夹）").isEqualTo(200);

        // 懒建产物：恰一个默认夹、名字「默认收藏夹」、公开（is_private 走列默认 0）
        assertThat(defaultFolderCount(fresh.id))
                .as("首次收藏懒建出**一个**默认夹").isEqualTo(1);
        java.util.Map<String, Object> folder = defaultFolderRow(fresh.id);
        assertThat(folder.get("name")).isEqualTo("默认收藏夹");
        assertThat(((Number) folder.get("is_default")).intValue()).isEqualTo(1);
        assertThat(((Number) folder.get("is_private")).intValue())
                .as("默认夹默认公开（R-05 列默认值）").isZero();
        long defaultId = ((Number) folder.get("id")).longValue();
        assertThat(itemRowCount(defaultId, content1)).as("收藏记录落在默认夹里").isOne();

        // 第二次不带夹：ON DUPLICATE KEY 跳过建夹 —— 仍然只有一个默认夹
        assertThat(addItem(fresh.token, content2, null).getStatusCode().value()).isEqualTo(200);
        assertThat(defaultFolderCount(fresh.id))
                .as("uk_user_default 兜住并发外的重复懒建（不会建出第二个默认夹）").isEqualTo(1);
        assertThat(itemRowCount(defaultId, content2)).isOne();
        assertThat(oracleItemCount(defaultId)).as("两条收藏都在默认夹").isEqualTo(2);
    }

    @Test
    @DisplayName("★失效内容不可收藏：is_deleted=1/2 都 404，且**不留任何行**（新用户连默认夹都不建）")
    void 失效内容不可收藏且不留行() {
        TestUser me = register("13800002003", "收藏人丙");
        long authorDeleted = insertContentWithState(me.id, 1);   // 1 = 作者删除
        long adminHidden = insertContentWithState(me.id, 2);     // 2 = 管理员下架

        assertThat(addItem(me.token, authorDeleted, null).getStatusCode().value())
                .as("作者删除的内容不可收藏（isContentExist 带 is_deleted = 0）").isEqualTo(404);
        assertThat(addItem(me.token, adminHidden, null).getStatusCode().value())
                .as("★ 下架（is_deleted=2）同样被拒 —— 判定是 != 0，别写成 = 1").isEqualTo(404);
        assertThat(addItem(me.token, GHOST_CONTENT_ID, null).getStatusCode().value())
                .as("内容行都不存在的幽灵 id 同样 404").isEqualTo(404);

        assertThat(defaultFolderCount(me.id))
                .as("★ 被拒的请求**不留痕**：懒建发生在内容校验之后，404 路径连默认夹都不建").isZero();
        assertThat(oracleTotalItemCount(me.id)).isZero();
    }

    @Test
    @DisplayName("收藏错误线：内容校验先于夹归属（幽灵→他人的夹=404）；他人的夹 403；夹不存在 404；未登录 401")
    void 收藏错误线() {
        TestUser me = register("13800002004", "我");
        TestUser other = register("13800002005", "别人");
        long others = insertFolderRow(other.id, "别人的夹");
        long content = insertContent(me.id);

        // ★ 顺序契约：内容存在性判在夹归属之前 —— 幽灵内容 + 他人的夹 = 404（不是 403）。
        assertThat(addItem(me.token, GHOST_CONTENT_ID, others).getStatusCode().value())
                .as("「先校验存在性」对齐 /like/* 写路径；不钉死，将来与 403 的先后会漂移")
                .isEqualTo(404);
        assertThat(addItem(me.token, content, others).getStatusCode().value())
                .as("内容正常但夹是别人的 → 403（归属口径与改名/删除一致）").isEqualTo(403);
        assertThat(addItem(me.token, content, 999999L).getStatusCode().value())
                .as("夹不存在 → 404").isEqualTo(404);
        assertThat(addItem(null, content, others).getStatusCode().value()).isEqualTo(401);

        assertThat(oracleTotalItemCount(me.id)).as("四次被拒都不得产生收藏记录").isZero();
        assertThat(oracleItemCount(others)).as("别人的夹也不得被写入").isZero();
    }

    // ========================================================================
    // 2. 移出（remove，批量）
    // ========================================================================

    @Test
    @DisplayName("★批量移出：失效条目（软删内容 + 幽灵 id）**能移出**（G11），其余记录与别的夹不受影响")
    void 批量移出_失效条目能移出() {
        TestUser me = register("13800002006", "整理者甲");
        long folderA = insertFolderRow(me.id, "待清理夹");
        long softDeleted = insertContentWithState(me.id, 1);

        // 夹 A 里 4 条：2 条正常 + 1 条软删内容 + 1 条幽灵（内容行不存在）
        // ⚠️ 软删/幽灵两条只能用夹具直插 —— add 端点本来就拒收失效内容（上一组用例）。
        insertItemRow(folderA, me.id, 1001L);
        insertItemRow(folderA, me.id, 1002L);
        insertItemRow(folderA, me.id, softDeleted);
        insertItemRow(folderA, me.id, GHOST_CONTENT_ID);

        // 逗号分隔形态（T7 前端一次提交的形态）：一次移出 3 条，含两种失效形态
        ResponseEntity<String> resp = removeItems(me.token, folderA, 1001L, softDeleted, GHOST_CONTENT_ID);

        assertThat(resp.getStatusCode().value()).as("失效条目必须删得掉 —— 这里若 404/409 就是把 G11 吃掉了").isEqualTo(200);
        assertThat(Envelope.data(resp).asString()).isEqualTo("移出成功");

        assertThat(itemRowCount(folderA, 1001L)).isZero();
        assertThat(itemRowCount(folderA, softDeleted)).as("软删内容的记录被移出").isZero();
        assertThat(itemRowCount(folderA, GHOST_CONTENT_ID)).as("幽灵内容的记录被移出").isZero();
        assertThat(itemRowCount(folderA, 1002L)).as("没勾选的记录原样保留").isOne();
    }

    @Test
    @DisplayName("★移出幂等 + 别的夹不受影响：记录已删再删仍 200；同内容在别的夹的记录不动（重复参数形态）")
    void 移出幂等_别的夹不受影响() {
        TestUser me = register("13800002007", "整理者乙");
        long folderA = insertFolderRow(me.id, "夹A");
        long folderB = insertFolderRow(me.id, "夹B");
        long contentX = insertContent(me.id);
        long contentY = insertContent(me.id);
        insertItemRow(folderA, me.id, contentX);
        insertItemRow(folderA, me.id, contentY);
        insertItemRow(folderB, me.id, contentX);

        // 重复参数形态（contentIds=X&contentIds=Y）—— 与逗号分隔等价的另一种绑定
        MultiValueMap<String, String> repeatedParams = repeatedForm("contentIds",
                String.valueOf(contentX), String.valueOf(contentY));
        repeatedParams.add("folderId", String.valueOf(folderA));
        ResponseEntity<String> resp = postForm("/favorite/remove", repeatedParams, me.token);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        assertThat(itemRowCount(folderA, contentX)).isZero();
        assertThat(itemRowCount(folderA, contentY)).isZero();
        assertThat(itemRowCount(folderB, contentX))
                .as("★ 只动 folderA：需求篇 §三「该内容还在别的夹里，那些夹里的不受影响」").isOne();

        // 幂等：同一批 id 再删一次（记录已不存在）→ 仍 200，DB 无变化
        ResponseEntity<String> again = removeItems(me.token, folderA, contentX, contentY);
        assertThat(again.getStatusCode().value())
                .as("0 行删除不算错（陈旧页面/双击的合法重放）").isEqualTo(200);
        assertThat(itemRowCount(folderB, contentX)).isOne();
    }

    @Test
    @DisplayName("移出错误线：他人的夹 403、夹不存在 404、空/缺 contentIds 400、未登录 401 —— 且 DB 无变化")
    void 移出错误线() {
        TestUser me = register("13800002008", "我移");
        TestUser other = register("13800002009", "别人存");
        long mine = insertFolderRow(me.id, "我的夹");
        long others = insertFolderRow(other.id, "别人的夹");
        insertItemRow(others, other.id, 2001L);
        insertItemRow(mine, me.id, 2002L);

        assertThat(removeItems(me.token, others, 2001L).getStatusCode().value())
                .as("别人的夹 → 403（G11 只豁免内容存在性，不豁免夹归属）").isEqualTo(403);
        assertThat(removeItems(me.token, 999999L, 2001L).getStatusCode().value())
                .as("夹不存在 → 404").isEqualTo(404);
        MultiValueMap<String, String> emptyContentIds = form("folderId", mine);
        emptyContentIds.add("contentIds", "");
        assertThat(postForm("/favorite/remove", emptyContentIds, me.token)
                .getStatusCode().value())
                .as("contentIds= 空串 → 绑定成空列表 → 400（什么都不移是调用方 bug）").isEqualTo(400);
        assertThat(postForm("/favorite/remove", form("folderId", mine), me.token)
                .getStatusCode().value())
                .as("缺 contentIds 参数 → 400").isEqualTo(400);
        assertThat(removeItems(null, mine, 2002L).getStatusCode().value()).isEqualTo(401);

        assertThat(oracleItemCount(others)).as("别人的记录不得被动过").isOne();
        assertThat(oracleItemCount(mine)).as("我自己的记录也不得被错误线请求带走").isOne();
    }

    // ========================================================================
    // 3. 移动（move）
    // ========================================================================

    @Test
    @DisplayName("移动：终态 = 目标夹有一行、源夹没有；内容在第三个夹的记录不受影响")
    void 移动终态() {
        TestUser me = register("13800002010", "移动者甲");
        long folderA = insertFolderRow(me.id, "源夹");
        long folderB = insertFolderRow(me.id, "目标夹");
        long folderC = insertFolderRow(me.id, "第三个夹");
        long content = insertContent(me.id);
        insertItemRow(folderA, me.id, content);
        insertItemRow(folderC, me.id, content);

        ResponseEntity<String> resp = moveItem(me.token, folderA, folderB, content);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.data(resp).asString()).isEqualTo("移动成功");

        assertThat(itemRowCount(folderA, content)).as("源夹不再有").isZero();
        assertThat(itemRowCount(folderB, content)).as("目标夹恰有一行").isOne();
        assertThat(itemUserId(folderB, content)).as("新行的冗余 user_id 仍是收藏人").isEqualTo(me.id);
        assertThat(itemRowCount(folderC, content))
                .as("move 只动 from→to 这一对，第三个夹照旧").isOne();
    }

    @Test
    @DisplayName("★失效内容不可移动：404 且**留在原位**（R-07「不可移入/移动」的另一半）")
    void 失效内容不可移动且留在原位() {
        TestUser me = register("13800002011", "移动者乙");
        long folderA = insertFolderRow(me.id, "源夹");
        long folderB = insertFolderRow(me.id, "目标夹");
        long adminHidden = insertContentWithState(me.id, 2);
        // 失效条目只能夹具直插（add 端点拒收失效内容 —— 正是"不可移入"的那一半）
        insertItemRow(folderA, me.id, GHOST_CONTENT_ID);
        insertItemRow(folderA, me.id, adminHidden);

        assertThat(moveItem(me.token, folderA, folderB, GHOST_CONTENT_ID).getStatusCode().value())
                .as("幽灵内容 → 404").isEqualTo(404);
        assertThat(moveItem(me.token, folderA, folderB, adminHidden).getStatusCode().value())
                .as("下架内容 → 404").isEqualTo(404);

        assertThat(itemRowCount(folderA, GHOST_CONTENT_ID))
                .as("★ 被拒的移动必须**留在原位** —— 若两段式 remove+add 实现，这里已经把它删了").isOne();
        assertThat(itemRowCount(folderA, adminHidden)).isOne();
        assertThat(oracleItemCount(folderB)).as("目标夹什么都没进来").isZero();
    }

    @Test
    @DisplayName("★移动到已有同内容的夹：409 且**两边都保留**（from==to 同理）—— 409 ⇒ 什么都不变")
    void 移动目标重复409_两边保留() {
        TestUser me = register("13800002012", "移动者丙");
        long folderA = insertFolderRow(me.id, "夹A");
        long folderB = insertFolderRow(me.id, "夹B");
        long content = insertContent(me.id);
        insertItemRow(folderA, me.id, content);
        insertItemRow(folderB, me.id, content);

        assertThat(moveItem(me.token, folderA, folderB, content).getStatusCode().value())
                .as("目标夹已有同内容 → 撞 uk_folder_content → 409").isEqualTo(409);

        // ★ 先插后删 + 同事务的可观察结果：409 时源夹与目标夹**都原样保留**。
        //   若实现是"先删后插"，源夹行已消失 —— 本断言变红（去掉 @Transactional 也红不了
        //   先插后删的路径，但能红先删后插的路径：钉的是"409 ⇒ 什么都不变"这条契约）。
        assertThat(itemRowCount(folderA, content)).as("源夹保留").isOne();
        assertThat(itemRowCount(folderB, content)).as("目标夹原有的那行也不动").isOne();

        // from == to：撞自己的唯一键 ⇒ 自然 409，不需要专门分支；DB 同样不变
        assertThat(moveItem(me.token, folderA, folderA, content).getStatusCode().value())
                .as("移到自己 = 目标夹已有同内容的特例，同样 409").isEqualTo(409);
        assertThat(oracleItemCount(folderA)).isOne();
    }

    @Test
    @DisplayName("移动错误线：源/目标夹归属 403、不存在 404、未登录 401 —— 且 DB 无变化")
    void 移动错误线() {
        TestUser me = register("13800002013", "我挪");
        TestUser other = register("13800002014", "别人夹");
        long mine = insertFolderRow(me.id, "我的夹");
        long others = insertFolderRow(other.id, "别人的夹");
        long content = insertContent(me.id);
        insertItemRow(mine, me.id, content);

        assertThat(moveItem(me.token, others, mine, content).getStatusCode().value())
                .as("源夹是别人的 → 403").isEqualTo(403);
        assertThat(moveItem(me.token, mine, others, content).getStatusCode().value())
                .as("目标夹是别人的 → 403（两个夹都必须是我的）").isEqualTo(403);
        assertThat(moveItem(me.token, 999999L, mine, content).getStatusCode().value())
                .as("源夹不存在 → 404").isEqualTo(404);
        assertThat(moveItem(me.token, mine, 999999L, content).getStatusCode().value())
                .as("目标夹不存在 → 404").isEqualTo(404);
        assertThat(moveItem(null, mine, others, content).getStatusCode().value()).isEqualTo(401);

        assertThat(itemRowCount(mine, content)).as("五次被拒都不得动源夹的记录").isOne();
        assertThat(oracleItemCount(others)).as("别人的夹也不得被写入").isZero();
    }

    // ========================================================================
    // HTTP
    // ========================================================================

    private record TestUser(long id, String token) {
    }

    private TestUser register(String phone, String username) {
        String token = registerAndGetToken(phone, username, PASSWORD);
        return new TestUser(userIdOf(phone), token);
    }

    /** POST + form（收藏域写形态）；{@code folderId == null} 时**不发**该参数（= 没选夹）。 */
    private ResponseEntity<String> addItem(String token, long contentId, Long folderId) {
        MultiValueMap<String, String> f = form("contentId", contentId);
        if (folderId != null) {
            f.add("folderId", String.valueOf(folderId));
        }
        return postForm("/favorite/add", f, token);
    }

    private ResponseEntity<String> removeItems(String token, long folderId, long... contentIds) {
        MultiValueMap<String, String> f = form("folderId", folderId);
        f.add("contentIds", commaJoined(contentIds));
        return postForm("/favorite/remove", f, token);
    }

    private ResponseEntity<String> moveItem(String token, long fromFolderId, long toFolderId, long contentId) {
        MultiValueMap<String, String> f = form("fromFolderId", fromFolderId);
        f.add("toFolderId", String.valueOf(toFolderId));
        f.add("contentId", String.valueOf(contentId));
        return postForm("/favorite/move", f, token);
    }

    /** POST + {@code x-www-form-urlencoded}（同域 T2 测试的同款实现）。 */
    private ResponseEntity<String> postForm(String path, MultiValueMap<String, String> form, String token) {
        return send(spec -> {
            RestClient.RequestBodySpec request = spec.uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED);
            if (token != null) {
                request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return request.body(form);
        });
    }

    private static MultiValueMap<String, String> form(String name, long value) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(name, String.valueOf(value));
        return form;
    }

    /** 重复参数形态：同名 key 出现多次（Spring 与逗号分隔等价绑定）。 */
    private static MultiValueMap<String, String> repeatedForm(String name, String... values) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (String value : values) {
            form.add(name, value);
        }
        return form;
    }

    private static String commaJoined(long... values) {
        StringJoiner joiner = new StringJoiner(",");
        for (long value : values) {
            joiner.add(String.valueOf(value));
        }
        return joiner.toString();
    }

    // ========================================================================
    // 夹具与独立 oracle（直连 DB，不经接口；期望值不抄实现）
    // ========================================================================

    private long insertContent(long authorId) {
        return insertContentRow(authorId, 0);
    }

    private long insertContentWithState(long authorId, int isDeleted) {
        return insertContentRow(authorId, isDeleted);
    }

    private long insertContentRow(long authorId, int isDeleted) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO content (user_id, type, title, description, is_deleted) VALUES (?, 1, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, authorId);
            ps.setString(2, "T4 测试内容 " + authorId);
            ps.setString(3, "T4 测试描述");
            ps.setInt(4, isDeleted);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("content 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    private long insertFolderRow(long userId, String name) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO favorite_folder (user_id, name, is_default) VALUES (?, ?, 0)",
                    new String[]{"id"});
            ps.setLong(1, userId);
            ps.setString(2, name);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        assertThat(key).as("favorite_folder 插入必须回填自增 id").isNotNull();
        return key.longValue();
    }

    /** 直接插收藏记录（夹具）。⚠️ 不校验 contentId：失效条目（幽灵/软删）正是本类的被测对象。 */
    private void insertItemRow(long folderId, long userId, long contentId) {
        jdbcTemplate.update("INSERT INTO favorite_item (folder_id, user_id, content_id) VALUES (?, ?, ?)",
                folderId, userId, contentId);
    }

    /** ★ 独立 oracle：夹内收藏**记录**数（不读接口回显）。 */
    private long oracleItemCount(long folderId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE folder_id = ?", Long.class, folderId);
        return n == null ? 0L : n;
    }

    /** 某用户全部夹里的记录总数（跨夹 oracle，用于"不留行"类断言）。 */
    private long oracleTotalItemCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    /** (folder_id, content_id) 精确对查的行数 —— 比"数总数"更能定位删错行。 */
    private long itemRowCount(long folderId, long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_item WHERE folder_id = ? AND content_id = ?",
                Long.class, folderId, contentId);
        return n == null ? 0L : n;
    }

    private long itemRowExists(long folderId, long contentId) {
        return itemRowCount(folderId, contentId);
    }

    /** 冗余列 user_id 的独立复核（判"同一人是否收藏过"按 (user_id, content_id) 的前提）。 */
    private long itemUserId(long folderId, long contentId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT user_id FROM favorite_item WHERE folder_id = ? AND content_id = ?",
                Long.class, folderId, contentId);
        assertThat(n).as("记录应存在（folder=%s, content=%s）", folderId, contentId).isNotNull();
        return n;
    }

    /** 某用户的默认夹数量（懒建"不重复建"的 oracle）。 */
    private long defaultFolderCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorite_folder WHERE user_id = ? AND is_default = 1",
                Long.class, userId);
        return n == null ? 0L : n;
    }

    /** 取默认夹整行（id/name/is_default/is_private）；不存在或不止一个时断言失败。 */
    private java.util.Map<String, Object> defaultFolderRow(long userId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM favorite_folder WHERE user_id = ? AND is_default = 1", Long.class, userId);
        assertThat(ids).as("默认夹应已存在").hasSize(1);
        return jdbcTemplate.queryForMap(
                "SELECT id, name, is_default, is_private FROM favorite_folder WHERE id = ?", ids.get(0));
    }
}
