import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['src/**/*.test.ts'],
    environment: 'node',
    // ★ 固定 UTC：契约 §2.7 的无偏移串语义是 +08:00，而"补 +08:00"与"按本地解析"
    //   在 +08:00 机器上**结果相同** ⇒ 不固定时区就测不出 parseServerTime 是否真的补了。
    //   断言见 format.test.ts「测试环境时区固定为 UTC」。
    env: { TZ: 'UTC' },
  },
})
