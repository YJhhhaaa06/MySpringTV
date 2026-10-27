<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { HistoryItemVM } from '../types/ui'
import { groupHistoryByDay, loadHistory } from '../api/history'
import { describeError } from '../api/http'
import { restoreSession, sessionState } from '../auth/session'
import { requireLogin } from '../auth/loginPrompt'
import AppIcon from '../components/common/AppIcon.vue'
import EmptyState from '../components/common/EmptyState.vue'
import Pagination from '../components/common/Pagination.vue'
import HistoryCard from '../components/history/HistoryCard.vue'

/**
 * 观看历史（`/history`）：B 站 2026-10 版式 —— **左侧日期时间轴 + 右侧多列卡片网格**。
 *
 * <h2>版式对照（用户提供的登录态截图）</h2>
 * - 时间轴：`今天` / `昨天` / `MM-DD` 在左栏，竖线 + 空心圆点贯穿整组；
 * - 卡片：封面底「已看/总长」条（`18:09/49:45` / `已看完` / `看到 12:00`）+ 进度线，
 *   标题两行，meta =「UP 主 · 今天 12:57」；
 * - 1600 视口下 **4 列**（左栏 76px + 间距 24px 后约 290px/卡）——
 *   旧版"一行一条"太稀，这一版是照截图重做的。
 *
 * <h2>★ 不渲染的 B 站控件（后端没有对应能力，摆了就是撒谎）</h2>
 * - tabs「综合 / 视频 / 直播 / 专栏」+「更多筛选」+ 搜索框：`GET /history` 只有 `page/pageSize`
 *   （GAP-20，无 `type` / `keyword` 参数）；
 * - 「清空历史」「批量管理」与逐条删除：没有删除端点（GAP-19）；
 * - 「记录浏览历史」开关：没有用户级开关端点（GAP-21）。
 * 这三块**不是本窗口漏做**，台账里有记录；后端补了之后再加。
 *
 * <h2>页面级身份态</h2>
 * 整个列表都属于"我" ⇒ 先 `await restoreSession()`，匿名渲染「登录后查看」+ 登录按钮
 * （按钮走动作级闸门 `requireLogin()`，登录成功后 watcher 自动装载）。
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
          <div v-for="n in 8" :key="n" class="history-skeleton__cell">
            <div class="skeleton history-skeleton__cover" />
            <div class="skeleton history-skeleton__line" />
            <div class="skeleton history-skeleton__line history-skeleton__line--short" />
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
          <!-- 时间轴的竖线画在**整段**上（不是每组自己的 border），组与组之间才连续 -->
          <div class="history-timeline">
            <section v-for="group in groups" :key="group.key" class="history-group">
              <div class="history-group__rail">
                <h2 class="history-group__label">{{ group.label }}</h2>
              </div>
              <div class="history-group__main">
                <div class="history-grid">
                  <HistoryCard
                    v-for="item in group.items"
                    :key="item.contentId"
                    :item="item"
                    :day-label="group.label"
                  />
                </div>
              </div>
            </section>
          </div>
          <Pagination :page="page" :total-pages="totalPages" @update:page="goPage" />
        </template>
      </template>
    </div>
  </main>
</template>

<style scoped>
.history-page {
  min-height: 100vh;
  /*
   * B 站历史页单独一档宽度（2026-10-27 复核）：
   * - 线上 `history-record` 的 `.history-wrap` 在 `min-width:1415px` 下就是 **1160px**；
   * - 登录态 2560 截图（1707 CSS）实测：时间轴 76 + 4 列 ×258 + 3×17 ≈ 1188。
   * 旧口径「锁 1320」比 B 站宽 11%（卡片 293 vs 258）⇒ 本窗口改成 1160。
   */
  --content-w: 1160px;
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
  margin-left: auto;
  font-size: 12px;
  color: var(--text-3);
}

.history-state {
  padding: 48px 0;
  font-size: 13px;
  color: var(--text-3);
  text-align: center;
}

/* 时间轴：竖线画在整段容器上（组间连续），每组 = 左栏标签 + 右栏网格 */
.history-timeline {
  position: relative;
}

.history-timeline::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 30px;
  left: 76px;
  width: 1px;
  background: var(--line-1);
}

.history-group {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  margin-top: 26px;
}

.history-group__rail {
  position: relative;
  padding: 6px 24px 0 0;
  text-align: right;
}

.history-group__rail::after {
  content: '';
  position: absolute;
  top: 12px;
  right: -4px;
  width: 8px;
  height: 8px;
  border: 2px solid var(--text-4);
  border-radius: 50%;
  background: var(--bg-0);
}

.history-group__label {
  font-size: 15px;
  font-weight: 600;
  line-height: 22px;
  color: var(--text-2);
}

.history-group__main {
  padding: 0 0 30px 24px;
}

/* B 站 1600 下是 4 列；1372 起主区变窄，逐级降列 */
.history-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 26px 16px;
}

.history-skeleton {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 26px 16px;
  margin-top: 26px;
}

.history-skeleton__cover {
  aspect-ratio: 16 / 9;
}

.history-skeleton__line {
  height: 16px;
  margin-top: 10px;
}

.history-skeleton__line--short {
  width: 45%;
  margin-top: 8px;
}

@media (max-width: 1200px) {
  .history-grid,
  .history-skeleton {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 1000px) {
  .history-grid,
  .history-skeleton {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .history-timeline::before {
    display: none;
  }

  .history-group {
    grid-template-columns: minmax(0, 1fr);
    margin-top: 18px;
  }

  .history-group__rail {
    padding: 0 0 10px;
    text-align: left;
  }

  .history-group__rail::after {
    display: none;
  }

  .history-group__main {
    padding: 0 0 20px;
  }
}
</style>
