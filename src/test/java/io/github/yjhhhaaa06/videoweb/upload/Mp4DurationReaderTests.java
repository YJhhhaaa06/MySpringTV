package io.github.yjhhhaaa06.videoweb.upload;

import io.github.yjhhhaaa06.videoweb.upload.service.Mp4DurationReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Mp4DurationReader} 的字节级契约（**手搓 mp4**，不依赖外部素材）。
 *
 * <h2>为什么手搓而不是用真实 mp4</h2>
 * 真实文件只覆盖"常见形态"一种；而本解析器的风险全在**边界**上：moov 在尾部（非 faststart）、
 * v1 的 64 位时长、{@code size==1} 的扩展长度、{@code timescale==0}、全 F 的"未知"、
 * 截断文件。这些**只有构造字节才能精确命中**——用真实文件永远测不到 {@code size==1}。
 *
 * <h2>期望值怎么来</h2>
 * 全部出自 {@code architecture/domain/history/history.md} §六.1 的解析规则
 * （偏移相对 mvhd **载荷起点**：v0 `timescale@12 / duration@16`，v1 `timescale@20 / duration@24`），
 * 以及"任何异常都返回 {@code OptionalInt.empty()}、绝不抛出"的契约。
 * 断言的是**秒数**（向下取整），不是内部字段——换实现（比如换成读 {@code moov} 的具体方式）用例不用改。
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * ① 只扫第一层 box（不进 moov）⇒ "moov 在前/在尾"两例变红；
 * ② 不支持 {@code size==1} ⇒ 扩展长度那例变红；
 * ③ 不判 {@code timescale==0} ⇒ 该例抛 {@code ArithmeticException}（红）；
 * ④ 不判"全 F" ⇒ 未知时长被当成 4294967295 秒（红）；
 * ⑤ 截断不判边界 ⇒ 读到文件外（红）。
 */
class Mp4DurationReaderTests {

    @TempDir
    Path tempDir;

    // ==================== 正常路径 ====================

    @Test
    @DisplayName("moov 在前（faststart）：2.5s @ timescale 1000 ⇒ 2（向下取整）")
    void moov在前_向下取整() throws IOException {
        Path file = write("faststart.mp4", concat(
                box("ftyp", ascii("isomiso2avc1mp41")),
                box("moov", box("mvhd", mvhdV0(1000, 2500))),
                box("mdat", new byte[2048])));

        assertThat(Mp4DurationReader.readSeconds(file))
                .as("2500 / 1000 = 2.5 ⇒ 向下取整 2（「看到第几秒」用整数就够了）")
                .hasValue(2);
    }

    @Test
    @DisplayName("★moov 在文件尾（非 faststart，真实录屏的常见形态）：先 seek 跳过 mdat 也能读到")
    void moov在尾_mdat被跳过() throws IOException {
        Path file = write("moov-at-tail.mp4", concat(
                box("ftyp", ascii("isomiso2avc1mp41")),
                box("mdat", new byte[64 * 1024]),
                box("moov", box("mvhd", mvhdV0(600, 7200)))));

        assertThat(Mp4DurationReader.readSeconds(file))
                .as("★ 只认前缀 moov 的实现会在这里返回 empty ⇒ 绝大多数真实文件时长恒未知")
                .hasValue(12);
    }

    @Test
    @DisplayName("v1 头：64 位 duration（timescale 90000 / duration 180000 ⇒ 2s）")
    void version1_64位时长() throws IOException {
        Path file = write("v1.mp4", concat(
                box("ftyp", ascii("isom")),
                box("moov", box("mvhd", mvhdV1(90_000, 180_000)))));

        assertThat(Mp4DurationReader.readSeconds(file))
                .as("v1 的 duration 是 8 字节；按 4 字节读会拿到 0")
                .hasValue(2);
    }

    @Test
    @DisplayName("★size==1 的扩展长度 box（64 位 largesize）也能跳过，找到后面的 moov")
    void 扩展长度box被跳过() throws IOException {
        Path file = write("large-size.mp4", concat(
                box("ftyp", ascii("isom")),
                extendedBox("mdat", new byte[32 * 1024]),
                box("moov", box("mvhd", mvhdV0(1000, 60_000)))));

        assertThat(Mp4DurationReader.readSeconds(file))
                .as("size==1 ⇒ 真实长度在随后的 8 字节 largesize 里；按 1 当长度会立刻越界")
                .hasValue(60);
    }

    @Test
    @DisplayName("ffmpeg 常见的 15360 timescale：90 秒")
    void 常见timescale() throws IOException {
        Path file = write("ffmpeg.mp4", concat(
                box("ftyp", ascii("isom")),
                box("moov", box("mvhd", mvhdV0(15360, 15360L * 90)))));

        assertThat(Mp4DurationReader.readSeconds(file)).hasValue(90);
    }

    // ==================== 未知 / 畸形 ⇒ empty（绝不抛） ====================

    @Test
    @DisplayName("timescale==0 ⇒ empty（除零是非法值，不是 0 秒）")
    void timescale为零() throws IOException {
        Path file = write("timescale-zero.mp4", concat(
                box("ftyp", ascii("isom")),
                box("moov", box("mvhd", mvhdV0(0, 5000)))));

        assertThat(Mp4DurationReader.readSeconds(file))
                .as("★ 必须显式判 0：不判会 ArithmeticException（发布流程变成新的失败点）")
                .isEmpty();
    }

    @Test
    @DisplayName("v0 的 duration 全 F = 标准里的「未知」 ⇒ empty")
    void duration全F_v0() throws IOException {
        Path file = write("duration-unknown.mp4", concat(
                box("ftyp", ascii("isom")),
                box("moov", box("mvhd", mvhdV0(1000, 0xFFFF_FFFFL)))));

        assertThat(Mp4DurationReader.readSeconds(file))
                .as("★ 不判全 F 会把它当成 4294967295 秒（≈136 年），进度百分比直接归零")
                .isEmpty();
    }

    @Test
    @DisplayName("v1 的 duration 全 F（8 字节）⇒ empty")
    void duration全F_v1() throws IOException {
        Path file = write("duration-unknown-v1.mp4", concat(
                box("ftyp", ascii("isom")),
                box("moov", box("mvhd", mvhdV1(1000, -1L)))));

        assertThat(Mp4DurationReader.readSeconds(file)).isEmpty();
    }

    @Test
    @DisplayName("没有 moov（只有 ftyp + mdat）⇒ empty")
    void 没有moov() throws IOException {
        Path file = write("no-moov.mp4", concat(
                box("ftyp", ascii("isom")),
                box("mdat", new byte[1024])));

        assertThat(Mp4DurationReader.readSeconds(file)).isEmpty();
    }

    @Test
    @DisplayName("moov 里没有 mvhd ⇒ empty")
    void 没有mvhd() throws IOException {
        Path file = write("no-mvhd.mp4", concat(
                box("ftyp", ascii("isom")),
                box("moov", box("trak", new byte[64]))));

        assertThat(Mp4DurationReader.readSeconds(file)).isEmpty();
    }

    @Test
    @DisplayName("未知 mvhd 版本（2）⇒ empty（不猜布局）")
    void 未知版本() throws IOException {
        byte[] payload = mvhdV0(1000, 5000);
        payload[0] = 2;
        Path file = write("mvhd-v2.mp4", concat(
                box("ftyp", ascii("isom")),
                box("moov", box("mvhd", payload))));

        assertThat(Mp4DurationReader.readSeconds(file)).isEmpty();
    }

    @Test
    @DisplayName("★截断：moov 声明的大小超出文件长度 ⇒ empty（不读到文件外）")
    void 截断的moov() throws IOException {
        Path file = write("truncated.mp4", concat(
                box("ftyp", ascii("isom")),
                box("moov", box("mvhd", mvhdV0(1000, 5000)))));
        // 砍掉最后 10 字节：moov 仍然"声明"原来的长度，但文件不够长
        byte[] all = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(all, all.length - 10));

        assertThat(Mp4DurationReader.readSeconds(file))
                .as("★ 不判 position+size <= 文件长度 ⇒ 读到垃圾甚至 IOException 冒泡")
                .isEmpty();
    }

    @Test
    @DisplayName("mvhd 载荷不足（只有 4 字节）⇒ empty")
    void mvhd载荷不足() throws IOException {
        byte[] payload = new byte[4];
        payload[0] = 0; // version 0 + flags，后面什么都没有
        Path file = write("short-mvhd.mp4", concat(
                box("ftyp", ascii("isom")),
                box("moov", box("mvhd", payload))));

        assertThat(Mp4DurationReader.readSeconds(file)).isEmpty();
    }

    @Test
    @DisplayName("非 mp4（PNG 头）⇒ empty")
    void 非mp4() throws IOException {
        Path file = write("fake.mp4", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 13, 'I', 'H', 'D', 'R'});

        assertThat(Mp4DurationReader.readSeconds(file)).isEmpty();
    }

    @Test
    @DisplayName("空文件 / 不存在的路径 / null ⇒ empty（不抛）")
    void 空文件与不存在的路径() throws IOException {
        Path empty = write("empty.mp4", new byte[0]);

        assertThat(Mp4DurationReader.readSeconds(empty)).isEmpty();
        assertThat(Mp4DurationReader.readSeconds(tempDir.resolve("not-exists.mp4"))).isEmpty();
        assertThat(Mp4DurationReader.readSeconds(null)).isEmpty();
    }

    // ==================== 字节构造 ====================

    private Path write(String name, byte[] content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content);
        return file;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 普通 box：4 字节 size + 4 字节 type + 载荷。 */
    private static byte[] box(String type, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(8 + payload.length);
        buffer.put(ascii(type));
        buffer.put(payload);
        return buffer.array();
    }

    /** {@code size==1} 的 box：4 字节 size(=1) + 4 字节 type + 8 字节 largesize + 载荷。 */
    private static byte[] extendedBox(String type, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(16 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(1);
        buffer.put(ascii(type));
        buffer.putLong(16L + payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    /**
     * mvhd 载荷（v0）：version(1)+flags(3) + creation(4) + modification(4) + timescale(4) + duration(4)。
     * 故 timescale 在载荷偏移 12、duration 在 16。
     */
    private static byte[] mvhdV0(long timescale, long duration) {
        ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) 0).put(new byte[3]);
        buffer.putInt(0).putInt(0);
        buffer.putInt((int) timescale);
        buffer.putInt((int) duration);
        return buffer.array();
    }

    /**
     * mvhd 载荷（v1）：creation(8) + modification(8) ⇒ timescale 在偏移 20、duration 在 24（8 字节）。
     */
    private static byte[] mvhdV1(long timescale, long duration) {
        ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) 1).put(new byte[3]);
        buffer.putLong(0).putLong(0);
        buffer.putInt((int) timescale);
        buffer.putLong(duration);
        return buffer.array();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
