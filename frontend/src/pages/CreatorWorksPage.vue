<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ApiError, describeError } from '../api/http'
import { loadMyWorks } from '../api/creator'
import { sessionState, signOut } from '../auth/session'
import { requireLogin } from '../auth/loginPrompt'
import { showToast } from '../common/toast'
import type { CreatorWorksVM } from '../types/ui'
import AppIcon from '../components/common/AppIcon.vue'
import EmptyState from '../components/common/EmptyState.vue'
import Pagination from '../components/common/Pagination.vue'
import SkeletonCard from '../components/common/SkeletonCard.vue'

/**
 * 创作中心「稿件管理」（`/creator/manage`）。
 *
 * <h2>这一页是什么</h2>
 * 编辑的入口：列出我发布的稿件（视频 + 图文），每行给「编辑」与「查看」。
 * 数据源与口径见 `api/creator.ts` 与 `api/adapters/creator.ts` 顶部注释 ——
 * 一句话：**这是公开作品页，不是审核后台**。
 *
 * <h2>刻意不渲染</h2>
 * 状态列（无审核域）、分区列（只有 0~9 数字，对"改哪一条"没帮助）、
 * 批量操作 / 删除（本窗未认领，删除是软删且不可恢复，要单独设计确认流程）。
 *
 * <h2>页面级身份态</h2>
 * 与投稿页同一口径：`GET /users/{id}` 匿名也能读，但"我的稿件"在匿名时没有意义
 * ⇒ 先渲染「登录后管理稿件」，登录成功后同一页自动重读（`watch` 身份态）。
 */
const PAGE_SIZE = 10

const works = ref<CreatorWorksVM | null>(null)
const loading = ref(false)
const pageError = ref('')
const page = ref(1)

const authenticated = computed(() => sessionState.status === 'authenticated')
const restoring = computed(() => sessionState.status === 'restoring')
const userId = computed(() => sessionState.user?.id ?? null)

function login(): void {
  void requireLogin()
}

async function reload(): Promise<void> {
  const id = userId.value
  if (id === null) return
  loading.value = true
  pageError.value = ''
  try {
    works.value = await loadMyWorks(id, page.value, PAGE_SIZE)
  } catch (error) {
    if (error instanceof ApiError && error.isAuthError) {
      // token 失效：回到「登录后管理稿件」，不要停在一张空表上
      signOut()
      showToast('登录已过期，请重新登录', 'error')
      works.value = null
      return
    }
    pageError.value = describeError(error, '稿件列表加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function retry(): void {
  void reload()
}

function changePage(next: number): void {
  page.value = next
}

// 身份态 / 页码变化都会重读；`immediate` 覆盖"带着 token 直接打开"的情形
watch(
  [authenticated, userId, page],
  () => {
    if (!authenticated.value) {
      works.value = null
      return
    }
    void reload()
  },
  { immediate: true },
)
</script>

<template>
  <div class="works-page">
    <header class="works-head">
      <h1 class="works-title">稿件管理</h1>
      <p v-if="works && !loading" class="works-count">共 {{ works.total }} 条</p>
      <RouterLink class="btn btn-blue works-new" to="/creator">投稿</RouterLink>
    </header>

    <p v-if="restoring" class="works-hint">正在恢复登录状态…</p>

    <EmptyState
      v-else-if="!authenticated"
      icon="user"
      title="登录后才能管理稿件"
      description="这里列出你发布的视频与图文，可以随时取回修改。"
    >
      <button class="btn btn-blue" type="button" @click="login">立即登录</button>
    </EmptyState>

    <template v-else>
      <p v-if="pageError" class="page-error">
        {{ pageError }}
        <button class="link-btn" type="button" @click="retry">重试</button>
      </p>

      <div v-if="loading && !works" class="works-skeleton">
        <SkeletonCard v-for="index in 4" :key="index" layout="row" />
      </div>

      <EmptyState
        v-else-if="!loading && works && works.items.length === 0"
        icon="upload"
        title="还没有稿件"
        description="投稿后这里会出现你的视频与图文，可以取回修改标题、封面与图片顺序。"
      >
        <RouterLink class="btn btn-blue" to="/creator">去投稿</RouterLink>
      </EmptyState>

      <template v-else-if="works">
        <div class="works-table" role="table" aria-label="我的稿件">
          <div class="works-row works-row--head" role="row">
            <span role="columnheader">稿件</span>
            <span role="columnheader" class="works-col--num">播放</span>
            <span role="columnheader" class="works-col--date">发布时间</span>
            <span role="columnheader" class="works-col--ops">操作</span>
          </div>

          <div v-for="work in works.items" :key="work.id" class="works-row" role="row">
            <div class="works-main" role="cell">
              <RouterLink class="works-cover" :to="work.viewHref" target="_blank" rel="noopener">
                <img v-if="work.coverUrl" :src="work.coverUrl" :alt="work.title" loading="lazy" />
                <span v-else class="works-cover__empty"><AppIcon name="tv" :size="18" /></span>
                <span v-if="work.durationText" class="works-cover__duration">{{ work.durationText }}</span>
              </RouterLink>
              <div class="works-info">
                <RouterLink class="works-name" :to="work.viewHref" target="_blank" rel="noopener">
                  {{ work.title }}
                </RouterLink>
                <p class="works-meta">
                  <span class="works-badge" :class="{ 'is-post': work.isPost }">{{ work.typeText }}</span>
                  <span class="works-id">id {{ work.id }}</span>
                </p>
              </div>
            </div>

            <div class="works-col--num" role="cell">
              <!-- 图文结构性没有播放量（GAP-05）：画「—」而不是「0 播放」 -->
              {{ work.playCountText ?? '—' }}
            </div>
            <div class="works-col--date" role="cell">{{ work.createdAtText || '—' }}</div>
            <div class="works-col--ops" role="cell">
              <RouterLink class="btn btn-ghost btn-sm" :to="work.editHref">编辑</RouterLink>
              <a class="link-btn" :href="work.viewHref" target="_blank" rel="noopener">查看</a>
            </div>
          </div>
        </div>

        <Pagination :page="works.page" :total-pages="works.totalPages" @update:page="changePage" />
      </template>
    </template>
  </div>
</template>

<style scoped>
.works-page {
  min-height: 420px;
}

.works-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line-2);
}

.works-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-1);
}

.works-count {
  font-size: 12px;
  color: var(--text-3);
}

.works-new {
  margin-left: auto;
}

.works-hint {
  padding: 60px 0;
  text-align: center;
  color: var(--text-3);
}

.works-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-top: 16px;
}

.works-table {
  padding-top: 8px;
}

.works-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 8px;
  border-bottom: 1px solid var(--line-2);
  font-size: 13px;
  color: var(--text-2);
}

.works-row:hover:not(.works-row--head) {
  background: var(--bg-1);
}

.works-row--head {
  padding-top: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--line-1);
  font-size: 12px;
  color: var(--text-3);
}

.works-main {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  align-items: center;
  gap: 12px;
}

.works-cover {
  position: relative;
  display: block;
  width: 112px;
  height: 63px;
  flex: none;
  overflow: hidden;
  border-radius: var(--radius-sm);
  background: var(--bg-1);
}

.works-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.works-cover__empty {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: var(--text-4);
}

.works-cover__duration {
  position: absolute;
  right: 4px;
  bottom: 4px;
  padding: 0 4px;
  border-radius: 2px;
  font-size: 11px;
  line-height: 16px;
  color: #fff;
  background: rgba(0, 0, 0, 0.6);
}

.works-info {
  min-width: 0;
}

.works-name {
  display: block;
  overflow: hidden;
  font-size: 14px;
  color: var(--text-1);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.works-name:hover {
  color: var(--brand-blue);
}

.works-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-3);
}

.works-badge {
  padding: 0 6px;
  border-radius: 2px;
  line-height: 18px;
  color: var(--brand-blue);
  background: var(--brand-blue-bg);
}

.works-badge.is-post {
  color: #d46b08;
  background: #fff4e6;
}

.works-id {
  color: var(--text-4);
}

.works-col--num {
  width: 90px;
  flex: none;
  text-align: right;
}

.works-col--date {
  width: 140px;
  flex: none;
  color: var(--text-3);
}

.works-col--ops {
  display: flex;
  width: 150px;
  flex: none;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.btn-sm {
  height: 28px;
  padding: 0 12px;
  font-size: 12px;
  line-height: 28px;
}

.link-btn {
  font-size: 12px;
  color: var(--brand-blue);
}

.link-btn:hover {
  color: var(--brand-blue-dark);
}

.page-error {
  margin-top: 12px;
  font-size: 13px;
  color: #f56c6c;
}

@media (max-width: 860px) {
  .works-col--num {
    display: none;
  }

  .works-col--date {
    display: none;
  }

  .works-col--ops {
    width: auto;
  }
}
</style>
