<script setup lang="ts">
import { toasts } from '../../common/toast'
</script>

<template>
  <Teleport to="body">
    <div class="toast-host" role="status" aria-live="polite">
      <TransitionGroup name="toast">
        <p v-for="toast in toasts" :key="toast.id" class="toast" :class="`toast--${toast.kind}`">
          {{ toast.text }}
        </p>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-host {
  position: fixed;
  bottom: 72px;
  left: 50%;
  z-index: 2000;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  transform: translateX(-50%);
  pointer-events: none;
}

.toast {
  padding: 9px 18px;
  border-radius: var(--radius-pill);
  font-size: 14px;
  line-height: 1.4;
  color: var(--text-inverse);
  background: rgba(0, 0, 0, 0.78);
  box-shadow: var(--shadow-2);
  white-space: nowrap;
}

.toast--error {
  background: rgba(214, 48, 49, 0.92);
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(8px);
}
</style>
