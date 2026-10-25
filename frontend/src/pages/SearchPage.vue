<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { searchContents } from '../api/endpoints'
import { ApiError } from '../api/http'
import type { SearchResultVM } from '../types/ui'
import EmptyState from '../components/common/EmptyState.vue'
import Pagination from '../components/common/Pagination.vue'
import VideoGrid from '../components/home/VideoGrid.vue'

/**
 * 搜索页：`GET /contents?keyword=&page=&pageSize=`（**搜索专用**；空白关键词后端会 400）。
 *
 * <p>★ 本仓搜索端点**没有** `type` 筛选、**没有**排序参数 ⇒ B 站那排 tab / 排序条在这里是灰态占位，
 * 不假装能点（GAP 台账）。
 */
const route = useRoute()
const router = useRouter()

const keyword = computed(() => (typeof route.query.keyword === 'string' ? route.query.keyword.trim() : ''))
const page = computed(() => {
  const raw = Number(route.query.page)
  return Number.isInteger(raw) && raw > 0 ? raw : 1
})

const result = ref<SearchResultVM | null>(null)
const loading = ref(false)
const error = ref('')

async function load(): Promise<void> {
  if (!keyword.value) {
    result.value = null
    error.value = ''
    return
  }
  loading.value = true
  error.value = ''
  try {
    result.value = await searchContents(keyword.value, page.value)
  } catch (exception) {
    result.value = null
    error.value = exception instanceof ApiError ? exception.message : '搜索失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

watch([keyword, page], load, { immediate: true })

function goPage(target: number): void {
  const query: Record<string, string> = { keyword: keyword.value }
  if (target > 1) query.page = String(target)
  void router.push({ name: 'search', query })
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
          <button class="search-tab is-active" type="button">综合</button>
          <button
            class="search-tab"
            type="button"
            disabled
            title="后端 GET /contents 没有 type 筛选参数（GAP 台账），不假装能筛"
          >
            视频
          </button>
          <button
            class="search-tab"
            type="button"
            disabled
            title="后端 GET /contents 没有 type 筛选参数（GAP 台账），不假装能筛"
          >
            专栏
          </button>
        </nav>
        <p v-if="result && !loading" class="search-head__meta">
          找到 {{ result.total }} 个结果
        </p>
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
      description="换个关键词试试。开发库内容很少，可以搜「测试」或「视频」。"
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
  padding-bottom: 56px;
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

.search-tab:hover:not(:disabled) {
  color: var(--brand-blue);
}

.search-tab.is-active {
  color: var(--text-inverse);
  background: var(--brand-blue);
}

.search-tab:disabled {
  color: var(--text-4);
  background: var(--bg-1);
}

.search-head__meta {
  font-size: 13px;
  color: var(--text-3);
}
</style>
