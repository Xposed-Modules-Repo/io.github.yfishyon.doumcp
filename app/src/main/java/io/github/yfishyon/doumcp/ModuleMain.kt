package io.github.yfishyon.doumcp

import android.app.Activity
import android.app.Application
import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块入口：进程判断、hook 安装、MCP 服务启动与热重载。
 *
 * 仅在宿主主进程生效；DexKit 初始化投递到自有单线程后台执行，不阻塞 hook 流程。
 */
class ModuleMain : XposedModule() {
    private var hostClassLoader: ClassLoader? = null
    private var appContext: Context? = null
    private val installed = AtomicBoolean(false)
    private val attached = AtomicBoolean(false)

    @Volatile
    private var executor: java.util.concurrent.ExecutorService = newExecutor()

    private fun newExecutor(): java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable).apply { isDaemon = true }
        }

    private fun post(block: Runnable) {
        if (executor.isShutdown) executor = newExecutor()
        executor.execute(block)
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        ModLog.bind(this)
        HostRuntime.processName = param.processName
        ModLog.i("onModuleLoaded: ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (HostRuntime.processName != param.packageName) return
        if (!installed.compareAndSet(false, true)) return

        ModLog.i("onPackageReady: ${param.packageName}")
        hostClassLoader = param.classLoader
        HostRuntime.classLoader = param.classLoader

        hookApplicationAttach()
        hookSettingsPage(param.classLoader)
    }

    /**
     * 热重载前停 MCP 服务（给排空时间）并停自有线程（契约要求），
     * 上下文与 ClassLoader 经 savedInstanceState 带到重载后的新实例。
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        McpServerHost.stop()
        executor.shutdown()
        executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)
        param.setSavedInstanceState(arrayOf(appContext, hostClassLoader))
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        param.oldHookHandles.forEach { it.unhook() }

        val saved = param.savedInstanceState as? Array<*> ?: return
        val context = saved.getOrNull(0) as? Context ?: return
        val classLoader =
            saved.getOrNull(1) as? ClassLoader ?: run {
                ModLog.e("热重载：classLoader 未恢复，MCP 服务不启动")
                return
            }
        hostClassLoader = classLoader

        hookSettingsPage(classLoader)
        startMcpServer(context)
        ModLog.i("热重载完成，MCP 服务已用新代码重启")
    }

    /** 宿主 Application attach 后启动 MCP 服务（只取第一次 attach） */
    private fun hookApplicationAttach() {
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        hook(attach).intercept { chain ->
            // 抖音加载插件时也会创建 Application
            if (attached.compareAndSet(false, true)) {
                val raw = chain.getArg(0) as Context
                startMcpServer(raw.applicationContext ?: raw)
            }
            chain.proceed()
        }
    }

    /** 设置页创建后注入「抖M设置」入口 */
    private fun hookSettingsPage(classLoader: ClassLoader) {
        val onCreate = SettingsInjector.targetCreateMethod(classLoader) ?: return
        hook(onCreate).intercept { chain ->
            chain.proceed()
            (chain.thisObject as? Activity)?.let { SettingsInjector.injectEntry(it) }
        }
    }

    /**
     * 启动 MCP 服务并初始化 DexKit。
     *
     * 总开关关闭时不启动；DexKit 初始化投递到后台线程（建索引耗时不阻塞启动）。
     */
    private fun startMcpServer(appContext: Context) {
        this.appContext = appContext
        HostRuntime.context = appContext
        if (!ModulePrefs.isEnabled(appContext)) return

        val apkPath = appContext.packageResourcePath
        val moduleApkPath = moduleApplicationInfo.sourceDir
        val versionCode =
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).let {
                if (android.os.Build.VERSION.SDK_INT >=
                    28
                ) {
                    it.longVersionCode
                } else {
                    it.versionCode.toLong()
                }
            }

        HostRuntime.versionCode = versionCode
        post {
            runCatching { DexKitSupport.init(apkPath, moduleApkPath, versionCode) }
                .onFailure { ModLog.e("DexKit 初始化失败", it) }
            runCatching { FeatureWarmup.warmUpIfNeeded() }
                .onFailure { ModLog.e("适配预热失败", it) }
        }

        McpServerHost.start(ModulePrefs.getPort(appContext))
    }
}
