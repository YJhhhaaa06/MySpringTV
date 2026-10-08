package io.github.yjhhhaaa06.videoweb.favorite.controller;

import io.github.yjhhhaaa06.videoweb.favorite.service.FavoriteService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收藏接口入口（路径前缀 {@code /favorite}）。
 *
 * <h2>为什么现在还没有端点（T1 只落骨架）</h2>
 * 端点形状已是**冻结契约**（{@code 收藏功能-分期与设计.md} §3.3：一期定下的路径、参数形态、
 * 分页语义 page/size 二期不许改），但它们**各有归属任务**：夹 CRUD 归 T2、
 * 他人公开夹归 T3、写路径归 T4、状态与计数归 T5、夹内分页归 T6。
 * 本类此刻只钉住**类级路径前缀**与装配关系，端点由各任务逐个补。
 *
 * <h2>★ 鉴权：绝不能照抄 {@code LikeController} 的类级 {@code @RequiresLogin}</h2>
 * like 域 8 个端点**全部**需登录，故用类级注解把"这一族整体受保护"写在一处。
 * <b>收藏域不是</b>：{@code GET /favorite/folder/public?userId=X} 是**他人视角的公开读**
 * （分期篇 §3.3 明写"匿名可访问，只返回公开"），一旦图省事打上类级 {@code @RequiresLogin}，
 * 这个端点就会 401 —— 而且**不会有人发现**（自己的私密夹自己照样看得见）。
 * 故本类的鉴权一律**逐端点**声明（{@code @RequiresLogin} 通过 {@code RequestMappingLookup}
 * 反射判定，见 {@code common/security/JwtAuthFilter}，没有 TV 那两份需手工维护的路径清单）。
 *
 * <p>⚠️ 端点上线时注意：读操作 {@code GET} + query 参数、写操作 {@code POST} 的形态由各任务定
 * （对齐 {@code /like/*} 的既有风格），**改 HTTP 层须同步 {@code static/js/api.js}**（项目纪律，T7）。
 */
@RestController
@RequestMapping("/favorite")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }
}
