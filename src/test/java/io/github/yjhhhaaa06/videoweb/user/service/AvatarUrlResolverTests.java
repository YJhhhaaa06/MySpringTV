package io.github.yjhhhaaa06.videoweb.user.service;

import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import io.github.yjhhhaaa06.videoweb.user.model.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AvatarUrlResolver} 的三态 / TTL / 负缓存 / 写纪元守卫单测（`R-37` / `T121`）。
 *
 * <h2>为什么是"可观察行为"而不是内部 Map 断言</h2>
 * 缓存是否命中，唯一有意义的判据是**有没有再查库** ⇒ 全部用
 * {@code verify(userDao, times(n))} 的调用次数断言，不读私有字段。
 *
 * <h2>★ 时间用可控 Clock</h2>
 * TTL 边界不允许 {@code Thread.sleep}（慢且抖）。注入 {@link MutableClock} 后
 * "4:59 命中 / 5:00 回源" 是可复算的等式，而不是概率。
 */
class AvatarUrlResolverTests {

    private static final Instant T0 = Instant.parse("2026-11-08T00:00:00Z");

    private UserDao userDao;
    private MutableClock clock;
    private AvatarUrlResolver resolver;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        clock = new MutableClock(T0);
        resolver = new AvatarUrlResolver(userDao, clock);
        when(userDao.findAvatarRefsByIds(anyList())).thenReturn(List.of());
    }

    @Test
    @DisplayName("首次批量：一趟 IN 回源，合法 id 都有 URL（自定义 / NULL 回落 / 查无此人）")
    void 首次批量回源并给每个合法id一个URL() {
        when(userDao.findAvatarRefsByIds(anyList())).thenReturn(List.of(
                user(1L, "/upload/avatar/a.jpg"),
                user(2L, null)));

        Map<Long, String> urls = resolver.resolveBatch(Arrays.asList(1L, 2L, 3L, 1L));

        assertThat(urls)
                .containsEntry(1L, "/upload/avatar/a.jpg")
                .containsEntry(2L, "/avatars/default-3.svg")
                .containsEntry(3L, "/avatars/default-4.svg")
                .hasSize(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(userDao).findAvatarRefsByIds(captor.capture());
        assertThat(captor.getValue()).as("去重 + 只留合法 id").containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("命中不查库：第二次同 id 不再回源")
    void 命中不查库() {
        resolver.resolve(1L);
        resolver.resolve(1L);

        verify(userDao, times(1)).findAvatarRefsByIds(anyList());
    }

    @Test
    @DisplayName("★ TTL 5min：4:59 命中，5:00 回源（命中不续期）")
    void ttl边界() {
        resolver.resolve(1L);

        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        resolver.resolve(1L);
        verify(userDao, times(1)).findAvatarRefsByIds(anyList());

        clock.advance(Duration.ofSeconds(1));
        resolver.resolve(1L);
        verify(userDao, times(2)).findAvatarRefsByIds(anyList());
    }

    @Test
    @DisplayName("★ 负缓存：avatar_ref 为 NULL 的用户也只回源一次")
    void 负缓存() {
        when(userDao.findAvatarRefsByIds(anyList())).thenReturn(List.of(user(2L, null)));

        String first = resolver.resolve(2L);
        String second = resolver.resolve(2L);

        assertThat(first).isEqualTo("/avatars/default-3.svg").isEqualTo(second);
        verify(userDao, times(1)).findAvatarRefsByIds(anyList());
    }

    @Test
    @DisplayName("★ 写后失效：invalidate 后下一次读必须回源拿新值")
    void 写后失效() {
        when(userDao.findAvatarRefsByIds(anyList()))
                .thenReturn(List.of(user(1L, null)))
                .thenReturn(List.of(user(1L, "/upload/avatar/new.jpg")));

        assertThat(resolver.resolve(1L)).isEqualTo("/avatars/default-2.svg");
        resolver.invalidate(1L);

        assertThat(resolver.resolve(1L)).isEqualTo("/upload/avatar/new.jpg");
        verify(userDao, times(2)).findAvatarRefsByIds(anyList());
    }

    @Test
    @DisplayName("混合批量：只把未命中的子集交给 IN 查询")
    void 混合批量只查未命中() {
        when(userDao.findAvatarRefsByIds(anyList()))
                .thenReturn(List.of(user(1L, "/upload/avatar/a.jpg")))
                .thenReturn(List.of(user(2L, "/upload/avatar/b.jpg")));

        resolver.resolve(1L);
        resolver.resolveBatch(List.of(1L, 2L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(userDao, times(2)).findAvatarRefsByIds(captor.capture());
        assertThat(captor.getAllValues().get(1)).containsExactly(2L);
    }

    @Test
    @DisplayName("非法 id：null / 0 / 负数一律忽略，不产生查询")
    void 非法id被忽略() {
        assertThat(resolver.resolveBatch(Arrays.asList(null, 0L, -1L))).isEmpty();
        verifyNoInteractions(userDao);
    }

    @Test
    @DisplayName("★ 写纪元守卫：回源期间发生写 ⇒ 本次不回填，下一次重新回源")
    void 回源期间的写不会被回填覆盖() {
        AtomicInteger calls = new AtomicInteger();
        when(userDao.findAvatarRefsByIds(anyList())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                resolver.invalidate(1L);
                return List.of(user(1L, "/upload/avatar/old.jpg"));
            }
            return List.of(user(1L, "/upload/avatar/new.jpg"));
        });

        assertThat(resolver.resolve(1L)).isEqualTo("/upload/avatar/old.jpg");
        assertThat(resolver.resolve(1L))
                .as("第一次的回填被放弃，第二次必须重新回源")
                .isEqualTo("/upload/avatar/new.jpg");
        verify(userDao, times(2)).findAvatarRefsByIds(anyList());
    }

    @Test
    @DisplayName("容量上限：达到上限后清空重来，不会无界增长")
    void 容量上限() {
        resolver = new AvatarUrlResolver(userDao, clock, 2);
        when(userDao.findAvatarRefsByIds(anyList()))
                .thenReturn(List.of(user(1L, "/upload/avatar/1.jpg"), user(2L, "/upload/avatar/2.jpg")))
                .thenReturn(List.of(user(3L, "/upload/avatar/3.jpg")))
                .thenReturn(List.of(user(1L, "/upload/avatar/1.jpg")));

        resolver.resolveBatch(List.of(1L, 2L));
        resolver.resolve(3L);
        assertThat(resolver.resolve(1L)).as("被清空后必须重新回源").isEqualTo("/upload/avatar/1.jpg");

        verify(userDao, times(3)).findAvatarRefsByIds(anyList());
    }

    private static User user(long id, String avatarRef) {
        User user = new User();
        user.setId(id);
        user.setAvatarRef(avatarRef);
        return user;
    }

    /** 可推进的 Clock：TTL 边界必须是等式，不能靠 sleep。 */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
