<script setup lang="ts">
withDefaults(defineProps<{ layout?: 'grid' | 'row' }>(), { layout: 'grid' })
</script>

<template>
  <div class="skeleton-card" :class="`skeleton-card--${layout}`" aria-hidden="true">
    <div class="skeleton-card__cover skeleton" />
    <div class="skeleton-card__lines">
      <div class="skeleton skeleton-card__line" />
      <div class="skeleton skeleton-card__line skeleton-card__line--short" />
    </div>
  </div>
</template>

<style scoped>
.skeleton-card {
  min-width: 0;
}

.skeleton-card__cover {
  aspect-ratio: 16 / 9;
  border-radius: var(--radius);
}

.skeleton-card__lines {
  padding-top: 10px;
}

.skeleton-card__line {
  height: 14px;
  border-radius: 4px;
}

.skeleton-card__line + .skeleton-card__line {
  margin-top: 8px;
}

.skeleton-card__line--short {
  width: 60%;
}

.skeleton-card--row {
  display: grid;
  grid-template-columns: 104px minmax(0, 1fr);
  gap: 8px;
}

.skeleton-card--row .skeleton-card__cover {
  aspect-ratio: 16 / 10;
}

.skeleton-card--row .skeleton-card__lines {
  padding-top: 0;
}
</style>
