package io.github.yjhhhaaa06.videoweb.contract;

import io.github.yjhhhaaa06.videoweb.admin.model.audit.MediaAuditItem;
import io.github.yjhhhaaa06.videoweb.admin.model.audit.MediaAuditResult;
import io.github.yjhhhaaa06.videoweb.content.model.vo.AdminContentVO;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ProfileVO;
import io.github.yjhhhaaa06.videoweb.coupon.model.vo.CouponOrderVO;
import io.github.yjhhhaaa06.videoweb.coupon.model.vo.CouponVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteFolderVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.FavoriteItemVO;
import io.github.yjhhhaaa06.videoweb.favorite.model.vo.WatchLaterItemVO;
import io.github.yjhhhaaa06.videoweb.history.model.vo.HistoryItemVO;
import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 载荷形状的**跨 VO 契约**（`T59-3`，依据《接口契约.md》§2.7）。
 *
 * <h2>为什么用"序列化同一个 ObjectMapper"而不是逐个打 HTTP</h2>
 * 本类断言的是**JSON 形状**（时间格式 / ID 是 number / 计数字段存在），它只由
 * Boot 自动配置的 {@link ObjectMapper} 决定。逐个端点造夹具会把"形状"断言与各域的
 * 业务前置耦合在一起，失败时分不清是形状漂移还是夹具坏了。
 * 因此直接构造 VO 并用**应用同一个 mapper**序列化 —— 与 MVC 出口逐字同源
 * （{@code JsonCodec} 也用这个 bean，见其类注释）。
 *
 * <p>⚠️ 作用域：本类**不**断言字段值来自 DB（那是各域用例的事），只钉"形状"。
 *
 * <h2>三条契约（§2.7 定稿）</h2>
 * <ol>
 *   <li><b>时间</b>：{@code yyyy-MM-ddTHH:mm:ss}（本地时间、**无偏移**、无毫秒、无 Z）。</li>
 *   <li><b>ID</b>：保持 JSON **number**（自增 id 远小于 2^53；改字符串是零收益返工）。</li>
 *   <li><b>计数</b>：JSON number（Java 侧端点标量统一 {@code long}，见
 *       {@code ApiContractInventoryTests#计数端点出参统一为long}；VO 内字段沿用实体类型，
 *       JSON 层无 int/long 之分）。</li>
 * </ol>
 */
class PayloadShapeContractTests extends AbstractIntegrationTest {

    /** 固定时刻：秒级、无纳秒，便于断言逐字格式。 */
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2026, 10, 23, 12, 34, 56);

    /** §2.7 的时间契约（本地时间、无偏移）。 */
    private static final String ISO_LOCAL = "2026-10-23T12:34:56";

    @Autowired
    private ObjectMapper mapper;

    @Test
    @DisplayName("★ 时间：所有对外 VO 的时间字段逐字为 yyyy-MM-ddTHH:mm:ss（无偏移 / 无毫秒 / 无 Z）")
    void 时间格式跨VO一致() {
        ContentVO content = new ContentVO();
        content.setCreateTime(FIXED_TIME);
        HistoryItemVO history = new HistoryItemVO();
        history.setWatchedTime(FIXED_TIME);
        FavoriteItemVO favorite = new FavoriteItemVO();
        favorite.setFavoriteTime(FIXED_TIME);
        WatchLaterItemVO watchLater = new WatchLaterItemVO();
        watchLater.setAddedTime(FIXED_TIME);
        CouponVO coupon = new CouponVO();
        coupon.setBeginTime(FIXED_TIME);
        coupon.setEndTime(FIXED_TIME);
        coupon.setCreateTime(FIXED_TIME);
        CouponOrderVO couponOrder = new CouponOrderVO();
        couponOrder.setCreateTime(FIXED_TIME);
        MediaAuditResult auditResult = new MediaAuditResult();
        auditResult.setScanTime(FIXED_TIME);
        MediaAuditItem auditItem = new MediaAuditItem();
        auditItem.setLastVerifyTime(FIXED_TIME);

        Map<String, Object> cases = new LinkedHashMap<>();
        cases.put("ContentVO.createTime", content);
        cases.put("HistoryItemVO.watchedTime", history);
        cases.put("FavoriteItemVO.favoriteTime", favorite);
        cases.put("WatchLaterItemVO.addedTime", watchLater);
        cases.put("CouponVO.beginTime", coupon);
        cases.put("CouponOrderVO.createTime", couponOrder);
        cases.put("MediaAuditResult.scanTime", auditResult);
        cases.put("MediaAuditItem.lastVerifyTime", auditItem);

        Map<String, String> fields = Map.of(
                "ContentVO.createTime", "createTime",
                "HistoryItemVO.watchedTime", "watchedTime",
                "FavoriteItemVO.favoriteTime", "favoriteTime",
                "WatchLaterItemVO.addedTime", "addedTime",
                "CouponVO.beginTime", "beginTime",
                "CouponOrderVO.createTime", "createTime",
                "MediaAuditResult.scanTime", "scanTime",
                "MediaAuditItem.lastVerifyTime", "lastVerifyTime");

        cases.forEach((label, vo) -> {
            JsonNode node = mapper.readTree(mapper.writeValueAsString(vo)).path(fields.get(label));
            assertThat(node.isTextual()).as("%s 必须序列化为字符串", label).isTrue();
            assertThat(node.asString())
                    .as("%s 的时间格式（§2.7：本地时间、不引入偏移）", label)
                    .isEqualTo(ISO_LOCAL)
                    .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        });
    }

    @Test
    @DisplayName("★ ID：所有对外 VO 的 id 类字段是 JSON number（不是字符串）")
    void ID保持JSONnumber() {
        ContentVO content = new ContentVO();
        content.setId(9007199254740993L);
        content.setAuthorId(42L);
        AdminContentVO admin = new AdminContentVO();
        admin.setId(7L);
        ProfileVO profile = new ProfileVO();
        profile.setUserId(8L);
        FavoriteFolderVO folder = new FavoriteFolderVO();
        folder.setId(9L);
        FavoriteItemVO favoriteItem = new FavoriteItemVO();
        favoriteItem.setId(10L);
        favoriteItem.setContentId(11L);
        WatchLaterItemVO watchLater = new WatchLaterItemVO();
        watchLater.setContentId(12L);
        HistoryItemVO history = new HistoryItemVO();
        history.setContentId(13L);
        CouponVO coupon = new CouponVO();
        coupon.setId(14L);
        CouponOrderVO couponOrder = new CouponOrderVO();
        couponOrder.setId(15L);
        couponOrder.setCouponId(16L);
        MediaAuditItem media = new MediaAuditItem();
        media.setMediaId(17L);
        media.setContentId(18L);

        Map<String, Object[]> cases = new LinkedHashMap<>();
        // label → [VO, 字段名]
        cases.put("ContentVO.id", new Object[]{content, "id"});
        cases.put("ContentVO.authorId", new Object[]{content, "authorId"});
        cases.put("AdminContentVO.id", new Object[]{admin, "id"});
        cases.put("ProfileVO.userId", new Object[]{profile, "userId"});
        cases.put("FavoriteFolderVO.id", new Object[]{folder, "id"});
        cases.put("FavoriteItemVO.id", new Object[]{favoriteItem, "id"});
        cases.put("FavoriteItemVO.contentId", new Object[]{favoriteItem, "contentId"});
        cases.put("WatchLaterItemVO.contentId", new Object[]{watchLater, "contentId"});
        cases.put("HistoryItemVO.contentId", new Object[]{history, "contentId"});
        cases.put("CouponVO.id", new Object[]{coupon, "id"});
        cases.put("CouponOrderVO.id", new Object[]{couponOrder, "id"});
        cases.put("CouponOrderVO.couponId", new Object[]{couponOrder, "couponId"});
        cases.put("MediaAuditItem.mediaId", new Object[]{media, "mediaId"});
        cases.put("MediaAuditItem.contentId", new Object[]{media, "contentId"});

        cases.forEach((label, pair) -> {
            JsonNode node = mapper.readTree(mapper.writeValueAsString(pair[0])).path((String) pair[1]);
            assertThat(node.isNumber()).as("%s 必须是 JSON number（§2.7：ID 保持 number）", label).isTrue();
        });
    }

    @Test
    @DisplayName("★ 计数：VO 内计数字段是 JSON number（不是字符串 / 不是 null）")
    void 计数字段是JSONnumber() {
        ProfileVO profile = new ProfileVO();
        profile.setFollowerCount(3);
        profile.setFollowCount(4);
        FavoriteFolderVO folder = new FavoriteFolderVO();
        folder.setItemCount(5);
        MediaAuditResult audit = new MediaAuditResult();
        audit.setTotal(6);
        audit.setExisting(7);
        audit.setMissing(8);
        audit.setInvalid(9);
        audit.setNotScanned(0);

        Map<String, Object[]> cases = new LinkedHashMap<>();
        cases.put("ProfileVO.followerCount", new Object[]{profile, "followerCount"});
        cases.put("ProfileVO.followCount", new Object[]{profile, "followCount"});
        cases.put("FavoriteFolderVO.itemCount", new Object[]{folder, "itemCount"});
        cases.put("MediaAuditResult.total", new Object[]{audit, "total"});
        cases.put("MediaAuditResult.existing", new Object[]{audit, "existing"});
        cases.put("MediaAuditResult.missing", new Object[]{audit, "missing"});
        cases.put("MediaAuditResult.invalid", new Object[]{audit, "invalid"});
        cases.put("MediaAuditResult.notScanned", new Object[]{audit, "notScanned"});

        cases.forEach((label, pair) -> {
            JsonNode node = mapper.readTree(mapper.writeValueAsString(pair[0])).path((String) pair[1]);
            assertThat(node.isNumber()).as("%s 必须是 JSON number", label).isTrue();
        });
    }
}
