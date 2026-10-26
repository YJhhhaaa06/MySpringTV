<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { HistoryItemVM } from '../types/ui'
import { groupHistoryByDay, loadHistory } from '../api/history'
import { describeError } from '../api/http'
import { restoreSession, sessionState } from '../auth/session'
import { requireLogin } from '../auth/loginPrompt'
import AppIcon from '../components/common/AppIcon.vue'
import EmptyState from '../components/common/EmptyState.vue'
import Pagination from '../components/common/Pagination.vue'

/**
 * 观看历史（`/history`）：按天分组 → 每行 = 封面（带进度条）+ 标题 + UP + 看到哪儿 + 观看时刻。
 *
 * <h2>版式按 B 站「历史记录」</h2>
 * 顶部图标 + 标题；登录后是**列表**（不是卡片网格），日期分组头 `今天 / 昨天 / MM-DD`；
 * 封面底部有一条播放进度条 —— 这是历史页与普通卡片列表唯一的形态差异，也是它的价值所在。
 *
 * <h2>★ GAP-19：后端没有「删除单条 / 清空历史」端点</h2>
 * `HistoryController` 只有 list / position / flush / report / play 五条。
 * ⇒ 页面**不渲染** B 站那样的单条删除与「清空历史」（摆了也点不动，等于撒谎），
 * 已登记进台账等后端；这不是本窗口漏做。
 *
 * <h2>页面级身份态</h2>
 * 同收藏页：整个列表都属于"我"，匿名请求必然 401 ⇒ 未登录渲染「登录后查看」+ 登录按钮，
 * 点按钮走动作级闸门 `requireLogin()`（登录成功后 watcher 自动装载）。
 *
 * <h2>失效条目</h2>
 * 后端保留 `contentId / progress / watchedTime`，但标题固定文案、封面/作者/时长是 `null`。
 * 页面渲染成灰行、不可点、不画进度条（**观看时间是事实，保留显示**）。
 */
const route = useRoute()
const router = useRouter()

const PAGE_SIZE = 20

const sessionReady = ref(false)
const loggedIn = computed(() => sessionState.status === 'authenticated')
const page = computed(() => {
  const value = Number(route.query.page)
  return Number.isInteger(value) && value > 0 ? value : 1
})

const items = ref<HistoryItemVM[]>([])
const total = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const error = ref('')
/** 封面解码失败的行（避免破图；不是 404，用 CDN 也会偶发） */
const brokenCovers = reactive<Record<number, boolean>>({})

const groups = computed(() => groupHistoryByDay(items.value))

onMounted(async () => {
  await restoreSession()
  sessionReady.value = true
})

watch(
  () => sessionState.status,
  (status) => {
    if (status === 'authenticated') {
      void load()
      return
    }
    if (status === 'anonymous') {
      items.value = []
      total.value = 0
      totalPages.value = 0
    }
  },
  { immediate: true },
)

watch(page, () => {
  if (sessionState.status === 'authenticated') void load()
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await loadHistory(page.value, PAGE_SIZE)
    items.value = result.items
    total.value = result.total
    totalPages.value = result.totalPages
  } catch (exception) {
    items.value = []
    error.value = describeError(exception)
  } finally {
    loading.value = false
  }
}

async function signIn(): Promise<void> {
  await requireLogin()
}

function goPage(nextPage: number): void {
  void router.push({ query: nextPage > 1 ? { page: String(nextPage) } : {} })
}

function guardInvalid(item: HistoryItemVM, event: MouseEvent): void {
  if (item.invalid) event.preventDefault()
}

function markBroken(contentId: number): void {
  brokenCovers[contentId] = true
}
</script>

<template>
  <main class="history-page page-main">
    <div class="page-content">
      <header class="history-head">
        <span class="history-head__icon"><AppIcon name="clock" :size="22" /></span>
        <h1 class="history-head__title">历史记录</h1>
        <span v-if="loggedIn && total" class="history-head__meta">共 {{ total }} 条</span>
      </header>

      <p v-if="!sessionReady" class="history-state">正在恢复登录态…</p>

      <EmptyState
        v-else-if="!loggedIn"
        icon="clock"
        title="登录后查看观看历史"
        description="历史只属于账号：登录后按最后观看时间倒序，看到哪儿也一起同步（Bearer token，不用 Cookie）。"
      >
        <button class="btn btn-primary" type="button" @click="signIn">立即登录</button>
      </EmptyState>

      <template v-else>
        <div v-if="loading && !items.length" class="history-skeleton">
          <div v-for="n in 5" :key="n" class="history-skeleton__row">
            <div class="skeleton history-skeleton__cover" />
            <div class="history-skeleton__body">
              <div class="skeleton history-skeleton__line" />
              <div class="skeleton history-skeleton__line history-skeleton__line--short" />
            </div>
          </div>
        </div>

        <EmptyState v-else-if="error" icon="clock" title="打不开观看历史" :description="error">
          <button class="btn btn-blue" type="button" @click="load">重试</button>
        </EmptyState>

        <EmptyState
          v-else-if="!items.length"
          icon="clock"
          title="还没有观看记录"
          description="在播放页看过视频后，会按天出现在这里。"
        >
          <a class="btn btn-ghost" href="/">去首页看看</a>
        </EmptyState>

        <template v-else>
          <section v-for="group in groups" :key="group.key" class="history-group">
            <h2 class="history-group__title">{{ group.label }}</h2>
            <ul class="history-list">
              <li
                v-for="item in group.items"
                :key="item.contentId"
                class="history-row"
                :class="{ 'is-invalid': item.invalid }"
              >
                <a
                  class="history-row__cover"
                  :href="item.href"
                  :aria-disabled="item.invalid || undefined"
                  :aria-label="item.title"
                  @click="guardInvalid(item, $event)"
                >
                  <img
                    v-if="item.coverUrl && !brokenCovers[item.contentId]"
                    class="history-row__img"
                    :src="item.coverUrl"
                    :alt="item.title"
                    loading="lazy"
                    decoding="async"
                    @error="markBroken(item.contentId)"
                  />
                  <span v-else class="history-row__cover-fallback" aria-hidden="true">
                    <AppIcon name="tv" :size="24" />
                  </span>
                  <span
                    v-if="item.progressPercent > 0"
                    class="history-row__bar"
                    :style="{ width: `${item.progressPercent}%` }"
                    aria-hidden="true"
                  />
                  <span v-if="item.invalid" class="history-row__badge">已失效</span>
                </a>

                <div class="history-row__body">
                  <a
                    class="history-row__title"
                    :href="item.href"
                    :aria-disabled="item.invalid || undefined"
                    @click="guardInvalid(item, $event)"
                  >{{ item.title }}</a>
                  <p class="history-row__meta">
                    <template v-if="item.authorName">
                      <AppIcon name="user" :size="13" />
                      <span>{{ item.authorName }}</span>
                    </template>
                    <template v-if="item.durationText">
                      <span v-if="item.authorName" class="history-row__dot">·</span>
                      <span>时长 {{ item.durationText }}</span>
                    </template>
                  </p>
                  <p v-if="item.progressText" class="history-row__progress">{{ item.progressText }}</p>
                </div>

                <span class="history-row__time">{{ item.watchedTimeText }}</span>
              </li>
            </ul>
          </section>
          <Pagination :page="page" :total-pages="totalPages" @update:page="goPage" />
        </template>
      </template>
    </div>
  </main>
</template>

<style scoped>
.history-page {
  min-height: 100vh;
}

.history-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line-2);
}

.history-head__icon {
  display: flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--brand-blue);
  background: var(--brand-blue-bg);
}

.history-head__title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-1);
}

.history-head__meta {
  margin-left: 4px;
  font-size: 12px;
  color: var(--text-3);
}

.history-state {
  padding: 48px 0;
  font-size: 13px;
  color: var(--text-3);
  text-align: center;
}

.history-group {
  margin-top: 22px;
}

.history-group__title {
  padding: 0 0 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.history-list {
  border-top: 1px solid var(--line-2);
}

.history-row {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr) 56px;
  gap: 16px;
  align-items: center;
  padding: 14px 0;
  border-bottom: 1px solid var(--line-2);
}

.history-row__cover {
  position: relative;
  display: block;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--bg-2);
}

.history-row__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.history-row__cover-fallback {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: var(--text-4);
  background: linear-gradient(135deg, #f1f2f3 0%, #e3e5e7 100%);
}

/* 底部进度条：宽度 = progress / duration（B 站历史页的标志性元素） */
.history-row__bar {
  position: absolute;
  bottom: 0;
  left: 0;
  height: 3px;
  background: var(--brand-pink);
}

.history-row__badge {
  position: absolute;
  top: 6px;
  left: 6px;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 12px;
  line-height: 16px;
  color: var(--text-inverse);
  background: rgba(0, 0, 0, 0.62);
}

.history-row__body {
  min-width: 0;
}

.history-row__title {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  line-height: 22px;
  color: var(--text-1);
  word-break: break-word;
}

.history-row__title:hover {
  color: var(--brand-blue);
}

.history-row__meta {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  font-size: 13px;
  color: var(--text-3);
}

.history-row__dot {
  color: var(--text-4);
}

.history-row__progress {
  margin-top: 4px;
  font-size: 12px;
  color: var(--brand-pink);
}

.history-row__time {
  font-size: 12px;
  color: var(--text-3);
  text-align: right;
  font-variant-numeric: tabular-nums;
}

/* 失效行：灰卡不可点；观看时间仍是事实，保留 */
.history-row.is-invalid .history-row__cover,
.history-row.is-invalid .history-row__title {
  cursor: default;
}

.history-row.is-invalid .history-row__img,
.history-row.is-invalid .history-row__cover-fallback {
  filter: grayscale(1);
  opacity: 0.82;
}

.history-row.is-invalid .history-row__title {
  color: var(--text-3);
}

.history-skeleton {
  margin-top: 22px;
}

.history-skeleton__row {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr);
  gap: 16px;
  padding: 14px 0;
}

.history-skeleton__cover {
  aspect-ratio: 16 / 9;
}

.history-skeleton__body {
  padding-top: 8px;
}

.history-skeleton__line {
  height: 16px;
  margin-bottom: 10px;
}

.history-skeleton__line--short {
  width: 40%;
}

@media (max-width: 760px) {
  .history-row {
    grid-template-columns: 120px minmax(0, 1fr);
  }

  .history-row__time {
    display: none;
  }

  .history-skeleton__row {
    grid-template-columns: 120px minmax(0, 1fr);
  }
}
</style>
