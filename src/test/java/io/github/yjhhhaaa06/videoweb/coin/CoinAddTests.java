package io.github.yjhhhaaa06.videoweb.coin;

import io.github.yjhhhaaa06.videoweb.support.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投币专项 T38 验收：投币写路径（校验顺序 + 两台原子条件写）。
 *
 * <h2>★ 每条用例的期望值都来自业务口径，不是"照抄实现"</h2>
 * 断言全部落在**可观察行为**上：HTTP 状态 + 信封 + 四处的列终态
 * （投票者余额 / 作者余额 / {@code content.coin_count} / {@code content_coin.amount}）。
 * 四处都是**独立 oracle**（直查 DB），不读接口回显——`data.balance` 只是被测实现的回声，
 * 用它断言等于让错误实现自己证明自己（{@code AGENTS.md}「测试纪律」②）。
 *
 * <p>期望值的推导链（设计 §三 / §四.1）：
 * <ul>
 *   <li>投 {@code count} 枚 ⇒ 投票者 {@code −count×10} 分位、作者 {@code +count} 分位
 *       （{@code count} 枚 × 1/10 枚 = {@code count} 个十分位）、{@code coin_count +count}、
 *       {@code amount +count}；</li>
 *   <li>余额不足 ⇒ 400 且**零副作用**（关系行、计数、两边余额都不动）—— 同时是
 *       "封顶写发生在扣款之前但仍被事务回滚"的证明；</li>
 *   <li>已满 ⇒ 409；「已满 + 余额不足」同时成立也答 409（**5 先于 6**）；</li>
 *   <li>并发两次各投 1 枚 ⇒ 恰好一条成功，{@code amount} 恰为 2。</li>
 * </ul>
 *
 * <h2>反向验证（改哪一行必红）</h2>
 * <ul>
 *   <li>去掉 {@code ContentCoinMapper.xml#addAmount} 的 {@code amount + ? <= 2}
 *       ⇒ {@link #并发各投一枚不突破封顶()} 会读到 {@code amount == 3} / 两条都 200；</li>
 *   <li>把封顶条件写挪到扣款之后 ⇒ {@link #已满且余额不足时先答409()} 会答 400；</li>
 *   <li>扣款改成"先 {@code findCoinDeci} 再 {@code updateCoinDeci}" ⇒ 余额不足用例会写出负数/脏数据；</li>
 *   <li>去掉 Service 的 {@code @Transactional} ⇒ 余额不足用例会留下 {@code amount = 1} 的行
 *       （⚠️ 不是"两个世界都写了"这种无鉴别力的场面：没有 Spring 事务时 MyBatis 的每次 mapper 调用
 *       各自提交 ⇒ {@code insertIgnoreZero} 落 {@code amount=0} 行、{@code addAmount} 把它改成 1、
 *       扣款 0 行抛异常，后面的 {@code updateCoinDeci(author)} / {@code updateCoinCount} **压根不执行**
 *       ⇒ {@code coin_count} 仍为 0。用例红在"关系行"与"计数"两条断言上）。</li>
 * </ul>
 *
 * <p>★ {@link #已满再投被拒()} **不是**事务证明：封顶失败时前面没有成功写可回滚（{@code INSERT IGNORE}
 * 命中已存在行是 no-op、条件累加影响 0 行）。它证明的是"409 拒单零副作用"；事务的鉴别力在
 * {@link #余额不足时整笔回滚()}（封顶写已落库）与 {@code CoinCacheTests#写路径最后一步失败后…}
 * （最后一步打桩，见 {@code AGENTS.md}「测试纪律」④）。
 *
 * <p>⚠️ 未登录用例对 {@code @RequiresLogin} 是**假绿**（401 由 {@code @CurrentUserId} 解析器兜出）——
 * 机制级判别力在 {@code common/security/SecurityContractTests}。
 */
class CoinAddTests extends AbstractCoinIntegrationTest {

    private static final int VIDEO = 1;
    private static final int IMAGE = 2;

    // ==================== 1. 成功（count = 1） ====================

    @Test
    @DisplayName("投一枚：200 +「投币成功」+ 余额 1.0（scale 1）；四处 DB 终态独立复算")
    void 投一枚成功() {
        TestUser voter = registerUser("13900003801", "coinadd_one");
        TestUser author = registerUser("13900003811", "coinadd_one_author");
        setCoinDeci(voter.id(), 20);   // 2.0 枚
        long contentId = insertContent(author.id(), VIDEO, "投币视频一");

        ResponseEntity<String> resp = addCoin(voter.token(), contentId, 1);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.msg(resp)).as("文案冻结：「投币成功」").isEqualTo("投币成功");
        JsonNode data = Envelope.data(resp);
        assertThat(data.path("balance").decimalValue().scale())
                .as("余额一位小数是契约：10 分位必须序列化成 1.0")
                .isEqualTo(1);
        assertThat(data.path("balance").decimalValue())
                .as("20 − 1×10 = 10 分位 = 1.0 枚")
                .isEqualByComparingTo("1.0");

        // 独立 oracle：四处列终态（不读上面的回显）
        assertThat(coinDeciOf(voter.id())).as("投票者：20 − 10 = 10").isEqualTo(10);
        assertThat(authorCoinDeciOf(author.id())).as("作者收益：1 枚 × 1/10 = 1 个十分位").isEqualTo(1);
        assertThat(coinCountOf(contentId)).as("累计被投币数 +1").isEqualTo(1);
        assertThat(amountOf(voter.id(), contentId)).as("关系行 amount = 1").isEqualTo(1);
        assertThat(countCoinRows()).as("单人单内容恰好一行（唯一键 uk_user_content）").isEqualTo(1);
    }

    // ==================== 2. ★ 累计语义：两个投票者 + 作者已有收益 ====================

    @Test
    @DisplayName("★累计：作者已有 5 分位，两个投票者分别投 1 / 2 枚 ⇒ 作者 5+1+2=8、coin_count=3=SUM(amount)")
    void 多次投币是累加而不是覆盖() {
        TestUser author = registerUser("13900003822", "coinadd_accum_author");
        TestUser voterA = registerUser("13900003823", "coinadd_accum_a");
        TestUser voterB = registerUser("13900003824", "coinadd_accum_b");
        setCoinDeci(author.id(), 5);          // 作者已有收益：改写成"覆盖"而非"累加"必红
        setCoinDeci(voterA.id(), 20);
        setCoinDeci(voterB.id(), 20);
        long contentId = insertContent(author.id(), VIDEO, "投币视频-累计");

        assertThat(addCoin(voterA.token(), contentId, 1).getStatusCode().value()).isEqualTo(200);
        assertThat(addCoin(voterB.token(), contentId, 2).getStatusCode().value()).isEqualTo(200);

        // 四条独立 oracle：作者余额 / 计数列 / 关系行之和 / 关系行条数
        assertThat(authorCoinDeciOf(author.id()))
                .as("★作者收益是 %s + 1 + 2 = 8 分位（写成 SET = delta 会得到 2）", 5)
                .isEqualTo(8);
        assertThat(coinCountOf(contentId))
                .as("★计数列是累加（写成 SET = 本次 count 会得到 2）").isEqualTo(3);
        assertThat(sumAmountOf(contentId))
                .as("不变量：coin_count == SUM(content_coin.amount)（两条关系行 1 + 2）").isEqualTo(3);
        assertThat(countCoinRows()).as("两个投票者 ⇒ 两行（唯一键按 (user,content) 而不是按内容）")
                .isEqualTo(2);
        assertThat(coinDeciOf(voterA.id())).as("投票者 A：20 − 1×10 = 10").isEqualTo(10);
        assertThat(coinDeciOf(voterB.id())).as("投票者 B：20 − 2×10 = 0").isEqualTo(0);
    }

    // ==================== 3. 成功（count = 2，一次投满） ====================

    @Test
    @DisplayName("一次投两枚：余额 0.0、作者 +2、coin_count 2、amount 2")
    void 一次投满两枚() {
        TestUser voter = registerUser("13900003802", "coinadd_two");
        TestUser author = registerUser("13900003812", "coinadd_two_author");
        setCoinDeci(voter.id(), 20);
        long contentId = insertContent(author.id(), VIDEO, "投币视频二");

        ResponseEntity<String> resp = addCoin(voter.token(), contentId, 2);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.msg(resp)).isEqualTo("投币成功");
        JsonNode data = Envelope.data(resp);
        assertThat(data.path("balance").decimalValue().scale()).isEqualTo(1);
        assertThat(data.path("balance").decimalValue())
                .as("20 − 2×10 = 0 分位 = 0.0 枚（余额也可以是 0，不省略小数位）")
                .isEqualByComparingTo("0.0");

        assertThat(coinDeciOf(voter.id())).isEqualTo(0);
        assertThat(authorCoinDeciOf(author.id())).as("2 枚 ⇒ +2 个十分位（不乘 10）").isEqualTo(2);
        assertThat(coinCountOf(contentId)).isEqualTo(2);
        assertThat(amountOf(voter.id(), contentId)).isEqualTo(2);
    }

    // ==================== 4. ★ 半枚余额：floor 语义（1.5 只能投 1） ====================

    @Test
    @DisplayName("★余额 1.5 枚：投 2 ⇒ 400 硬币不足（0 副作用）；投 1 ⇒ 200、余额 0.5（scale 1）")
    void 半枚余额只能投一枚() {
        TestUser voter = registerUser("13900003825", "coinadd_half");
        TestUser author = registerUser("13900003826", "coinadd_half_author");
        setCoinDeci(voter.id(), 15);          // 1.5 枚 —— 口径：floor(15/10) = 1，投不了 2 枚
        long contentId = insertContent(author.id(), VIDEO, "投币视频-半枚");

        // ① 2 枚 ⇒ 400（判据是 coin_deci >= count × 10，不是"整数枚够不够"的近似）
        ResponseEntity<String> tooMany = addCoin(voter.token(), contentId, 2);
        assertThat(tooMany.getStatusCode().value())
                .as("15 分位 < 2×10 ⇒ 硬币不足（把判据写成 count <= coin_deci/10 + 1 之类会放行）")
                .isEqualTo(400);
        assertThat(Envelope.msg(tooMany)).isEqualTo("硬币不足");
        assertThat(countCoinRows()).as("被拒 ⇒ 不落行").isZero();
        assertThat(coinCountOf(contentId)).isZero();
        assertThat(coinDeciOf(voter.id())).as("被拒 ⇒ 一分不扣").isEqualTo(15);

        // ② 1 枚 ⇒ 200，剩 5 分位 = 0.5 枚（一位小数，不是 0 或 1）
        ResponseEntity<String> one = addCoin(voter.token(), contentId, 1);
        assertThat(one.getStatusCode().value()).isEqualTo(200);
        assertThat(Envelope.data(one).path("balance").decimalValue())
                .as("15 − 10 = 5 分位 = 0.5 枚（整数除法会得到 0）")
                .isEqualByComparingTo("0.5");
        assertThat(Envelope.data(one).path("balance").decimalValue().scale())
                .as("半枚也必须是一位小数").isEqualTo(1);
        assertThat(coinDeciOf(voter.id())).isEqualTo(5);
        assertThat(authorCoinDeciOf(author.id())).as("作者收益与余额是否有半枚无关：1 枚 ⇒ +1 分位")
                .isEqualTo(1);
    }

    // ==================== 5. ★ 余额不足 ⇒ 零副作用 ====================

    @Test
    @DisplayName("余额不足：400「硬币不足」+ 零副作用（无行、计数不动、两边余额不动）")
    void 余额不足时整笔回滚() {
        TestUser voter = registerUser("13900003803", "coinadd_poor");
        TestUser author = registerUser("13900003813", "coinadd_poor_author");
        setCoinDeci(voter.id(), 0);
        long contentId = insertContent(author.id(), VIDEO, "投币视频三");

        ResponseEntity<String> resp = addCoin(voter.token(), contentId, 1);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.code(resp)).isEqualTo(400);
        assertThat(Envelope.msg(resp)).as("文案冻结：「硬币不足」").isEqualTo("硬币不足");

        // ★ 零副作用 = 封顶写（INSERT IGNORE + 条件累加）已发生，但被事务一起撤回。
        //   去掉 @Transactional ⇒ 这里会看到 amount = 1 的行（coin_count 仍为 0 —— 计数写在扣款之后，
        //   压根没执行；见类注释的反向验证口径）。
        assertThat(countCoinRows()).as("被拒的投币不落 amount = 0/1 行").isZero();
        assertThat(amountOf(voter.id(), contentId)).as("关系行不存在").isNull();
        assertThat(coinCountOf(contentId)).as("累计被投币数不动").isZero();
        assertThat(authorCoinDeciOf(author.id())).as("作者没收到币").isZero();
        assertThat(coinDeciOf(voter.id())).as("投票者没被扣款").isZero();
    }

    // ==================== 5.1 ★ 锁序分支的对照（作者 id 更小 ⇒ 先入账后扣款） ====================

    @Test
    @DisplayName("★ 作者 id 更小：先入账、扣款失败仍整笔回滚（作者没白拿币）")
    void 作者id更小时先入账分支同样整笔回滚() {
        // 夹具刻意**反序**创建：先注册 author、后注册 voter ⇒ authorId < userId，
        // 让实现走"先入账后扣款"那一支（其余用例的 voter 都先注册，只覆盖另一支）。
        TestUser author = registerUser("13900003851", "coinadd_lock_author");
        TestUser voter = registerUser("13900003861", "coinadd_lock_voter");
        assertThat(author.id()).as("夹具前提：作者 id 更小（自增序变了这条就失效）").isLessThan(voter.id());
        setCoinDeci(voter.id(), 0);
        long contentId = insertContent(author.id(), VIDEO, "投币视频锁序");

        ResponseEntity<String> resp = addCoin(voter.token(), contentId, 1);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(resp)).as("文案冻结：「硬币不足」").isEqualTo("硬币不足");
        // ★ 判别力：这一支里作者入账发生在扣款**之前**（已是成功写），只有事务能把它撤掉 ——
        //   去掉 @Transactional 会看到作者余额 +1；把 if 分支的入账删掉则互投用例先红。
        assertThat(authorCoinDeciOf(author.id())).as("先入账的那笔被回滚，作者没收到币").isZero();
        assertThat(coinDeciOf(voter.id())).as("投票者没被扣款").isZero();
        assertThat(coinCountOf(contentId)).as("计数写在扣款之后 ⇒ 没执行").isZero();
        assertThat(amountOf(voter.id(), contentId)).as("封顶写被一起回滚").isNull();
    }

    @Test
    @DisplayName("互投：两人分别给对方内容各投 1 枚 ⇒ 两个锁序分支各走一次，四处终态都正确")
    void 互投两笔都成功() {
        TestUser a = registerUser("13900003871", "coinadd_mutual_a");
        TestUser b = registerUser("13900003881", "coinadd_mutual_b");
        setCoinDeci(a.id(), 10);
        setCoinDeci(b.id(), 10);
        long contentA = insertContent(a.id(), VIDEO, "A 的视频");
        long contentB = insertContent(b.id(), VIDEO, "B 的视频");

        // a → b 的内容：authorId(b) > userId(a) ⇒ 先扣款；b → a 的内容：反向 ⇒ 先入账。
        assertThat(addCoin(a.token(), contentB, 1).getStatusCode().value()).isEqualTo(200);
        assertThat(addCoin(b.token(), contentA, 1).getStatusCode().value()).isEqualTo(200);

        // 各自：−10（投出）+ 1（收到对方 1 枚的 1/10）= 1 个十分位
        assertThat(coinDeciOf(a.id())).isEqualTo(1);
        assertThat(coinDeciOf(b.id())).isEqualTo(1);
        assertThat(coinCountOf(contentA)).isEqualTo(1);
        assertThat(coinCountOf(contentB)).isEqualTo(1);
        assertThat(amountOf(a.id(), contentB)).isEqualTo(1);
        assertThat(amountOf(b.id(), contentA)).isEqualTo(1);
    }

    // ==================== 6. 已满 ⇒ 409 ====================

    @Test
    @DisplayName("已投满：409「对本稿件投币枚数已用完」+ amount/计数停在 2 + 不扣款")
    void 已满再投被拒() {
        TestUser voter = registerUser("13900003804", "coinadd_full");
        TestUser author = registerUser("13900003814", "coinadd_full_author");
        setCoinDeci(voter.id(), 20);
        long contentId = insertContent(author.id(), VIDEO, "投币视频四");
        // 前置态：该用户在这条内容上已投满 2（与 coin_count 自洽）
        setAmount(voter.id(), contentId, 2);
        setCoinCount(contentId, 2);

        ResponseEntity<String> resp = addCoin(voter.token(), contentId, 1);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(Envelope.code(resp)).isEqualTo(409);
        assertThat(Envelope.msg(resp)).as("文案冻结（B站）：「对本稿件投币枚数已用完」")
                .isEqualTo("对本稿件投币枚数已用完");

        assertThat(amountOf(voter.id(), contentId)).as("amount 停在 2").isEqualTo(2);
        assertThat(coinCountOf(contentId)).as("coin_count 停在 2").isEqualTo(2);
        assertThat(coinDeciOf(voter.id())).as("封顶写在扣款之前 ⇒ 一分钱都没扣").isEqualTo(20);
        assertThat(authorCoinDeciOf(author.id())).as("作者收益不变").isZero();
    }

    // ==================== 7. ★ 「已满 + 余额不足」⇒ 409（钉 5 先于 6） ====================

    @Test
    @DisplayName("★已满且余额不足：答 409（不是 400）——钉住封顶判定先于余额判定")
    void 已满且余额不足时先答409() {
        TestUser voter = registerUser("13900003805", "coinadd_full_poor");
        TestUser author = registerUser("13900003815", "coinadd_full_poor_author");
        setCoinDeci(voter.id(), 0);   // 余额不足
        long contentId = insertContent(author.id(), VIDEO, "投币视频五");
        setAmount(voter.id(), contentId, 2);   // 同时已满
        setCoinCount(contentId, 2);

        ResponseEntity<String> resp = addCoin(voter.token(), contentId, 1);

        // ★ 判别力：把条件累加挪到扣款之后，这条会先撞"余额不足"而答 400。
        assertThat(resp.getStatusCode().value())
                .as("资源状态内部次序是契约：5（已满）先于 6（余额不足）")
                .isEqualTo(409);
        assertThat(Envelope.msg(resp)).isEqualTo("对本稿件投币枚数已用完");
        assertThat(amountOf(voter.id(), contentId)).isEqualTo(2);
        assertThat(coinCountOf(contentId)).isEqualTo(2);
        assertThat(coinDeciOf(voter.id())).isZero();
    }

    // ==================== 8. ★ 校验次序：1 > 2 / 3 / 4 > 5 ====================

    @Test
    @DisplayName("★枚数非法先于存在性与类型：count=3 打不存在的内容 / 图文帖都答「数量只能是 1 或 2」")
    void 枚数非法先于存在性与类型() {
        TestUser voter = registerUser("13900003827", "coinadd_order1");
        TestUser author = registerUser("13900003828", "coinadd_order1_author");
        setCoinDeci(voter.id(), 20);

        // ① count 校验（第 1 级）先于存在性（第 2 级）：内容不存在也应是「数量」文案
        ResponseEntity<String> missing = addCoin(voter.token(), 987654323L, 3);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(missing))
                .as("★顺序 1>2：把存在性检查提前会答 404").isEqualTo("投币数量只能是 1 或 2");

        // ② count 校验（第 1 级）先于类型（第 3 级）：图文帖也应是「数量」文案
        long postId = insertContent(author.id(), IMAGE, "图文-次序一");
        ResponseEntity<String> post = addCoin(voter.token(), postId, 3);
        assertThat(post.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(post))
                .as("★顺序 1>3：把类型检查提前会答「该内容不支持投币」").isEqualTo("投币数量只能是 1 或 2");
        assertThat(countCoinRows()).isZero();
    }

    @Test
    @DisplayName("★类型先于归属：作者给自己投图文帖 ⇒ 「该内容不支持投币」（不是「不能给自己投币」）")
    void 类型先于归属() {
        TestUser author = registerUser("13900003829", "coinadd_order2");
        setCoinDeci(author.id(), 20);
        long postId = insertContent(author.id(), IMAGE, "图文-次序二");

        ResponseEntity<String> resp = addCoin(author.token(), postId, 1);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(resp))
                .as("★顺序 3>4：把自投检查提前会答「不能给自己投币」").isEqualTo("该内容不支持投币");
        assertThat(countCoinRows()).isZero();
        assertThat(coinDeciOf(author.id())).isEqualTo(20);
    }

    @Test
    @DisplayName("★归属先于封顶：作者已投满 2 枚再自投 ⇒ 「不能给自己投币」（不是 409）")
    void 归属先于封顶() {
        TestUser author = registerUser("13900003830", "coinadd_order3");
        setCoinDeci(author.id(), 20);
        long contentId = insertContent(author.id(), VIDEO, "视频-次序三");
        setAmount(author.id(), contentId, 2);
        setCoinCount(contentId, 2);

        ResponseEntity<String> resp = addCoin(author.token(), contentId, 1);

        assertThat(resp.getStatusCode().value())
                .as("★顺序 4>5：把封顶检查提前会答 409").isEqualTo(400);
        assertThat(Envelope.msg(resp)).isEqualTo("不能给自己投币");
        assertThat(amountOf(author.id(), contentId)).isEqualTo(2);
        assertThat(coinCountOf(contentId)).isEqualTo(2);
        assertThat(coinDeciOf(author.id())).isEqualTo(20);
    }

    // ==================== 9. 图文帖 ⇒ 400 ====================

    @Test
    @DisplayName("图文帖：400「该内容不支持投币」+ 零副作用")
    void 图文帖不可投币() {
        TestUser voter = registerUser("13900003806", "coinadd_image");
        TestUser author = registerUser("13900003816", "coinadd_image_author");
        setCoinDeci(voter.id(), 20);
        long contentId = insertContent(author.id(), IMAGE, "图文帖六");

        ResponseEntity<String> resp = addCoin(voter.token(), contentId, 1);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(resp)).as("文案冻结：「该内容不支持投币」").isEqualTo("该内容不支持投币");
        assertThat(countCoinRows()).isZero();
        assertThat(coinCountOf(contentId)).isZero();
        assertThat(coinDeciOf(voter.id())).isEqualTo(20);
        assertThat(authorCoinDeciOf(author.id())).isZero();
    }

    // ==================== 10. 自投 ⇒ 400 ====================

    @Test
    @DisplayName("自投：400「不能给自己投币」+ 零副作用")
    void 不能给自己投币() {
        TestUser author = registerUser("13900003807", "coinadd_self");
        setCoinDeci(author.id(), 20);
        long contentId = insertContent(author.id(), VIDEO, "自己投自己七");

        ResponseEntity<String> resp = addCoin(author.token(), contentId, 1);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(resp)).as("文案冻结：「不能给自己投币」").isEqualTo("不能给自己投币");
        assertThat(countCoinRows()).isZero();
        assertThat(coinCountOf(contentId)).isZero();
        assertThat(coinDeciOf(author.id())).isEqualTo(20);
    }

    // ==================== 11. 内容不存在（含软删）⇒ 404 ====================

    @Test
    @DisplayName("内容不存在 / 已软删：404「内容不存在」+ 零副作用")
    void 内容不存在或已软删() {
        TestUser voter = registerUser("13900003808", "coinadd_missing");
        TestUser author = registerUser("13900003818", "coinadd_missing_author");
        setCoinDeci(voter.id(), 20);

        // ① 完全不存在
        ResponseEntity<String> missing = addCoin(voter.token(), 987654321L, 1);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(Envelope.msg(missing)).isEqualTo("内容不存在");

        // ② 存在但已软删（窄查询的 is_deleted = 0 是判据）
        long contentId = insertContent(author.id(), VIDEO, "已删除视频八");
        jdbcTemplate.update("UPDATE content SET is_deleted = 1 WHERE id = ?", contentId);
        ResponseEntity<String> deleted = addCoin(voter.token(), contentId, 1);
        assertThat(deleted.getStatusCode().value())
                .as("软删内容对投币不可见 —— 与 isContentExist 同口径")
                .isEqualTo(404);
        assertThat(Envelope.msg(deleted)).isEqualTo("内容不存在");

        assertThat(countCoinRows()).isZero();
        assertThat(coinDeciOf(voter.id())).isEqualTo(20);
    }

    // ==================== 12. 参数形态 ⇒ 400 ====================

    @Test
    @DisplayName("count = 3：400「投币数量只能是 1 或 2」+ 零副作用")
    void 枚数非法被拒() {
        TestUser voter = registerUser("13900003809", "coinadd_badcount");
        TestUser author = registerUser("13900003819", "coinadd_badcount_author");
        setCoinDeci(voter.id(), 20);
        long contentId = insertContent(author.id(), VIDEO, "投币视频九");

        ResponseEntity<String> resp = addCoin(voter.token(), contentId, 3);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(Envelope.msg(resp)).as("文案冻结：「投币数量只能是 1 或 2」")
                .isEqualTo("投币数量只能是 1 或 2");
        assertThat(countCoinRows()).isZero();
        assertThat(coinDeciOf(voter.id())).isEqualTo(20);
        assertThat(coinCountOf(contentId)).isZero();
    }

    // ==================== 13. ★ 并发各投 1 枚 ====================

    @Test
    @DisplayName("★并发各投 1 枚：恰一条 200、一条 409；amount 恰为 2、余额恰扣 10、作者恰 +1")
    void 并发各投一枚不突破封顶() throws Exception {
        TestUser voter = registerUser("13900003810", "coinadd_concurrent");
        TestUser author = registerUser("13900003820", "coinadd_concurrent_author");
        setCoinDeci(voter.id(), 20);
        long contentId = insertContent(author.id(), VIDEO, "投币视频十");
        // 前置态：已投 1 枚、余额 2.0 —— 两个线程各再投 1 枚，竞争同一行。
        setAmount(voter.id(), contentId, 1);
        setCoinCount(contentId, 1);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>(threads);
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        return addCoin(voter.token(), contentId, 1);
                    } finally {
                        finished.countDown();
                    }
                }));
            }
            startGate.countDown();

            assertThat(finished.await(30, TimeUnit.SECONDS))
                    .as("并发请求在 30 秒内未全部结束——疑似行锁等待（InnoDB 默认 50s）")
                    .isTrue();

            List<ResponseEntity<String>> responses = new ArrayList<>(threads);
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get());
            }

            // 恰好一条成功、一条被封顶拦住（顺序不定，按状态集合断言）
            assertThat(responses).extracting(r -> r.getStatusCode().value())
                    .as("★判别力：去掉 addAmount 的 amount + ? <= 2 守卫 ⇒ 两条都 200")
                    .containsExactlyInAnyOrder(200, 409);

            // 独立 oracle：DB 终态只反映一次成功投币
            assertThat(amountOf(voter.id(), contentId))
                    .as("★去掉封顶守卫 ⇒ 这里会是 3")
                    .isEqualTo(2);
            assertThat(coinCountOf(contentId)).as("计数只 +1").isEqualTo(2);
            assertThat(coinDeciOf(voter.id())).as("只扣一次：20 − 10 = 10（不是 0）").isEqualTo(10);
            assertThat(authorCoinDeciOf(author.id())).as("作者只收一次：1").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 14. ★ 并发扣款：余额只有 1 枚，两条请求各投 1 枚 ====================

    @Test
    @DisplayName("★并发扣款：余额 1.0、两请求各投 1 枚到不同内容 ⇒ 恰一 200 一 400、余额恰为 0（不为负）")
    void 并发扣款不把余额扣成负数() throws Exception {
        TestUser voter = registerUser("13900003831", "coinadd_race");
        TestUser author = registerUser("13900003832", "coinadd_race_author");
        setCoinDeci(voter.id(), 10);          // 恰好 1 枚 —— 两个请求只能活一个
        long first = insertContent(author.id(), VIDEO, "并发扣款-一");
        long second = insertContent(author.id(), VIDEO, "并发扣款-二");

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>(threads);
        try {
            long[] targets = {first, second};
            for (int i = 0; i < threads; i++) {
                long target = targets[i];
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        return addCoin(voter.token(), target, 1);
                    } finally {
                        finished.countDown();
                    }
                }));
            }
            startGate.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS))
                    .as("并发请求在 30 秒内未全部结束——疑似行锁等待（InnoDB 默认 50s）").isTrue();

            List<ResponseEntity<String>> responses = new ArrayList<>(threads);
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get());
            }

            // ★ 判别力：扣款改成"先查余额再无条件 UPDATE"（或去掉 coin_deci >= ? 守卫）⇒ 两条都 200、
            //   余额变 -10；只用串行"余额 0 投 1"的用例抓不到这个窗口。
            assertThat(responses).extracting(r -> r.getStatusCode().value())
                    .as("余额只够一笔 ⇒ 恰好一条 200、一条 400（不是两条 200，也不是 500）")
                    .containsExactlyInAnyOrder(200, 400);
            assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 400)
                    .map(r -> Envelope.msg(r)).findFirst().orElse(null))
                    .as("失败那条的拒因是「硬币不足」").isEqualTo("硬币不足");

            // 独立 oracle：只有一笔落库、余额不为负
            assertThat(coinDeciOf(voter.id())).as("★余额恰为 0（不为负）").isZero();
            assertThat(sumAmountOfUser(voter.id())).as("只有一笔投币落行").isEqualTo(1);
            assertThat(countCoinRows()).as("失败请求的 content_coin 行随事务回滚").isEqualTo(1);
            assertThat(authorCoinDeciOf(author.id())).as("作者只收一笔：+1 分位").isEqualTo(1);
            assertThat(coinCountOf(first) + coinCountOf(second))
                    .as("两条内容的 coin_count 合计 = 1（失败那条的计数未 +1）").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================== 15. 未登录 ====================

    @Test
    @DisplayName("未登录投币：401（本条对 @RequiresLogin 是假绿，判别力在 SecurityContractTests）")
    void 未登录投币被拒() {
        // 内容存在与否不影响：鉴权在参数解析 / 注解层就短路了。
        MultiValueMap<String, String> form = coinForm(1L, 1);

        ResponseEntity<String> resp = postForm("/coin/add", form, null);

        // ⚠️ 这条 401 可能来自 @CurrentUserId 的参数解析器，而不是 @RequiresLogin ——
        //    机制是否生效只能由 SecurityContractTests 直接对 RequestMappingLookup 求值来证明
        //    （AGENTS.md「测试纪律」④）。
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(Envelope.code(resp)).isEqualTo(401);
        assertThat(Envelope.hasDataField(resp)).as("错误信封不含 data 键").isFalse();
    }

    // ==================== 夹具 ====================

    /** 投币端点：{@code POST + form}，带 {@code contentId} / {@code count}。 */
    private ResponseEntity<String> addCoin(String token, long contentId, int count) {
        return postForm("/coin/add", coinForm(contentId, count), token);
    }

    private static MultiValueMap<String, String> coinForm(long contentId, int count) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("contentId", String.valueOf(contentId));
        form.add("count", String.valueOf(count));
        return form;
    }
}
