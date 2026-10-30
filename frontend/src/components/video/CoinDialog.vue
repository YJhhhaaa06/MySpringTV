<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import AppIcon from '../common/AppIcon.vue'

/**
 * 投币弹层（B 站版式：两个币按钮 + 余额 + 「投币」确认）。
 *
 * <p>★ 硬币余额来自**登录态里的真数字**（`GET /users/me` 的 `coin`，投币后由
 * 接口返回的 `balance` 覆盖）。硬币不足时给"签到领硬币"的出口 —— 那也是一条真接口
 * （`POST /users/me/check-ins`），不是前端假加一枚。
 *
 * <p>**纯展示 + 事件**：`confirm(count)` 抛给页面，成功/失败由页面的状态机决定
 * （失败信息回填到 `error`，弹层不自己关）。
 */
const props = defineProps<{
  balance: number
  error: string
  insufficient: boolean
  submitting: boolean
  /** 已投币数（0 ⇒ 还能投两枚；1 ⇒ 只能再投一枚） */
  alreadyCoins: number
}>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'confirm', count: 1 | 2): void
  (event: 'checkin'): void
}>()

const selected = ref<1 | 2>(1)
const card = ref<HTMLElement | null>(null)

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') emit('close')
}

onMounted(() => {
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', onKeydown)
  card.value?.focus()
})

onUnmounted(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
})

/** 余额不够 2 枚时只能选 1（别让用户选了才被后端拒） */
function maxAllowed(): 1 | 2 {
  const remaining = Math.max(0, 2 - props.alreadyCoins)
  if (props.balance < 2 || remaining < 2) return 1
  return 2
}
</script>

<template>
  <Teleport to="body">
    <div class="dialog-mask" @click.self="emit('close')">
      <div
        ref="card"
        class="coin-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="coin-title"
        tabindex="-1"
      >
        <header class="coin-dialog__head">
          <h2 id="coin-title" class="coin-dialog__title">给这个稿件投币</h2>
          <button class="coin-dialog__close" type="button" aria-label="关闭" @click="emit('close')">
            <AppIcon name="close" :size="18" />
          </button>
        </header>

        <p class="coin-dialog__balance">
          我的硬币：<strong>{{ balance }}</strong>
          <template v-if="alreadyCoins > 0"> · 已投 {{ alreadyCoins }} 枚</template>
        </p>

        <div class="coin-options" role="radiogroup" aria-label="投币数量">
          <button
            class="coin-option"
            :class="{ 'is-active': selected === 1 }"
            type="button"
            role="radio"
            :aria-checked="selected === 1"
            @click="selected = 1"
          >
            <AppIcon name="coin" :size="30" />
            <span>1 枚</span>
          </button>
          <button
            class="coin-option"
            :class="{ 'is-active': selected === 2 }"
            type="button"
            role="radio"
            :aria-checked="selected === 2"
            :disabled="maxAllowed() === 1"
            title="余额不足 2 枚或已投过币"
            @click="selected = 2"
          >
            <span class="coin-option__stack">
              <AppIcon name="coin" :size="30" />
              <AppIcon name="coin" :size="30" />
            </span>
            <span>2 枚</span>
          </button>
        </div>

        <p v-if="error" class="coin-dialog__error" role="alert">{{ error }}</p>

        <button
          v-if="insufficient"
          class="btn btn-ghost coin-dialog__checkin"
          type="button"
          @click="emit('checkin')"
        >
          每日签到领硬币
        </button>

        <footer class="coin-dialog__foot">
          <button class="btn btn-ghost" type="button" @click="emit('close')">取消</button>
          <button
            class="btn coin-dialog__submit"
            type="button"
            :disabled="submitting"
            @click="emit('confirm', selected)"
          >
            {{ submitting ? '投币中…' : `投 ${selected} 枚` }}
          </button>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.coin-dialog {
  width: 100%;
  max-width: 380px;
  padding: 20px 22px 18px;
  border-radius: var(--radius-lg);
  background: var(--bg-0);
  box-shadow: var(--shadow-2);
  outline: none;
}

.coin-dialog__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.coin-dialog__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.coin-dialog__close {
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--text-3);
}

.coin-dialog__close:hover {
  color: var(--text-1);
  background: var(--bg-1);
}

.coin-dialog__balance {
  margin-top: 10px;
  font-size: 13px;
  color: var(--text-3);
}

.coin-dialog__balance strong {
  color: var(--brand-pink);
}

.coin-options {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}

.coin-option {
  display: flex;
  flex: 1;
  height: 72px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--text-2);
  background: var(--bg-0);
}

.coin-option:hover:not(:disabled) {
  border-color: var(--brand-pink);
  color: var(--brand-pink);
}

.coin-option.is-active {
  border-color: var(--brand-pink);
  color: var(--brand-pink);
  background: #fff5f8;
}

.coin-option:disabled {
  opacity: 0.45;
}

.coin-option__stack {
  display: flex;
  margin-right: -6px;
}

.coin-option__stack > :last-child {
  margin-left: -10px;
}

.coin-dialog__error {
  margin-top: 12px;
  font-size: 13px;
  line-height: 20px;
  color: #f56c6c;
}

.coin-dialog__checkin {
  width: 100%;
  margin-top: 10px;
}

.coin-dialog__foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 18px;
}

.coin-dialog__submit {
  color: var(--text-inverse);
  background: var(--brand-pink);
}

.coin-dialog__submit:hover:not(:disabled) {
  background: var(--brand-pink-dark);
  color: var(--text-inverse);
}

.coin-dialog__submit:disabled {
  opacity: 0.6;
}
</style>
