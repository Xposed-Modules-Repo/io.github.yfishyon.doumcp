package io.github.yfishyon.doumcp.features.comment.bridge

import android.graphics.BitmapFactory
import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.Reflect
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 一张上传完成的评论图片（uri 为宿主上传器返回的对象键）。 */
data class UploadedImage(
    val uri: String,
    val width: Int,
    val height: Int,
    val format: String,
)

/**
 * 评论图片上传器。
 *
 * 复用宿主自己的上传入口：入口内部按需取上传凭证、配置对象存储上传器并上传，
 * 单张完成时回调里带对象键——与宿主发图片评论拿到的是同一个值。
 * 上传进度回调宿主可空（宿主自身也传空），因此只挂图片上传监听。
 */
object CommentImageUploader {
    /** 宿主缓存上传凭证用的配置键（该常量只出现在上传器类里，作全等锚点） */
    private const val UPLOAD_AUTH_KEY = "comment_image_upload_auth_config"

    private const val IMAGE_LISTENER = "com.ss.bduploader.BDImageXUploaderListener"
    private const val VIDEO_LISTENER = "com.ss.bduploader.BDVideoUploaderListener"
    private const val IMAGE_OBJECT_KEY = "mImageTosKey"
    private const val IMAGE_FILE_INDEX = "mFileIndex"

    /** 上传回调：单张完成 */
    private const val NOTIFY_FILE_FINISHED = 6

    /** 上传回调：全部完成 */
    private const val NOTIFY_ALL_FINISHED = 0

    /** 上传回调：失败 */
    private const val NOTIFY_FAILED = 7

    /** 单次最多张数（宿主上传器自身限制） */
    const val MAX_IMAGES = 9

    private const val TIMEOUT_MS = 180_000L

    /**
     * 上传本地图片（阻塞，调用方负责后台线程）。
     *
     * @param paths 本地图片绝对路径（最多 [MAX_IMAGES] 张）
     */
    fun upload(paths: List<String>): List<UploadedImage> {
        require(paths.isNotEmpty()) { "图片路径为空" }
        require(paths.size <= MAX_IMAGES) { "最多 $MAX_IMAGES 张图片" }

        val method = resolveUploadMethod() ?: throw IllegalStateException("图片上传入口未定位")
        val keys = arrayOfNulls<String>(paths.size)
        val latch = CountDownLatch(paths.size)
        val failed = AtomicBoolean(false)

        val listenerClass = HostRuntime.hostClass(IMAGE_LISTENER)
        val listener =
            Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, m, args ->
                when (m.name) {
                    "onNotify" -> {
                        when ((args[0] as Number).toInt()) {
                            NOTIFY_FILE_FINISHED -> {
                                val info = args[2]
                                val index = (Reflect.field(info!!, IMAGE_FILE_INDEX) as? Number)?.toInt() ?: -1
                                val key = Reflect.field(info, IMAGE_OBJECT_KEY) as? String
                                if (index in keys.indices && key != null && keys[index] == null) {
                                    keys[index] = key
                                    latch.countDown()
                                }
                            }

                            NOTIFY_ALL_FINISHED -> {
                                drain(latch)
                            }

                            NOTIFY_FAILED -> {
                                failed.set(true)
                                drain(latch)
                            }
                        }
                        null
                    }

                    // 上传器的网络自检：返回可用
                    "imageXUploadCheckNetState" -> {
                        1
                    }

                    "toString" -> {
                        "DouMcpCommentImageUploadListener"
                    }

                    else -> {
                        null
                    }
                }
            }

        runCatching { method.invoke(null, null, paths, listener, false, ArrayList<Any>(), null) }
            .getOrElse {
                ModLog.e("评论图片上传：调用失败", it.cause ?: it)
                throw IllegalStateException("图片上传调用失败: ${it.cause ?: it}")
            }

        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw IllegalStateException("图片上传超时")
        if (failed.get()) throw IllegalStateException("图片上传失败")

        return paths.mapIndexed { index, path ->
            val uri = keys[index] ?: throw IllegalStateException("第 ${index + 1} 张图片上传失败")
            val size = imageSize(path)
            UploadedImage(uri, size.first, size.second, formatOf(path))
        }
    }

    /** 上传入口（锚点类里参数表与宿主发图片评论一致的那个静态方法）。 */
    private fun resolveUploadMethod(): Method? =
        DexKitSupport.resolveCached("comment_image_upload") { dexKit ->
            dexKit
                .findClass {
                    matcher { usingStrings(listOf(UPLOAD_AUTH_KEY), StringMatchType.Equals) }
                }.findMethod {
                    matcher {
                        paramTypes(
                            null,
                            "java.util.List",
                            IMAGE_LISTENER,
                            "boolean",
                            "java.util.List",
                            VIDEO_LISTENER,
                        )
                    }
                }.firstOrNull()
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }

    /** 图片像素尺寸（只解码边界，不加载位图）。 */
    private fun imageSize(path: String): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        return options.outWidth to options.outHeight
    }

    /** 图片格式（取文件扩展名，统一成常见写法）。 */
    private fun formatOf(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "png"
            "gif" -> "gif"
            "webp" -> "webp"
            "heic", "heif" -> "heic"
            else -> "jpeg"
        }

    private fun drain(latch: CountDownLatch) {
        while (latch.count > 0) latch.countDown()
    }
}
