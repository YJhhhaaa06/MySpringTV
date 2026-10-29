<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import AppIcon from '../common/AppIcon.vue'

/**
 * 封面预览弹层：把封面放进**仓库里真实存在的三个展示位**里看。
 *
 * <p>比"再显示一张大图"有用，是因为**每个框的比例与裁切方式都跟真页面一致**：
 * - 播放页海报区 / 首页·搜索卡片 / 历史·动态卡片：`aspect-ratio: 16 / 9`（`VideoCard.vue`、`VideoPage.vue`）；
 * - 播放页侧栏相关推荐：`aspect-ratio: 16 / 10`（`VideoCard.vue` 的 `--row`）；
 * 三处都是 `object-fit: cover` ⇒ 侧栏那格会在 16:9 的封面上**再切掉左右各约 5%**，
 * 预览里能直接看出来 —— 这正是"投稿时看不见、上线后才发现被切了"的那类问题。
 *
 * <p>框里的标题用**草稿里的真标题**；没有标题就写「未命名稿件」，
 * 不编播放量 / 时长（本仓"界面数字必须来自真接口"，草稿没有这些数）。
 */
defineProps<{
  src: string | null
  title: string
}>()

const emit = defineEmits<{
  (event: 'close'): void
}>()

const placeholder = '未命名稿件'

function close(): void {
  emit('close')
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
  }
}

onMounted(() => {
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <div class="dialog-mask" @click.self="close">
      <div class="preview" role="dialog" aria-modal="true" aria-labelledby="preview-title" tabindex="-1">
        <header class="preview__head">
          <div>
            <h2 id="preview-title" class="preview__title">封面预览</h2>
            <p class="preview__note">框的比例与裁切方式跟真页面一致；框外部分观众看不到</p>
          </div>
          <button class="preview__close" type="button" aria-label="关闭" @click="close">
            <AppIcon name="close" :size="18" />
          </button>
        </header>

        <div v-if="!src" class="preview__empty">还没有封面，先上传一张再预览。</div>

        <div v-else class="preview__body">
          <figure class="frame">
            <div class="frame__box frame__box--player">
              <img :src="src" alt="播放页封面预览" />
              <span class="frame__play" aria-hidden="true"><AppIcon name="play" :size="26" /></span>
            </div>
            <figcaption class="frame__caption">
              <span class="frame__label">播放页海报区</span>
              <span class="frame__ratio">16 : 9</span>
            </figcaption>
            <p class="frame__title">{{ title || placeholder }}</p>
          </figure>

          <div class="preview__side">
            <figure class="frame">
              <div class="frame__box frame__box--card">
                <img :src="src" alt="卡片封面预览" />
              </div>
              <figcaption class="frame__caption">
                <span class="frame__label">首页 / 搜索 / 历史卡片</span>
                <span class="frame__ratio">16 : 9</span>
              </figcaption>
              <p class="frame__title">{{ title || placeholder }}</p>
            </figure>

            <figure class="frame">
              <div class="frame__row">
                <div class="frame__box frame__box--row">
                  <img :src="src" alt="侧栏封面预览" />
                </div>
                <p class="frame__title frame__title--row">{{ title || placeholder }}</p>
              </div>
              <figcaption class="frame__caption">
                <span class="frame__label">播放页侧栏相关推荐</span>
                <span class="frame__ratio">16 : 10</span>
              </figcaption>
              <p class="frame__hint">侧栏 16:10 会在 16:9 封面上再切掉左右各约 5%</p>
            </figure>
          </div>
        </div>

        <footer class="preview__foot">
          <button class="btn btn-ghost" type="button" @click="close">关闭</button>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.preview {
  width: 100%;
  max-width: 680px;
  padding: 18px 20px 16px;
  border-radius: var(--radius-lg);
  background: var(--bg-0);
  box-shadow: var(--shadow-2);
  outline: none;
}

.preview__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.preview__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.preview__note {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-3);
}

.preview__close {
  display: flex;
  width: 28px;
  height: 28px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--text-3);
}

.preview__close:hover {
  color: var(--text-1);
  background: var(--bg-1);
}

.preview__empty {
  padding: 48px 0;
  font-size: 13px;
  text-align: center;
  color: var(--text-3);
}

.preview__body {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
  gap: 18px;
  margin-top: 16px;
}

.preview__side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.frame {
  min-width: 0;
  margin: 0;
}

.frame__box {
  position: relative;
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--bg-2);
}

.frame__box img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.frame__box--player,
.frame__box--card {
  aspect-ratio: 16 / 9;
}

.frame__box--player {
  background: #000;
}

.frame__box--row {
  width: 104px;
  flex: none;
  aspect-ratio: 16 / 10;
}

.frame__play {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(255, 255, 255, 0.92);
  background: rgba(0, 0, 0, 0.18);
}

.frame__row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.frame__caption {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-3);
}

.frame__label {
  color: var(--text-2);
}

.frame__ratio {
  font-variant-numeric: tabular-nums;
  color: var(--text-4);
}

.frame__title {
  margin-top: 4px;
  overflow: hidden;
  font-size: 13px;
  line-height: 18px;
  color: var(--text-1);
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.frame__title--row {
  min-width: 0;
  flex: 1 1 auto;
  margin-top: 0;
}

.frame__hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 16px;
  color: var(--text-4);
}

.preview__foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--line-2);
}

@media (max-width: 720px) {
  .preview__body {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
