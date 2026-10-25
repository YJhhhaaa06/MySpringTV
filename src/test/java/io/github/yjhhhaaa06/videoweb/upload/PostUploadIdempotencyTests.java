package io.github.yjhhhaaa06.videoweb.upload;

import org.springframework.util.MultiValueMap;

/**
 * {@code POST /api/v1/contents/posts} 的 6 条幂等矩阵（`T68`；骨架在
 * {@link AbstractUploadIdempotencyTests}，口径见那里的类注释与本专项
 * {@code architecture/tech/idempotency/写接口幂等-测试与契约.md} §二）。
 *
 * <p>本端点的一次成功上传落 **3 条**媒体行 / **3 个**文件（封面 1 + 正文图 2）——
 * 图片数也是断言的一部分（同 key 重放时 {@code content_media} 必须仍是 3 条）。
 */
class PostUploadIdempotencyTests extends AbstractUploadIdempotencyTests {

    private static final String TITLE = "T68 幂等图文";
    private static final String OTHER_TITLE = "T68 幂等图文-换载荷";

    @Override
    protected String path() {
        return "/api/v1/contents/posts";
    }

    @Override
    protected String slug() {
        return "post";
    }

    @Override
    protected MultiValueMap<String, Object> validForm() {
        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", TITLE);
        addText(form, "description", "T68 幂等图文简介");
        addText(form, "categoryId", "0");
        addFilePart(form, "cover", FIXTURE_COVER, "image/png");
        addFilePart(form, "image", FIXTURE_IMAGE, "image/jpeg");
        addFilePart(form, "image", FIXTURE_IMAGE, "image/jpeg");
        return form;
    }

    /**
     * 换载荷：标题换掉、封面换成 jpg、正文图由 2 张减到 1 张
     * ⇒ 若实现真的执行了第二份，媒体行会是 2 条而不是首次的 3 条（差异可判别）。
     */
    @Override
    protected MultiValueMap<String, Object> alternativeForm() {
        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", OTHER_TITLE);
        addText(form, "description", "T68 换载荷后的简介");
        addText(form, "categoryId", "0");
        addFilePart(form, "cover", FIXTURE_IMAGE, "image/jpeg");
        addFilePart(form, "image", FIXTURE_IMAGE, "image/jpeg");
        return form;
    }

    /**
     * 可恢复失败：**分区非法**（{@code categoryId=12}，多位数）⇒ 400「暂时没有这个分区」。
     *
     * <p>校验发生在落盘之前 ⇒ 首次 400 且零文件；随后同 key 用合法分区重发必须能重新占位执行。
     * <p>这条与视频那条（缺必填 part）刻意用**两种不同的 400 来源**：一条走 Spring 的
     * {@code MissingServletRequestPartException}，一条走 Controller 自己的参数校验。
     */
    @Override
    protected MultiValueMap<String, Object> failingForm() {
        MultiValueMap<String, Object> form = newForm();
        addText(form, "title", TITLE);
        addText(form, "categoryId", "12");
        addFilePart(form, "cover", FIXTURE_COVER, "image/png");
        return form;
    }

    @Override
    protected int mediaRowsPerUpload() {
        return 3;
    }
}
