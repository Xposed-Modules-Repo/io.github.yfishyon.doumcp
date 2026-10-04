package io.github.yfishyon.doumcp.features.publish.bridge

import android.graphics.BitmapFactory
import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.Reflect
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 一条上传完成的视频（videoId 为 VOD 视频号，coverUri 为封面对象键）。 */
data class UploadedVideo(
    val videoId: String,
    val coverUri: String,
    val width: Int,
    val height: Int,
)

/** 一张上传完成的图片（uri 为对象存储里的对象键）。 */
data class UploadedMedia(
    val uri: String,
    val width: Int,
    val height: Int,
    val format: String,
)

/**
 * 发布素材上传器：走宿主自己的上传链路（与发作品时上传图片/视频完全相同）。
 *
 * 链路：发布上传凭证接口（参数为空 map）→ `UploadAuthKeyConfig` → 宿主上传器工厂建
 * `BDImageUploader` → 逐张上传，回调里拿对象键。凭证优先取宿主缓存，未命中再请求。
 *
 * 全部锚点只用未混淆类型/字段名（UploadAuthKeyConfig / BDImageUploader / mListener 等），
 * 混淆类名与字段一律运行时取，不写字面量。
 */
object PublishUploader {
    private const val UPLOAD_AUTH_CONFIG = "com.ss.android.ugc.aweme.shortvideo.UploadAuthKeyConfig"
    private const val BD_IMAGE_UPLOADER = "com.ss.bduploader.BDImageUploader"
    private const val BD_VIDEO_UPLOADER = "com.ss.bduploader.BDVideoUploader"
    private const val BD_VIDEO_INFO = "com.ss.bduploader.BDVideoInfo"
    private const val BD_VIDEO_LISTENER = "com.ss.bduploader.BDVideoUploaderListener"
    private const val FUNCTION1 = "kotlin.jvm.functions.Function1"

    /** 上传场景标记（宿主用它区分上传来源） */
    private const val SCENE_TAG = "ImageListUploader"

    private const val TIMEOUT_MS = 180_000L
    private const val AUTH_TIMEOUT_SEC = 30L

    /** 上传回调：单张完成 */
    private const val NOTIFY_FILE_FINISHED = 6

    /** 上传回调：全部完成 */
    private const val NOTIFY_ALL_FINISHED = 0

    /** 上传回调：失败 */
    private const val NOTIFY_FAILED = 7

    /** 视频上传回调：失败 */
    private const val VIDEO_NOTIFY_FAILED = 2

    /** 视频上传回调：单条完成（结果里带 videoId/封面） */
    private const val VIDEO_NOTIFY_SINGLE_FINISHED = 8

    /** 视频上传回调：上传结束（收尾） */
    private const val VIDEO_NOTIFY_END = 10

    @Volatile
    private var authConfig: Any? = null

    /**
     * 上传本地图片（阻塞，调用方负责后台线程）。
     *
     * @param paths 本地图片绝对路径
     */
    fun uploadImages(paths: List<String>): List<UploadedMedia> {
        require(paths.isNotEmpty()) { "图片路径为空" }

        val config = authConfig() ?: throw IllegalStateException("上传凭证获取失败")
        val configFields = Reflect.fieldsOf(config.javaClass)
        val imgConfig = configFields["imgConfig"]?.get(config) ?: throw IllegalStateException("imgConfig 缺失")
        val settingConfig =
            configFields["uploadSettingConfig"]?.get(config) ?: throw IllegalStateException("uploadSettingConfig 缺失")

        val uploader =
            buildImageUploader(imgConfig, settingConfig) ?: throw IllegalStateException("上传器创建失败")
        val keys = arrayOfNulls<String>(paths.size)
        val latch = CountDownLatch(paths.size)
        val failed = AtomicBoolean(false)

        attachListener(uploader, keys, latch, failed)

        invoke(uploader, "setFilePath", paths.size, paths.toTypedArray())
        invoke(uploader, "start")

        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            invalidateAuthConfig()
            throw IllegalStateException("图片上传超时")
        }
        if (failed.get()) {
            invalidateAuthConfig()
            throw IllegalStateException("图片上传失败")
        }

        return paths.mapIndexed { index, path ->
            val uri = keys[index] ?: throw IllegalStateException("第 ${index + 1} 张图片上传失败")
            val size = imageSize(path)
            UploadedMedia(uri, size.first, size.second, formatOf(path))
        }
    }

    /**
     * 上传本地视频（阻塞，调用方负责后台线程）。
     *
     * @param path 本地视频绝对路径
     */
    fun uploadVideo(path: String): UploadedVideo {
        val config = authConfig() ?: throw IllegalStateException("上传凭证获取失败")
        val uploader = buildVideoUploader(config) ?: throw IllegalStateException("视频上传器创建失败")

        val latch = CountDownLatch(1)
        val result = arrayOfNulls<UploadedVideo>(1)
        val error = arrayOfNulls<String>(1)

        val listenerClass = HostRuntime.hostClass(BD_VIDEO_LISTENER)
        val listener =
            Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                if (args != null && args.size == 3 && args[0] is Number) {
                    runCatching { handleVideoNotify(args, result, error, latch) }
                    null
                } else if (args != null && args.size == 2 && args[0] is Number) {
                    1
                } else {
                    when (method.name) {
                        "toString" -> "DouMcpPublishVideoListener"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> (args?.firstOrNull() === proxy)
                        else -> null
                    }
                }
            }
        invoke(uploader, "setListener", listener)
        invoke(uploader, "setFilePath", 1, arrayOf(path))
        invoke(uploader, "start")

        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            invalidateAuthConfig()
            throw IllegalStateException("视频上传超时")
        }
        return result[0] ?: run {
            invalidateAuthConfig()
            throw IllegalStateException(error[0] ?: "视频上传失败")
        }
    }

    /** 视频上传回调：8 单条完成（带结果）、2 失败、0/10 收尾；单文件上传不一定发 0。 */
    private fun handleVideoNotify(
        args: Array<Any?>,
        result: Array<UploadedVideo?>,
        error: Array<String?>,
        latch: CountDownLatch,
    ) {
        val what = (args[0] as? Number)?.toInt() ?: return
        val info = args.getOrNull(2)
        when (what) {
            VIDEO_NOTIFY_SINGLE_FINISHED -> {
                if (info != null && result[0] == null) {
                    val videoId = Reflect.field(info, "mVideoId") as? String ?: ""
                    val coverUri = Reflect.field(info, "mCoverUri") as? String ?: ""
                    val mediaInfo = Reflect.field(info, "mVideoMediaInfo") as? String
                    val size = parseMediaSize(mediaInfo)
                    result[0] = UploadedVideo(videoId, coverUri, size.first, size.second)
                }
                latch.countDown()
            }

            // 全部完成/收尾：只在已拿到结果时放行，避免空 info 被当成成功
            NOTIFY_ALL_FINISHED, VIDEO_NOTIFY_END -> {
                if (result[0] != null || error[0] != null) latch.countDown()
            }

            VIDEO_NOTIFY_FAILED -> {
                val code = info?.let { Reflect.field(it, "mErrorCode") }
                val message = info?.let { Reflect.field(it, "mErrorMsg") as? String } ?: ""
                error[0] = "视频上传失败 code=$code $message"
                latch.countDown()
            }
        }
    }

    /** 宿主上传器工厂：静态方法（UploadAuthKeyConfig）→ BDVideoUploader。 */
    private fun buildVideoUploader(config: Any): Any? =
        DexKitSupport
            .resolveCached("publish_video_uploader_factory") { dexKit ->
                dexKit
                    .findMethod {
                        matcher {
                            paramTypes(UPLOAD_AUTH_CONFIG)
                            returnType = BD_VIDEO_UPLOADER
                            modifiers(
                                org.luckypray.dexkit.query.matchers.base
                                    .AccessFlagsMatcher(Modifier.STATIC),
                            )
                        }
                    }.firstOrNull()
                    ?.getMethodInstance(HostRuntime.requireClassLoader())
            }?.invoke(null, config)

    /** 视频宽高（宿主上传信息里的 Width/Height）。 */
    private fun parseMediaSize(mediaInfo: String?): Pair<Int, Int> =
        runCatching {
            val json = org.json.JSONObject(mediaInfo ?: "")
            json.optInt("Width") to json.optInt("Height")
        }.getOrDefault(0 to 0)

    /** 上传凭证：优先读宿主缓存（宿主自行刷新失效），本地仅在宿主没有时请求一次兜底。 */
    private fun authConfig(): Any? {
        cachedAuthConfig()?.let {
            authConfig = it
            return it
        }
        authConfig?.let { return it }
        synchronized(this) {
            cachedAuthConfig()?.let {
                authConfig = it
                return it
            }
            authConfig?.let { return it }
            val config = requestAuthConfig() ?: return null
            authConfig = config
            return config
        }
    }

    /** 上传失败时丢弃本地兜底凭证，下次重新向宿主取。 */
    private fun invalidateAuthConfig() {
        authConfig = null
    }

    /** 宿主已缓存的凭证（持有者里类型为 [UPLOAD_AUTH_CONFIG] 的静态字段）。 */
    private fun cachedAuthConfig(): Any? {
        val holder = authHolderClass() ?: return null
        val configClass = HostRuntime.hostClass(UPLOAD_AUTH_CONFIG)
        val field =
            holder.declaredFields.firstOrNull {
                Modifier.isStatic(it.modifiers) && it.type == configClass
            } ?: return null
        field.isAccessible = true
        return field.get(null)
    }

    /** 主动请求凭证：调持有者里「两个 Function1 + boolean → void」的静态请求方法。 */
    private fun requestAuthConfig(): Any? {
        val holder = authHolderClass() ?: return null
        val request =
            holder.declaredMethods.firstOrNull {
                Modifier.isStatic(it.modifiers) &&
                    it.returnType == Void.TYPE &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0].name == FUNCTION1 &&
                    it.parameterTypes[1].name == FUNCTION1 &&
                    it.parameterTypes[2] == Boolean::class.javaPrimitiveType
            } ?: run {
                ModLog.w("发布上传：凭证请求方法未定位")
                return null
            }

        val latch = CountDownLatch(1)
        val result = arrayOfNulls<Any>(1)
        val onSuccess =
            function1 {
                result[0] = it
                latch.countDown()
            }
        val onError = function1 { latch.countDown() }
        request.isAccessible = true
        runCatching { request.invoke(null, onSuccess, onError, false) }
            .onFailure {
                ModLog.w("发布上传：凭证请求调用失败", it.cause ?: it)
                return null
            }

        if (!latch.await(AUTH_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            ModLog.w("发布上传：凭证请求超时")
            return null
        }
        return result[0]
    }

    /** 凭证持有者类：含类型为 [UPLOAD_AUTH_CONFIG] 的静态字段的那个类。 */
    private fun authHolderClass(): Class<*>? =
        DexKitSupport
            .resolveCached("publish_upload_auth_holder") { dexKit ->
                dexKit
                    .findMethod {
                        matcher {
                            paramTypes(FUNCTION1, FUNCTION1, "boolean")
                            returnType = "void"
                            modifiers(
                                org.luckypray.dexkit.query.matchers.base
                                    .AccessFlagsMatcher(Modifier.STATIC),
                            )
                        }
                    }.firstOrNull()
                    ?.getMethodInstance(HostRuntime.requireClassLoader())
            }?.declaringClass

    /** 宿主上传器工厂：静态方法（imgConfig, uploadSettingConfig, String）→ BDImageUploader。 */
    private fun buildImageUploader(
        imgConfig: Any,
        settingConfig: Any,
    ): Any? =
        DexKitSupport
            .resolveCached("publish_image_uploader_factory") { dexKit ->
                dexKit
                    .findMethod {
                        matcher {
                            paramTypes(imgConfig.javaClass.name, settingConfig.javaClass.name, "java.lang.String")
                            returnType = BD_IMAGE_UPLOADER
                            modifiers(
                                org.luckypray.dexkit.query.matchers.base
                                    .AccessFlagsMatcher(Modifier.STATIC),
                            )
                        }
                    }.firstOrNull()
                    ?.getMethodInstance(HostRuntime.requireClassLoader())
            }?.invoke(null, imgConfig, settingConfig, SCENE_TAG)

    /** 挂上传监听：单张完成取对象键，失败置标志。回调里按字段顺序取值，不写混淆字段名。 */
    private fun attachListener(
        uploader: Any,
        keys: Array<String?>,
        latch: CountDownLatch,
        failed: AtomicBoolean,
    ) {
        val listenerField: Field =
            Reflect.fieldsOf(uploader.javaClass)["mListener"]
                ?: throw IllegalStateException("上传监听字段未定位")
        val listenerClass = listenerField.type
        val listener =
            Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                // 按参数形状分派：三元组(int,long,info)为上传回调，单参数字为网络自检
                if (args != null && args.size == 3 && args[0] is Number) {
                    runCatching { handleNotify(args, keys, latch, failed) }
                    null
                } else if (args != null && args.size == 1 && args[0] is Number) {
                    1
                } else {
                    when (method.name) {
                        "toString" -> "DouMcpPublishUploadListener"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> (args?.firstOrNull() === proxy)
                        else -> null
                    }
                }
            }
        invoke(uploader, "setListener", listener)
    }

    private fun handleNotify(
        args: Array<Any?>,
        keys: Array<String?>,
        latch: CountDownLatch,
        failed: AtomicBoolean,
    ) {
        val code = (args[0] as? Number)?.toInt() ?: return
        val info = args.getOrNull(2)
        when (code) {
            NOTIFY_FILE_FINISHED -> {
                if (info == null) return
                // 按类型取：对象键是字符串、文件下标是数字（不依赖字段声明顺序）
                val values = orderedValues(info)
                val key = values.firstOrNull { it is String && it.isNotBlank() } as? String
                val index = (values.firstOrNull { it is Number } as? Number)?.toInt() ?: -1
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
    }

    private fun orderedValues(info: Any): List<Any?> =
        Reflect.fieldsOf(info.javaClass).values.map { runCatching { it.get(info) }.getOrNull() }

    private fun invoke(
        target: Any,
        name: String,
        vararg args: Any?,
    ): Any? {
        val method: Method =
            target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == args.size
            } ?: throw IllegalStateException("方法不存在: $name")
        method.isAccessible = true
        return try {
            method.invoke(target, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw (e.cause ?: e)
        }
    }

    private fun function1(block: (Any?) -> Unit): Any {
        val functionClass = Class.forName(FUNCTION1, false, HostRuntime.requireClassLoader())
        return Proxy.newProxyInstance(functionClass.classLoader, arrayOf(functionClass)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    runCatching { block(args?.firstOrNull()) }
                    null
                }

                "toString" -> {
                    "DouMcpPublishCallback"
                }

                "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                "equals" -> {
                    (args?.firstOrNull() === proxy)
                }

                else -> {
                    null
                }
            }
        }
    }

    private fun drain(latch: CountDownLatch) {
        while (latch.count > 0) latch.countDown()
    }

    /** 图片像素尺寸（只解码边界，不加载位图）。 */
    private fun imageSize(path: String): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw IllegalStateException("无法读取图片尺寸（文件不存在或非图片）: $path")
        }
        return options.outWidth to options.outHeight
    }

    /** 是否视频文件：按内容探测（能读到视频轨），不依赖扩展名。 */
    fun isVideoFile(path: String): Boolean =
        runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "1"
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrDefault(false)

    /** 视频时长（毫秒）。 */
    fun videoDurationMs(path: String): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
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
}
