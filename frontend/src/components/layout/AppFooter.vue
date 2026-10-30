<template>
  <footer class="app-footer">
    <div class="page-content app-footer__inner">
      <p class="app-footer__line">
        MySpringTV · 仿哔哩哔哩网页版的学习项目，与哔哩哔哩无隶属关系
      </p>
      <p class="app-footer__line app-footer__line--muted">
        页面上的数字都来自本仓后端的真实接口；弹幕 / 番剧 / 直播 / 会员购 / 游戏中心永远占位（不排期、不阻塞）。
      </p>
    </div>
  </footer>
</template>

<style scoped>
.app-footer {
  margin-top: 48px;
  padding: 24px 0 32px;
  border-top: 1px solid var(--line-2);
  background: var(--bg-0);
}

.app-footer__inner {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.app-footer__line {
  font-size: 12px;
  line-height: 18px;
  color: var(--text-2);
}

.app-footer__line--muted {
  color: var(--text-3);
}
</style>
