import { describe, expect, it } from 'vitest'
import { AssetUploadPool } from './upload'
import type { UploadedAsset, UploadProgress } from './upload'
import type { AssetType } from './uploadDraft'

/**
 * `AssetUploadPool` 的契约测试。
 *
 * <p>它钉的不是"缓存能省一次请求"，而是**重试的正确性**：创建端点的幂等指纹包含请求体
 * （见 `api/upload.ts` 顶部），重试若换来新 URL，服务端会按指纹冲突回 409。
 * 所以"同一文件的重试必须复用同一 URL"是契约，不是优化。
 */

const MB = 1024 * 1024

function fakeFile(name: string, size: number, lastModified = 1): File {
  const file = new File([new Uint8Array(1)], name, { type: 'application/octet-stream', lastModified })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

/** 记录调用次数的假上传器：每次返回一个新 URL（真实现也是如此，文件名是 UUID）。 */
function recordingUploader() {
  const calls: Array<{ name: string; type: AssetType }> = []
  let seq = 0
  const uploader = async (file: File, type: AssetType, _onProgress?: UploadProgress): Promise<UploadedAsset> => {
    calls.push({ name: file.name, type })
    seq += 1
    return { url: `/upload/${type}/u${seq}`, durationSeconds: type === 'video' ? 42 : null }
  }
  return { calls, uploader }
}

describe('已上传文件的复用（重试不重传）', () => {
  it('同一文件第二次上传命中缓存：不发请求、URL 不变', async () => {
    const { calls, uploader } = recordingUploader()
    const pool = new AssetUploadPool(uploader)
    const file = fakeFile('a.mp4', 30 * MB)

    const first = await pool.upload(file, 'video')
    const second = await pool.upload(file, 'video')

    expect(calls).toHaveLength(1)
    expect(second.url).toBe(first.url)
  })

  it('换了文件（哪怕同名）就重新上传：name/size/lastModified 任一不同都算换', async () => {
    const { calls, uploader } = recordingUploader()
    const pool = new AssetUploadPool(uploader)

    await pool.upload(fakeFile('a.mp4', 30 * MB, 1), 'video')
    await pool.upload(fakeFile('a.mp4', 30 * MB, 2), 'video')

    expect(calls).toHaveLength(2)
  })

  it('同一文件换角色（封面 → 图片）各传一次：服务端按目录校验类型', async () => {
    const { calls, uploader } = recordingUploader()
    const pool = new AssetUploadPool(uploader)
    const file = fakeFile('a.png', 1024)

    await pool.upload(file, 'cover')
    await pool.upload(file, 'image')

    expect(calls.map((call) => call.type)).toEqual(['cover', 'image'])
  })

  it('提交成功后的 clear ⇒ 下一次投稿重新上传（那些 URL 已被绑到上一条内容）', async () => {
    const { calls, uploader } = recordingUploader()
    const pool = new AssetUploadPool(uploader)
    const file = fakeFile('a.png', 1024)

    await pool.upload(file, 'cover')
    pool.clear()
    await pool.upload(file, 'cover')

    expect(calls).toHaveLength(2)
  })
})
