package io.github.yjhhhaaa06.videoweb.comment.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 发表评论请求。
 *
 * <p>承接 TV {@code com.itheima.comment.model.dto.CommentDTO}（去掉 {@code userId}——
 * 它由 {@code @CurrentUserId} 注入，不该由客户端传）。
 *
 * <p>校验口径**逐条**来自 TV {@code CommandConverter.commentToCommand}，
 * 并由旧 pytest {@code E-03}（缺 message → 400）固化：
 *
 * <table>
 *   <caption>TV 手写校验 → 声明式约束</caption>
 *   <tr><th>TV</th><th>新实现</th></tr>
 *   <tr><td>{@code if (contentId == 0) throw ParamException("未指定被点赞内容")}</td>
 *       <td>{@code @NotNull @Positive} → 400</td></tr>
 *   <tr><td>{@code if (message == null || message.isBlank()) throw ParamException("输入不能为空")}</td>
 *       <td>{@code @NotBlank} → 400</td></tr>
 *   <tr><td>{@code if (message.length() > 1000) throw ParamException("不可发送超过1000字的评论")}</td>
 *       <td>{@code @Size(max = 1000)} → 400（{@code @Size} 与 {@code String.length()} 同为 UTF-16 计数，口径一致）</td></tr>
 * </table>
 *
 * <p>⚠️ **一处文案修正（有意）**：TV 对 {@code contentId == 0} 的提示是
 * <i>"未指定被点赞内容"</i>——那是从点赞域复制粘贴过来的错文案（评论接口里说"点赞"）。
 * 此处改为 <i>"未指定被评论内容"</i>。状态码仍是 400，未变。
 *
 * <p>用包装类型 {@code Long contentId} 而非 TV 的原始类型 {@code long}：只有包装类型表达得出"字段缺失"，
 * 原始类型会让缺失静默变成 0（同 {@code coupon/GrabCouponRequest} 的处理）。
 *
 * @param contentId 被评论的内容 id
 * @param message   评论正文，≤1000 字
 * @param parentId  被回复的评论 id；{@code null} 或 {@code 0} = 发表主楼评论（TV 口径）
 */
public record AddCommentRequest(

        @NotNull(message = "未指定被评论内容")
        @Positive(message = "未指定被评论内容")
        Long contentId,

        @NotBlank(message = "输入不能为空")
        @Size(max = 1000, message = "不可发送超过1000字的评论")
        String message,

        /** 无校验：TV 对 parentId 无约束，{@code null}/{@code 0} 均表示主楼。 */
        Long parentId
) {
}
