package io.github.yjhhhaaa06.videoweb.common.security;

import io.github.yjhhhaaa06.videoweb.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全契约测试：三个授权注解（{@link Public} / {@link RequiresLogin} / {@link RequiresAdmin}）
 * → 是否真的被 {@link RequestMappingLookup} 识别。
 *
 * <h2>★ 2026-10-18：判定口径从「声明才拦」改为「声明才放」（F-01 的两条真风险）</h2>
 * <ol>
 *   <li><b>默认拒绝</b>：未标任何注解 ⇒ 需登录。放行口只有 {@link Public} 一处。</li>
 *   <li><b>角色判定跟着处理器走</b>：{@link RequiresAdmin} 取代 {@code path.startsWith("/api/admin")}，
 *       并且**蕴含**登录要求（匿名访问 admin 端点仍得 401，不再依赖两个注解同时写对）。</li>
 * </ol>
 * 两条都由本类钉住；{@code 所有admin前缀控制器都必须标RequiresAdmin} 是新增控制器的护栏。
 *
 * <h2>存在理由（这个类本该在切片 0 就存在）</h2>
 * {@link RequiresLogin} 的 Javadoc 一直写着"可被测试自动校验（见 {@code SecurityContractTests}）"，
 * 但那个类**从未被创建**——于是机制坏了很久没人知道（见 {@link RequestMappingLookup} 的修复说明）。
 *
 * <p><b>为什么端到端测试抓不到这个 bug</b>：修复前，所有需要登录的端点恰好都接收
 * {@code @CurrentUserId}，而那个参数解析器取不到 userId 时会抛 401。
 * 于是"无 token → 401"的断言**在机制完全失效的情况下也照样通过**——
 * 401 来自参数解析器，不是来自 {@code @RequiresLogin}。
 * 端到端断言只看到"401"，看不到"401 是谁给的"。
 *
 * <p>所以本类**直接断言机制本身**（拿 {@link RequestMappingLookup} 对各类端点求值），
 * 而不是断言 HTTP 状态码。这是"断言可观察行为"原则的一处必要例外：
 * 当"谁给出的结果"本身是契约时，就得直接测那个来源。
 *
 * <p>配套的 HTTP 级覆盖是 {@code LikeFlowTests.未登录全部401}——它专门包含
 * **不接收 {@code @CurrentUserId}** 的端点（{@code /like/content/count}），
 * 那正是修复前唯一会漏的那种端点。
 */
class SecurityContractTests extends AbstractIntegrationTest {

    @Autowired
    private RequestMappingLookup mappingLookup;

    @Autowired
    private ApplicationContext applicationContext;

    private boolean requiresLogin(String method, String uri) {
        return mappingLookup.requiresLogin(new MockHttpServletRequest(method, uri));
    }

    private boolean requiresAdmin(String method, String uri) {
        return mappingLookup.requiresAdmin(new MockHttpServletRequest(method, uri));
    }

    /**
     * 用**桩处理器**构造 lookup：不依赖容器里真有这样一个端点，就能断言「注解 → 判定」这条链。
     *
     * <p>为什么需要它：默认拒绝 / 冲突取严 / admin 蕴含登录这三种形态，**绝不能**在真实控制器上造
     * （那会把矛盾注解写进生产代码）。而"未标注解"这一形态恰恰是本轮新增的**默认值**，
     * 只能靠桩来钉。
     */
    private RequestMappingLookup lookupFor(String methodName) throws NoSuchMethodException {
        Method method = AuthzProbe.class.getMethod(methodName);
        HandlerMapping stub = request -> new HandlerExecutionChain(new HandlerMethod(PROBE, method));
        return new RequestMappingLookup(stub);
    }

    private RequestMappingLookup.HandlerAuthz resolveOnProbe(String methodName) throws NoSuchMethodException {
        return lookupFor(methodName).resolve(new MockHttpServletRequest("GET", "/probe"));
    }

    private static final AuthzProbe PROBE = new AuthzProbe();

    @Test
    @DisplayName("★回归：类级 @RequiresLogin 且**不接收** @CurrentUserId 的端点也必须判为需登录")
    void 类级注解且不接收UserId的端点需登录() {
        assertThat(requiresLogin("GET", "/api/v1/contents/1/like-count"))
                .as("2026-10-02 修复前的漏洞点：该端点标了 @RequiresLogin 但不接收 userId，"
                        + "机制失效时它在无 token 下返回 200（其余端点因 @CurrentUserId 抛 401 而掩盖了问题）")
                .isTrue();
        assertThat(requiresLogin("GET", "/api/v1/comments/1/like-count"))
                .as("同上：另一个不接收 userId 的受保护端点")
                .isTrue();
    }

    @Test
    @DisplayName("类级 @RequiresLogin：该控制器全部端点判为需登录")
    void 类级注解覆盖全部端点() {
        assertThat(requiresLogin("PUT", "/api/v1/contents/1/like")).isTrue();
        assertThat(requiresLogin("DELETE", "/api/v1/contents/1/like")).isTrue();
        assertThat(requiresLogin("PUT", "/api/v1/comments/1/like")).isTrue();
        assertThat(requiresLogin("DELETE", "/api/v1/comments/1/like")).isTrue();
        assertThat(requiresLogin("GET", "/api/v1/contents/1/like")).isTrue();
        assertThat(requiresLogin("GET", "/api/v1/comments/1/like")).isTrue();
    }

    @Test
    @DisplayName("类级 @RequiresLogin：follow 全部端点判为需登录（TV 的 `/follow` 是**前缀**保护项，`T58` 后改由类级注解继续表达）")
    void follow类级注解覆盖全部端点() {
        // `T58` 契约变更（接口契约.md §2.3/§2.4）：关系 = PUT|DELETE /api/v1/follows/{id}，
        //   列表 = GET /api/v1/users/{id}/{following,followers}。followedUserId / userId 进路径 ⇒
        //   路径变量**必须**给样本值（写 {id} 匹配不到 handler ⇒ 恒判 PUBLIC，假绿）。
        assertThat(requiresLogin("PUT", "/api/v1/follows/1")).isTrue();
        assertThat(requiresLogin("DELETE", "/api/v1/follows/1")).isTrue();
        assertThat(requiresLogin("GET", "/api/v1/users/1/following")).isTrue();
        assertThat(requiresLogin("GET", "/api/v1/users/1/followers")).isTrue();
    }

    @Test
    @DisplayName("方法级 @RequiresLogin：user / coupon / comment 的受保护端点判为需登录")
    void 方法级注解端点需登录() {
        assertThat(requiresLogin("GET", "/api/v1/users/me")).isTrue();
        assertThat(requiresLogin("PUT", "/api/v1/users/me/password")).isTrue();
        assertThat(requiresLogin("PUT", "/api/v1/users/me/username")).isTrue();
        assertThat(requiresLogin("POST", "/api/v1/coupons/1/claims")).isTrue();
        assertThat(requiresLogin("GET", "/api/v1/users/me/coupons")).isTrue();
        assertThat(requiresLogin("POST", "/api/v1/comments")).isTrue();
        assertThat(requiresLogin("DELETE", "/api/v1/comments/1")).isTrue();
    }

    @Test
    @DisplayName("公开端点不得被判为需登录（否则会误拦匿名访问）")
    void 公开端点不需登录() {
        assertThat(requiresLogin("POST", "/api/v1/sessions")).isFalse();
        assertThat(requiresLogin("POST", "/api/v1/users")).isFalse();
        assertThat(requiresLogin("GET", "/api/v1/coupons")).isFalse();
    }

    @Test
    @DisplayName("S5：content 作者写端点（TV 的 PROTECTED_EXACT 精确项）判为需登录")
    void content写端点需登录() {
        // TV 的 AuthFilter 里，/content/** 下**只有这几条**在精确清单里（该域没有前缀级保护）
        // ⇒ 新实现必须用**方法级** @RequiresLogin（用类级会把将来的公开端点误拦）。
        assertThat(requiresLogin("PUT", "/api/v1/contents/1/comment-enabled")).isTrue();
        assertThat(requiresLogin("PUT", "/api/v1/contents/1")).isTrue();
        assertThat(requiresLogin("DELETE", "/api/v1/contents/1/media")).isTrue();
        assertThat(requiresLogin("DELETE", "/api/v1/contents/1")).isTrue();
    }

    @Test
    @DisplayName("S5：content / comment 读端点公开（TV 清单不含它们；带 token 只做个性化）")
    void content读端点公开() {
        // ★ `T58` reads 域（T58-8）落地后，GET /api/v1/contents/1（详情）与 GET /api/v1/users/1（主页）
        //   才有真实 handler 匹配——断言语义自 S5 起不变：这四个读端点都必须是公开面。
        //   GET /api/v1/contents/recommended 与 GET /api/v1/users/{id} 的路由优先级由
        //   PathPattern"字面量段优先于 {id}"保证（字面量 /recommended、/me 各自优先命中）。
        assertThat(requiresLogin("GET", "/api/v1/contents/1")).isFalse();
        assertThat(requiresLogin("GET", "/api/v1/contents")).isFalse();
        assertThat(requiresLogin("GET", "/api/v1/contents/recommended")).isFalse();
        assertThat(requiresLogin("GET", "/api/v1/users/1")).isFalse();
    }

    @Test
    @DisplayName("S5：comment 读端点公开（TV 只保护 /comment/add 与 /comment/delete 两个精确项）")
    void comment读端点公开() {
        assertThat(requiresLogin("GET", "/api/v1/contents/1/comments")).isFalse();
        assertThat(requiresLogin("GET", "/api/v1/comments/1/replies")).isFalse();
        // 反向对照：同域的两个写端点**仍是**需登录的（排除"整个 /comment 都被放行"的假绿）
        assertThat(requiresLogin("POST", "/api/v1/comments")).isTrue();
        assertThat(requiresLogin("DELETE", "/api/v1/comments/1")).isTrue();
    }

    @Test
    @DisplayName("S7：上传三个端点判为需登录（`T58` 起归 contents 域；类级 @RequiresLogin）")
    void upload类级注解覆盖全部端点() {
        assertThat(requiresLogin("POST", "/api/v1/contents/videos")).isTrue();
        assertThat(requiresLogin("POST", "/api/v1/contents/posts")).isTrue();
        assertThat(requiresLogin("PUT", "/api/v1/contents/1/media")).isTrue();
    }

    @Test
    @DisplayName("S8：admin 8 端点判为需登录（TV 的 `/api/admin` 是**前缀**保护项 ⇒ 类级注解）")
    void admin类级注解覆盖全部端点() {
        // ⚠️ 本组断言是 401/403 契约的**机制级**保证：三个 admin 控制器都标的是 @RequiresAdmin，
        //   它**蕴含**登录（见 RequiresAdmin蕴含需登录）——所以匿名访问这些端点得 401 而非 403。
        //   （旧实现靠额外标 @RequiresLogin 才能得到 401，漏标就会掉成 403。）
        //   端到端侧由 AdminSecurityTests 断言状态码，两者不可互相替代（SOP §2.5）。
        assertThat(requiresLogin("GET", "/api/admin/content/list")).isTrue();
        assertThat(requiresLogin("POST", "/api/admin/content/hide")).isTrue();
        assertThat(requiresLogin("POST", "/api/admin/content/unhide")).isTrue();
        assertThat(requiresLogin("POST", "/api/admin/comment/delete")).isTrue();
        assertThat(requiresLogin("GET", "/api/admin/media/me")).isTrue();
        assertThat(requiresLogin("GET", "/api/admin/media/list")).isTrue();
        assertThat(requiresLogin("POST", "/api/admin/media/scan")).isTrue();
        assertThat(requiresLogin("POST", "/api/admin/media/restore")).isTrue();
    }

    @Test
    @DisplayName("S9：feed 端点判为需登录（TV 的 `/feed` 是 PROTECTED_**PREFIXES** 前缀保护项）")
    void feed端点需登录() {
        // TV AuthFilter.PROTECTED_PREFIXES 含 "/feed"（前缀，见 AuthFilter.java:25）
        // ⇒ 类级 @RequiresLogin 与之对应。
        // 2026-10-03 更正：原来写 PROTECTED_EXACT（"精确，非前缀"）是误记——会被复算者当成事实依据，
        // 故一并改正。行为无差异：单端点时前缀与精确两种口径等价。
        // `T58` 契约变更（接口契约.md §2.3）：/feed 资源化为 GET /api/v1/feed——鉴权口径不变。
        assertThat(requiresLogin("GET", "/api/v1/feed")).isTrue();
    }

    @Test
    @DisplayName("收藏一期 T2：夹 CRUD 四端点判为需登录（**方法级**注解 —— 该域将来有匿名端点）")
    void favorite夹CRUD端点需登录() {
        // ⚠️ 本组是 T2 鉴权口径的**机制级**保证。收藏域**刻意不用类级** @RequiresLogin：
        //  favorite.md §三 要求 GET /api/v1/users/{id}/favorite-folders 匿名可访问（T3 已落地），类级会把它误拦成 401，
        //  而"私密夹自己照样看得见"会让这个误拦**长期无人发现**。
        //  反向对照（isFalse 那条）已在 T3 补入，见 favorite公开端点不得判为需登录。
        // `T58` 契约变更（接口契约.md §2.3/§2.4）：夹 CRUD 资源化到 /api/v1/favorite-folders，
        //   update/remove 的夹 id 进路径 —— 路径变量**必须**给样本值（写 {id} 匹配不到 handler ⇒ 恒判 PUBLIC，假绿）。
        assertThat(requiresLogin("POST", "/api/v1/favorite-folders")).isTrue();
        assertThat(requiresLogin("PUT", "/api/v1/favorite-folders/1")).isTrue();
        assertThat(requiresLogin("DELETE", "/api/v1/favorite-folders/1")).isTrue();
        assertThat(requiresLogin("GET", "/api/v1/favorite-folders")).isTrue();
    }

    @Test
    @DisplayName("收藏一期 T3：他人公开夹端点**不得**判为需登录（匿名可访问；与上一条四端点 true 构成成对断言）")
    void favorite公开端点不得判为需登录() {
        // ★ 与上面那组构成**成对断言**：四端点 true + 公开端点 false。
        //   它钉住的是"这一条**必须**公开"：若有人图省事给整个类搬上 @RequiresLogin，
        //   本类是**最先**变红的那条 —— T3 实测：类级误标 ⇒ 本类 1 红 + HTTP 侧 5 红
        //   （HTTP 侧同样抓得到，别误以为这里是唯一防线；见 FavoritePrivacyTests 的匿名用例）。
        //   机制级断言的**独立价值**是直接回答"这个 401 是谁要求的"，不依赖 HTTP 用例的覆盖面
        //   （T2 教训：机制失效时 401 常由参数解析器兜出，端到端看起来全对 —— 只有对
        //   RequestMappingLookup 直接求值才看得见）。
        // `T58`：userId 进路径（/api/v1/users/{id}/favorite-folders），样本值 1。
        assertThat(requiresLogin("GET", "/api/v1/users/1/favorite-folders"))
                .as("favorite.md §三 冻结契约：看他人公开夹匿名可访问（T3）")
                .isFalse();
    }

    @Test
    @DisplayName("收藏三期 T18：他人公开夹内容端点**不得**判为需登录（匿名可访问；与上面四端点 true 构成成对断言）")
    void favorite公开夹内容端点不得判为需登录() {
        // ★ T18 新增的第三条匿名端点（"点开他人公开夹看内容"）：与 /users/{id}/favorite-folders 同为**他人视角的公开读**，
        //   与"我是谁"无关 ⇒ 必须匿名可访问。
        //   机制级断言的**独立价值**同 T3 那条：直接回答"这个 401 是谁要求的"，不依赖 HTTP 用例覆盖面
        //   （T2 教训：机制失效时 401 常由参数解析器兜出，端到端看起来全对）。★ 反向：若给本类误加
        //   类级 @RequiresLogin，本类是**最先**变红的那条（HTTP 侧同样会红 —— 见 PublicFolderItemsTests 匿名用例）。
        // `T58`：folderId 进路径（/api/v1/favorite-folders/{id}/items），样本值 1。
        assertThat(requiresLogin("GET", "/api/v1/favorite-folders/1/items"))
                .as("T18 冻结契约：看他人公开夹的内容匿名可访问（服务端另以 is_private = 0 兜隐私）")
                .isFalse();
    }

    @Test
    @DisplayName("收藏一期 T4：收藏写路径三端点判为需登录（方法级注解，与 T2/T3 同一口径）")
    void favorite收藏写路径端点需登录() {
        // 三个**全登录**端点。T2 的教训在这里同样适用：三个端点都收 @CurrentUserId
        // （取不到即 401），所以"未登录 → 401"的 HTTP 用例对 @RequiresLogin 机制是假绿
        // （401 可能来自参数解析器而非注解）—— 机制级的"是谁要求的"只有本类能回答。
        // 反向对照（公开端点 isFalse）随上一条用例钉着：类级误标会让那条先红。
        // `T58` 契约变更（接口契约.md §2.3/§2.4）：add=POST /favorites、remove=DELETE /favorites、
        //   move=PUT /favorites/{contentId}（contentId 进路径，样本值 1）。
        assertThat(requiresLogin("POST", "/api/v1/favorites")).isTrue();
        assertThat(requiresLogin("DELETE", "/api/v1/favorites")).isTrue();
        assertThat(requiresLogin("PUT", "/api/v1/favorites/1")).isTrue();
    }

    @Test
    @DisplayName("收藏一期 T5：status 判为需登录、count 判为**匿名可访问**（成对断言）")
    void favorite状态与计数鉴权() {
        // ★ 两类端点、两个信任边界（T5）：
        //   /contents/{id}/favorite = "我收在哪些夹"（私有）⇒ 需登录；
        //   /contents/{id}/favorite-count = 公开展示数（favorite-需求.md §三）⇒ 匿名可访问
        //             —— ★ 与 /contents/{id}/like-count 的"需登录"刻意不同
        //             （那条是 TV 的 /like 前缀保护惯性；收藏是新域，按真实口径定）。
        //   两条必须**成对**断：只断 status=true 的话，把这个端点漏标（或反过来给 count 误标）
        //   在断言上都看不出来。HTTP 侧也有覆盖（FavoriteStatusAndCountTests.状态需登录计数匿名可访问），
        //   本类是**最先、最精确**的信号（直接回答"这个 401 是谁要求的"，T2 教训）。
        // `T58` 契约变更（接口契约.md §2.3）：contentId 进路径，样本值 1。
        assertThat(requiresLogin("GET", "/api/v1/contents/1/favorite"))
                .as("私有状态，必须登录")
                .isTrue();
        assertThat(requiresLogin("GET", "/api/v1/contents/1/favorite-count"))
                .as("公开展示数，必须匿名可访问（误加 @RequiresLogin 会让匿名内容页看不到收藏数）")
                .isFalse();
    }

    @Test
    @DisplayName("收藏一期 T6：夹内列表判为需登录（**方法级**注解 —— 与三条匿名端点构成成对断言）")
    void favorite夹内列表需登录() {
        // 夹内列表是"我的夹里有什么" ⇒ 私有视角，必须登录。
        // ⚠️ 同样**不能**靠类级 @RequiresLogin 解决：本域已有公开夹列表与公开计数两条匿名端点
        // （T3 / T5），类级会一并误拦。故这里断的是**方法级**注解是否落到 GET /api/v1/favorites 上。
        // ★ 判别力：本端点的 401 在 HTTP 侧同样可能由 @CurrentUserId 的参数解析器兜出
        // （T2 教训：机制失效时端到端看起来全对）——"这个 401 是谁要求的"只有本类能回答。
        assertThat(requiresLogin("GET", "/api/v1/favorites"))
                .as("夹内列表是'我的'私有视角，必须登录（T6）")
                .isTrue();
    }

    @Test
    @DisplayName("收藏三期 T17：稍后再看四端点判为需登录（**方法级**注解；本域没有公开面）")
    void watchlater四端点需登录() {
        // ★ 稍后再看是"我的私有池"（无公开面）——即便如此也**逐端点**声明、**绝不用类级**：
        //   该域将来若长出匿名端点（如将来做"公开的稍后再看"），类级会静默误拦。
        //   本组是 T17 鉴权口径的**机制级**保证：四个端点都收 @CurrentUserId（取不到即 401）⇒
        //   HTTP 用例对 @RequiresLogin 机制是**假绿**（401 可能来自参数解析器而非注解，
        //   见 T2 教训）——"这个 401 是谁要求的"只有本类能回答。
        // `T58` 契约变更（接口契约.md §2.3/§2.4）：资源化为 /api/v1/watch-later；
        //   add=PUT /{id}、move=PUT /{id}/folder（contentId 进路径，样本值 1）、remove=DELETE。
        assertThat(requiresLogin("GET", "/api/v1/watch-later")).isTrue();
        assertThat(requiresLogin("PUT", "/api/v1/watch-later/1")).isTrue();
        assertThat(requiresLogin("DELETE", "/api/v1/watch-later")).isTrue();
        assertThat(requiresLogin("PUT", "/api/v1/watch-later/1/folder")).isTrue();
    }

    @Test
    @DisplayName("投币 T37：coin 两条路由都判为需登录（钉路由；**不**证明类级注解在不在）")
    void coin端点需登录() {
        // 本域两个端点（T37 的 signIn + T38 的 add）都只对当前登录用户有意义 ⇒ 类级 @RequiresLogin。
        // ★ 判别力边界（如实声明）：本仓是**默认拒绝**（loginRequired = adminRequired || 注解 || !@Public），
        //   所以去掉类级注解后 requiresLogin 仍然是 true ⇒ 这条用例**测不到"注解在不在"**。
        //   它真正钉的是"路由落到了一个 @Controller 处理器上"（路径写错 / 控制器没被扫到 ⇒ 未映射 ⇒ false）。
        //   "类级覆盖"是读代码的约定，不是本类能断言的机制（与 `类级注解覆盖全部端点` 那条只列 /like/* 一致，
        //   它同样没有覆盖 coin —— 别把那条当成 coin 的兜底）。
        // ⚠️ HTTP 侧对 @RequiresLogin 也是假绿（401 由 @CurrentUserId 参数解析器兜出）。
        assertThat(requiresLogin("POST", "/api/v1/users/me/check-ins")).isTrue();
        assertThat(requiresLogin("POST", "/api/v1/contents/1/coins")).isTrue();
    }

    @Test
    @DisplayName("★播放记录 T47/T49/T51-2：history 五端点判为需登录（钉路由；**不**证明类级注解在不在）")
    void history写端点需登录() {
        // 本域五个端点都只对当前登录用户有意义（历史是"我的"私有记录）⇒ 类级 @RequiresLogin。
        // ★ 判别力边界（如实声明，同 coin）：本仓是**默认拒绝** ⇒ 去掉类级注解后 requiresLogin
        //   仍然是 true ⇒ 这条用例**测不到"注解在不在"**。它真正钉的是"路由落到了一个
        //   @Controller 处理器上"（路径写错 / 控制器没被扫到 ⇒ 未映射 ⇒ false）。
        // ⚠️ HTTP 侧对 @RequiresLogin 同样是假绿（401 由 @CurrentUserId 参数解析器兜出）。
        // 📌 T47 落了写路径两段；T49 读面两段已补进本方法（核销 I-23）；T51-2 补 /flush
        //    ——五个端点都有至少"路由可达"的机制级证据。方法名里的"写端点"是 T47 的引用锚点
        //   （多处文档按名引用），语义已扩展为五端点，改名会带来静默断链。
        // `T58` 契约变更（接口契约.md §2.3/§2.4）：资源化 /api/v1/history、
        //   /api/v1/contents/{id}/progress（report=PUT）、/api/v1/contents/{id}/play（{id} 取样成 1）。
        assertThat(requiresLogin("PUT", "/api/v1/contents/1/progress")).isTrue();
        assertThat(requiresLogin("POST", "/api/v1/history/flush")).isTrue();
        assertThat(requiresLogin("POST", "/api/v1/contents/1/play")).isTrue();
        assertThat(requiresLogin("GET", "/api/v1/history")).isTrue();
        assertThat(requiresLogin("GET", "/api/v1/contents/1/progress")).isTrue();
    }

    @Test
    @DisplayName("未映射的路径返回 false：真正的 404 由 DispatcherServlet 产生，本机制不越权处理路由")
    void 未映射路径不需登录() {
        assertThat(requiresLogin("GET", "/no/such/endpoint")).isFalse();
    }

    @Test
    @DisplayName("S7：静态媒体路径 /upload/** 不受 @RequiresLogin 管辖（媒体匿名可访问，同 TV 的 Tomcat 挂载）")
    void 静态媒体路径公开() {
        // /upload/** 由 SimpleUrlHandlerMapping（资源处理器）承接，不在 requestMappingHandlerMapping 里
        // ⇒ RequestMappingLookup 返回 false。这不是"漏保护"：媒体文件本就公开（TV 一致）。
        assertThat(requiresLogin("GET", "/upload/video/some-file.mp4")).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2026-10-18：默认拒绝（fail-closed）与处理器级角色判定
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★默认拒绝：未标任何注解的端点判定为**需登录**（放行口只有 @Public）")
    void 未标注解的端点默认需登录() throws NoSuchMethodException {
        RequestMappingLookup.HandlerAuthz authz = resolveOnProbe("undeclared");

        // 判别力：把 RequestMappingLookup.resolve 的兜底从 fail-closed 改回 fail-open
        // （即 `loginRequired = loginDeclared`），本断言立刻变红。
        assertThat(authz.loginRequired())
                .as("漏标注解 = 401（可见故障），而不是对匿名开放 —— 旧口径下只有"
                        + "'不接收 @CurrentUserId 的端点'会真正敞开，正是历史 bug 的形态")
                .isTrue();
        assertThat(authz.adminRequired()).isFalse();
        assertThat(resolveOnProbe("loginOnly").loginRequired())
                .as("显式 @RequiresLogin 与默认值等价 —— 它的价值只剩'可读性声明'，不是判定来源")
                .isTrue();
    }

    @Test
    @DisplayName("默认拒绝的反向对照：@Public 是唯一放行口，标了就匿名可访问")
    void Public注解是唯一放行口() throws NoSuchMethodException {
        RequestMappingLookup.HandlerAuthz authz = resolveOnProbe("publicOnly");

        assertThat(authz.loginRequired())
                .as("没有这条反向对照，'全部端点都需登录'也能让上一条通过（假绿）")
                .isFalse();
        assertThat(authz.adminRequired()).isFalse();
    }

    @Test
    @DisplayName("★声明冲突：@Public 与 @RequiresLogin 同时出现 ⇒ 取更严（需登录），不允许安静的宽松")
    void Public与RequiresLogin冲突时取严() throws NoSuchMethodException {
        RequestMappingLookup.HandlerAuthz authz = resolveOnProbe("publicAndLogin");

        assertThat(authz.loginRequired())
                .as("矛盾声明若按 @Public 判宽 ⇒ 一处笔误就静默放开一个端点")
                .isTrue();
    }

    @Test
    @DisplayName("★@RequiresAdmin 蕴含需登录：匿名访问 admin 端点得 401（不再依赖两个注解同时写对）")
    void RequiresAdmin蕴含需登录() throws NoSuchMethodException {
        RequestMappingLookup.HandlerAuthz authz = resolveOnProbe("adminOnly");

        assertThat(authz.adminRequired()).isTrue();
        assertThat(authz.loginRequired())
                .as("旧实现要求 admin 控制器**额外**标 @RequiresLogin，否则匿名请求跳过了登录段、"
                        + "掉进角色段得 403（'拦得对但状态码错'）—— 该陷阱现在被语义消除")
                .isTrue();
    }

    @Test
    @DisplayName("★「admin 蕴含登录」的判别点：@Public + @RequiresAdmin ⇒ 仍要求登录（唯一能区分两者的输入）")
    void Public与RequiresAdmin冲突时仍要求登录() throws NoSuchMethodException {
        // ⚠️ 为什么要这一条：adminOnly 探针**没有** @Public，而默认拒绝本身就会让 loginRequired=true
        //   ⇒ 单看那一条，"admin 蕴含登录"这半边可以删掉而不变红（= 空转断言）。
        //   只有 explicitPublic=true 且 adminRequired=true 的输入才能区分
        //   `adminRequired || loginDeclared || !explicitPublic` 与漏掉 adminRequired 的版本。
        RequestMappingLookup.HandlerAuthz authz = resolveOnProbe("publicAndAdmin");

        assertThat(authz.adminRequired()).isTrue();
        assertThat(authz.loginRequired())
                .as("漏掉'admin 蕴含登录'时会判成匿名放行（loginRequired=false），"
                        + "anonymous 请求到不了角色段 ⇒ 403 契约退化")
                .isTrue();
    }

    @Test
    @DisplayName("OPTIONS 不参与授权判定：交 MVC 回 Allow（否则匿名 OPTIONS 会从 200 变 401）")
    void OPTIONS不参与授权判定() {
        // Spring 对"路径能匹配、方法不匹配"的 OPTIONS 会返回内部 HttpOptionsHandler ——
        // 它是一个**不属于任何业务控制器**的 HandlerMethod，默认拒绝下会被判成需登录。
        // 本仓无 CORS（不会产生预检），但 curl / 探活工具会看到 401，属于未被设计的行为变化。
        RequestMappingLookup.HandlerAuthz authz =
                mappingLookup.resolve(new MockHttpServletRequest("OPTIONS", "/api/v1/favorites"));

        // ⚠️ 两个维度都要断：只比 HandlerAuthz.NONE 会变成"对生产常量自证"
        //   （NONE 被人改成 (false,true) 时不报警），只断 loginRequired 则会漏掉 adminRequired。
        assertThat(authz.loginRequired()).as("受保护端点的 OPTIONS 也交 MVC（不执行业务逻辑）").isFalse();
        assertThat(authz.adminRequired()).isFalse();
    }

    @Test
    @DisplayName("★成对断言：角色要求只在 @RequiresAdmin 端点成立，普通受保护端点不得被判为需管理员")
    void 非admin端点不得被判为需管理员() {
        // 真实端点对（不是桩）：一边是 3 个 admin 控制器，一边是同域之外的受保护端点。
        // 判别力：若实现把"是否 admin"写成恒 true（或仍按 /api/admin 之外的前缀误命中），下面三条立刻红。
        assertThat(requiresAdmin("GET", "/api/admin/content/list")).isTrue();
        assertThat(requiresAdmin("GET", "/api/v1/contents/1/favorite"))
                .as("收藏状态是私有视角但**不是**管理端点")
                .isFalse();
        assertThat(requiresAdmin("GET", "/like/content/count"))
                .as("历史 bug 端点：受 @RequiresLogin 保护，但与角色无关")
                .isFalse();
        assertThat(requiresAdmin("GET", "/api/v1/users/me")).isFalse();
        // `T58` 契约变更：/start 资源化为 GET /api/v1/contents/recommended（接口契约.md §2.3）
        assertThat(requiresAdmin("GET", "/api/v1/contents/recommended")).as("公开端点同样不是 admin").isFalse();
    }

    @Test
    @DisplayName("★护栏：凡落在 /api/admin 前缀的控制器都必须标 @RequiresAdmin（漏标=只要求登录）")
    void 所有admin前缀控制器都必须标RequiresAdmin() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Object> entry : applicationContext.getBeansWithAnnotation(RestController.class).entrySet()) {
            Class<?> type = ClassUtils.getUserClass(entry.getValue().getClass());
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            String[] paths = mapping.value().length > 0 ? mapping.value() : mapping.path();
            boolean adminPath = false;
            for (String path : paths) {
                if (path.startsWith("/api/admin")) {
                    adminPath = true;
                }
            }
            if (adminPath && !AnnotatedElementUtils.hasAnnotation(type, RequiresAdmin.class)) {
                violations.add(type.getName());
            }
        }

        // 这条就是"角色权威源从路径前缀改成注解"的代价护栏：前缀不再自动兜底，
        // 靠它保证"新增 admin 控制器漏标注解"这件事**会红**，而不是静默降级成普通登录端点。
        assertThat(violations)
                .as("这些控制器在 /api/admin 前缀下却没标 @RequiresAdmin ⇒ 非管理员也能访问")
                .isEmpty();
    }

    /**
     * 桩控制器：只为给 {@link HandlerMethod} 提供"带/不带注解"的四种形态，不注册进容器。
     *
     * <p>★ 标 {@code @RestController} 是**必需**的：`RequestMappingLookup` 只对"属于某个
     * {@code @Controller} 的处理器"做授权判定（框架内部 handler 被筛掉，见
     * `RequestMappingLookup#isControllerEndpoint`）。不标 ⇒ 这些探针会被当成"非业务端点"恒放行，
     * 断言全部空转。
     */
    @RestController
    static class AuthzProbe {

        public void undeclared() {
            // 什么都没标 ⇒ 默认拒绝
        }

        @Public
        public void publicOnly() {
            // 唯一放行口
        }

        @RequiresLogin
        public void loginOnly() {
            // 显式登录（与默认值等价，保留为"显式声明"的形态）
        }

        @RequiresAdmin
        public void adminOnly() {
            // 管理员：蕴含登录
        }

        @Public
        @RequiresLogin
        public void publicAndLogin() {
            // 矛盾声明：取更严
        }

        @Public
        @RequiresAdmin
        public void publicAndAdmin() {
            // 矛盾声明：取更严（**且这是"admin 蕴含登录"唯一的判别输入**）
        }
    }
}
