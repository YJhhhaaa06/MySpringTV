<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import type { Count, RelationPageVM, RelationUserVM } from '../../types/ui'
import { loadFollowers, loadFollowing } from '../../api/relations'
import { followUser, unfollowUser } from '../../api/interactions'
import { describeError } from '../../api/http'
import { formatCount } from '../../api/adapters/format'
import { requireLogin } from '../../auth/loginPrompt'
import { sessionState } from '../../auth/session'
import { showToast } from '../../common/toast'
import AppIcon from '../common/AppIcon.vue'
import EmptyState from '../common/EmptyState.vue'
import Pagination from '../common/Pagination.vue'
import UserAvatar from '../common/UserAvatar.vue'

/**
 * 空间页的「关注 / 粉丝」面板（B 站 `/{uid}/relation/following` 的对应物）。
 *
 * <h2>版式（B 站 2026-10-26 登录态截图 `space-following`）</h2>
 * 左：关注/粉丝两个入口（B 站那里是分组列表，本仓没有分组端点 ⇒ 只有两项）；
 * 右：「全部关注 / 全部粉丝」+ 3 列用户卡（首字头像 + 名字 + 关注按钮）。
 * tab 条保持 主页 / 收藏，进入本面板后**两个 tab 都不高亮**（B 站同样没有高亮项）。
 *
 * <h2>★ 降级（GAP-25，界面上不假装有）</h2>
 * `FollowUserVO` 只有 `userId / username / isFollowed / isSelf`：
 * - 没有头像 ⇒ 首字 + 渐变底；没有签名 ⇒ **不渲染**（B 站那句「这个人很神秘」是
 *   B 站自己的默认签名，我们连"有没有签名"都不知道，写上去就是编）；
 * - 没有分组 / 最近关注 / 最常访问 / 关键词搜索 / 批量操作 ⇒ 一个都不摆
 *   （这些在 B 站各自有端点，本仓没有）。
 *
 * <h2>鉴权</h2>
 * 两个端点都是类级 `@RequiresLogin`（B 站同样：`x/relation/followings` 匿名 `-101`）
 * ⇒ 匿名渲染「登录后查看」+ 登录按钮，**不把 401 画成空列表**。登录成功后本组件的
 * session watcher 自动装载（深链直接落在本 tab 也不会停在空白）。
 */
const props = defineProps<{
  userId: number
  kind: 'following' | 'followers'
  page: number
  isSelf: boolean
  followingCount: Count
  followerCount: Count
}>()

const emit = defineEmits<{
  (event: 'update:kind', kind: 'following' | 'followers'): void
  (event: 'update:page', page: number): void
  /** 关注关系变了 ⇒ 让空间页重读头部计数（服务端权威值，不自己加减） */
  (event: 'follow-changed'): void
}>()

const relation = ref<RelationPageVM | null>(null)
const loading = ref(false)
const error = ref('')
/** 正在请求关注/取关的用户（同一人只发一次；其余人不受影响） */
const busy = reactive(new Set<number>())

const title = computed(() => (props.kind === 'following' ? '全部关注' : '全部粉丝'))

/**
 * 竞态守卫：切「关注 ↔ 粉丝」很快时，先发的请求可能后到 ⇒ 旧结果会覆盖新列表。
 * 序号只认最后一次装载。
 */
let loadSeq = 0

async function load(): Promise<void> {
  if (props.userId <= 0) return
  // 匿名不请求（端点必然 401）：把身份问题说清楚，不画成空列表
  if (sessionState.status !== 'authenticated') {
    relation.value = null
    error.value = ''
    return
  }
  const seq = ++loadSeq
  loading.value = true
  error.value = ''
  try {
    const page =
      props.kind === 'following'
        ? await loadFollowing(props.userId, props.page)
        : await loadFollowers(props.userId, props.page)
    if (seq !== loadSeq) return
    relation.value = page
  } catch (exception) {
    if (seq !== loadSeq) return
    relation.value = null
    error.value = describeError(exception)
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

watch(
  [() => props.userId, () => props.kind, () => props.page, () => sessionState.status],
  () => {
    void load()
  },
  { immediate: true },
)

/**
 * 列表里的关注按钮（真接口 `PUT|DELETE /follows/{id}`）。
 *
 * <p>★ 关注列表里取关 ⇒ 他不再属于「我关注的人」⇒ **乐观摘掉这一行 + 回读**；
 * 粉丝列表里关注/取关只改按钮态（那个人仍然是粉丝），但也回读当前页
 * （后端可能把行挪到别的页 —— 不做前端假排序）。
 */
async function toggleFollow(user: RelationUserVM): Promise<void> {
  if (user.isSelf || busy.has(user.id)) return
  if (!(await requireLogin())) return
  const target = !user.followed
  const before = user.followed
  busy.add(user.id)
  user.followed = target
  try {
    if (target) await followUser(user.id)
    else await unfollowUser(user.id)
    showToast(target ? '已关注' : '已取消关注')
    emit('follow-changed')
    if (props.kind === 'following' && !target) {
      if (relation.value) {
        relation.value = {
          ...relation.value,
          items: relation.value.items.filter((item) => item.id !== user.id),
          total: Math.max(0, relation.value.total - 1),
        }
      }
      await load()
      if (relation.value && !relation.value.items.length && props.page > 1) {
        emit('update:page', props.page - 1)
      }
    } else {
      await load()
    }
  } catch (exception) {
    user.followed = before
    showToast(describeError(exception), 'error')
  } finally {
    busy.delete(user.id)
  }
}

async function signIn(): Promise<void> {
  await requireLogin()
}
</script>

<template>
  <div class="relation-layout">
    <aside class="relation-nav">
      <p class="relation-nav__title">{{ isSelf ? '我的关注' : 'TA 的关注' }}</p>
      <button
        class="relation-nav__item"
        :class="{ 'is-active': kind === 'following' }"
        type="button"
        @click="emit('update:kind', 'following')"
      >
        <AppIcon name="user" :size="16" />
        <span>全部关注</span>
        <span class="relation-nav__count">{{ formatCount(followingCount) }}</span>
      </button>
      <button
        class="relation-nav__item"
        :class="{ 'is-active': kind === 'followers' }"
        type="button"
        @click="emit('update:kind', 'followers')"
      >
        <AppIcon name="user" :size="16" />
        <span>全部粉丝</span>
        <span class="relation-nav__count">{{ formatCount(followerCount) }}</span>
      </button>
    </aside>

    <section class="relation-main">
      <header class="relation-head">
        <h2 class="relation-head__title">{{ title }}</h2>
        <span v-if="relation?.total" class="relation-head__meta">共 {{ relation.total }} 人</span>
      </header>

      <p v-if="sessionState.status === 'restoring'" class="relation-state">正在恢复登录态…</p>

      <EmptyState
        v-else-if="sessionState.status !== 'authenticated'"
        icon="user"
        title="登录后查看关注列表"
        description="关注关系只对账号可见（B 站同样要登录才能看）：登录后会显示这个 UP 主的关注与粉丝。"
      >
        <button class="btn btn-blue" type="button" @click="signIn">登录</button>
      </EmptyState>

      <p v-else-if="loading && !relation" class="relation-state">加载中…</p>

      <EmptyState v-else-if="error" icon="user" title="关注列表加载失败" :description="error">
        <button class="btn btn-blue" type="button" @click="load">重试</button>
      </EmptyState>

      <template v-else-if="relation">
        <p v-if="!relation.items.length" class="relation-state">
          {{ kind === 'following' ? '还没有关注任何人' : '还没有粉丝' }}
        </p>
        <template v-else>
          <ul class="relation-grid">
            <li v-for="user in relation.items" :key="user.id" class="relation-card">
              <RouterLink
                class="relation-card__avatar"
                :to="{ name: 'space', params: { id: user.id } }"
                :title="`${user.name} 的空间`"
              ><UserAvatar :user-id="user.id" :initial="user.initial" :size="60" /></RouterLink>
              <div class="relation-card__body">
                <RouterLink
                  class="relation-card__name"
                  :to="{ name: 'space', params: { id: user.id } }"
                >{{ user.name }}</RouterLink>
                <button
                  v-if="!user.isSelf"
                  class="relation-card__follow"
                  :class="{ 'is-followed': user.followed }"
                  type="button"
                  :disabled="busy.has(user.id)"
                  :title="user.followed ? '点击取消关注' : '点击关注'"
                  @click="toggleFollow(user)"
                >{{ user.followed ? '已关注' : '关注' }}</button>
                <span v-else class="relation-card__self">当前用户</span>
              </div>
            </li>
          </ul>
          <Pagination
            :page="relation.page"
            :total-pages="relation.totalPages"
            @update:page="emit('update:page', $event)"
          />
        </template>
      </template>
    </section>
  </div>
</template>

<style scoped>
.relation-layout {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 28px;
  margin-top: 20px;
  align-items: start;
}

.relation-nav {
  padding: 14px;
  border-radius: var(--radius);
  background: var(--bg-1);
}

.relation-nav__title {
  padding: 0 8px 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
}

.relation-nav__item {
  display: flex;
  width: 100%;
  height: 40px;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  border-radius: var(--radius-sm);
  font-size: 14px;
  color: var(--text-2);
  text-align: left;
}

.relation-nav__item:hover {
  color: var(--brand-blue);
  background: var(--bg-0);
}

.relation-nav__item.is-active {
  color: var(--text-inverse);
  background: var(--brand-blue);
}

.relation-nav__count {
  margin-left: auto;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  opacity: 0.85;
}

.relation-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line-2);
}

.relation-head__title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-1);
}

.relation-head__meta {
  font-size: 12px;
  color: var(--text-3);
}

.relation-state {
  padding: 48px 0;
  font-size: 13px;
  color: var(--text-3);
  text-align: center;
}

/* B 站是 3 列 × 10 行；卡片没有边框，靠留白分行 */
.relation-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 16px;
  margin-top: 12px;
}

.relation-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 10px;
  border-radius: var(--radius);
}

.relation-card:hover {
  background: var(--bg-1);
}

.relation-card__avatar {
  display: block;
  flex: none;
  border-radius: 50%;
  line-height: 0;
}

.relation-card__body {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.relation-card__name {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.relation-card__name:hover {
  color: var(--brand-blue);
}

.relation-card__follow {
  height: 28px;
  padding: 0 12px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  color: var(--text-2);
  background: var(--bg-2);
}

.relation-card__follow:hover:not(:disabled) {
  color: var(--text-1);
  background: var(--line-1);
}

/* 没关注 ⇒ 粉色（与空间页头部的关注按钮同色）；已关注 ⇒ 灰（B 站同） */
.relation-card__follow:not(.is-followed) {
  color: var(--text-inverse);
  background: var(--brand-pink);
}

.relation-card__follow:not(.is-followed):hover:not(:disabled) {
  color: var(--text-inverse);
  background: var(--brand-pink-dark);
}

.relation-card__follow:disabled {
  opacity: 0.6;
}

.relation-card__self {
  font-size: 12px;
  color: var(--text-3);
}

@media (max-width: 1100px) {
  .relation-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .relation-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .relation-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
