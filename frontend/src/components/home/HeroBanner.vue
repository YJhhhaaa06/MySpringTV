<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { HeroSlideVM } from '../../types/ui'
import AppIcon from '../common/AppIcon.vue'

const props = defineProps<{ slides: HeroSlideVM[] }>()

const index = ref(0)
const paused = ref(false)
let timer: number | undefined

const count = computed(() => props.slides.length)
const signature = computed(() => props.slides.map((slide) => slide.id).join(','))

function go(target: number): void {
  if (count.value === 0) return
  index.value = ((target % count.value) + count.value) % count.value
}

function next(): void {
  go(index.value + 1)
}

function prev(): void {
  go(index.value - 1)
}

function stop(): void {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

function start(): void {
  stop()
  if (count.value > 1) {
    timer = window.setInterval(() => {
      if (!paused.value) next()
    }, 5000)
  }
}

watch(signature, () => {
  index.value = 0
  start()
})

onMounted(start)
onUnmounted(stop)
</script>

<template>
  <section
    class="hero"
    aria-label="推荐橱窗"
    @mouseenter="paused = true"
    @mouseleave="paused = false"
    @focusin="paused = true"
    @focusout="paused = false"
  >
    <a
      v-for="(slide, i) in slides"
      v-show="i === index"
      :key="slide.id"
      class="hero__slide"
      :href="slide.href"
      target="_blank"
      rel="noopener"
    >
      <img class="hero__img" :src="slide.coverUrl" :alt="slide.title" loading="eager" />
      <span class="hero__shade" aria-hidden="true" />
      <span class="hero__caption">
        <span class="hero__title">{{ slide.title }}</span>
        <span class="hero__author">UP主：{{ slide.authorName }}</span>
      </span>
    </a>

    <template v-if="slides.length > 1">
      <button class="hero__arrow hero__arrow--prev" type="button" aria-label="上一张" @click.prevent="prev">
        <AppIcon name="chevron-left" :size="22" />
      </button>
      <button class="hero__arrow hero__arrow--next" type="button" aria-label="下一张" @click.prevent="next">
        <AppIcon name="chevron-right" :size="22" />
      </button>
    </template>

    <div v-if="slides.length > 1" class="hero__dots">
      <button
        v-for="(slide, i) in slides"
        :key="`dot-${slide.id}`"
        class="hero__dot"
        :class="{ 'is-active': i === index }"
        type="button"
        :aria-label="`第 ${i + 1} 张`"
        @click.prevent="go(i)"
      />
    </div>
  </section>
</template>

<style scoped>
/* ★ 网格占位写在组件自己身上：它是 2 列 × 2 行的「推荐橱窗」，
   与 B 站一致地**不铺满两行**（height 固定，换来看起来像运营位而不是一张超大图） */
.hero {
  position: relative;
  grid-column: span 2;
  grid-row: span 2;
  align-self: start;
  height: 380px;
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--bg-2);
}

.hero__slide {
  position: absolute;
  inset: 0;
  display: block;
}

.hero__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.hero__shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0) 45%, rgba(0, 0, 0, 0.62) 100%);
}

.hero__caption {
  position: absolute;
  right: 20px;
  bottom: 18px;
  left: 20px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: #fff;
}

.hero__title {
  font-size: 18px;
  font-weight: 600;
  line-height: 26px;
  overflow: hidden;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.hero__author {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.82);
}

.hero__arrow {
  position: absolute;
  top: 50%;
  display: flex;
  width: 34px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  color: #fff;
  background: rgba(0, 0, 0, 0.28);
  opacity: 0;
  transform: translateY(-50%);
  transition: opacity 0.2s ease, background-color 0.2s ease;
}

.hero:hover .hero__arrow,
.hero__arrow:focus-visible {
  opacity: 1;
}

.hero__arrow:hover {
  background: rgba(0, 0, 0, 0.5);
}

.hero__arrow--prev {
  left: 0;
}

.hero__arrow--next {
  right: 0;
}

.hero__dots {
  position: absolute;
  right: 0;
  bottom: 10px;
  left: 0;
  display: flex;
  justify-content: center;
  gap: 8px;
}

.hero__dot {
  width: 8px;
  height: 8px;
  padding: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.5);
  transition: width 0.2s ease, background-color 0.2s ease;
}

.hero__dot.is-active {
  width: 18px;
  border-radius: var(--radius-pill);
  background: #fff;
}

@media (max-width: 760px) {
  .hero {
    grid-column: span 2;
    height: 220px;
  }
}
</style>
