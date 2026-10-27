/**
 * 内联图标（24×24 viewBox，线宽 1.7）。只放**真的用到的**图标 —— 没有插画库依赖。
 * 口径：B 站视觉复刻，图形为手写近似，不复制官方图标资源。
 */
export const ICONS = {
  tv: '<rect x="3" y="7" width="18" height="13" rx="3.5"/><path d="M8 3.5 11 7M16 3.5 13 7"/><path d="M10.5 11.5v4l3.5-2z" fill="currentColor" stroke="none"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="m16.2 16.2 4.3 4.3"/>',
  upload: '<path d="M12 16.5V4.5"/><path d="m7.2 9.3 4.8-4.8 4.8 4.8"/><path d="M4 20h16"/>',
  download: '<path d="M12 4v12"/><path d="m7.2 11.2 4.8 4.8 4.8-4.8"/><path d="M4 20h16"/>',
  message: '<rect x="3" y="5" width="18" height="13" rx="2.5"/><path d="m3.5 7 8.5 6 8.5-6"/>',
  dynamic: '<path d="M13 2.5 4.5 13.5H11l-1 8 9-11.5h-6.5z"/>',
  fire: '<path d="M12 2.8c1 3.2-1.2 4.4-1.2 6.6 0 1.6 1.7 2.1 2.7 1.6 1.6-.8 1.6-3.4 1.6-3.4 2 2 3 4.2 3 6.2a6.1 6.1 0 0 1-12.2 0c0-3.1 2-5.2 3.6-7.3.8-1 1.5-2 1.5-3.7z"/>',
  star: '<path d="m12 3.6 2.6 5.4 5.9.8-4.3 4.1 1.1 5.9-5.3-2.9-5.3 2.9 1.1-5.9L3.5 9.8l5.9-.8z"/>',
  clock: '<circle cx="12" cy="12" r="8.4"/><path d="M12 7v5.4l3.6 2.1"/>',
  dashboard: '<rect x="3" y="3" width="7.2" height="7.2" rx="1.6"/><rect x="13.8" y="3" width="7.2" height="7.2" rx="1.6"/><rect x="3" y="13.8" width="7.2" height="7.2" rx="1.6"/><rect x="13.8" y="13.8" width="7.2" height="7.2" rx="1.6"/>',
  /* 播放量（B 站卡片 / 详情页的统计位）：描边方框 + 实心三角，近似官方 18px 图标 */
  play: '<rect x="2.5" y="4.5" width="19" height="15" rx="4"/><path d="M10 8.6v6.8l5.6-3.4z" fill="currentColor" stroke="none"/>',
  danmaku: '<rect x="3" y="5" width="18" height="14" rx="3.2"/><path d="M7 10h5M7 14h10M15.5 10h2"/>',
  refresh: '<path d="M20 12a8 8 0 1 1-2.5-5.8"/><path d="M20.2 4.2v4.6h-4.6"/>',
  'chevron-left': '<path d="m14.6 5-7 7 7 7"/>',
  'chevron-right': '<path d="m9.4 5 7 7-7 7"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  user: '<circle cx="12" cy="8" r="3.6"/><path d="M5 20c.8-3.6 3.6-5.6 7-5.6s6.2 2 7 5.6"/>',
  thumb: '<path d="M7.2 10.2v9.6H4V10.2z"/><path d="M7.2 10.2 11.4 4c.6-.8 1.8-.9 2.4-.1.3.4.5.9.5 1.5v4.4h4.3c1.2 0 2 1 1.8 2.1l-1.2 6.8c-.1.9-.9 1.6-2 1.6H7.2"/>',
  coin: '<circle cx="12" cy="12" r="8.4"/><path d="M9 9.4 12 15l3-5.6"/><path d="M8 12.2h8"/>',
  comment: '<path d="M20 15.2a3 3 0 0 1-3 3H8.3L4 21V6.4a3 3 0 0 1 3-3h10a3 3 0 0 1 3 3z"/>',
  /* B 站卡片 hover / 播放页菜单的「稍后再看」：圆周箭头 + 中间播放三角 */
  'watch-later':
    '<path d="M19.6 12a7.6 7.6 0 1 1-2.2-5.4"/><path d="M19.7 3.6v5h-5"/><path d="M10.4 8.8v6.4l5.2-3.2z" fill="currentColor" stroke="none"/>',
  /* 操作栏右端的「更多」⋮（B 站把「用手机看 / 稍后再看」收在这里） */
  more: '<circle cx="12" cy="5" r="1.6" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.6" fill="currentColor" stroke="none"/><circle cx="12" cy="19" r="1.6" fill="currentColor" stroke="none"/>',
} as const

export type IconName = keyof typeof ICONS
