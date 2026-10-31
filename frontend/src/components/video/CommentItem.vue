<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { CommentVM } from '../../types/ui'
import { fetchReplies, likeComment, publishComment, removeComment, unlikeComment } from '../../api/comments'
import { toPublishedComment } from '../../api/adapters/comment'
import { describeError } from '../../api/http'
import { newIdempotencyKey } from '../../api/idempotency'
import { requireLogin } from '../../auth/loginPrompt'
import { sessionState } from '../../auth/session'
import { showToast } from '../../common/toast'
import AppIcon from '../common/AppIcon.vue'
import UserAvatar from '../common/UserAvatar.vue'
import CommentComposer from './CommentComposer.vue'

/**
 * 一条主楼评论 + 它的楼中楼。
 *
 * <h2>★ 两种分页在这里**显式分开**</h2>
 * 主楼列表是 Page（父组件管）；楼中楼是 Cursor（本组件管：`nextCursor === null` ⇒ 到底）。
 * 本组件内部的 `expanded` 只表示"楼中楼已展开并接管了预览数据"，
 * 不代表"全部加载完"——全部加载完的判据只有 `nextCursor`。
 *
 * <h2>★ 新回复直接本地追加（不回读）</h2>
 * `POST /comments` 返回 `commentId`，作者 / 文本 / 被 @ 者都是确定的 ⇒ 本地拼一条即可。
 * 楼中楼后端按 `comment_id` 升序，所以新回复落在**末尾**，追加位置与真实顺序一致。
 *
 * <h2>删除是两段式</h2>
 * 第一次点"删除"变成"确认删除 / 取消"（不做 `window.confirm`：阻塞式原生弹窗
 * 在播放页会打断播放，而且样式不可控）。
 */
const props = defineProps<{ comment: CommentVM; contentId: number; highlight?: boolean }>()

const emit = defineEmits<{
  (event: 'removed', commentId: number): void
  (event: 'count-changed', delta: number): void
}>()

const rootEl = ref<HTMLElement | null>(null)
const likePending = ref(false)
/** 两段式删除：先记"要对哪一条动手"，再点一次才真删 */
const deleteTarget = ref<CommentVM | null>(null)
const deleting = ref(false)

// ── 楼中楼 ────────────────────────────────────────────────────────────
const expanded = ref(false)
const expandedReplies = ref<CommentVM[]>([])
const replyCursor = ref<number | null>(null)
const replyLoading = ref(false)
const replyDone = ref(false)

const shownReplies = computed(() => (expanded.value ? expandedReplies.value : props.comment.replies))
/** 还有多少条没显示（后端 replyCount 是总数，预览只有前 2 条） */
const hiddenReplies = computed(() => Math.max(0, props.comment.replyCount - shownReplies.value.length))
const canLoadMore = computed(() => !replyDone.value && (hiddenReplies.value > 0 || replyCursor.value !== null))

// ── 回复输入 ──────────────────────────────────────────────────────────
const replyOpen = ref(false)
const replyDraft = ref('')
const replySubmitting = ref(false)
const replyTarget = ref<CommentVM | null>(null)
let replyKey: string | null = null

// 草稿一改就是"新的发表意图"：旧幂等键必须作废，否则服务端按同一个键返回旧评论
// （或直接 409 IDEMPOTENCY_KEY_CONFLICT）。同一个草稿的**重试**才复用键。
watch(replyDraft, () => {
  replyKey = null
})

if (props.highlight) {
  void nextTick(() => rootEl.value?.scrollIntoView({ block: 'center', behavior: 'smooth' }))
}

/** 点赞（主楼与楼内回复共用同一套乐观更新 + 回滚） */
async function toggleLike(target: CommentVM): Promise<void> {
  if (likePending.value) return
  if (!(await requireLogin())) return
  likePending.value = true
  const next = !target.liked
  target.liked = next
  target.likeCount = Math.max(0, target.likeCount + (next ? 1 : -1))
  try {
    if (next) await likeComment(target.id)
    else await unlikeComment(target.id)
  } catch (error) {
    target.liked = !next
    target.likeCount = Math.max(0, target.likeCount + (next ? -1 : 1))
    showToast(describeError(error), 'error')
  } finally {
    likePending.value = false
  }
}

/** 删除（两段式确认：主楼与楼内回复同一套） */
function onDeleteClick(target: CommentVM): void {
  if (deleteTarget.value?.id !== target.id) {
    deleteTarget.value = target
    return
  }
  void doDelete(target)
}

async function doDelete(target: CommentVM): Promise<void> {
  if (deleting.value) return
  deleting.value = true
  try {
    await removeComment(target.id)
    if (target.id === props.comment.id) {
      // 主楼整栋消失：连楼内回复一起算进总数（后端也是这么扣的）
      emit('removed', props.comment.id)
      emit('count-changed', -(1 + props.comment.replyCount))
    } else {
      expandedReplies.value = expandedReplies.value.filter((item) => item.id !== target.id)
      props.comment.replyCount = Math.max(0, props.comment.replyCount - 1)
      emit('count-changed', -1)
    }
    showToast('已删除')
  } catch (error) {
    showToast(describeError(error), 'error')
  } finally {
    deleting.value = false
    deleteTarget.value = null
  }
}

function openReply(target: CommentVM | null): void {
  replyTarget.value = target
  replyOpen.value = true
}

async function submitReply(text: string): Promise<void> {
  if (replySubmitting.value) return
  replySubmitting.value = true
  replyKey ??= newIdempotencyKey()
  const parentId = replyTarget.value?.id ?? props.comment.id
  const replyToName = replyTarget.value?.authorName ?? null
  try {
    const id = await publishComment(props.contentId, text, parentId, replyKey)
    replyKey = null
    const created = toPublishedComment({
      id,
      parentId: props.comment.id,
      content: text,
      viewer: sessionState.user,
      replyToName,
    })
    if (!expanded.value) {
      // 从预览切到"接管"：先铺上已有预览，再追加新回复
      expandedReplies.value = [...props.comment.replies]
      expanded.value = true
      // 预览已满 2 条时后端的"更多"游标还没取过 ⇒ 用当前预览末条 id 作为起点
      replyCursor.value = props.comment.replies.at(-1)?.id ?? null
    }
    expandedReplies.value = [...expandedReplies.value, created]
    props.comment.replyCount += 1
    replyDraft.value = ''
    replyOpen.value = false
    replyTarget.value = null
    emit('count-changed', 1)
    showToast('回复已发布')
  } catch (error) {
    showToast(describeError(error), 'error')
  } finally {
    replySubmitting.value = false
  }
}

/** 展开 / 加载更多楼中楼（**游标**：`nextCursor` 为空才是到底） */
async function loadReplies(): Promise<void> {
  if (replyLoading.value) return
  replyLoading.value = true
  try {
    if (!expanded.value) {
      const first = await fetchReplies(props.comment.id, null, 10)
      expandedReplies.value = first.items
      replyCursor.value = first.nextCursor
      replyDone.value = first.nextCursor === null
      expanded.value = true
    } else {
      const next = await fetchReplies(props.comment.id, replyCursor.value, 10)
      // 游标页可能与已追加的本地回复重叠 ⇒ 先按 id 去重，再按 id 升序（= 创建顺序，
      // 后端楼中楼就是 `comment_id` 升序）。刚发的那条 id 最大，排序后自然落在末尾。
      const merged = new Map<number, CommentVM>()
      for (const item of [...expandedReplies.value, ...next.items]) merged.set(item.id, item)
      expandedReplies.value = [...merged.values()].sort((a, b) => a.id - b.id)
      replyCursor.value = next.nextCursor
      replyDone.value = next.nextCursor === null
    }
  } catch (error) {
    showToast(describeError(error), 'error')
  } finally {
    replyLoading.value = false
  }
}
</script>

<template>
  <article ref="rootEl" class="comment" :class="{ 'comment--highlight': highlight }">
    <UserAvatar :user-id="comment.authorId" :initial="comment.initial" :size="40" aria-hidden="true" />

    <div class="comment__body">
      <a class="comment__author" :href="`/placeholder/space?uid=${comment.authorId}`">{{ comment.authorName }}</a>
      <p class="comment__content">{{ comment.content }}</p>

      <p class="comment__meta">
        <!-- 时间：GAP-12 补回后由后端 createTime 格式化；空串（存量缓存 / 脏数据）不渲染节点 -->
        <span v-if="comment.timeText" class="comment__time">{{ comment.timeText }}</span>
        <button
          class="comment__action"
          :class="{ 'is-active': comment.liked }"
          type="button"
          :disabled="likePending"
          @click="toggleLike(comment)"
        >
          <AppIcon name="thumb" :size="14" />
          <span>{{ comment.likeCount || '赞' }}</span>
        </button>
        <button class="comment__action" type="button" @click="openReply(null)">
          <AppIcon name="comment" :size="14" />
          <span>回复</span>
        </button>
        <template v-if="comment.canDelete">
          <button
            v-if="deleteTarget?.id !== comment.id"
            class="comment__action"
            type="button"
            @click="onDeleteClick(comment)"
          >
            删除
          </button>
          <template v-else>
            <button
              class="comment__action comment__action--danger"
              type="button"
              :disabled="deleting"
              @click="onDeleteClick(comment)"
            >
              {{ deleting ? '删除中…' : '确认删除' }}
            </button>
            <button class="comment__action" type="button" @click="deleteTarget = null">取消</button>
          </template>
        </template>
      </p>

      <CommentComposer
        v-if="replyOpen"
        v-model="replyDraft"
        compact
        :submitting="replySubmitting"
        :placeholder="replyTarget ? `回复 @${replyTarget.authorName}` : `回复 @${comment.authorName}`"
        class="comment__composer"
        @submit="submitReply"
      />

      <ul v-if="shownReplies.length" class="reply-list">
        <li v-for="reply in shownReplies" :key="reply.id" class="reply">
          <UserAvatar :user-id="reply.authorId" :initial="reply.initial" :size="24" aria-hidden="true" />
          <div class="reply__body">
            <p class="reply__head">
              <a class="reply__author" :href="`/placeholder/space?uid=${reply.authorId}`">{{ reply.authorName }}</a>
              <template v-if="reply.replyToName">
                <span class="reply__to">回复</span>
                <span class="reply__to-name">@{{ reply.replyToName }}</span>
              </template>
            </p>
            <p class="reply__content">{{ reply.content }}</p>
            <p class="reply__meta">
              <button
                class="comment__action comment__action--sm"
                :class="{ 'is-active': reply.liked }"
                type="button"
                :disabled="likePending"
                @click="toggleLike(reply)"
              >
                <AppIcon name="thumb" :size="13" />
                <span>{{ reply.likeCount || '赞' }}</span>
              </button>
              <button class="comment__action comment__action--sm" type="button" @click="openReply(reply)">回复</button>
              <template v-if="reply.canDelete">
                <button
                  v-if="deleteTarget?.id !== reply.id"
                  class="comment__action comment__action--sm"
                  type="button"
                  @click="onDeleteClick(reply)"
                >
                  删除
                </button>
                <template v-else>
                  <button
                    class="comment__action comment__action--sm comment__action--danger"
                    type="button"
                    :disabled="deleting"
                    @click="onDeleteClick(reply)"
                  >
                    {{ deleting ? '删除中…' : '确认删除' }}
                  </button>
                  <button class="comment__action comment__action--sm" type="button" @click="deleteTarget = null">
                    取消
                  </button>
                </template>
              </template>
            </p>
          </div>
        </li>
      </ul>

      <button
        v-if="canLoadMore"
        class="reply-more"
        type="button"
        :disabled="replyLoading"
        @click="loadReplies"
      >
        <template v-if="replyLoading">加载中…</template>
        <template v-else-if="expanded">展开更多回复{{ hiddenReplies ? `（还有 ${hiddenReplies} 条）` : '' }}</template>
        <template v-else>共 {{ comment.replyCount }} 条回复，点击查看</template>
      </button>
    </div>
  </article>
</template>

<style scoped>
.comment {
  display: flex;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--line-2);
}

.comment--highlight {
  animation: comment-flash 1.8s ease;
}

@keyframes comment-flash {
  0%,
  40% {
    background: #fff5f8;
  }
  100% {
    background: transparent;
  }
}

.comment__body {
  flex: 1;
  min-width: 0;
}

.comment__author {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
}

.comment__author:hover {
  color: var(--brand-blue);
}

.comment__content {
  margin-top: 4px;
  font-size: 14px;
  line-height: 22px;
  color: var(--text-1);
  white-space: pre-wrap;
  word-break: break-word;
}

.comment__meta {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 6px;
}

.comment__action {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 12px;
  color: var(--text-3);
}

.comment__time {
  font-size: 12px;
  color: var(--text-3);
}

.comment__action:hover {
  color: var(--brand-blue);
}

.comment__action.is-active {
  color: var(--brand-pink);
}

.comment__action--danger {
  color: #f56c6c;
}

.comment__action--sm {
  font-size: 12px;
}

.comment__composer {
  margin-top: 10px;
}

.reply-list {
  margin-top: 10px;
  padding: 8px 12px;
  border-radius: var(--radius);
  background: var(--bg-1);
}

.reply {
  display: flex;
  gap: 8px;
  padding: 5px 0;
}

.reply__body {
  flex: 1;
  min-width: 0;
}

.reply__head {
  display: flex;
  align-items: baseline;
  gap: 4px;
  font-size: 12px;
}

.reply__author {
  font-weight: 600;
  color: var(--text-2);
}

.reply__author:hover {
  color: var(--brand-blue);
}

.reply__to {
  color: var(--text-3);
}

.reply__to-name {
  color: var(--brand-blue);
}

.reply__content {
  font-size: 13px;
  line-height: 20px;
  color: var(--text-1);
  white-space: pre-wrap;
  word-break: break-word;
}

.reply__meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 2px;
}

.reply-more {
  margin-top: 8px;
  font-size: 13px;
  color: var(--brand-blue);
}

.reply-more:hover {
  color: var(--brand-blue-dark);
}

.reply-more:disabled {
  color: var(--text-3);
}
</style>
