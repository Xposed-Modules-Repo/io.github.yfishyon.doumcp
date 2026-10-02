package io.github.yfishyon.doumcp.core

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method

/**
 * DexKit 共享设施：桥懒加载，定位结果走「内存 + FastKV（按宿主版本隔离）」两级缓存。
 *
 * 缓存全命中时不创建 DexKit 索引；查询并发参数在此统一设置。
 */
object DexKitSupport {
    /** 宿主视频模型类（未混淆固定类名，多个功能包用作字段类型锚点） */
    const val FEED_AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme"

    @Volatile
    private var bridge: DexKitBridge? = null

    @Volatile
    private var hostApkPath: String? = null

    @Volatile
    private var moduleApkPath: String? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var hostVersionCode: Long = -1

    private val methodCache = java.util.concurrent.ConcurrentHashMap<String, Method>()

    /**
     * 记录宿主与模块信息（幂等，仅首次生效）；真正的 DexKit 桥在首次查询时才创建。
     *
     * @param hostApkPath 宿主 APK 路径
     * @param moduleApkPath 模块 APK 路径（加载 dexkit so 用）
     * @param hostVersionCode 宿主版本号（缓存隔离用）
     */
    fun init(
        hostApkPath: String,
        moduleApkPath: String,
        hostVersionCode: Long,
    ) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            this.hostApkPath = hostApkPath
            this.moduleApkPath = moduleApkPath
            this.hostVersionCode = hostVersionCode
            initialized = true
        }
        ModLog.i("DexKit：宿主版本=$hostVersionCode, apk=$hostApkPath")
    }

    /**
     * 按功能定位方法：内存缓存 → FastKV 缓存（按宿主版本隔离）→ DexKit 查询并回写。
     *
     * @param feature 功能名（作缓存键的一部分，保持稳定）
     * @param find 缓存未命中时的 DexKit 查询逻辑（锚点必须全等匹配）
     * @return 定位到的方法；DexKit 未就绪或未命中时返回 null
     */
    fun resolveCached(
        feature: String,
        find: (DexKitBridge) -> Method?,
    ): Method? {
        methodCache[feature]?.let { return it }
        if (!initialized) return null

        val context = HostRuntime.context
        val cacheKey = ResolvedCache.versionedKey(hostVersionCode, feature)
        if (context != null) {
            ResolvedCache.load(context, cacheKey)?.let { cached ->
                val (className, methodName) = cached.split("#", limit = 2)
                restoreMethod(HostRuntime.requireClassLoader(), className, methodName)?.let {
                    methodCache[feature] = it
                    return it
                }
            }
        }

        val dexKit = dexKit() ?: return null
        val method = find(dexKit) ?: return null

        methodCache[feature] = method
        context?.let { ResolvedCache.save(it, cacheKey, "${method.declaringClass.name}#${method.name}") }
        return method
    }

    private fun dexKit(): DexKitBridge? {
        bridge?.let { return it }
        synchronized(this) {
            bridge?.let { return it }
            val apk = hostApkPath ?: return null
            val moduleApk = moduleApkPath ?: return null
            ModLog.i("DexKit：缓存未命中，开始查询dex")
            loadDexKitLibrary(moduleApk)
            val created =
                runCatching { DexKitBridge.create(apk) }
                    .onFailure { ModLog.e("DexKit：加载dexkit失败", it) }
                    .getOrNull() ?: return null
            created.setMaxConcurrentQueries(0)
            created.setThreadNum(minOf(8, Runtime.getRuntime().availableProcessors()))
            bridge = created
            return created
        }
    }

    /** 优先系统方式加载 dexkit so，失败再从模块 APK 内加载 */
    private fun loadDexKitLibrary(moduleApkPath: String) {
        runCatching { System.loadLibrary("dexkit") }.onSuccess { return }
        runCatching { System.load("$moduleApkPath!/lib/arm64-v8a/libdexkit.so") } // todo: 理论上上面的走mmap如果能加载，那么下面的也肯定可以。但我没做手动解压目录手动load兜底
    }

    private fun resolveClassName(
        feature: String,
        find: (DexKitBridge) -> String?,
    ): String? {
        val context = HostRuntime.context
        val cacheKey = ResolvedCache.versionedKey(hostVersionCode, feature)
        if (context != null) {
            ResolvedCache.load(context, cacheKey)?.let { return it }
        }
        val dexKit = dexKit() ?: return null
        val name = find(dexKit) ?: return null
        context?.let { ResolvedCache.save(it, cacheKey, name) }
        return name
    }

    /** 按缓存的「类名#方法名」在宿主里还原 Method（类或方法消失时返回 null） */
    private fun restoreMethod(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
    ): Method? =
        runCatching {
            Class
                .forName(className, false, classLoader)
                .declaredMethods
                .firstOrNull { it.name == methodName }
        }.getOrNull()
}
