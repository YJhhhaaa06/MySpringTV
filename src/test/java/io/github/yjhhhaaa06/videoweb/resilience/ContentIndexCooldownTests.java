package io.github.yjhhhaaa06.videoweb.resilience;

import io.github.yjhhhaaa06.videoweb.common.cache.CacheAside;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.config.ContentCacheProperties;
import io.github.yjhhhaaa06.videoweb.common.config.MediaProperties;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentCache;
import io.github.yjhhhaaa06.videoweb.content.cache.ContentRedisOps;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentDao;
import io.github.yjhhhaaa06.videoweb.content.dao.ContentMediaDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 索引重建的**进程内冷却退避**（第三批 T3 / 账 B6）。
 *
 * <h2>这条账的正确性含义（为什么它不是纯优化）</h2>
 * {@code ContentCache.ensureIndex} 在索引 key 缺失时会触发一次 {@code findAllContent()}——
 * 一条**无上限的全表查询**。若 DB 恰好不可用，装载会失败并"跳过重建、保留旧索引"，
 * 但**每次请求**都会再试一次：DB 故障 + 索引缺失 ⇒ <b>每请求一次全表重建</b>。
 * TV 用 10 秒冷却退避把它收敛为"每冷却窗口一次"，本仓迁移期裁掉了这条能力（B6）。
 *
 * <h2>为什么是纯单测（不掺 Testcontainers）</h2>
 * 该行为是 {@code ContentCache} 内部的**进程内状态机**（{@code volatile lastFailedRebuildAtMillis}
 * + 窗口判定），不依赖真实 Redis/DB。用 mock 注入"故障"（loader 抛异常 / 索引写抛异常），
 * 既能精确控制失败，又能**每用例新建一个 ContentCache**——避免单例上的冷却状态跨测试泄漏
 * （集成测试里 {@code Containers} 是进程级单例，这种"跨用例残留的冷却窗口"会制造难查的耦合）。
 *
 * <h2>★ 反向验证（能力的存在证明）</h2>
 * 删掉 {@code ensureIndex} 开头的 {@code if (inIndexRebuildCooldown()) return;}，
 * {@link #冷却窗口内的重复读只重建一次()} 立刻变红（{@code findAllContent} 被调 3 次）。
 */
@Tag("resilience")
class ContentIndexCooldownTests {

    private ContentDao contentDao;
    private ContentRedisOps indexOps;

    /** 造一个只依赖 mock 的 ContentCache；冷却窗口由用例指定。 */
    private ContentCache contentCache(Duration cooldown) {
        contentDao = mock(ContentDao.class);
        ContentMediaDao contentMediaDao = mock(ContentMediaDao.class);
        CacheAside cacheAside = mock(CacheAside.class);
        indexOps = mock(ContentRedisOps.class);
        return new ContentCache(contentDao, contentMediaDao, cacheAside, indexOps,
                new ContentCacheProperties(Duration.ofMinutes(30), cooldown), new MediaProperties(""));
    }

    /** 前置：索引 key 不存在（触发懒重建），且 DB 装载失败（模拟 DB 故障）。 */
    private void stubIndexMissingAndDbFailing() {
        when(indexOps.keyExists(anyString())).thenReturn(false);
        when(contentDao.findAllContent()).thenThrow(new DataAccessException("模拟 DB 故障") {
        });
    }

    @Test
    @DisplayName("★冷却窗口内的重复读只触发一次全表重建（无退避时=每请求一次）")
    void 冷却窗口内的重复读只重建一次() {
        ContentCache cache = contentCache(Duration.ofMinutes(5));
        stubIndexMissingAndDbFailing();

        for (int i = 0; i < 3; i++) {
            assertThat(cache.getRecommendByFilter(null, null, 10))
                    .as("重建失败 ⇒ 降级为空推荐，不 500")
                    .isEmpty();
        }

        verify(contentDao, times(1)).findAllContent();
    }

    @Test
    @DisplayName("★冷却期满后自动重试重建（退避不是永久关闭）")
    void 冷却期满后自动重试重建() throws InterruptedException {
        ContentCache cache = contentCache(Duration.ofMillis(150));
        stubIndexMissingAndDbFailing();

        cache.getRecommendByFilter(null, null, 10);
        verify(contentDao, times(1)).findAllContent();

        Thread.sleep(400); // 越过冷却窗口

        cache.getRecommendByFilter(null, null, 10);
        verify(contentDao, times(2)).findAllContent();
    }

    @Test
    @DisplayName("对照：重建成功不进入冷却——不失败则每次缺失都照常重建")
    void 重建成功不进入冷却() {
        ContentCache cache = contentCache(Duration.ofMinutes(5));
        when(indexOps.keyExists(anyString())).thenReturn(false);
        // findAllContent 默认返回空列表 = 装载成功 ⇒ 每次都应照常重建（冷却不生效）

        for (int i = 0; i < 3; i++) {
            cache.getRecommendByFilter(null, null, 10);
        }

        verify(contentDao, times(3)).findAllContent();
    }

    @Test
    @DisplayName("索引写入失败（Redis 故障）同样进入冷却退避")
    void 索引写入失败同样进入冷却() {
        ContentCache cache = contentCache(Duration.ofMinutes(5));
        when(indexOps.keyExists(anyString())).thenReturn(false);
        doThrow(new CacheUnavailableException("模拟 Redis 故障"))
                .when(indexOps).rebuildIndexes(any(), any());

        for (int i = 0; i < 3; i++) {
            cache.getRecommendByFilter(null, null, 10);
        }

        verify(contentDao, times(1)).findAllContent();
    }
}
