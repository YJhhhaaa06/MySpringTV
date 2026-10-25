/**
 * 写接口幂等键（客户端侧）。
 *
 * <p>后端只有 4 条 `KEYED` 端点会消费它（`POST /comments`、`POST /contents/{id}/coins`、
 * 两条投稿），其余端点**带不带都一样**（`NATURAL` 的语义是"重试不改变 DB 终态"）。
 * 键形态由 `IdempotencyFilter.CLIENT_KEY = [A-Za-z0-9._-]{1,64}` 钉死，故 UUID 合法（36 字符）。
 *
 * <h2>为什么是"生成一次、重试复用"，而不是"每次请求都新生成"</h2>
 * 幂等的全部价值在**同一次用户意图的重试**：用户点"发表评论"→ 请求超时 → 再点一次，
 * 这两下必须是**同一个键**（服务端才认得出是同一次意图）。每次新生成 = 服务端每次当新请求
 * ⇒ 评论发两条。所以调用点要在**发起意图时**取键，失败重试沿用同一个键。
 *
 * <p>⚠️ 键的作用域是 `idem:{slug}:{uid}:{key}` ⇒ 换用户/换动作都要换键；
 * 本函数不承诺唯一性之外的任何语义（不落库、不复用）。
 */
export function newIdempotencyKey(): string {
  const uuid = globalThis.crypto?.randomUUID?.()
  if (uuid) return uuid
  // 兜底（老浏览器 / 非安全上下文）：仍满足 [A-Za-z0-9._-]{1,64}
  const random = Math.random().toString(36).slice(2, 12)
  return `k-${Date.now().toString(36)}-${random}`
}

/** 幂等键请求头名（与后端 `IdempotencyFilter.HEADER_KEY` 同字面量）。 */
export const IDEMPOTENCY_HEADER = 'Idempotency-Key'

/** 便捷构造：`postJson(path, body, withIdempotencyKey())` */
export function withIdempotencyKey(key: string = newIdempotencyKey()): {
  headers: Record<string, string>
} {
  return { headers: { [IDEMPOTENCY_HEADER]: key } }
}
