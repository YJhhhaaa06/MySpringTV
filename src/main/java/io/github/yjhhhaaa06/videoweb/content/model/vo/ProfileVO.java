package io.github.yjhhhaaa06.videoweb.content.model.vo;

import io.github.yjhhhaaa06.videoweb.common.model.dto.PageResult;

/**
 * 用户主页视图（承接 TV {@code com.itheima.content.model.vo.ProfileVO}）。
 *
 * <h2>★ 分页信封嵌在 {@code data.contentPage}（而不是 {@code data} 本身）</h2>
 * 主页除了内容列表还要返回用户维度信息（用户名 / 关注数 / 粉丝数 / 我是否关注了他），
 * 故信封只能嵌一层。旧 pytest {@code TestProfilePaging} 的断言路径就是
 * {@code data.contentPage.{list,total,page,pageSize,totalPages}}——**冻结契约**，
 * 不要"顺手"把信封提到顶层。
 *
 * <h2>{@code isFollowed} 是三态</h2>
 * {@code Boolean}（不是 {@code boolean}）：
 * <ul>
 *   <li>匿名访问 → {@code null}（字段**不出现在 JSON 里**吗？不——本类没有
 *       {@code @JsonInclude(NON_NULL)}，故输出 {@code "isFollowed": null}，与 TV 一致）；</li>
 *   <li>看自己的主页（{@code currentUserId == profileUserId}）→ {@code null}
 *       （"我是否关注我自己"是无意义的问题，TV 原样）；</li>
 *   <li>登录且看别人 → 真实值。</li>
 * </ul>
 * 用 {@code boolean} 会把第一种情况静默变成 {@code false}（"我没关注他"），属语义错误。
 *
 * <h2>手写 getter 的理由同 {@code ContentVO}</h2>
 * Lombok 对 {@code Boolean isFollowed} 生成 {@code getIsFollowed()} 其实是对的，但为了与
 * 其它 VO 保持同一写法（并避免将来把字段改成 {@code boolean} 时命名突然漂移），此处显式声明。
 */
public class ProfileVO {

    private long userId;
    private String username;
    private int followerCount;
    private int followCount;
    private Boolean isFollowed;
    private PageResult<ContentVO> contentPage;

    public ProfileVO() {
    }

    public ProfileVO(long userId, String username, int followerCount, int followCount,
                     Boolean isFollowed, PageResult<ContentVO> contentPage) {
        this.userId = userId;
        this.username = username;
        this.followerCount = followerCount;
        this.followCount = followCount;
        this.isFollowed = isFollowed;
        this.contentPage = contentPage;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getFollowerCount() {
        return followerCount;
    }

    public void setFollowerCount(int followerCount) {
        this.followerCount = followerCount;
    }

    public int getFollowCount() {
        return followCount;
    }

    public void setFollowCount(int followCount) {
        this.followCount = followCount;
    }

    public Boolean getIsFollowed() {
        return isFollowed;
    }

    public void setIsFollowed(Boolean isFollowed) {
        this.isFollowed = isFollowed;
    }

    public PageResult<ContentVO> getContentPage() {
        return contentPage;
    }

    public void setContentPage(PageResult<ContentVO> contentPage) {
        this.contentPage = contentPage;
    }
}
