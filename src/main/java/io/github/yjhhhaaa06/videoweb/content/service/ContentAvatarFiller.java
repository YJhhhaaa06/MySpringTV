package io.github.yjhhhaaa06.videoweb.content.service;

import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentDetailVO;
import io.github.yjhhhaaa06.videoweb.content.model.vo.ContentVO;
import io.github.yjhhhaaa06.videoweb.user.service.AvatarReadModel;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内容响应的**作者头像读时装饰**（`R-37`）。
 *
 * <h2>为什么是 filler，而不是 {@code ContentCache#toContentVO} 里顺手填</h2>
 * 形态照 {@link ContentStatusFiller} / {@link ContentCountFiller}：缓存载荷只装慢变字段，
 * 请求期再加"读时才确定"的字段。区别只是来源——互动态来自互动域、计数来自计数聚合，
 * 头像来自 user 域的 {@link AvatarReadModel}（实现 = `AvatarUrlResolver` 的 JVM 缓存）。
 *
 * <p>★ <b>绝不能在 {@code toContentVO} 单条里解析</b>：那会把"一页一次批量"退化成
 * "一条一次"（N+1），与 `T9` 的批量纪律相反。本类只做一件事——收集该页 authorId、
 * 调一次批量契约、写回每个 VO。
 *
 * <h2>为什么头像不进 {@code ContentCacheDTO}</h2>
 * 缓存载荷是全用户共享的慢变快照；头像虽慢变，但换头像后旧 URL 会失效。放进载荷就需要
 * "换头像 ⇒ 失效所有含此作者的内容缓存"的扇出。放在响应层 ⇒ 每次读拿当前 URL，
 * 代价只是一次 JVM 缓存命中（命中不查库）。
 *
 * <h2>匿名也填</h2>
 * 头像是公开数据，与登录态无关；不要照搬 {@link ContentStatusFiller} 的匿名短路。
 *
 * <h2>★ 只给有头像展示位的面调用</h2>
 * 当前只有三处：{@code FeedService#renderPage}（动态卡）、{@code ProfileService}（空间动态 / 投稿）、
 * {@code ContentService#getContentDetailVO}（视频 / 图文作者）。
 * 收藏夹 / 稍后再看 / 历史 / 首页推荐 / 搜索结果都**不显示**作者头像 ⇒ 不调，
 * 响应里的 {@code avatarUrl} 保持 {@code null}（与"按面填"的计数口径一致，不是漏填）。
 *
 * <h2>反向验证</h2>
 * 去掉任一展示面的 {@code fillAvatarBatch} / {@code fillAvatar} ⇒ 该面的
 * "avatarUrl 非空且等于独立 oracle"断言红。
 */
@Component
public class ContentAvatarFiller {

    private final AvatarReadModel avatarReadModel;

    public ContentAvatarFiller(AvatarReadModel avatarReadModel) {
        this.avatarReadModel = avatarReadModel;
    }

    /** 列表出口：一趟批量解析该页所有作者。 */
    public void fillAvatarBatch(List<ContentVO> vos) {
        if (vos == null || vos.isEmpty()) {
            return;
        }
        Set<Long> authorIds = new LinkedHashSet<>();
        for (ContentVO vo : vos) {
            if (vo != null && vo.getAuthorId() > 0) {
                authorIds.add(vo.getAuthorId());
            }
        }
        if (authorIds.isEmpty()) {
            return;
        }
        Map<Long, String> urls = avatarReadModel.resolveBatch(authorIds);
        for (ContentVO vo : vos) {
            if (vo != null && vo.getAuthorId() > 0) {
                vo.setAvatarUrl(urls.get(vo.getAuthorId()));
            }
        }
    }

    /** 详情出口：单条。 */
    public void fillAvatar(ContentDetailVO vo) {
        if (vo == null || vo.getAuthorId() <= 0) {
            return;
        }
        vo.setAvatarUrl(avatarReadModel.resolve(vo.getAuthorId()));
    }
}
