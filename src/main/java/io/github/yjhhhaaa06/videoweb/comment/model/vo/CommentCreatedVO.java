package io.github.yjhhhaaa06.videoweb.comment.model.vo;

/**
 * 发表评论成功后的出参（{@code POST /api/v1/comments}，`T66` / `N5`）。
 *
 * <p>JSON 形状：{@code {"commentId": N}}。★ 这是**对外契约变更**（此前为无 {@code data}）：
 * 幂等重放要求"客户端能定位刚创建的那一条"——只回 {@code 200} 时，重发只能告诉它"成功过"，
 * 却不能告诉它"成功的是哪一条"（响应层判据，见
 * {@code architecture/tech/idempotency/写接口幂等.md} §一）。
 *
 * <p>⚠️ 「刷新后找不到自己的评论」仍是允许的（评论被吞是平台常态）——本字段只承诺
 * "这次响应对应的那条评论的 id"，不承诺它长期可读。
 *
 * @param commentId 新建评论的 id（{@code CommentDao#insert} 的 {@code useGeneratedKeys} 回填值）
 */
public record CommentCreatedVO(long commentId) {
}
