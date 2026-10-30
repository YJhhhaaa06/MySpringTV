<script setup lang="ts">
import { computed } from 'vue'
import { isAuthenticated, sessionState } from '../../auth/session'
import { requireLogin } from '../../auth/loginPrompt'

/**
 * 评论输入框（主页与楼中楼共用）。
 *
 * <h2>★ 未登录时点输入框 → 弹登录（不是禁用）</h2>
 * 禁用输入框会让用户以为"评论关了"；B 站也是点下去弹登录。登录成功后输入的文字
 * 还在（textarea 不被卸载），用户接着发即可。
 *
 * <h2>为什么用 `v-model` 而不是自己清空</h2>
 * 发布成功要清空、失败要**保留**（否则用户白打一遍）—— 这是调用方的决策，
 * 组件只负责画与收字。键只在 input 上，草稿归调用方。
 */
const props = withDefaults(
  defineProps<{
    placeholder?: string
    submitting?: boolean
    /** 楼中楼的紧凑版（高度更小、无头像） */
    compact?: boolean
    maxlength?: number
  }>(),
  { placeholder: '发一条友善的评论', submitting: false, compact: false, maxlength: 1000 },
)

const message = defineModel<string>({ default: '' })

const emit = defineEmits<{ (event: 'submit', text: string): void }>()

const avatar = computed(() => sessionState.user?.initial ?? '游')
const remaining = computed(() => props.maxlength - message.value.length)
const canSubmit = computed(() => message.value.trim().length > 0 && !props.submitting)

function onFocus(): void {
  if (!isAuthenticated()) void requireLogin()
}

async function submit(): Promise<void> {
  const text = message.value.trim()
  if (!text || props.submitting) return
  if (!isAuthenticated() && !(await requireLogin())) return
  emit('submit', text)
}
</script>

<template>
  <form class="composer" :class="{ 'composer--compact': compact }" @submit.prevent="submit">
    <span v-if="!compact" class="composer__avatar" aria-hidden="true">{{ avatar }}</span>
    <div class="composer__body">
      <textarea
        v-model="message"
        class="composer__input"
        :class="{ 'composer__input--compact': compact }"
        :maxlength="maxlength"
        :placeholder="placeholder"
        :rows="compact ? 2 : 3"
        @focus="onFocus"
      />
      <div class="composer__foot">
        <span class="composer__hint">
          {{ sessionState.status === 'anonymous' ? '登录后即可评论' : `${remaining} 字` }}
        </span>
        <button class="btn composer__submit" type="submit" :disabled="!canSubmit">
          {{ submitting ? '发布中…' : '发布' }}
        </button>
      </div>
    </div>
  </form>
</template>

<style scoped>
.composer {
  display: flex;
  gap: 12px;
}

.composer__avatar {
  display: flex;
  width: 40px;
  height: 40px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-pink) 0%, var(--brand-blue) 100%);
}

.composer__body {
  flex: 1;
  min-width: 0;
}

.composer__input {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius);
  font-family: inherit;
  font-size: 14px;
  line-height: 22px;
  color: var(--text-1);
  background: var(--bg-0);
  outline: none;
  resize: vertical;
}

.composer__input:focus {
  border-color: var(--brand-blue);
}

.composer__input--compact {
  font-size: 13px;
  line-height: 20px;
}

.composer__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.composer__hint {
  font-size: 12px;
  color: var(--text-3);
}

.composer__submit {
  height: 30px;
  padding: 0 18px;
  font-size: 13px;
  color: var(--text-inverse);
  background: var(--brand-blue);
}

.composer__submit:hover:not(:disabled) {
  background: var(--brand-blue-dark);
  color: var(--text-inverse);
}

.composer__submit:disabled {
  color: var(--text-4);
  background: var(--bg-2);
  cursor: not-allowed;
}
</style>
