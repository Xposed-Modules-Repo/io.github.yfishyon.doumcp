package io.github.yfishyon.doumcp.core

import org.luckypray.dexkit.DexKitBridge
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
                val parts = cached.split("#", limit = 3)
                val className = parts[0]
                val methodName = parts.getOrNull(1) ?: ""
                val paramTypes = if (parts.size == 3) parts[2] else null
                restoreMethod(HostRuntime.requireClassLoader(), className, methodName, paramTypes)?.let {
                    methodCache[feature] = it
                    if (paramTypes == null) {
                        // 老格式缓存（无参数类型）就地升级，避免同名重载还原错
                        val upgraded = it.parameterTypes.joinToString(",") { type -> type.name }
                        ResolvedCache.save(context, cacheKey, "${it.declaringClass.name}#${it.name}#$upgraded")
                    }
                    return it
                }
            }
        }

        val dexKit = dexKit() ?: return null
        val method = find(dexKit) ?: return null

        methodCache[feature] = method
        val paramTypes = method.parameterTypes.joinToString(",") { it.name }
        context?.let {
            ResolvedCache.save(it, cacheKey, "${method.declaringClass.name}#${method.name}#$paramTypes")
        }
        return method
    }

    /** 宿主 APK 路径（未初始化时为 null） */
    val apkPath: String?
        get() = hostApkPath

    /** 宿主版本号（未初始化时为 -1） */
    val hostVersion: Long
        get() = hostVersionCode

    /** 已建好的桥（不触发索引构建）；供只读状态查询用 */
    fun bridgeIfReady(): DexKitBridge? = bridge

    /** 取桥并按需懒建（复用同一实例与线程配置）；供 devtool 直接跑查询 */
    fun bridgeOrNull(): DexKitBridge? = dexKit()

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

    /**
     * 按缓存的「类名#方法名#参数类型」在宿主里还原 Method（类或方法消失时返回 null）。
     *
     * 参数类型为 null 表示老格式缓存：仅当同名方法唯一时才认，否则当作未命中重新定位并写新格式。
     */
    private fun restoreMethod(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        paramTypes: String?,
    ): Method? =
        runCatching {
            val candidates =
                Class
                    .forName(className, false, classLoader)
                    .declaredMethods
                    .filter { it.name == methodName }
            if (paramTypes == null) {
                // 老格式：仅当同名方法唯一才认，避免同名重载取错（不确定就当 miss 重新定位）
                candidates.singleOrNull()
            } else {
                candidates.firstOrNull { it.parameterTypes.joinToString(",") { type -> type.name } == paramTypes }
            }
        }.getOrNull()
}
