package io.github.yfishyon.doumcp.features.devtool.tool

import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.core.boolArg
import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.intArg
import io.github.yfishyon.doumcp.core.jsonArg
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.stringListArg
import io.github.yfishyon.doumcp.core.stringListArgOrNull
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.devtool.bridge.RuntimeBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * devtool 运行时工具注册：读取/修改宿主运行时对象、构造与调用、代理、堆栈与界面、宿主存储。
 *
 * 全部只在当前进程（MCP 所在进程）生效；且这些工具能真实改变宿主状态，后果自负。
 */
internal fun Server.registerDevtoolRuntimeTools() {
    addDouMcpTool(
        name = "runtimeInfo",
        description =
            "当前进程运行态：进程名、pid、uid、宿主版本、线程数、已加载类数、堆内存用量，" +
                "以及本 app 的进程列表。运行时工具只对本进程生效，其它进程（IM、推送等）的类与对象看不到",
    ) { _ ->
        withContext(Dispatchers.IO) {
            toolCall { RuntimeBridge.runtimeInfoJson() }
        }
    }

    addDouMcpTool(
        name = "reflectClass",
        description =
            "加载宿主类（不触发类初始化）并打印真实反射结构：继承链上全部字段（名/类型/静态/final/当前值）与方法签名。" +
                "参数：class（类名或描述符）、withValues（默认 true，读静态字段当前值）" + "。提醒：输出可能很大（含继承链上全部字段与方法）",
    ) { request ->
        val clazz = request.arguments.stringArg("class") ?: return@addDouMcpTool errorResult("缺少 class 参数")
        withContext(Dispatchers.IO) {
            toolCall {
                RuntimeBridge.reflectClassJson(
                    clazz = clazz,
                    withValues = request.arguments.boolArg("withValues", true),
                )
            }
        }
    }

    addDouMcpTool(
        name = "listClassLoaders",
        description = "列出 ClassLoader 链。参数：class（可选，列该类所在 loader 及其父链；缺省列宿主主 loader 链）",
    ) { request ->
        withContext(Dispatchers.IO) {
            toolCall { RuntimeBridge.listClassLoadersJson(request.arguments.stringArg("class")) }
        }
    }

    addDouMcpTool(
        name = "loadClass",
        description =
            "按名加载宿主类（不初始化）并确认其存在：返回所在 ClassLoader、是否接口/枚举、父类、方法数。" +
                "类不在本进程时明确报错。参数：class（类名或描述符）",
    ) { request ->
        val clazz = request.arguments.stringArg("class") ?: return@addDouMcpTool errorResult("缺少 class 参数")
        withContext(Dispatchers.IO) {
            toolCall { RuntimeBridge.loadClassJson(clazz) }
        }
    }

    addDouMcpTool(
        name = "listEnumConstants",
        description = "列出枚举类的常量（名与序号）。参数：class（枚举类全名）",
    ) { request ->
        val clazz = request.arguments.stringArg("class") ?: return@addDouMcpTool errorResult("缺少 class 参数")
        withContext(Dispatchers.IO) {
            toolCall { RuntimeBridge.listEnumConstantsJson(clazz) }
        }
    }

    addDouMcpTool(
        name = "dumpStatics",
        description =
            "列出类的全部静态字段及当前值——找单例入口（类型指向自身的静态字段）最直接。" +
                "参数：class（类全名）、filter（可选，字段名包含该子串才输出）" + "。提醒：字段多时输出可能很大",
    ) { request ->
        val clazz = request.arguments.stringArg("class") ?: return@addDouMcpTool errorResult("缺少 class 参数")
        withContext(Dispatchers.IO) {
            toolCall { RuntimeBridge.dumpStaticsJson(clazz, request.arguments.stringArg("filter")) }
        }
    }

    addDouMcpTool(
        name = "inspectObject",
        description =
            "解析对象路径并深度 dump 字段值（环去重；值不截断）。" +
                "对象路径：`类全名#静态字段.实例字段[下标]...`，如 com.x.Y#sInstance.user.id；" +
                "无 # 时按类处理（等同 dumpStatics）。参数：path 或 handle（二者给一个）、" +
                "depth（默认 2，防递归过深）" + "。提醒：对象图大时输出可能非常大",
    ) { request ->
        withContext(Dispatchers.IO) {
            toolCall {
                RuntimeBridge.inspectObjectJson(
                    path = request.arguments.stringArg("path"),
                    handle = request.arguments.stringArg("handle"),
                    depth = request.arguments.intArg("depth", 2),
                )
            }
        }
    }

    addDouMcpTool(
        name = "readField",
        description =
            "读一个字段。path 为类名时读静态字段，为对象路径/句柄时读实例字段。" +
                "参数：path 或 handle、field（字段名）",
    ) { request ->
        val field = request.arguments.stringArg("field") ?: return@addDouMcpTool errorResult("缺少 field 参数")
        withContext(Dispatchers.IO) {
            toolCall {
                RuntimeBridge.readFieldJson(
                    path = request.arguments.stringArg("path"),
                    handle = request.arguments.stringArg("handle"),
                    field = field,
                )
            }
        }
    }

    addDouMcpTool(
        name = "writeField",
        description =
            "写一个字段（按字段类型转换值，可传表达式）。参数：path 或 handle、field、value（表达式或标量）、" +
                "force（默认 false；true 时尝试去掉 final 再写）。会真实改变宿主状态，后果自负",
    ) { request ->
        val field = request.arguments.stringArg("field") ?: return@addDouMcpTool errorResult("缺少 field 参数")
        withContext(Dispatchers.IO) {
            toolCall {
                RuntimeBridge.writeFieldJson(
                    path = request.arguments.stringArg("path"),
                    handle = request.arguments.stringArg("handle"),
                    field = field,
                    value = request.arguments.jsonArg("value"),
                    force = request.arguments.boolArg("force", false),
                )
            }
        }
    }

    addDouMcpTool(
        name = "newInstance",
        description =
            "构造一个宿主对象，返回对象句柄（obj:N），后续可用 {\"handle\":\"obj:N\"} 传给别的工具。" +
                "参数：class（类全名）、args（参数数组，元素是表达式或标量；表达式见 invokeMethod 说明）",
    ) { request ->
        val clazz = request.arguments.stringArg("class") ?: return@addDouMcpTool errorResult("缺少 class 参数")
        withContext(Dispatchers.IO) {
            toolCall { RuntimeBridge.newInstanceJson(clazz, request.arguments.jsonArg("args") as? JsonArray) }
        }
    }

    addDouMcpTool(
        name = "invokeMethod",
        description =
            "反射调用方法。参数：path 或 handle（path 为类名调静态方法，为对象路径/句柄调实例方法）、method（方法名）、" +
                "args（参数数组）、argTypes（可选，参数类型名数组，用于重载消歧）、asHandle（默认 false；true 时对象结果入句柄表）、" +
                "special（默认 false；true 时非虚调用，绕过子类重写、直接调该类定义的方法）、" +
                "chain（origin(默认，跳过所有 hook 调原实现) / full(走完整 hook 链，可验证自己挂的 hook 是否命中)）。" +
                "参数元素是表达式或标量：标量直接给；取既有对象 {\"ref\":\"<对象路径>\"} 或 {\"handle\":\"obj:N\"}；" +
                "构造实例 {\"new\":\"<类名>\",\"args\":[...]}；静态字段 {\"static\":\"<类名>#<字段>\"}；" +
                "Class 对象 {\"class\":\"<类名>\"}；枚举 {\"enum\":\"<枚举类>\",\"name\":\"<常量>\"}；" +
                "动态代理 {\"proxy\":{\"interfaces\":[\"...\"],\"returns\":{\"方法名\":值}}}。会真实改变宿主状态，后果自负",
    ) { request ->
        val method = request.arguments.stringArg("method") ?: return@addDouMcpTool errorResult("缺少 method 参数")
        withContext(Dispatchers.IO) {
            toolCall {
                RuntimeBridge.invokeMethodJson(
                    path = request.arguments.stringArg("path"),
                    handle = request.arguments.stringArg("handle"),
                    method = method,
                    args = request.arguments.jsonArg("args") as? JsonArray,
                    argTypes = request.arguments.stringListArgOrNull("argTypes"),
                    asHandle = request.arguments.boolArg("asHandle", false),
                    special = request.arguments.boolArg("special", false),
                    chainFull = request.arguments.stringArg("chain")?.equals("full", ignoreCase = true) == true,
                )
            }
        }
    }

    addDouMcpTool(
        name = "dynamicProxy",
        description =
            "用 java.lang.reflect.Proxy 造一个接口实现，返回句柄，可当作回调参数传进别的方法。" +
                "参数：interfaces（接口全名数组，至少一个）、returns（可选对象：方法名 → 返回值表达式；" +
                "未配置的方法按返回类型给默认值）。只支持接口，不支持抽象类",
    ) { request ->
        val interfaces = request.arguments.stringListArg("interfaces")
        if (interfaces.isEmpty()) return@addDouMcpTool errorResult("缺少 interfaces 参数")
        withContext(Dispatchers.IO) {
            toolCall { RuntimeBridge.dynamicProxyJson(interfaces, request.arguments.jsonArg("returns") as? JsonObject) }
        }
    }

    addDouMcpTool(
        name = "dumpStack",
        description =
            "dump 进程内线程堆栈——找 caller、定位功能入口常用。参数：thread（可选，线程名包含该子串）、" +
                "packageFilter（可选，只保留类名含该子串的帧）" + "。提醒：不传过滤会 dump 所有线程的全部堆栈，非常大",
    ) { request ->
        withContext(Dispatchers.IO) {
            toolCall {
                RuntimeBridge.dumpStackJson(
                    thread = request.arguments.stringArg("thread"),
                    packageFilter = request.arguments.stringArg("packageFilter"),
                )
            }
        }
    }

    addDouMcpTool(
        name = "dumpActivities",
        description = "dump 当前 Activity 栈（ActivityThread 记录）与栈顶/暂停中的页面，用于定位当前界面入口",
    ) { _ ->
        withContext(Dispatchers.IO) {
            toolCall { RuntimeBridge.dumpActivitiesJson() }
        }
    }

    addDouMcpTool(
        name = "dumpHeap",
        description =
            "导出堆快照（hprof）到磁盘供 PC 分析（MAT/jhat）。参数：path（目录，默认 /sdcard/DouMCP）。" +
                "会短暂卡顿宿主，大堆可能数秒到数十秒。目录不可写时明确报错",
    ) { request ->
        withContext(Dispatchers.IO) {
            toolCall { RuntimeBridge.dumpHeapJson(request.arguments.stringArg("path")) }
        }
    }

    addDouMcpTool(
        name = "readPreferences",
        description =
            "读取宿主 SharedPreferences。参数：name（可选；缺省列出并读取全部 SP 文件）。" +
                "注意宿主自己的 SP 大多不是明文，敏感值可能读不出可读内容" + "。不传 name 会读取全部 SP 文件",
    ) { request ->
        withContext(Dispatchers.IO) {
            toolCall { RuntimeBridge.readPreferencesJson(request.arguments.stringArg("name")) }
        }
    }

    addDouMcpTool(
        name = "packageInfo",
        description = "宿主包信息：版本、安装路径、数据目录、签名 SHA-256、申请的权限",
    ) { _ ->
        withContext(Dispatchers.IO) {
            toolCall { RuntimeBridge.packageInfoJson() }
        }
    }
}
