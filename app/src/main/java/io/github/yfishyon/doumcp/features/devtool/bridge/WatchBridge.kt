package io.github.yfishyon.doumcp.features.devtool.bridge

import io.github.libxposed.api.XposedInterface
import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.ValueFormat
import io.github.yfishyon.doumcp.features.devtool.resolver.Expr
import io.github.yfishyon.doumcp.features.devtool.resolver.TargetResolver
import io.github.yfishyon.doumcp.features.devtool.resolver.matchTypeNames
import io.github.yfishyon.doumcp.features.devtool.resolver.paramsText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 动态 hook 桥：给宿主方法挂观察/篡改 hook，记录每次调用的参数、返回值、耗时与（可选）调用栈。
 *
 * 用满 libxposed：`setId` 支持同 id 原子替换（对同一目标再次 watch 即替换，不摘再挂）、
 * `hookClassInitializer` 挂 `<clinit>`、`Chain.proceedWith` 换 `this`、`ExceptionMode` 可切严格模式、
 * `deoptimize` 反内联调用方。
 *
 * 记录在**回调当场**就转成文本，不长期强引用宿主对象；环形缓冲满后不再抓栈（省开销）。
 * 摘除在工具线程同步执行（不在 hook 回调栈内，安全）。
 */
object WatchBridge {
    private const val MAX_STACK_DEPTH = 64
    private const val MAX_RECORDS = 10000

    private val watches = ConcurrentHashMap<String, Watch>()
    private val idSeq = AtomicLong(0)

    /** 安装串行化：确保「按 label 查已有 → 分配 id → 注册」在同一临界区完成。 */
    private val installLock = Any()

    private class Condition(
        val argIndex: Int,
        val equals: String?,
    )

    private class Record(
        val seq: Long,
        val timeMs: Long,
        val thread: String,
        val selfText: String,
        val argsText: List<String>,
        val resultText: String,
        val error: String?,
        val costMs: Double,
        val stack: List<String>?,
    )

    private class Watch(
        val id: String,
        val label: String,
        val queryClass: String,
        val queryMethod: String?,
        val captureStack: Boolean,
        val stackDepth: Int,
        val maxCalls: Int,
        val exceptionMode: XposedInterface.ExceptionMode,
        val priority: Int,
        val argOverrides: Map<Int, JsonElement>,
        val returnOverride: JsonElement?,
        val thisOverride: JsonElement?,
        val condition: Condition?,
    ) {
        lateinit var handle: XposedInterface.HookHandle

        val hits = AtomicLong(0)
        val dropped = AtomicLong(0)
        private val records = ArrayDeque<Record>()
        private var seq = 0L

        /** 入队（序号分配与入队在同一临界区，保证序号与插入顺序一致）。 */
        fun add(
            timeMs: Long,
            thread: String,
            selfText: String,
            argsText: List<String>,
            resultText: String,
            error: String?,
            costMs: Double,
            stack: List<String>?,
        ) {
            synchronized(records) {
                val next = ++seq
                records.addLast(
                    Record(next, timeMs, thread, selfText, argsText, resultText, error, costMs, stack),
                )
                while (records.size > maxCalls) {
                    records.removeFirst()
                    dropped.incrementAndGet()
                }
            }
        }

        fun isFull(): Boolean = synchronized(records) { records.size >= maxCalls }

        /** 缓冲已满时只记丢弃次数，不再做值文本化（热方法省 CPU）。 */
        fun noteDropped() {
            dropped.incrementAndGet()
        }

        fun after(
            cursor: Long,
            limit: Int?,
        ): List<Record> =
            synchronized(records) {
                val filtered = records.filter { it.seq > cursor }
                if (limit == null || limit <= 0) filtered else filtered.take(limit)
            }

        fun brief(): JSONObject =
            JSONObject()
                .put("watchId", id)
                .put("target", label)
                .put("hits", hits.get())
                .put("dropped", dropped.get())
                .put("captureStack", captureStack)
                .put("maxCalls", maxCalls)
                .put("exceptionMode", exceptionMode.name)
                .put("priority", priority)
                .put("hasArgOverrides", argOverrides.isNotEmpty())
                .put("hasReturnOverride", returnOverride != null)
                .put("hasThisOverride", thisOverride != null)
    }

    // ==================== 安装 / 摘除 ====================

    fun watchJson(
        clazz: String,
        method: String?,
        paramTypes: List<String>?,
        kind: String,
        captureStack: Boolean,
        stackDepth: Int,
        maxCalls: Int,
        exceptionMode: String?,
        priority: Int?,
        condition: JsonObject?,
        argOverrides: JsonObject?,
        returnOverride: JsonElement?,
        hasReturnOverride: Boolean,
        thisOverride: JsonElement?,
        hasThisOverride: Boolean,
    ): String {
        val target = TargetResolver.loadClass(clazz)
        val kindName = kind.lowercase()
        if (kindName !in listOf("method", "ctor", "constructor", "init", "clinit")) {
            throw IllegalStateException("kind 只能是 method/ctor/constructor/init/clinit，实际: $kind")
        }
        val isClassInitializer = kindName == "clinit"
        val executable =
            if (isClassInitializer) null else resolveExecutable(target, method, paramTypes, kindName)
        val label = if (isClassInitializer) "${target.name}#<clinit>" else labelOf(executable!!)

        if (hasReturnOverride) {
            val isConstruction = isClassInitializer || executable is Constructor<*>
            if (isConstruction) {
                throw IllegalStateException("构造器 / <clinit> 不能强制返回值（returnOverride）")
            }
            if (executable is Method && executable.returnType == Void.TYPE) {
                throw IllegalStateException("void 方法不能强制返回值（returnOverride）")
            }
        }
        if (hasThisOverride && (
                isClassInitializer || executable?.let {
                    java.lang.reflect.Modifier
                        .isStatic(it.modifiers)
                } == true
            )
        ) {
            throw IllegalStateException("静态方法 / <clinit> 不能替换 this（thisOverride）")
        }

        val paramCount = executable?.parameterCount ?: 0
        val parsedCondition =
            condition?.let {
                val equalsElement = it["equals"]
                val argIndex =
                    (it["argIndex"] as? JsonPrimitive)?.content?.toIntOrNull()
                        ?: throw IllegalStateException("condition.argIndex 需要整数")
                if (argIndex !in 0 until paramCount) {
                    throw IllegalStateException("condition.argIndex=$argIndex 越界（该方法参数个数 $paramCount）")
                }
                Condition(
                    argIndex = argIndex,
                    equals = if (equalsElement is JsonNull) null else (equalsElement as? JsonPrimitive)?.content,
                )
            }
        val parsedOverrides: Map<Int, JsonElement> =
            argOverrides?.entries?.associate { (key, value) ->
                val index =
                    key.toIntOrNull()
                        ?: throw IllegalStateException("argOverrides 的键必须是参数下标: $key")
                if (index !in 0 until paramCount) {
                    throw IllegalStateException("argOverrides 下标 $index 越界（该方法参数个数 $paramCount）")
                }
                index to value
            } ?: emptyMap()

        val watch =
            synchronized(installLock) {
                val existing = watches.values.firstOrNull { it.label == label }
                val created =
                    Watch(
                        id = existing?.id ?: "watch:${idSeq.incrementAndGet()}",
                        label = label,
                        queryClass = clazz,
                        queryMethod = method,
                        captureStack = captureStack,
                        stackDepth = stackDepth.coerceIn(1, MAX_STACK_DEPTH),
                        maxCalls = maxCalls.coerceIn(1, MAX_RECORDS),
                        exceptionMode = exceptionMode(exceptionMode),
                        priority = priority ?: XposedInterface.PRIORITY_DEFAULT,
                        argOverrides = parsedOverrides,
                        returnOverride = if (hasReturnOverride) returnOverride else null,
                        thisOverride = if (hasThisOverride) thisOverride else null,
                        condition = parsedCondition,
                    )
                val hooker = XposedInterface.Hooker { chain -> intercept(created, chain) }
                val builder =
                    if (isClassInitializer) {
                        module().hookClassInitializer(target)
                    } else {
                        module().hook(executable!!)
                    }
                // 同一目标复用 id：libxposed 按相同 id 原子替换并返回有效的新句柄
                created.handle =
                    builder
                        .setId(created.id)
                        .setPriority(priority ?: XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(created.exceptionMode)
                        .intercept(hooker)
                watches[created.id] = created
                ModLog.i("devtool：挂 hook ${created.id} ${created.label}（replaced=${existing != null}）")
                created
            }

        return JSONObject()
            .put("ok", true)
            .put("watchId", watch.id)
            .put("target", watch.label)
            .put("captureStack", watch.captureStack)
            .put("maxCalls", watch.maxCalls)
            .put("exceptionMode", watch.exceptionMode.name)
            .put("hasArgOverrides", watch.argOverrides.isNotEmpty())
            .put("hasReturnOverride", watch.returnOverride != null)
            .put("hasThisOverride", watch.thisOverride != null)
            .toString()
    }

    fun unwatchJson(
        watchId: String?,
        clazz: String?,
        method: String?,
    ): String {
        // 移表与摘钩同处 installLock 内：既不与并发安装交错（避免瞬时双 hook），
        // 也不会死锁——hook 回调 intercept() 从不获取 installLock。
        val removed = JSONArray()
        synchronized(installLock) {
            val targets: List<Watch> =
                when {
                    !watchId.isNullOrBlank() -> {
                        listOfNotNull(watches.remove(watchId))
                    }

                    !clazz.isNullOrBlank() && !method.isNullOrBlank() -> {
                        val matched = watches.values.filter { it.queryClass == clazz && it.queryMethod == method }
                        matched.forEach { watches.remove(it.id) }
                        matched
                    }

                    else -> {
                        throw IllegalStateException("需要 watchId，或 class + method")
                    }
                }
            if (targets.isEmpty()) throw IllegalStateException("没有匹配的 watch")
            for (watch in targets) {
                runCatching { watch.handle.unhook() }
                    .onFailure { ModLog.e("devtool：摘 hook 失败 ${watch.label}", it) }
                removed.put(watch.id)
                ModLog.i("devtool：摘 hook ${watch.id} ${watch.label}")
            }
        }
        return JSONObject().put("ok", true).put("removed", removed).toString()
    }

    fun listJson(): String {
        val arr = JSONArray()
        for (watch in watches.values) arr.put(watch.brief())
        return JSONObject()
            .put("ok", true)
            .put("count", arr.length())
            .put("watches", arr)
            .toString()
    }

    fun traceLogJson(
        watchId: String,
        cursor: Long,
        limit: Int?,
    ): String {
        val watch = watches[watchId] ?: throw IllegalStateException("watch 不存在: $watchId")
        val records = watch.after(cursor, limit)
        val arr = JSONArray()
        var maxSeq = cursor
        for (record in records) {
            maxSeq = maxOf(maxSeq, record.seq)
            val json =
                JSONObject()
                    .put("seq", record.seq)
                    .put("timeMs", record.timeMs)
                    .put("thread", record.thread)
                    .put("self", record.selfText)
                    .put("args", JSONArray(record.argsText))
                    .put("result", record.resultText)
                    .put("costMs", record.costMs)
            record.error?.let { json.put("error", it) }
            record.stack?.let { json.put("stack", JSONArray(it)) }
            arr.put(json)
        }
        return JSONObject()
            .put("ok", true)
            .put("watchId", watchId)
            .put("target", watch.label)
            .put("hits", watch.hits.get())
            .put("dropped", watch.dropped.get())
            .put("nextCursor", maxSeq)
            .put("returned", arr.length())
            .put("records", arr)
            .toString()
    }

    /**
     * 反内联：短方法被内联时 hook 不触发，需反内联其**调用方**。
     * 用 DexKit 调用关系找出全部调用方逐个反内联（API 语义即反内联调用方）。
     */
    fun deoptimizeJson(
        clazz: String,
        method: String,
        paramTypes: List<String>?,
    ): String {
        val module = module()
        val callers = callerExecutables(clazz, method, paramTypes)
        val entries = JSONArray()
        for (caller in callers) {
            val json = JSONObject().put("method", labelOf(caller))
            runCatching { module.deoptimize(caller) }
                .onSuccess {
                    json.put("deoptimized", it)
                    ModLog.i("devtool：反内联 ${labelOf(caller)} -> $it")
                }.onFailure {
                    json.put("deoptimized", false).put("error", it.toString())
                }
            entries.put(json)
        }
        return JSONObject()
            .put("ok", true)
            .put("count", entries.length())
            .put("entries", entries)
            .put(
                "hint",
                if (callers.isEmpty()) {
                    "DexKit 未找到该方法的调用方（可能由框架或其它 dex 调用），无法按调用方反内联"
                } else {
                    "已按 DexKit 的调用关系反内联全部调用方（hook 不触发通常是调用方内联了短方法）"
                },
            ).toString()
    }

    /** 热重载前调用：清空注册表。摘钩子交给 onHotReloaded 对 oldHookHandles 的统一处理。 */
    fun stop() {
        synchronized(installLock) { watches.clear() }
    }

    // ==================== 拦截逻辑 ====================

    private class CallOutcome(
        val result: Any?,
        val args: List<Any?>,
    )

    private fun intercept(
        watch: Watch,
        chain: XposedInterface.Chain,
    ): Any? {
        val condition = watch.condition
        if (condition != null && !conditionMatches(condition, chain)) return chain.proceed()

        watch.hits.incrementAndGet()
        val startNs = System.nanoTime()
        var result: Any? = null
        var error: String? = null
        var actualArgs: List<Any?> = chain.args
        try {
            val outcome = call(watch, chain)
            result = outcome.result
            actualArgs = outcome.args
        } catch (t: Throwable) {
            error = t.toString()
            throw t
        } finally {
            // 记录失败不得影响被 hook 方法的结果/异常
            runCatching {
                if (watch.isFull()) {
                    watch.noteDropped()
                    return@runCatching
                }
                watch.add(
                    timeMs = System.currentTimeMillis(),
                    thread = Thread.currentThread().name,
                    selfText = ValueFormat.describe(chain.thisObject),
                    argsText = actualArgs.map { ValueFormat.describe(it) },
                    resultText = ValueFormat.describe(result),
                    error = error,
                    costMs = (System.nanoTime() - startNs) / 1_000_000.0,
                    stack = if (watch.captureStack) captureStack(watch.stackDepth) else null,
                )
            }.onFailure { ModLog.w("devtool: hook 记录失败 ${watch.label}", it) }
        }
        return result
    }

    private fun call(
        watch: Watch,
        chain: XposedInterface.Chain,
    ): CallOutcome {
        watch.returnOverride?.let { override ->
            return CallOutcome(Expr.eval(override, returnTypeOf(chain.executable)), chain.args)
        }
        val newThis =
            watch.thisOverride?.let { Expr.eval(it, chain.executable.declaringClass) }
        if (watch.argOverrides.isEmpty()) {
            return if (newThis != null) {
                CallOutcome(chain.proceedWith(newThis), chain.args)
            } else {
                CallOutcome(chain.proceed(), chain.args)
            }
        }

        val args = chain.args.toMutableList()
        val types = chain.executable.parameterTypes
        for ((index, expression) in watch.argOverrides) {
            if (index !in args.indices) throw IllegalStateException("argOverrides 下标越界: $index")
            args[index] = Expr.eval(expression, types[index])
        }
        val newArgs = args.toTypedArray()
        return if (newThis != null) {
            CallOutcome(chain.proceedWith(newThis, newArgs), args)
        } else {
            CallOutcome(chain.proceed(newArgs), args)
        }
    }

    private fun conditionMatches(
        condition: Condition,
        chain: XposedInterface.Chain,
    ): Boolean {
        val args = chain.args
        if (condition.argIndex !in args.indices) return false
        val value = args[condition.argIndex]
        val expected = condition.equals
        if (expected == null) return value == null
        return when (value) {
            null -> false
            is String -> value == expected
            is Number -> expected.toDoubleOrNull()?.let { value.toDouble() == it } ?: (value.toString() == expected)
            is Boolean -> value.toString() == expected
            else -> value.toString() == expected
        }
    }

    private fun captureStack(depth: Int): List<String> =
        Thread
            .currentThread()
            .stackTrace
            .take(depth)
            .map { it.toString() }

    private fun returnTypeOf(executable: Executable): Class<*> = if (executable is Method) executable.returnType else Void.TYPE

    private fun resolveExecutable(
        clazz: Class<*>,
        method: String?,
        paramTypes: List<String>?,
        kind: String,
    ): Executable =
        when (kind) {
            "ctor", "constructor", "init" -> {
                TargetResolver.findConstructor(clazz, paramTypes)
            }

            else -> {
                if (method.isNullOrBlank()) throw IllegalStateException("kind=method 时需要 method 参数")
                TargetResolver.findMethod(clazz, method, paramTypes)
            }
        }

    /** 目标方法的调用方（DexKit 调用关系）；同名重载取并集，构造器按构造器解析。 */
    private fun callerExecutables(
        clazz: String,
        method: String,
        paramTypes: List<String>?,
    ): List<Executable> {
        val bridge = DexKitSupport.bridgeOrNull() ?: return emptyList()
        val classData = runCatching { bridge.getClassData(clazz) }.getOrNull() ?: return emptyList()
        val candidates =
            classData.methods
                .filter { it.name == method }
                .filter { paramTypes == null || it.paramTypeNames.matchTypeNames(paramTypes) }
        val result = LinkedHashMap<String, Executable>()
        for (candidate in candidates) {
            for (caller in candidate.callers) {
                runCatching {
                    val owner = TargetResolver.loadClass(caller.className)
                    val resolved =
                        when {
                            caller.isConstructor -> TargetResolver.findConstructor(owner, caller.paramTypeNames)
                            caller.isStaticInitializer -> null
                            else -> TargetResolver.findMethod(owner, caller.name, caller.paramTypeNames)
                        }
                    resolved?.let { result.putIfAbsent(labelOf(it), it) }
                }
            }
        }
        return result.values.toList()
    }

    private fun labelOf(executable: Executable): String {
        val owner = executable.declaringClass.name
        return when (executable) {
            is Constructor<*> -> "$owner#<init>${executable.paramsText()}"
            else -> "$owner#${executable.name}${executable.paramsText()}"
        }
    }

    private fun exceptionMode(name: String?): XposedInterface.ExceptionMode =
        when (name?.trim()?.lowercase()) {
            null, "", "protective" -> XposedInterface.ExceptionMode.PROTECTIVE
            "passthrough" -> XposedInterface.ExceptionMode.PASSTHROUGH
            "default" -> XposedInterface.ExceptionMode.DEFAULT
            else -> throw IllegalStateException("不支持的 exceptionMode: $name（protective(默认) / passthrough / default）")
        }

    private fun module(): XposedInterface = HostRuntime.requireModule()
}
