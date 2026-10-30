<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ApiError } from '../../api/http'
import { signIn } from '../../auth/session'
import AppIcon from '../common/AppIcon.vue'

const emit = defineEmits<{ (event: 'close'): void; (event: 'success'): void }>()

const phone = ref('')
const password = ref('')
const error = ref('')
const submitting = ref(false)
const phoneInput = ref<HTMLInputElement | null>(null)

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') emit('close')
}

onMounted(async () => {
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', onKeydown)
  await nextTick()
  phoneInput.value?.focus()
})

onUnmounted(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
})

async function submit(): Promise<void> {
  if (submitting.value) return
  error.value = ''
  const phoneValue = phone.value.trim()
  if (!phoneValue || !password.value) {
    error.value = '请输入手机号和密码'
    return
  }
  submitting.value = true
  try {
    // 真接口：POST /api/v1/sessions → token 写 localStorage → GET /users/me
    await signIn(phoneValue, password.value)
    emit('success')
    emit('close')
  } catch (exception) {
    error.value =
      exception instanceof ApiError
        ? (exception.fieldMessage('phone') ?? exception.fieldMessage('password') ?? exception.message)
        : '登录失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="login-modal" @click.self="emit('close')">
      <div class="login-modal__card" role="dialog" aria-modal="true" aria-labelledby="login-title">
        <button class="login-modal__close" type="button" aria-label="关闭" @click="emit('close')">
          <AppIcon name="close" :size="20" />
        </button>

        <div class="login-modal__logo"><AppIcon name="tv" :size="36" /></div>
        <h2 id="login-title" class="login-modal__title">登录</h2>
        <p class="login-modal__subtitle">登录后可以投币、收藏、看到自己的播放记录</p>

        <form class="login-modal__form" @submit.prevent="submit">
          <label class="login-modal__field">
            <span class="login-modal__label">手机号</span>
            <input
              ref="phoneInput"
              v-model.trim="phone"
              type="tel"
              inputmode="numeric"
              autocomplete="username"
              maxlength="11"
              placeholder="请输入手机号"
            />
          </label>
          <label class="login-modal__field">
            <span class="login-modal__label">密码</span>
            <input
              v-model="password"
              type="password"
              autocomplete="current-password"
              placeholder="请输入密码"
            />
          </label>

          <p v-if="error" class="login-modal__error" role="alert">{{ error }}</p>

          <button class="btn btn-primary login-modal__submit" type="submit" :disabled="submitting">
            {{ submitting ? '登录中…' : '登录' }}
          </button>
        </form>

        <p class="login-modal__hint">
          这是学习项目：没有扫码登录与短信验证码，注册页在后续窗口交付。
        </p>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.login-modal {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--bg-mask);
}

.login-modal__card {
  position: relative;
  width: 100%;
  max-width: 400px;
  padding: 32px 32px 24px;
  border-radius: var(--radius-lg);
  background: var(--bg-0);
  box-shadow: var(--shadow-2);
  text-align: center;
}

.login-modal__close {
  position: absolute;
  top: 12px;
  right: 12px;
  display: flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--text-3);
}

.login-modal__close:hover {
  color: var(--text-1);
  background: var(--bg-1);
}

.login-modal__logo {
  display: flex;
  justify-content: center;
  color: var(--brand-pink);
}

.login-modal__title {
  margin-top: 8px;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-1);
}

.login-modal__subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: var(--text-3);
}

.login-modal__form {
  margin-top: 22px;
  text-align: left;
}

.login-modal__field {
  display: block;
}

.login-modal__field + .login-modal__field {
  margin-top: 14px;
}

.login-modal__label {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  color: var(--text-2);
}

.login-modal__field input {
  width: 100%;
  height: 40px;
  padding: 0 12px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius);
  font-size: 14px;
  color: var(--text-1);
  background: var(--bg-0);
  outline: none;
  transition: border-color 0.15s ease;
}

.login-modal__field input:focus {
  border-color: var(--brand-blue);
}

.login-modal__error {
  margin-top: 12px;
  font-size: 13px;
  color: #f56c6c;
}

.login-modal__submit {
  width: 100%;
  height: 40px;
  margin-top: 18px;
  font-size: 15px;
}

.login-modal__hint {
  margin-top: 16px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-3);
}
</style>
