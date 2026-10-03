package io.github.yjhhhaaa06.videoweb.feed.model.message;

/**
 * 降级补推指令（承接 TV {@code follow.model.dto.AuthorBackfillMessage}）：{@code authorId} 由大V降为
 * 普通（滞回判定 edge）⇒ 消费者把该作者**最近 K 条**内容补写进其**现任粉丝**收件箱。
 *
 * <p><b>投递点</b>：关注 / 取关**事务提交后**，且**只在**状态迁移为 {@code DOWNGRADED}
 * （edge，{@code affected == 1}）时投递——edge 天然唯一（并发"恰一次"由 {@code AutoBigVStateService}
 * 的行锁 + {@code affected rows} 保证），故**无需去抖 / 补偿**。
 *
 * <p><b>载荷只放 authorId**（不带内容清单、不带粉丝清单）：补推内容在消费时刻按 DB 真相重算
 * （最近 K 条 = {@code feed.inbox.windowPerAuthor}，粉丝 = 消费时刻的现任粉丝，游标迭代），
 * 故载荷不含任何不可重算状态——与 {@link InboxRebuildMessage} 同口径。
 */
public record AuthorBackfillMessage(long authorId) {
}
