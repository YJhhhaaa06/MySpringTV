package io.github.yjhhhaaa06.videoweb.upload;

import org.springframework.util.MultiValueMap;

/**
 * {@code POST /api/v1/contents/videos} 的 6 条幂等矩阵（`T68`；骨架在
 * {@link AbstractUploadIdempotencyTests}，口径见那里的类注释与本专项
 * {@code architecture/tech/idempotency/写接口幂等-测试与契约.md} §二）。
 *
 * <p>本端点的一次成功上传落 **2 条**媒体行 / **2 个**文件（视频 + 封面）。
 */
class VideoUploadIdempotencyTests extends AbstractUploadIdempotencyTests {

    private static final String TITLE = "T68 幂等视频";
    private static final String OTHER_TITLE = "T68 幂等视频-换载荷";

    @Override
    protected String path() {
        return "/api/v1/contents/videos";
    }

    @Override
    protected String slug() {
        return "video";
    }

    @Override
    protected MultiValueMap<String, Object> validForm() {
        return videoForm(TITLE, "T68 幂等视频简介", FIXTURE_COVER, "image/png");
    }

    /** 换载荷：标题、简介、封面文件（png → jpg）全换 ⇒ multipart body 确实不同。 */
    @Override
    protected MultiValueMap<String, Object> alternativeForm() {
        return videoForm(OTHER_TITLE, "T68 换载荷后的简介", FIXTURE_IMAGE, "image/jpeg");
    }

    /**
     * 可恢复失败：**缺必填 part {@code video}**（只有封面）。
     *
     * <p>校验发生在落盘之前 ⇒ 首次 400 且零文件；随后同 key 用完整表单重发必须能重新占位执行。
     * <p>⚠️ 本仓 multipart 的指纹**只到路径级** ⇒ 两次请求的表单体不同也不会变成 422，
     * 用例 5 要钉的是"{@code FAILED} 是墓碑不是锁"，不是载荷比对（那是用例 4）。
     */
    @Override
    protected MultiValueMap<String, Object> failingForm() {
        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", TITLE);
        addText(form, "categoryId", "1");
        addFilePart(form, "cover", FIXTURE_COVER, "image/png");
        return form;
    }

    @Override
    protected int mediaRowsPerUpload() {
        return 2;
    }

    /** 视频表单：标题 / 简介 / 封面可换（换载荷用例要造一份**不同的** multipart body）。 */
    private static MultiValueMap<String, Object> videoForm(String title, String description,
                                                           String coverFileName, String coverContentType) {
        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", title);
        addText(form, "description", description);
        addText(form, "categoryId", "1");
        addFilePart(form, "video", FIXTURE_VIDEO, "video/mp4");
        addFilePart(form, "cover", coverFileName, coverContentType);
        return form;
    }
}
