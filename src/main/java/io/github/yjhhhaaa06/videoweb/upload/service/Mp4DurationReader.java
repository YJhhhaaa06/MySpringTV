package io.github.yjhhhaaa06.videoweb.upload.service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.OptionalInt;

/**
 * mp4 时长解析器：只读 box 头，取 {@code moov → mvhd} 里的 {@code duration / timescale}。
 *
 * <h2>为什么不整文件读、也不引 ffmpeg</h2>
 * 上传的视频可能几十 MB，而需要的信息只是两个整数。{@link RandomAccessFile} 顺序读每个
 * box 的 8 字节头、按 {@code size} {@code seek} 跳过 {@code mdat} 即可 —— **内存占用与文件大小无关**。
 * 引 ffmpeg 的成本（二进制依赖 / 平台差异 / 部署）远大于"读 32 字节"。
 *
 * <h2>为什么必须支持 moov 在文件尾</h2>
 * 只有做过 faststart（moov 前置）的文件才能边下边播；**普通录屏 / 手机导出的 mp4 通常 moov 在尾部**。
 * 只认"前缀 moov"会让绝大多数真实文件解析失败 ⇒ 时长恒为未知，等于没做。
 *
 * <h2>契约：一切异常都是 {@code empty}</h2>
 * 返回 {@link OptionalInt#empty()} 的情形（全部按"未知"处理，**绝不抛异常**）：
 * <ul>
 *   <li>无 {@code moov} / 无 {@code mvhd}；</li>
 *   <li>{@code timescale == 0}（除零，且本身就是非法值）；</li>
 *   <li>{@code duration} 全 F（0xFFFF_FFFF / 0xFFFF_FFFF_FFFF_FFFF）= 标准里的"未知"；</li>
 *   <li>文件截断 / box 长度越界 / 版本号既非 0 也非 1；</li>
 *   <li>根本不是 mp4（首个 box 的长度就超出文件）。</li>
 * </ul>
 * ★ **解析失败不能阻断发布**：时长只服务"进度百分比"，内容本身仍可播。
 * 调用方（{@code UploadController}）拿到 {@code empty} 就写 NULL，发布照常 200
 * （口径见 {@code architecture/domain/history/history.md} §六.2）。
 *
 * <h2>无状态、纯函数</h2>
 * 静态方法、无注入：它不依赖任何配置（不用媒体根、不看 {@code UploadProperties}），
 * 输入是"磁盘上的一个路径"。这样单测直接传临时文件即可，不需要 Spring 上下文。
 */
public final class Mp4DurationReader {

    private static final int BOX_HEADER_BYTES = 8;

    /** 32 位 box 长度的"扩展到 64 位"标记：真实长度在后 8 字节 {@code largesize} 里。 */
    private static final long SIZE_TO_EOF_OR_EXTENDED = 1L;

    /** {@code size == 0} ⇒ 本 box 一直到文件末尾（规范允许，常见于末尾的 {@code mdat}）。 */
    private static final long SIZE_TO_EOF = 0L;

    /** v0 的 duration 未知标记：4 字节全 F。 */
    private static final long UNKNOWN_DURATION_V0 = 0xFFFF_FFFFL;

    private Mp4DurationReader() {
    }

    /**
     * 解析时长。
     *
     * @param file mp4 文件路径；{@code null} 或不存在 ⇒ {@code empty}
     * @return 时长（**向下取整秒**）；未知 ⇒ {@code empty}
     */
    public static OptionalInt readSeconds(Path file) {
        if (file == null) {
            return OptionalInt.empty();
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long fileLength = raf.length();
            Box moov = findBox(raf, 0, fileLength, "moov");
            if (moov == null) {
                return OptionalInt.empty();
            }
            Box mvhd = findBox(raf, moov.payloadStart, moov.end, "mvhd");
            if (mvhd == null) {
                return OptionalInt.empty();
            }
            return readDuration(raf, mvhd);
        } catch (IOException | RuntimeException e) {
            // 截断 / 权限 / 根本不是 mp4 —— 全部按"未知"处理，绝不把发布流程变成新的失败点。
            return OptionalInt.empty();
        }
    }

    /**
     * 读 {@code mvhd} 载荷。偏移**相对载荷起点**（即跳过 box 头）：
     * <pre>
     *   v0: version(1)+flags(3) | creation(4) modification(4) timescale(4)@12 duration(4)@16
     *   v1: version(1)+flags(3) | creation(8) modification(8) timescale(4)@20 duration(8)@24
     * </pre>
     */
    private static OptionalInt readDuration(RandomAccessFile raf, Box mvhd) throws IOException {
        long payloadLength = mvhd.end - mvhd.payloadStart;
        if (payloadLength < 4) {
            return OptionalInt.empty();
        }
        raf.seek(mvhd.payloadStart);
        int version = raf.readUnsignedByte();
        int timescaleOffset;
        int durationOffset;
        int durationBytes;
        if (version == 0) {
            timescaleOffset = 12;
            durationOffset = 16;
            durationBytes = 4;
            if (payloadLength < durationOffset + durationBytes) {
                return OptionalInt.empty();
            }
        } else if (version == 1) {
            timescaleOffset = 20;
            durationOffset = 24;
            durationBytes = 8;
            if (payloadLength < durationOffset + durationBytes) {
                return OptionalInt.empty();
            }
        } else {
            return OptionalInt.empty();
        }

        raf.seek(mvhd.payloadStart + timescaleOffset);
        long timescale = readUInt32(raf);
        raf.seek(mvhd.payloadStart + durationOffset);
        long duration = durationBytes == 4 ? readUInt32(raf) : raf.readLong();

        // timescale = 0 ⇒ 除零（非法值）；duration 全 F ⇒ 标准里的"未知"
        if (timescale == 0 || duration < 0) {
            return OptionalInt.empty();
        }
        if (durationBytes == 4 && duration == UNKNOWN_DURATION_V0) {
            return OptionalInt.empty();
        }
        if (durationBytes == 8 && duration == -1L) {
            return OptionalInt.empty();
        }
        long seconds = duration / timescale;
        return OptionalInt.of((int) Math.min(seconds, Integer.MAX_VALUE));
    }

    /**
     * 在 {@code [from, to)} 内顺序扫 box 头，找第一个 {@code type} 匹配的 box。
     *
     * <p>只 {@code seek} 不读载荷 ⇒ {@code mdat} 再大也只花一次定位。
     *
     * @return 命中的 box；未命中 / 长度越界（截断）/ 长度非法 ⇒ {@code null}
     */
    private static Box findBox(RandomAccessFile raf, long from, long to, String type) throws IOException {
        long position = from;
        while (position + BOX_HEADER_BYTES <= to) {
            raf.seek(position);
            long size = readUInt32(raf);
            String boxType = readFourCc(raf);
            long headerBytes = BOX_HEADER_BYTES;
            if (size == SIZE_TO_EOF_OR_EXTENDED) {
                // size==1 ⇒ 真实长度在随后的 8 字节 largesize 里（box 头变 16 字节）
                if (position + 16 > to) {
                    return null;
                }
                size = raf.readLong();
                headerBytes = 16;
            } else if (size == SIZE_TO_EOF) {
                size = to - position;
            }
            if (size < headerBytes || position + size > to) {
                // 截断 / 非法长度：继续扫下去只会拿到垃圾 ⇒ 判定"解析失败"
                return null;
            }
            if (type.equals(boxType)) {
                return new Box(position + headerBytes, position + size);
            }
            position += size;
        }
        return null;
    }

    private static long readUInt32(RandomAccessFile raf) throws IOException {
        return raf.readInt() & 0xFFFF_FFFFL;
    }

    private static String readFourCc(RandomAccessFile raf) throws IOException {
        byte[] raw = new byte[4];
        raf.readFully(raw);
        return new String(raw, StandardCharsets.ISO_8859_1);
    }

    /**
     * @param payloadStart box 头之后（{@code size==1} 时是 16 字节之后）
     * @param end          box 结束位置（不含）
     */
    private record Box(long payloadStart, long end) {
    }
}
