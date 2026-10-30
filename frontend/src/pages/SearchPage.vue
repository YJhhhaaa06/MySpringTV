<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { searchContents } from '../api/endpoints'
import { parseSearchFilters, toSearchQuery } from '../api/adapters/searchFilters'
import type { SearchOrder, SearchType } from '../api/adapters/searchFilters'
import { ApiError } from '../api/http'
import type { SearchResultVM } from '../types/ui'
import EmptyState from '../components/common/EmptyState.vue'
import Pagination from '../components/common/Pagination.vue'
import VideoGrid from '../components/home/VideoGrid.vue'

/**
 * 搜索页：`GET /contents?keyword=&type=&order=&page=&pageSize=`（**搜索专用**；空白关键词后端会 400）。
 *
 * <p>GAP-10（2026-10-27）起后端支持 `type=VIDEO|POST` 与 `order=LATEST|PLAY`：
 * tab 与排序条都落到 **URL query**（可分享、可后退），缺省态不写键（综合 / 最新）。
 * <p>★ **图文 tab 不渲染排序条**：图文的 `play_count` 结构性恒 0（`/play` 只收视频），
 * "最多播放"在那里等于按 id 倒序；且 `adapters/searchFilters` 会把 `type=POST` 下的 `PLAY`
 * 直接丢掉 ⇒ 这条退化路径从客户端不可达。后端**不设特例**（少一条只在一种组合下成立的校验）。
 */
const route = useRoute()
const router = useRouter()

const keyword = computed(() => (typeof route.query.keyword === 'string' ? route.query.keyword.trim() : ''))
const page = computed(() => {
  const raw = Number(route.query.page)
  return Number.isInteger(raw) && raw > 0 ? raw : 1
})
/** URL → 筛选；非法值在这里被静默丢弃（后端对非法枚举是 400，手改 URL 不该让整页挂掉）。 */
const filters = computed(() => parseSearchFilters(route.query))
/** 只随**取值**变化触发重新请求（computed 每次重算都是新对象，直接 watch 会导致翻页请求两次）。 */
const filterKey = computed(() => `${filters.value.type ?? ''}:${filters.value.order ?? ''}`)

/** 分类 tab：`undefined` = 综合（不筛）。 */
const TYPE_TABS: ReadonlyArray<{ label: string; value: SearchType | undefined }> = [
  { label: '综合', value: undefined },
  { label: '视频', value: 'VIDEO' },
  { label: '图文', value: 'POST' },
]

/** 排序条：`undefined` = 最新发布（后端缺省，与不传等价）。 */
const SORT_OPTIONS: ReadonlyArray<{ label: string; value: SearchOrder | undefined }> = [
  { label: '最新发布', value: undefined },
  { label: '最多播放', value: 'PLAY' },
]

/** 空结果的提示要**说清是哪一层空的**：筛了分类却提示"换个关键词"会让人以为全站没有。 */
const emptyHint = computed(() =>
  filters.value.type
    ? '这个分类下没有匹配内容。切到「综合」看看，或换个关键词。'
    : '换个关键词试试。开发库内容很少，可以搜「测试」或「视频」。',
)

const result = ref<SearchResultVM | null>(null)
const loading = ref(false)
const error = ref('')

/**
 * 请求序号：连点 tab / 排序时**后发者胜**。
 *
 * <p>没有它的话，"视频 → 图文"两次请求谁先回谁写 state ⇒ 图文 tab 下可能渲染出视频条目，
 * 旧请求的 catch 还会把错误盖在已成功的新结果上。新增 tab 让这条路径从"手速问题"变成日常操作。
 */
let requestSeq = 0

async function load(): Promise<void> {
  const seq = ++requestSeq
  if (!keyword.value) {
    result.value = null
    error.value = ''
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await searchContents(keyword.value, page.value, filters.value)
    if (seq !== requestSeq) return
    result.value = data
  } catch (exception) {
    if (seq !== requestSeq) return
    result.value = null
    error.value = exception instanceof ApiError ? exception.message : '搜索失败，请稍后重试'
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

watch([keyword, page, filterKey], load, { immediate: true })

/**
 * 统一构造 URL：**保留 keyword 与当前筛选**，只覆盖调用方显式给的那一项；
 * 除翻页外一律回到第 1 页（切 tab / 换排序后停在第 3 页是错的）。
 */
function buildQuery(overrides: { type?: SearchType; order?: SearchOrder; page?: number } = {}): Record<string, string> {
  const merged = { type: filters.value.type, order: filters.value.order, ...overrides }
  const query: Record<string, string> = { keyword: keyword.value, ...toSearchQuery(merged) }
  const target = overrides.page ?? 1
  if (target > 1) query.page = String(target)
  return query
}

function goPage(target: number): void {
  void router.push({ name: 'search', query: buildQuery({ page: target }) })
}

function selectType(type: SearchType | undefined): void {
  void router.push({ name: 'search', query: buildQuery({ type }) })
}

function selectOrder(order: SearchOrder | undefined): void {
  void router.push({ name: 'search', query: buildQuery({ order }) })
}
</script>

<template>
  <main class="search-page page-content page-main">
    <header class="search-head">
      <h1 class="search-head__title">
        <template v-if="keyword">“{{ keyword }}” 的搜索结果</template>
        <template v-else>搜索</template>
      </h1>

      <div class="search-head__row">
        <nav class="search-tabs" aria-label="搜索分类">
          <button
            v-for="tab in TYPE_TABS"
            :key="tab.label"
            class="search-tab"
            :class="{ 'is-active': filters.type === tab.value }"
            type="button"
            :aria-pressed="filters.type === tab.value"
            @click="selectType(tab.value)"
          >
            {{ tab.label }}
          </button>
        </nav>

        <div class="search-head__right">
          <!-- 图文没有「最多播放」（play_count 恒 0）⇒ 整个排序条都不渲染，而不是摆一个点不动的 -->
          <div v-if="filters.type !== 'POST'" class="search-sorts" role="group" aria-label="排序">
            <button
              v-for="option in SORT_OPTIONS"
              :key="option.label"
              class="search-sort"
              :class="{ 'is-active': filters.order === option.value }"
              type="button"
              :aria-pressed="filters.order === option.value"
              @click="selectOrder(option.value)"
            >
              {{ option.label }}
            </button>
          </div>
          <p v-if="result && !loading" class="search-head__meta">
            找到 {{ result.total }} 个结果
          </p>
        </div>
      </div>
    </header>

    <EmptyState
      v-if="error"
      icon="search"
      title="搜索失败"
      :description="error"
    >
      <button class="btn btn-blue" type="button" @click="load">重试</button>
    </EmptyState>

    <EmptyState
      v-else-if="!keyword"
      icon="search"
      title="输入关键词开始搜索"
      description="在顶栏搜索框里输入内容后回车。本仓搜索按标题/简介匹配，空关键词会被后端拒绝（400）。"
    />

    <VideoGrid
      v-else
      :items="loading ? [] : (result?.items ?? [])"
      :loading="loading"
      :skeleton-count="10"
    />

    <EmptyState
      v-if="!loading && !error && keyword && result && !result.items.length"
      icon="search"
      title="没有找到相关结果"
      :description="emptyHint"
    />

    <Pagination
      v-if="result && !loading && !error"
      :page="result.page"
      :total-pages="result.totalPages"
      @update:page="goPage"
    />
  </main>
</template>

<style scoped>
.search-page {
  /* B 站 1600 = 1472（5×282 + 4×16）、1707 = 1500（6×237）——比首页宽，独立 token */
  --content-w: var(--content-w-search);
  --grid-gap: 16px;
  padding-bottom: 56px;
}

/* 搜索结果是唯一在宽屏上会从 5 列变 6 列的面（1600→1707 实测）；首页始终 5 列 */
@media (min-width: 1680px) {
  .search-page {
    --grid-cols: 6;
  }
}

.search-head__title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-1);
  word-break: break-word;
}

.search-head__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 14px 0 20px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line-2);
}

.search-tabs {
  display: flex;
  gap: 8px;
}

.search-tab {
  height: 32px;
  padding: 0 16px;
  border-radius: var(--radius);
  font-size: 14px;
  color: var(--text-2);
  background: var(--bg-1);
}

.search-tab:hover {
  color: var(--brand-blue);
}

.search-tab.is-active {
  color: var(--text-inverse);
  background: var(--brand-blue);
}

/* 右侧：排序条 + 结果数（B 站是同一行、靠右） */
.search-head__right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.search-sorts {
  display: flex;
  gap: 4px;
}

.search-sort {
  height: 32px;
  padding: 0 12px;
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--text-3);
  background: transparent;
}

.search-sort:hover {
  color: var(--brand-blue);
}

.search-sort.is-active {
  color: var(--brand-blue);
  font-weight: 600;
}

.search-head__meta {
  font-size: 13px;
  color: var(--text-3);
  white-space: nowrap;
}
</style>
