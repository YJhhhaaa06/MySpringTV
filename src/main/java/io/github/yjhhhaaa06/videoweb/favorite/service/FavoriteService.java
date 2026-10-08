package io.github.yjhhhaaa06.videoweb.favorite.service;

import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteFolderDao;
import io.github.yjhhhaaa06.videoweb.favorite.dao.FavoriteItemDao;
import org.springframework.stereotype.Service;

/**
 * 收藏业务（收藏夹 CRUD / 收藏与移出 / 状态与计数 / 夹内分页）。
 *
 * <h2>为什么现在还没有方法（T1 只落骨架）</h2>
 * T1 的目标是「V2 建表 + 域骨架落地」，**不是**提前实现别人任务里的端点：
 * 夹 CRUD 归 T2、私密与公开夹归 T3、写路径归 T4、状态与计数归 T5、夹内分页归 T6。
 * 本类此刻承担的是**装配关系**（controller → 本类 → 两个 DAO）与**边界声明**，
 * 由上下文加载 + {@code ArchitectureTests} 负责验证；方法随任务逐个补。
 *
 * <h2>一期红线（写方法前先读，别让实现把口径吃掉）</h2>
 * <ul>
 *   <li>🚫 <b>本域不得接 feed</b>（G10）：不调 {@code FeedDelivery.deliver}、不发
 *       {@code ContentPublishedEvent}。feed 关注流的**唯一**驱动源是
 *       {@code ContentService} 发的该事件。⚠️ 这是"不要做某事"，**写成测试必假绿**（无实现可去掉），
 *       只能靠 review 守。</li>
 *   <li>🚫 <b>一期不写缓存、不写冗余计数</b>（G7）：没有 {@code favorite.cache} 包，
 *       不维护 {@code content.favorite_count}（R-06：一期实时
 *       {@code COUNT(DISTINCT user_id)}，二期才上冗余列）。发现自己需要缓存 ⇒ 先停下改分期。</li>
 *   <li>★ <b>{@code remove} 不校验内容存在性</b>（G11）：见 {@code FavoriteItemDao} 的口径 1。</li>
 *   <li>跨域只走三条合法通道：对方的 Service 公开方法 / 同事务内的 DAO 薄依赖 / 自己的
 *       {@code event} 包订阅别人的事件（见 {@code architecture/tech/模块边界与依赖.md} §三）。
 *       ⚠️ 本域**没有** {@code event} 包也不该有：收藏不是"别人要响应的事实"。</li>
 * </ul>
 *
 * <h2>事务</h2>
 * 写路径（尤其是"删夹一并删条目" R-02）必须同事务，切分口径见
 * {@code architecture/tech/事务边界.md}；{@code @Transactional} 标在**本类的 public 入口**上，
 * 别标在同类内部调用的私有方法上（自调用绕过代理 ⇒ 静默失效）。
 */
@Service
public class FavoriteService {

    private final FavoriteFolderDao folderDao;
    private final FavoriteItemDao itemDao;

    public FavoriteService(FavoriteFolderDao folderDao, FavoriteItemDao itemDao) {
        this.folderDao = folderDao;
        this.itemDao = itemDao;
    }
}
