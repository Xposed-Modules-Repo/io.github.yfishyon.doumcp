package io.github.yfishyon.doumcp.features.devtool.bridge

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ObjectDump
import io.github.yfishyon.doumcp.core.ValueFormat
import io.github.yfishyon.doumcp.features.devtool.resolver.Expr
import io.github.yfishyon.doumcp.features.devtool.resolver.InvokerSupport
import io.github.yfishyon.doumcp.features.devtool.resolver.TargetResolver
import io.github.yfishyon.doumcp.features.devtool.resolver.matchParams
import io.github.yfishyon.doumcp.features.devtool.resolver.signatureText
import io.github.yfishyon.doumcp.features.devtool.resolver.typeText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.security.MessageDigest

/**
 * 运行时观测与执行桥：真实反射读写、类加载器、枚举、静态字段、对象构造与调用、动态代理、
 * 线程与 Activity 堆栈、进程信息、堆快照、SharedPreferences、包信息。
 *
 * 全部只在**当前进程**（MCP 所在进程）生效：宿主子进程里的类与对象在本进程不可见。
 */
object RuntimeBridge {
    // ==================== 进程与运行态 ====================

    fun runtimeInfoJson(): String {
        val context = HostRuntime.requireContext()
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)

        val processes = JSONArray()
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (info in manager.runningAppProcesses ?: emptyList()) {
                if (info.processName.startsWith(context.packageName)) {
                    processes.put(JSONObject().put("name", info.processName).put("pid", info.pid))
                }
            }
        }

        val framework = JSONObject()
        runCatching {
            val module = HostRuntime.requireModule()
            framework.put("name", module.frameworkName)
            framework.put("version", module.frameworkVersion)
            framework.put("apiVersion", module.apiVersion)
        }.onFailure { framework.put("error", it.toString()) }

        return JSONObject()
            .put("ok", true)
            .put("processName", HostRuntime.processName)
            .put("framework", framework)
            .put("pid", android.os.Process.myPid())
            .put("uid", android.os.Process.myUid())
            .put("packageName", context.packageName)
            .put("hostVersion", HostRuntime.versionCode)
            .put("threadCount", Thread.activeCount())
            .put("loadedClassCount", runCatching { android.os.Debug.getLoadedClassCount() }.getOrDefault(-1))
            .put("heapUsedMb", usedMb)
            .put("heapMaxMb", maxMb)
            .put("appProcesses", processes)
            .put("hint", "运行时工具只对当前进程生效；其它进程（如 IM、推送）里的类与对象在本进程不可见")
            .toString()
    }

    // ==================== 类结构 ====================

    fun reflectClassJson(
        clazz: String,
        withValues: Boolean,
    ): String {
        val target = TargetResolver.loadClass(clazz)
        val fields = JSONArray()
        for (field in TargetResolver.staticFieldsOf(target)) {
            fields.put(fieldJson(field, null, withValues))
        }
        var current: Class<*>? = target
        val instanceFields = JSONArray()
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                instanceFields.put(fieldJson(field, null, withValues))
            }
            current = current.superclass
        }

        val methods = JSONArray()
        for (method in TargetResolver.methodsOf(target)) {
            methods.put(
                JSONObject()
                    .put("name", method.name)
                    .put("sign", method.signatureText())
                    .put("modifiers", Modifier.toString(method.modifiers)),
            )
        }

        val interfaces = JSONArray()
        for (iface in target.interfaces) interfaces.put(iface.name)

        return JSONObject()
            .put("ok", true)
            .put("name", target.name)
            .put("classLoader", target.classLoader?.javaClass?.name ?: JSONObject.NULL)
            .put("isInterface", target.isInterface)
            .put("isEnum", target.isEnum)
            .put("isAbstract", Modifier.isAbstract(target.modifiers))
            .put("super", target.superclass?.name ?: JSONObject.NULL)
            .put("interfaces", interfaces)
            .put("staticFieldCount", fields.length())
            .put("staticFields", fields)
            .put("instanceFieldCount", instanceFields.length())
            .put("instanceFields", instanceFields)
            .put("methodCount", methods.length())
            .put("methods", methods)
            .toString()
    }

    fun listClassLoadersJson(clazz: String?): String {
        val arr = JSONArray()
        var loader: ClassLoader? =
            if (clazz.isNullOrBlank()) {
                HostRuntime.requireClassLoader()
            } else {
                TargetResolver.loadClass(clazz).classLoader ?: HostRuntime.requireClassLoader()
            }
        var depth = 0
        while (loader != null && depth < 32) {
            arr.put(
                JSONObject()
                    .put("depth", depth)
                    .put("class", loader.javaClass.name)
                    .put("identity", Integer.toHexString(System.identityHashCode(loader)))
                    .put("toString", ValueFormat.describe(loader)),
            )
            loader = loader.parent
            depth++
        }
        return JSONObject().put("ok", true).put("loaders", arr).toString()
    }

    fun loadClassJson(clazz: String): String {
        val target = TargetResolver.loadClass(clazz)
        return JSONObject()
            .put("ok", true)
            .put("name", target.name)
            .put("loaded", true)
            .put("classLoader", target.classLoader?.javaClass?.name ?: JSONObject.NULL)
            .put("isInterface", target.isInterface)
            .put("isEnum", target.isEnum)
            .put("super", target.superclass?.name ?: JSONObject.NULL)
            .put("methodCount", TargetResolver.methodsOf(target).size)
            .toString()
    }

    fun listEnumConstantsJson(clazz: String): String {
        val target = TargetResolver.loadClass(clazz)
        if (!target.isEnum) throw IllegalStateException("不是枚举类: ${target.name}")
        val arr = JSONArray()
        for ((index, constant) in (target.enumConstants ?: emptyArray()).withIndex()) {
            val enumConstant = constant as Enum<*>
            arr.put(JSONObject().put("index", index).put("name", enumConstant.name).put("ordinal", enumConstant.ordinal))
        }
        return JSONObject()
            .put("ok", true)
            .put("name", target.name)
            .put("constants", arr)
            .toString()
    }

    fun dumpStaticsJson(
        clazz: String,
        filter: String?,
    ): String {
        val target = TargetResolver.loadClass(clazz)
        val arr = JSONArray()
        for (field in TargetResolver.staticFieldsOf(target)) {
            if (!filter.isNullOrBlank() && !field.name.contains(filter)) continue
            arr.put(fieldJson(field, null, true))
        }
        return JSONObject()
            .put("ok", true)
            .put("name", target.name)
            .put("fieldCount", arr.length())
            .put("fields", arr)
            .toString()
    }

    // ==================== 对象观测 ====================

    fun inspectObjectJson(
        path: String?,
        handle: String?,
        depth: Int,
    ): String {
        val target = holder(path, handle)
        if (target is Class<*>) {
            return dumpStaticsJson(target.name, null)
        }
        val dumped = ObjectDump.deep(target, depth.coerceIn(0, 64))
        return JSONObject()
            .put("ok", true)
            .put("class", target.javaClass.name)
            .put("identity", Integer.toHexString(System.identityHashCode(target)))
            .put("depth", depth)
            .put("fields", dumped)
            .toString()
    }

    fun readFieldJson(
        path: String?,
        handle: String?,
        field: String,
    ): String {
        val target = holder(path, handle)
        val owner = if (target is Class<*>) target else target.javaClass
        val reflected =
            TargetResolver.findField(owner, field)
                ?: throw IllegalStateException("字段不存在: ${owner.name}.$field")
        val value =
            if (Modifier.isStatic(reflected.modifiers)) {
                reflected.get(null)
            } else {
                if (target is Class<*>) throw IllegalStateException("静态上下文不能取实例字段: ${owner.name}.$field")
                reflected.get(target)
            }
        val (safe, text) = ValueFormat.described(value)
        return JSONObject()
            .put("ok", true)
            .put("owner", owner.name)
            .put("field", field)
            .put("type", reflected.type.typeText())
            .put("isStatic", Modifier.isStatic(reflected.modifiers))
            .put("isFinal", Modifier.isFinal(reflected.modifiers))
            .put("value", safe)
            .put("valueText", text)
            .toString()
    }

    fun writeFieldJson(
        path: String?,
        handle: String?,
        field: String,
        value: kotlinx.serialization.json.JsonElement?,
        force: Boolean,
    ): String {
        val target = holder(path, handle)
        val owner = if (target is Class<*>) target else target.javaClass
        val reflected =
            TargetResolver.findField(owner, field)
                ?: throw IllegalStateException("字段不存在: ${owner.name}.$field")
        val isStatic = Modifier.isStatic(reflected.modifiers)
        if (!isStatic && target is Class<*>) {
            throw IllegalStateException("静态上下文不能写实例字段: ${owner.name}.$field")
        }
        val previous = runCatching { reflected.get(if (isStatic) null else target) }.getOrNull()
        val converted = Expr.eval(value, reflected.type)

        var finalStripped = false
        if (force) {
            finalStripped = stripFinal(reflected)
        }
        try {
            reflected.set(if (isStatic) null else target, converted)
        } catch (e: IllegalAccessException) {
            throw IllegalStateException(
                "写入被拒绝（字段为 final？）: ${owner.name}.$field —— 用 writeField 并设 force=true 尝试绕过 final（$e）",
            )
        }
        val (beforeSafe, beforeText) = ValueFormat.described(previous)
        val (afterSafe, afterText) = ValueFormat.described(converted)
        return JSONObject()
            .put("ok", true)
            .put("owner", owner.name)
            .put("field", field)
            .put("type", reflected.type.typeText())
            .put("isStatic", isStatic)
            .put("finalStripped", finalStripped)
            .put("before", beforeSafe)
            .put("beforeText", beforeText)
            .put("after", afterSafe)
            .put("afterText", afterText)
            .toString()
    }

    // ==================== 构造与调用 ====================

    fun newInstanceJson(
        clazz: String,
        args: JsonArray?,
    ): String {
        val instance = Expr.newInstance(clazz, args)
        val id = ObjectHandles.put(instance)
        return JSONObject()
            .put("ok", true)
            .put("class", instance.javaClass.name)
            .put("handle", id)
            .put("describe", ValueFormat.describe(instance))
            .put("fields", ObjectDump.deep(instance, 2))
            .toString()
    }

    fun invokeMethodJson(
        path: String?,
        handle: String?,
        method: String,
        args: JsonArray?,
        argTypes: List<String>?,
        asHandle: Boolean,
        special: Boolean,
        chainFull: Boolean = false,
    ): String {
        val target = holder(path, handle)
        val staticContext = target is Class<*>
        if (special && staticContext) throw IllegalStateException("静态上下文不能做非虚调用（special）")
        val owner = if (staticContext) target as Class<*> else target.javaClass
        val items = args ?: JsonArray(emptyList())
        var candidates = TargetResolver.methodsOf(owner).filter { it.name == method }
        if (candidates.isEmpty()) throw IllegalStateException("方法不存在: ${owner.name}.$method")
        if (argTypes != null) {
            candidates = candidates.filter { it.matchParams(argTypes) }
        } else {
            candidates = candidates.filter { it.parameterCount == items.size }
        }
        if (staticContext) {
            val staticOnly = candidates.filter { Modifier.isStatic(it.modifiers) }
            if (staticOnly.isEmpty()) {
                throw IllegalStateException("静态上下文不能调实例方法: ${owner.name}.$method（请给出对象路径或句柄）")
            }
            candidates = staticOnly
        }
        if (candidates.isEmpty()) {
            throw IllegalStateException(
                "参数不匹配: ${owner.name}.$method，候选：${
                    TargetResolver.methodsOf(owner).filter { it.name == method }.joinToString("; ") { it.signatureText() }
                }",
            )
        }

        var lastError: Throwable? = null
        var lastCause: Throwable? = null
        for (candidate in candidates) {
            try {
                val values = Expr.evalArgs(items, candidate.parameterTypes)
                val receiver = if (Modifier.isStatic(candidate.modifiers)) null else target
                val result = InvokerSupport.invoke(receiver, candidate, values, special, chainFull)
                val (resultSafe, resultText) = ValueFormat.described(result)
                val json =
                    JSONObject()
                        .put("ok", true)
                        .put("class", owner.name)
                        .put("method", method)
                        .put("sign", candidate.signatureText())
                        .put("static", Modifier.isStatic(candidate.modifiers))
                        .put("special", special)
                        .put("chainFull", chainFull)
                        .put("result", resultSafe)
                        .put("resultText", resultText)
                if (asHandle && result != null && result !is String && result !is Number && result !is Boolean && result !is Class<*>) {
                    json.put("handle", ObjectHandles.put(result))
                }
                return json.toString()
            } catch (t: java.lang.reflect.InvocationTargetException) {
                // 目标方法已执行并自身抛出：直接解包抛出，不再尝试其它重载
                throw (t.cause ?: t)
            } catch (t: Throwable) {
                lastError = t
                lastCause = t.cause ?: t
            }
        }
        throw IllegalStateException("调用失败: ${owner.name}.$method（$lastCause）")
    }

    fun dynamicProxyJson(
        interfaces: List<String>,
        returns: JsonObject?,
    ): String {
        val classes = interfaces.filter { it.isNotBlank() }.map { TargetResolver.loadClass(it) }
        val proxy = Expr.createProxy(classes, returns)
        val id = ObjectHandles.put(proxy)
        return JSONObject()
            .put("ok", true)
            .put("handle", id)
            .put("interfaces", JSONArray(classes.map { it.name }))
            .put("returns", returns?.keys?.toList() ?: emptyList<String>())
            .put("hint", "把 handle 传进需要的接口参数即可；未配置的方法按返回类型给默认值")
            .toString()
    }

    // ==================== 堆栈与界面 ====================

    fun dumpStackJson(
        thread: String?,
        packageFilter: String?,
    ): String {
        val threads = JSONArray()
        for ((worker, stack) in Thread.getAllStackTraces()) {
            if (!thread.isNullOrBlank() && !worker.name.contains(thread)) continue
            val frames = JSONArray()
            for (frame in stack) {
                if (!packageFilter.isNullOrBlank() && !frame.className.contains(packageFilter)) continue
                frames.put(JSONObject().put("at", frame.toString()))
            }
            if (frames.length() == 0) continue
            threads.put(
                JSONObject()
                    .put("name", worker.name)
                    .put("state", worker.state.name)
                    .put("frames", frames),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("threadFilter", thread ?: JSONObject.NULL)
            .put("packageFilter", packageFilter ?: JSONObject.NULL)
            .put("threads", threads)
            .toString()
    }

    fun dumpActivitiesJson(): String {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current =
            activityThread.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
                ?: throw IllegalStateException("ActivityThread 未就绪")

        val activities = fieldValue(activityThread, current, "mActivities") as? Map<*, *> ?: emptyMap<Any, Any>()
        val arr = JSONArray()
        for ((_, record) in activities) {
            if (record == null) continue
            val activity = fieldValue(record.javaClass, record, "activity")
            val paused = fieldValue(record.javaClass, record, "paused")
            val stopped = fieldValue(record.javaClass, record, "stopped")
            arr.put(
                JSONObject()
                    .put("activity", activity?.javaClass?.name ?: JSONObject.NULL)
                    .put("paused", paused as? Boolean ?: false)
                    .put("stopped", stopped as? Boolean ?: false),
            )
        }

        val resumed = fieldValue(activityThread, current, "mResumedActivity")
        val lastPaused = fieldValue(activityThread, current, "mLastPausedActivity")
        return JSONObject()
            .put("ok", true)
            .put("processName", HostRuntime.processName)
            .put("resumed", resumed?.javaClass?.name ?: JSONObject.NULL)
            .put("lastPaused", lastPaused?.javaClass?.name ?: JSONObject.NULL)
            .put("activityCount", arr.length())
            .put("activities", arr)
            .toString()
    }

    // ==================== 宿主存储与包信息 ====================

    fun dumpHeapJson(path: String?): String {
        val dir = File(path?.takeIf { it.isNotBlank() } ?: "/sdcard/DouMCP")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("输出目录不可写: ${dir.absolutePath}（/sdcard 受存储权限限制，可改 path 参数）")
        }
        val file = File(dir, "doumcp-heap-${System.currentTimeMillis()}.hprof")
        android.os.Debug.dumpHprofData(file.absolutePath)
        return JSONObject()
            .put("ok", true)
            .put("file", file.absolutePath)
            .put("sizeBytes", file.length())
            .put("hint", "堆快照已落盘，可拉到 PC 用 MAT/jhat 分析")
            .toString()
    }

    fun readPreferencesJson(name: String?): String {
        val context = HostRuntime.requireContext()
        val available = sharedPrefNames(context)
        val names = if (!name.isNullOrBlank()) listOf(name) else available
        val result = JSONObject()
        for (prefName in names) {
            val keyValues = JSONObject()
            runCatching {
                val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                for ((key, value) in prefs.all) {
                    keyValues.put(key, ValueFormat.jsonSafe(value))
                }
            }.onFailure { keyValues.put("__error__", it.toString()) }
            result.put(prefName, keyValues)
        }
        return JSONObject()
            .put("ok", true)
            .put("available", JSONArray(available))
            .put("preferences", result)
            .toString()
    }

    @Suppress("DEPRECATION")
    fun packageInfoJson(): String {
        val context = HostRuntime.requireContext()
        val packageName = context.packageName
        val signFlag =
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS or signFlag)
        val signatures = JSONArray()
        val certs =
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners
            } else {
                info.signatures
            }
        for (signature in certs ?: emptyArray()) {
            signatures.put(sha256(signature.toByteArray()))
        }
        val permissions = JSONArray()
        for (permission in info.requestedPermissions ?: emptyArray()) permissions.put(permission)
        return JSONObject()
            .put("ok", true)
            .put("packageName", packageName)
            .put("versionName", info.versionName ?: JSONObject.NULL)
            .put("versionCode", if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
            .put("sourceDir", info.applicationInfo?.sourceDir ?: JSONObject.NULL)
            .put("dataDir", info.applicationInfo?.dataDir ?: JSONObject.NULL)
            .put("signatureSha256", signatures)
            .put("requestedPermissions", permissions)
            .toString()
    }

    // ==================== 内部工具 ====================

    private fun holder(
        path: String?,
        handle: String?,
    ): Any {
        if (!path.isNullOrBlank()) return TargetResolver.resolveHolder(path)
        if (!handle.isNullOrBlank()) {
            return ObjectHandles.get(handle) ?: throw IllegalStateException("句柄不存在或已淘汰: $handle")
        }
        throw IllegalStateException("需要 path 或 handle 参数")
    }

    private fun fieldJson(
        field: Field,
        receiver: Any?,
        withValue: Boolean,
    ): JSONObject {
        val json =
            JSONObject()
                .put("name", field.name)
                .put("type", field.type.typeText())
                .put("isStatic", Modifier.isStatic(field.modifiers))
                .put("isFinal", Modifier.isFinal(field.modifiers))
                .put("modifiers", Modifier.toString(field.modifiers))
        if (withValue) {
            field.isAccessible = true
            val value = runCatching { field.get(if (Modifier.isStatic(field.modifiers)) null else receiver) }.getOrNull()
            val (safe, text) = ValueFormat.described(value)
            json.put("value", safe)
            json.put("valueText", text)
        }
        return json
    }

    private fun fieldValue(
        owner: Class<*>,
        receiver: Any,
        name: String,
    ): Any? =
        runCatching {
            owner.getDeclaredField(name).apply { isAccessible = true }.get(receiver)
        }.getOrNull()

    /** 尝试去掉 final 修饰符（去掉才能写）；失败返回 false，由后续 set 抛出真实原因。 */
    private fun stripFinal(field: Field): Boolean {
        field.isAccessible = true
        return runCatching {
            val modifiers = Field::class.java.getDeclaredField("modifiers")
            modifiers.isAccessible = true
            modifiers.setInt(field, field.modifiers and Modifier.FINAL.inv())
            true
        }.getOrDefault(false)
    }

    private fun sharedPrefNames(context: Context): List<String> {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        return dir
            .listFiles { file -> file.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?.sorted()
            ?: emptyList()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
