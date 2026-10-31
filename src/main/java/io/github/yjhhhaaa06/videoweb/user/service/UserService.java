package io.github.yjhhhaaa06.videoweb.user.service;

import io.github.yjhhhaaa06.videoweb.common.exception.BadCredentialsException;
import io.github.yjhhhaaa06.videoweb.common.exception.BusinessException;
import io.github.yjhhhaaa06.videoweb.common.exception.ConflictException;
import io.github.yjhhhaaa06.videoweb.common.exception.DuplicatePhoneException;
import io.github.yjhhhaaa06.videoweb.common.exception.ErrorCode;
import io.github.yjhhhaaa06.videoweb.common.exception.InvalidPasswordException;
import io.github.yjhhhaaa06.videoweb.common.exception.OldPasswordMismatchException;
import io.github.yjhhhaaa06.videoweb.common.exception.ParamException;
import io.github.yjhhhaaa06.videoweb.common.exception.UserNotFoundException;
import io.github.yjhhhaaa06.videoweb.common.security.AdminChecker;
import io.github.yjhhhaaa06.videoweb.common.security.JwtService;
import io.github.yjhhhaaa06.videoweb.common.time.SiteTime;
import io.github.yjhhhaaa06.videoweb.user.dao.UserDao;
import io.github.yjhhhaaa06.videoweb.user.event.UserRenamedEvent;
import io.github.yjhhhaaa06.videoweb.user.model.dto.ChangePasswordRequest;
import io.github.yjhhhaaa06.videoweb.user.model.dto.RegisterRequest;
import io.github.yjhhhaaa06.videoweb.user.model.entity.User;
import io.github.yjhhhaaa06.videoweb.user.model.vo.HistoryRecordingVO;
import io.github.yjhhhaaa06.videoweb.user.model.vo.LoginVO;
import io.github.yjhhhaaa06.videoweb.user.model.vo.UserInfoVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * 用户业务。
 *
 * <p>迁移自 TV {@code com.itheima.user.service.UserService}。
 * 骨架（{@code transactionTemplate.execute} / {@code conn} 穿透 / {@code catch SQLException} 包装）已删除，
 * 业务语义（校验顺序、异常类型、事务拆分）逐条保留——对照表见《事务边界决策表》U 系列。
 */
@Slf4j
@Service
public class UserService implements AdminChecker {

    /** 密码规则，从 TV {@code PasswordUtil.isPasswordLegal} 承接：6-16 位字母或数字。 */
    private static final Pattern PWD_PATTERN = Pattern.compile("^[a-zA-Z0-9]{6,16}$");

    private final UserDao userDao;
    private final JwtService jwtService;
    private final ApplicationEventPublisher events;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 登录失败时用来"陪跑"的假哈希（`T55`）。
     *
     * <p>★ 为什么要它：账号不存在时若直接抛错，就**跳过了 BCrypt 校验**——那条路径比"密码错"快
     * 约 50–100ms。攻击者不用读 {@code code}，只看响应时间就能判断手机号是否注册过：
     * **把 code 和 msg 统一了、却不统一耗时，等于没统一**。
     *
     * <p>用 {@code encode(...)} 生成而不是硬编码一串 bcrypt：硬编码的哈希在注释里长得像"某个真实口令的
     * 散列"，下一个人会去猜它、或者误以为它必须与某个账号匹配。这里只需要"一个合法的 bcrypt 哈希"。
     */
    private final String dummyPasswordHash = passwordEncoder.encode("T55-dummy-credential-for-timing");

    public UserService(UserDao userDao, JwtService jwtService, ApplicationEventPublisher events) {
        this.userDao = userDao;
        this.jwtService = jwtService;
        this.events = events;
    }

    // ========================================================================
    // U-1：registerAndLogin —— 刻意**不**加 @Transactional
    // ========================================================================

    /**
     * 注册并自动登录。**注册成功即视为成功**（沿袭 TV 池 U-16 兜底语义）。
     *
     * <p><b>⚠️ 本方法不得标 {@code @Transactional}</b>。理由见《事务边界决策表》U-1：
     * 注册与自动登录是**两个独立事务**（注册事务先提交，再做登录查询）。
     * 若整体纳入一个事务，"自动登录失败"会连带回滚掉已成功的注册——
     * 直接违反产品语义（旧行为是注册成功、返回 token=null、提示手动登录）。
     *
     * <p>自动登录失败时不再抛异常，而是返回 {@code token == null} 的 {@link LoginVO}，token 由前端提示。
     * 但**注册本身失败**（手机号/用户名占用）仍照旧抛错，不被兜底吞掉。
     */
    public LoginVO registerAndLogin(RegisterRequest request) {
        long id = registerAsUser(request);
        // T2 里程碑（B12）：注册成功。**放在这里而不是 registerAsUser 内**——
        // 本方法刻意无事务（U-1），故这一行执行时内层插入已提交/落库 ⇒ 不会"未提交先宣告成功"。
        // 将来若 U-2 被拍板修好（registerAsUser 成为真事务），本行仍在其**之外**，语义不变。
        // 只记 userId：账号（手机号）与密码一律不落盘（TV LOG_CONVENTION）。
        log.info("用户注册成功, userId={}", id);
        try {
            return login(id, request.password());
        } catch (BusinessException e) {
            // 注册已提交：不能把自动登录失败报成注册失败；留痕不静默
            log.warn("注册后自动登录失败, userId={}, 改为提示手动登录", id);
            return new LoginVO(id, request.username(), null);
        }
    }

    /**
     * 用户注册，返回新用户 id。
     *
     * <p>决策表 U-2：校验占用与插入必须原子 ⇒ {@code @Transactional}（REQUIRED）。
     * 并发下唯一键仍在 DB 层兜底，由 {@code GlobalExceptionHandler} 把
     * {@code DuplicateKeyException} 映射为 409，使"先查后插"的竞态对客户端不可见。
     */
    @Transactional
    public long registerAsUser(RegisterRequest request) {
        String username = request.username();
        String phone = request.phone();

        if (!isPasswordLegal(request.password())) {
            throw new InvalidPasswordException();
        }
        if (userDao.isPhoneUsed(phone)) {
            throw new DuplicatePhoneException();
        }
        if (userDao.isUsernameUsed(username)) {
            throw new ConflictException(ErrorCode.USER_NAME_TAKEN, "用户名已被占用");
        }

        User user = new User();
        user.setUsername(username);
        user.setPhone(phone);
        user.setHashedPassword(passwordEncoder.encode(request.password()));
        userDao.insert(user);   // useGeneratedKeys 回填 id
        return user.getId();
    }

    // ========================================================================
    // 登录
    // ========================================================================

    /** 按手机号登录（对外 HTTP 入口）。 */
    public LoginVO login(String phone, String rawPassword) {
        User dbUser = userDao.findByPhoneForLogin(phone);
        String token = doLogin(dbUser, rawPassword);
        return new LoginVO(dbUser.getId(), dbUser.getUsername(), token);
    }

    /**
     * 按 id 登录。仅供 {@link #registerAndLogin} 内部调用（与 TV 一致——
     * TV 的 login(id, pwd) 是 registerAndLogin 的内部路径，无独立 HTTP 入口）。
     */
    public LoginVO login(long id, String rawPassword) {
        User dbUser = userDao.findByIdForLogin(id);
        String token = doLogin(dbUser, rawPassword);
        return new LoginVO(dbUser.getId(), dbUser.getUsername(), token);
    }

    /**
     * 执行登录校验并签发 token。
     *
     * <p>决策表 U-3/U-4：TV 这里包了 {@code transactionTemplate.execute}，但**纯读无写**——
     * 那个事务只是"取连接的手段"，没有原子性需求，故新实现不加事务注解。
     *
     * <h2>★ 登录失败只有一种回答（`T55`，安全口径）</h2>
     * 「账号不存在」与「密码错误」**同码同文案同耗时**：
     * <ul>
     *   <li><b>同码同文案</b>：都抛 {@link BadCredentialsException}（{@code AUTH_BAD_CREDENTIALS}
     *       / "账号或密码错误"）。分开回答 = 给出「这个手机号注册过没有」的探测口，
     *       一个脚本就能批量筛出全站已注册手机号 —— 与收藏域「收藏夹不存在 / 不公开同 404 同文案」
     *       是同一条原则。</li>
     *   <li><b>同耗时</b>：账号不存在时**也**对一个假哈希跑一次 BCrypt（见
     *       {@link #dummyPasswordHash}），否则时间差本身就是同一个探测口。</li>
     * </ul>
     * ⚠️ 注册接口的「手机号已被使用」（{@code USER_PHONE_TAKEN}）仍然如实回答 —— 注册流程必须告知，
     * 它是**已知代价**（真正的收口手段是短信验证，本期不做），口径见
     * {@code architecture/tech/api/接口契约.md} §2.2。
     * ⚠️ 改密接口的「原密码错」（{@code AUTH_OLD_PASSWORD_WRONG}）不在此列：操作者已用令牌证明身份。
     */
    private String doLogin(User user, String rawPassword) {
        // 两个分支都走一次 BCrypt：user 不存在时对假哈希校验（结果必然 false，丢弃）
        boolean passwordOk = isPasswordCorrect(
                rawPassword, user == null ? dummyPasswordHash : user.getHashedPassword());
        if (user == null || !passwordOk) {
            throw new BadCredentialsException();
        }
        String token = jwtService.generateToken(user.getId());
        // 里程碑：登录成功。只记 userId——账号（手机号）与 token 一律不落盘（沿袭 TV LOG_CONVENTION）
        log.info("登录成功, userId={}", user.getId());
        return token;
    }

    // ========================================================================
    // 资料与修改
    // ========================================================================

    /**
     * 当前用户信息（新增端点，见 UserInfoVO 说明）。
     *
     * <p><b>T37 加了硬币两个字段</b>（{@code coin} / {@code signedToday}）：
     * <ul>
     *   <li>{@code coin}：读 {@code users.coin_deci}（**十分位整数**）后在 VO 出口换算成一位小数。
     *       ⚠️ 这里**刻意不调** {@code CoinService.toCoinAmount}——那会让 user 域新增一条
     *       指向 coin.service 的依赖边，而设计里没有登记这条边（coin → user.dao 才是登记过的方向）。
     *       换算写法（{@code BigDecimal.valueOf(deci).movePointLeft(1).setScale(1)}）
     *       必须与 {@code CoinService#toCoinAmount} **逐字一致**，口径见
     *       {@code architecture/domain/coin/coin.md} §三。</li>
     *   <li>{@code signedToday}：{@code last_sign_date == SiteTime.today()}。签到按钮靠它
     *       **进页就置灰**，否则只能"点了才知道已签"。日期口径必须走 {@code SiteTime}
     *       （上海自然日），不能用 JVM 默认时区或 DB 时钟——否则跨时区部署时按钮会指错一天。</li>
     *   <li>{@code historyRecording}（{@code R-25}）：{@code users.history_recording}。
     *       它是「记录浏览历史」开关的**唯一读入口**（客户端每次整页装载读一次）——
     *       同 {@code signedToday} 的形态：不新增"设置"端点族，也不把用户偏好塞进内容载荷。</li>
     * </ul>
     *
     * <p>多三次**单列窄查询**（而不是把三列塞进 {@code findByIdForProfile}）：那个方法返回的是
     * {@link User} 实体，加列会改实体与既有的列集契约；而这里只关心三个标量，
     * 窄查询让"资料端点不需要知道签到日 / 开关"这件事保持在实现里，不扩散到 VO。
     */
    @Transactional(readOnly = true)
    public UserInfoVO getProfile(long userId) {
        User user = userDao.findByIdForProfile(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }
        Integer coinDeci = userDao.findCoinDeci(userId);
        // 用户刚被查到（上面已挡 404）；null 只可能是"同一行在两次查询之间被删"的窗口，
        // 按 0 处理，避免读接口因这种竞态抛 NPE / 500。
        BigDecimal coin = BigDecimal.valueOf(coinDeci == null ? 0 : coinDeci)
                .movePointLeft(1)
                .setScale(1);
        boolean signedToday = SiteTime.today().equals(userDao.findLastSignDate(userId));
        Boolean recording = userDao.findHistoryRecording(userId);
        // null = "同一行在两次查询之间被删"的窗口（列 NOT NULL ⇒ 不会读到空值）。
        // 按**开**处理：DB 默认就是开，"误开"只是多记一次观看，"误关"要用户自己发现记录少了。
        boolean historyRecording = recording == null || recording;
        return new UserInfoVO(user.getId(), user.getUsername(), user.getPhone(), user.isAdmin(),
                coin, signedToday, historyRecording);
    }

    /**
     * 写「记录浏览历史」开关（{@code PUT /api/v1/users/me/history-recording}，{@code R-25}）。
     *
     * <p>单条 UPDATE ⇒ <b>不开事务</b>（同 coin 的单语句写）；同值重写结果不变 ⇒ <b>天然幂等</b>，
     * 重试安全，不需要幂等键（登记见 {@code IdempotencyScope} 的 {@code NATURAL}）。
     *
     * <p>★ 只改一个**偏好**，不做任何"关掉之后不会再记录"的保证 —— 那是客户端义务：
     * 本仓**不**在 {@code /play} / {@code /report} / {@code /flush} / 轮询兜底里校验这一列。
     * 这是 {@code R-25} 的刻意取舍（B站 实测是服务端强制），代价与推翻条件见
     * {@code architecture/domain/history/history-已知代价.md}。
     *
     * @param recording {@code false} = 停用记录
     * @throws UserNotFoundException 影响 0 行（token 有效但用户行已不在）
     */
    public HistoryRecordingVO updateHistoryRecording(long userId, boolean recording) {
        if (userDao.updateHistoryRecording(userId, recording) == 0) {
            throw new UserNotFoundException();
        }
        return new HistoryRecordingVO(recording);
    }

    /**
     * 修改密码。
     *
     * <p>校验口径沿袭 TV：先查用户存在 → 再校验手机号是否**本人** → 再校验原密码 → 再校验新密码合法性 → 落库。
     *
     * <p>⭐ **校验顺序不是随意的**：TV 的 {@code doChangePassword} 里，"手机号比对"**严格先于**"旧密码比对"
     * （{@code UserService.java:188} 早于 {@code :193}）。这意味着"错手机号 + 错旧密码"只会回 400（手机号不匹配），
     * 不会回 401。本顺序由 {@code UserFlowTests.手机号比对先于旧密码_错号且错密码回400} 钉住。
     *
     * <p>⭐ **手机号比对只判"是否等于本人"，不判格式**：老侧口径在这两级上本来就不同
     * （格式在 {@code CommandConverter} 用**宽** {@code phoneCheck}，本处只比对）。
     * 若在此加严到注册的 {@code ^1[3-9]\d{9}$}，库里存在的宽口径历史号（《遗留台账》E-6）
     * 将**连自己的密码都改不了**。详见 {@link ChangePasswordRequest} 的类注释。
     */
    @Transactional
    public void changePassword(long userId, ChangePasswordRequest request) {
        User user = userDao.findByIdForLogin(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }
        if (!request.phone().equals(user.getPhone())) {
            // TV 同位置抛 ParamException("手机号不匹配") ⇒ 400（先于旧密码判定，顺序即冻结契约）
            throw new ParamException(ErrorCode.USER_PHONE_MISMATCH, "手机号不匹配");
        }
        if (!isPasswordCorrect(request.oldPassword(), user.getHashedPassword())) {
            throw new OldPasswordMismatchException();
        }
        if (!isPasswordLegal(request.newPassword())) {
            throw new InvalidPasswordException();
        }
        userDao.updatePassword(userId, passwordEncoder.encode(request.newPassword()));
    }

    /**
     * 修改用户名（`T59` 起**幂等设置**：改成同一个名字 = 200 no-op）。
     *
     * <p>占用校验与更新同事务（同 U-2 口径）。★ 必须先判"同值"再判占用：{@code isUsernameUsed}
     * 的 SQL 是 {@code exists(where username = ?)}，**包含自己** ⇒ 不先判同值就会把
     * "重复提交同一个名字"错报成 409 {@code USER_NAME_TAKEN}。
     */
    @Transactional
    public void changeUserName(long userId, String newUsername) {
        if (newUsername == null || newUsername.isBlank()) {
            throw new ParamException(ErrorCode.PARAM_INVALID, "用户名不能为空");
        }
        User current = userDao.findByIdForLogin(userId);
        if (current != null && newUsername.equals(current.getUsername())) {
            return;   // 同值 PUT = 幂等成功，不发改名事件（没有变化）
        }
        if (userDao.isUsernameUsed(newUsername)) {
            throw new ConflictException(ErrorCode.USER_NAME_TAKEN, "用户名已被占用");
        }
        userDao.updateUsername(userId, newUsername);
        // S5：改名必须级联失效该作者的内容缓存（内容详情/主页里的 authorName 是从 users JOIN 来的
        // 冗余快照）。TV 在这里直接调 contentCache.invalidateAuthorContentKeys(userId)，
        // 本实现改为**发事件**（提交后由 ContentCacheChangedListener 处理）——user 域不必知道
        // content 域有缓存。旧 pytest 的 test_change_user_name.py 对这条级联有逐字断言。
        events.publishEvent(new UserRenamedEvent(userId, newUsername));
    }

    /**
     * 是否管理员（承接 TV {@code UserService.isAdmin}，供 {@code @RequiresAdmin} 端点鉴权用）。
     *
     * <p>本方法同时是 {@link AdminChecker} 端口的实现（S6-B1）——由本类提供实现、
     * 由 {@code common.security.JwtAuthFilter} 按接口消费，避免 {@code common} 反向依赖 {@code user}。
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isAdmin(long userId) {
        return userDao.findRoleById(userId) == 1;
    }

    // ========================================================================
    // 密码工具（从 TV PasswordUtil 内联——原本就是 Spring BCrypt，无需移植成本）
    // ========================================================================

    private boolean isPasswordCorrect(String rawPassword, String hashedPassword) {
        return rawPassword != null && hashedPassword != null
                && passwordEncoder.matches(rawPassword, hashedPassword);
    }

    private boolean isPasswordLegal(String password) {
        return password != null && PWD_PATTERN.matcher(password).matches();
    }
}
