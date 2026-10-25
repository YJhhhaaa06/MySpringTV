<script setup lang="ts">
import { ref, watch } from 'vue'
import AppIcon from '../common/AppIcon.vue'

const props = withDefaults(defineProps<{ initialKeyword?: string }>(), { initialKeyword: '' })

const keyword = ref(props.initialKeyword)
watch(
  () => props.initialKeyword,
  (value) => {
    keyword.value = value
  },
)

/**
 * ★ 跨应用跳转：搜索是**另一个入口**（`search.html`），所以整页跳转、不 vue-router。
 * 口径：搜索是**另一个入口**（`search.html`），跨应用一律整页跳转、不 vue-router。
 */
function submit(): void {
  const value = keyword.value.trim()
  if (!value) return
  window.location.href = `/search?keyword=${encodeURIComponent(value)}`
}
</script>

<template>
  <form class="search-box" role="search" @submit.prevent="submit">
    <input
      v-model="keyword"
      class="search-box__input"
      type="search"
      placeholder="搜索你感兴趣的视频"
      aria-label="搜索"
      autocomplete="off"
    />
    <button class="search-box__btn" type="submit" aria-label="搜索">
      <AppIcon name="search" :size="18" />
    </button>
  </form>
</template>

<style scoped>
.search-box {
  display: flex;
  align-items: center;
  width: 100%;
  height: 38px;
  border-radius: var(--radius);
  background: var(--search-bg, var(--bg-1));
  transition: background-color 0.2s ease, box-shadow 0.2s ease;
}

.search-box:focus-within {
  background: var(--bg-0);
  box-shadow: 0 0 0 1px var(--brand-blue);
}

.search-box__input {
  flex: 1;
  min-width: 0;
  height: 100%;
  padding: 0 8px 0 14px;
  border: 0;
  background: transparent;
  font-size: 14px;
  color: var(--text-1);
  outline: none;
}

.search-box__input::placeholder {
  color: var(--text-3);
}

.search-box__input::-webkit-search-cancel-button {
  display: none;
}

.search-box__btn {
  display: flex;
  width: 44px;
  height: 100%;
  align-items: center;
  justify-content: center;
  border-radius: 0 var(--radius) var(--radius) 0;
  color: var(--text-2);
}

.search-box__btn:hover {
  color: var(--brand-blue);
}
</style>
