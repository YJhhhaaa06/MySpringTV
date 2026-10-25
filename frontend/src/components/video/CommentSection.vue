<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CommentPageVM } from '../../types/ui'
import { fetchComments, publishComment } from '../../api/comments'
import { describeError } from '../../api/http'
import { newIdempotencyKey } from '../../api/idempotency'
import { restoreSession } from '../../auth/session'
import { showToast } from '../../common/toast'
import Pagination from '../common/Pagination.vue'
import CommentComposer from './CommentComposer.vue'
import CommentItem from './CommentItem.vue'

/**
 * 评论区（**Page 分页**；楼中楼由每个 `CommentItem` 自己走 Cursor）。
 *
 * <h2>两套计数别混</h2>
 * - 标题上的数字 = `ContentDetailVO.commentCount`（**含楼中楼**，B 站也是这个口径）；
 * - 分页信封的 `total` = **主楼条数**（后端定义），只用来算页数。
 * 拿 `total` 当标题数字会少算所有回复。
 *
 * <h2>★ 发布后跳到最后一页</h2>
 * 本仓主楼按 `comment_id` **升序**（后端没有排序参数，GAP 台账）⇒ 新评论落在最后一页。
 * 所以发布成功后算一次末页、跳过去、把新评论高亮 2 秒 —— 比"留在原页然后什么都不发生"诚实。
 *
 * <h2>★ 评论区关闭 = 不渲染输入框</h2>
 * `commentEnabled === false` 时只显示"评论区已关闭"，不给一个点了没反应的框。
 */
const props = defineProps<{
  contentId: number
  commentEnabled: boolean
  /** 详情侧的真实评论数（含楼中楼）；null = 后端没给 */
  initialCount: number | null
}>()

const emit = defineEmits<{ (event: 'count-changed', count: number): void }>()

const PAGE_SIZE = 10
const page = ref(1)
const result = ref<CommentPageVM | null>(null)
const loading = ref(false)
const error = ref('')
/** 本地维持的"含楼中楼"总数（后端没有单独的评论计数端点，只能以详情为基线递增/递减） */
const total = ref<number | null>(props.initialCount)
const draft = ref('')
const publishing = ref(false)
const highlightId = ref<number | null>(null)
let submitKey: string | null = null

const shownCount = computed(() => total.value ?? result.value?.total ?? null)

// 草稿一改 = 新的发表意图 ⇒ 旧幂等键作废（同一草稿的重试才复用它）
watch(draft, () => {
  submitKey = null
})

watch(
  () => props.contentId,
  () => {
    page.value = 1
    total.value = props.initialCount
    void load(1)
  },
)

async function load(targetPage: number = page.value): Promise<void> {
  if (!props.contentId) return
  loading.value = true
  error.value = ''
  try {
    // `canDelete` 依赖"我是谁"：先把登录态恢复完再映射，否则自己的评论也不显示删除
    // （restoreSession 内部复用同一个请求，入口文件已经调过一次也不会重复打）
    await restoreSession()
    const data = await fetchComments(props.contentId, targetPage, PAGE_SIZE)
    result.value = data
    page.value = data.page
  } catch (exception) {
    error.value = describeError(exception)
    result.value = null
  } finally {
    loading.value = false
  }
}

async function publish(text: string): Promise<void> {
  if (publishing.value) return
  publishing.value = true
  submitKey ??= newIdempotencyKey()
  try {
    const id = await publishComment(props.contentId, text, null, submitKey)
    submitKey = null
    draft.value = ''
    total.value = (total.value ?? 0) + 1
    emit('count-changed', total.value)
    showToast('评论已发布')
    const totalAfter = (result.value?.total ?? 0) + 1
    await load(Math.max(1, Math.ceil(totalAfter / PAGE_SIZE)))
    highlightId.value = id
    setTimeout(() => {
      if (highlightId.value === id) highlightId.value = null
    }, 2400)
  } catch (exception) {
    // 失败保留草稿与幂等键：同一个草稿再点一次是"重试"，不是"新评论"
    showToast(describeError(exception), 'error')
  } finally {
    publishing.value = false
  }
}

function onRemoved(commentId: number): void {
  if (!result.value) return
  result.value.items = result.value.items.filter((item) => item.id !== commentId)
  result.value.total = Math.max(0, result.value.total - 1)
  // 删掉本页最后一条 ⇒ 回退一页（否则停在空页上）
  if (!result.value.items.length && result.value.page > 1) void load(result.value.page - 1)
}

function onCountDelta(delta: number): void {
  total.value = Math.max(0, (total.value ?? 0) + delta)
  emit('count-changed', total.value)
}

void load(1)
</script>

<template>
  <section class="comments">
    <h2 class="comments__head">
      评论
      <span v-if="shownCount !== null" class="comments__count">{{ shownCount }}</span>
    </h2>

    <CommentComposer
      v-if="commentEnabled"
      v-model="draft"
      :submitting="publishing"
      class="comments__composer"
      @submit="publish"
    />
    <p v-else class="comments__closed">作者关闭了这条内容的评论区</p>

    <p v-if="loading" class="comments__hint">加载评论…</p>

    <div v-else-if="error" class="comments__error">
      <span>{{ error }}</span>
      <button class="comments__retry" type="button" @click="load(page)">重试</button>
    </div>

    <template v-else-if="result">
      <p v-if="!result.items.length" class="comments__hint">还没有评论，来发第一条吧</p>
      <div v-else class="comments__list">
        <CommentItem
          v-for="item in result.items"
          :key="item.id"
          :comment="item"
          :content-id="contentId"
          :highlight="highlightId === item.id"
          @removed="onRemoved"
          @count-changed="onCountDelta"
        />
      </div>

      <!-- Page 分页：主楼按页翻；楼中楼是另一套（游标），在 CommentItem 里 -->
      <Pagination :page="result.page" :total-pages="result.totalPages" @update:page="(target) => load(target)" />
    </template>
  </section>
</template>

<style scoped>
.comments {
  margin-top: 24px;
}

.comments__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.comments__count {
  font-size: 13px;
  font-weight: 400;
  color: var(--text-3);
}

.comments__composer {
  margin-top: 14px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line-2);
}

.comments__closed {
  margin-top: 12px;
  padding: 14px;
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--text-3);
  background: var(--bg-1);
}

.comments__hint {
  margin-top: 20px;
  font-size: 13px;
  color: var(--text-3);
}

.comments__error {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 20px;
  font-size: 13px;
  color: #f56c6c;
}

.comments__retry {
  font-size: 13px;
  color: var(--brand-blue);
}

.comments__list {
  margin-top: 6px;
}
</style>
