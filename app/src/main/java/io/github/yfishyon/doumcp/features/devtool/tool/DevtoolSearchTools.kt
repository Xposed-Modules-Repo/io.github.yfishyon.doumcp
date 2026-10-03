package io.github.yfishyon.doumcp.features.devtool.tool

import io.github.yfishyon.doumcp.core.addDouMcpTool
import io.github.yfishyon.doumcp.core.boolArg
import io.github.yfishyon.doumcp.core.errorResult
import io.github.yfishyon.doumcp.core.intArgOrNull
import io.github.yfishyon.doumcp.core.jsonArg
import io.github.yfishyon.doumcp.core.nullableStringListArg
import io.github.yfishyon.doumcp.core.numberListArg
import io.github.yfishyon.doumcp.core.stringArg
import io.github.yfishyon.doumcp.core.stringListArg
import io.github.yfishyon.doumcp.core.toolCall
import io.github.yfishyon.doumcp.features.devtool.bridge.DexSearchBridge
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** devtool 静态定位工具注册：基于 DexKit 搜类 / 搜方法 / 搜字段 / 找 caller / 打印结构。 */
internal fun Server.registerDevtoolSearchTools() {
    addDouMcpTool(
        name = "dexKitInfo",
        description =
            "查看 DexKit 桥状态：宿主 APK 路径、宿主版本、已解析 dex 数、桥是否已建。" +
                "桥未建时首次搜索会构建索引（耗时较长），之后复用同一实例",
    ) { _ ->
        withContext(Dispatchers.IO) {
            toolCall { DexSearchBridge.infoJson() }
        }
    }

    addDouMcpTool(
        name = "dexKitInitCache",
        description =
            "初始化 DexKit 全量缓存（initFullCache）。会占用**大量内存与时间**，仅建议在开始批量/重查询前调用一次" +
                "（对 usingNumbers/opCodes 这类按方法解析的查询提速明显）。返回耗时",
    ) { _ ->
        withContext(Dispatchers.IO) {
            toolCall { DexSearchBridge.initFullCacheJson() }
        }
    }

    addDouMcpTool(
        name = "findClasses",
        description =
            "按结构或字符串锚点搜类（DexKit）。至少需要一个条件，或用 package / excludePackage 限定范围（只给 package 即列出整包含子包的类）。" +
                "参数：name（类名匹配，配 nameMatch）、package / excludePackage、superClass、interface（全等）、" +
                "source（源文件名）、annotation（类注解全名，全等）、" +
                "modifiers（Java 修饰符位，如 1=public、1024=abstract；配 modifiersExact=true 精确相等，否则按位包含）、" +
                "accessFlags（**原始 DEX 标志**，配 accessFlagsExact=true 时精确相等否则按位包含）、" +
                "fieldName / fieldType（类里含该字段）、methodName / methodReturn / methodParams（类里含该方法，params 元素给 null 表示任意）、" +
                "usingStrings（用到的字符串，配 usingStringMatch）、excludeStrings（**不用**这些字符串，做排除）、" +
                "methodCountMin/Max、fieldCountMin/Max、interfaceCountMin/Max（方法/字段/接口数范围）、" +
                "first（可选，命中即返回；此时 total 不代表真实总数，且 DexKit 2.3.0 的类 findFirst 有已知问题，慎用）、" +
                "limit（可选且必须为正；不传返回全部结果）。" +
                "nameMatch / usingStringMatch 取值用 DexKit 的 StringMatchType 枚举名：" +
                "Contains(默认) / Equals / StartsWith / EndsWith / SimilarRegex；" +
                "速度 Equals > StartsWith > EndsWith > Contains > SimilarRegex，结构条件（类型/修饰符/数量）比字符串更快。" +
                "提醒：不传 limit 会返回全部匹配，结果可能非常多，务必先用结构条件收窄输出",
    ) { request ->
        val args = request.arguments
        withContext(Dispatchers.IO) {
            toolCall {
                DexSearchBridge.findClassesJson(
                    name = args.stringArg("name"),
                    nameMatch = args.stringArg("nameMatch"),
                    pkg = args.stringArg("package"),
                    excludePkg = args.stringArg("excludePackage"),
                    superClass = args.stringArg("superClass"),
                    iface = args.stringArg("interface"),
                    source = args.stringArg("source"),
                    annotation = args.stringArg("annotation"),
                    modifiers = args.intArgOrNull("modifiers"),
                    modifiersExact = args.boolArg("modifiersExact", false),
                    accessFlags = args.intArgOrNull("accessFlags"),
                    accessFlagsExact = args.boolArg("accessFlagsExact", false),
                    fieldName = args.stringArg("fieldName"),
                    fieldType = args.stringArg("fieldType"),
                    methodName = args.stringArg("methodName"),
                    methodReturn = args.stringArg("methodReturn"),
                    methodParams = args.nullableStringListArg("methodParams"),
                    usingStrings = args.stringListArg("usingStrings"),
                    usingStringMatch = args.stringArg("usingStringMatch"),
                    excludeStrings = args.stringListArg("excludeStrings"),
                    methodCountMin = args.intArgOrNull("methodCountMin"),
                    methodCountMax = args.intArgOrNull("methodCountMax"),
                    fieldCountMin = args.intArgOrNull("fieldCountMin"),
                    fieldCountMax = args.intArgOrNull("fieldCountMax"),
                    interfaceCountMin = args.intArgOrNull("interfaceCountMin"),
                    interfaceCountMax = args.intArgOrNull("interfaceCountMax"),
                    first = args.boolArg("first", false),
                    limit = args.intArgOrNull("limit"),
                )
            }
        }
    }

    addDouMcpTool(
        name = "findMethods",
        description =
            "按结构或字符串锚点搜方法（DexKit）。至少需要一个条件，或用 class / package / excludePackage 限定范围。" +
                "参数：name（配 nameMatch）、class（声明类，全等；与 package 同时给时二者为 AND）、" +
                "package / excludePackage、returnType、paramTypes（参数类型全名数组，元素给 null 表示任意）、paramCount、" +
                "modifiers（Java 修饰符位，按位包含）、accessFlags（原始 DEX 标志，配 accessFlagsExact）、" +
                "annotation（方法注解全名，全等）、callerClass / callerMethod（被这些方法调用的）、invokeClass / invokeMethod（调用了这些方法的）、" +
                "usingFieldName / usingFieldType / usingFieldUsage（any(默认)/read/write，方法用到的字段）、" +
                "usingStrings（配 usingStringMatch）、excludeStrings（**不用**这些字符串）、usingNumbers（用到的数字常量数组）、" +
                "opNames（连续 smali 助记符数组，如 [\"invoke-virtual\",\"return-void\"]）、first（命中即返回）、" +
                "limit（可选；不传返回全部结果）。" +
                "nameMatch / usingStringMatch 用 StringMatchType 枚举名：Contains(默认) / Equals / StartsWith / EndsWith / SimilarRegex；" +
                "速度 Equals > StartsWith > EndsWith > Contains > SimilarRegex。输出方法清单：class/name/sign/returnType/paramTypes/modifiers",
    ) { request ->
        val args = request.arguments
        withContext(Dispatchers.IO) {
            toolCall {
                DexSearchBridge.findMethodsJson(
                    name = args.stringArg("name"),
                    nameMatch = args.stringArg("nameMatch"),
                    clazz = args.stringArg("class"),
                    pkg = args.stringArg("package"),
                    excludePkg = args.stringArg("excludePackage"),
                    returnType = args.stringArg("returnType"),
                    paramTypes = args.nullableStringListArg("paramTypes"),
                    paramCount = args.intArgOrNull("paramCount"),
                    protoShorty = args.stringArg("protoShorty"),
                    modifiers = args.intArgOrNull("modifiers"),
                    modifiersExact = args.boolArg("modifiersExact", false),
                    accessFlags = args.intArgOrNull("accessFlags"),
                    accessFlagsExact = args.boolArg("accessFlagsExact", false),
                    annotation = args.stringArg("annotation"),
                    callerClass = args.stringArg("callerClass"),
                    callerMethod = args.stringArg("callerMethod"),
                    invokeClass = args.stringArg("invokeClass"),
                    invokeMethod = args.stringArg("invokeMethod"),
                    usingFieldName = args.stringArg("usingFieldName"),
                    usingFieldType = args.stringArg("usingFieldType"),
                    usingFieldUsage = args.stringArg("usingFieldUsage"),
                    usingStrings = args.stringListArg("usingStrings"),
                    usingStringMatch = args.stringArg("usingStringMatch"),
                    excludeStrings = args.stringListArg("excludeStrings"),
                    usingNumbers = args.numberListArg("usingNumbers"),
                    opNames = args.stringListArg("opNames"),
                    first = args.boolArg("first", false),
                    limit = args.intArgOrNull("limit"),
                )
            }
        }
    }

    addDouMcpTool(
        name = "findFields",
        description =
            "按结构搜字段（DexKit）。参数：name（字段名全等）、class（声明类，全等）、type（字段类型全名，如 java.lang.String）、" +
                "package / excludePackage、annotation（字段注解全名，全等）、modifiers（Java 修饰符位；配 modifiersExact 精确相等）、" +
                "accessFlags（原始 DEX 标志，配 accessFlagsExact）、readByMethod / writeByMethod（被指定方法读/写）、" +
                "first（命中即返回）、limit（可选；不传返回全部结果）。class 与 name/type 至少给一个。" +
                "注意 package 需配合 name/type 使用（不能只给 package 列整包字段）。" +
                "输出字段清单：class/name/type/modifiers。要看某个字段的类型与读写方，用 dumpField",
    ) { request ->
        val args = request.arguments
        withContext(Dispatchers.IO) {
            toolCall {
                DexSearchBridge.findFieldsJson(
                    name = args.stringArg("name"),
                    clazz = args.stringArg("class"),
                    type = args.stringArg("type"),
                    pkg = args.stringArg("package"),
                    excludePkg = args.stringArg("excludePackage"),
                    modifiers = args.intArgOrNull("modifiers"),
                    modifiersExact = args.boolArg("modifiersExact", false),
                    accessFlags = args.intArgOrNull("accessFlags"),
                    accessFlagsExact = args.boolArg("accessFlagsExact", false),
                    annotation = args.stringArg("annotation"),
                    readByMethod = args.stringArg("readByMethod"),
                    writeByMethod = args.stringArg("writeByMethod"),
                    first = args.boolArg("first", false),
                    limit = args.intArgOrNull("limit"),
                )
            }
        }
    }

    addDouMcpTool(
        name = "batchFindUsingStrings",
        description =
            "一次检索多组字符串锚点（DexKit 批量接口，比逐组查询快）。**组内为 AND**（目标需用到该组全部字符串），不同组互相独立。" +
                "参数：groups（对象：组名 → 字符串数组）、target（class(默认)/method）、class（可选，限定在某类内）、" +
                "package / excludePackage、stringMatch（StringMatchType 枚举名，默认 Contains；Equals 更快）、limit（可选）。" +
                "输出按组名分组的结果清单。提醒：每组都可能命中很多，先用结构条件缩小 package 范围",
    ) { request ->
        val groupsElement =
            request.arguments.jsonArg("groups") as? JsonObject
                ?: return@addDouMcpTool errorResult("缺少 groups 参数（对象：组名 → 字符串数组）")
        val groups =
            groupsElement.entries.map { (groupName, value) ->
                val strings =
                    (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                groupName to strings
            }
        if (groups.isEmpty()) return@addDouMcpTool errorResult("groups 为空")
        withContext(Dispatchers.IO) {
            toolCall {
                DexSearchBridge.batchFindJson(
                    target = request.arguments.stringArg("target") ?: "class",
                    groups = groups,
                    pkg = request.arguments.stringArg("package"),
                    excludePkg = request.arguments.stringArg("excludePackage"),
                    clazz = request.arguments.stringArg("class"),
                    stringMatch = request.arguments.stringArg("stringMatch"),
                    limit = request.arguments.intArgOrNull("limit"),
                )
            }
        }
    }

    addDouMcpTool(
        name = "dumpField",
        description =
            "打印单个字段详情：声明类、类型、描述符、修饰符、原始 DEX 标志、注解，以及**读取该字段**和**写入该字段**的方法清单" +
                "（DexKit 的 iput/sput/iget/sget 引用关系）——找「这个字段在哪儿被改」很直接。" +
                "参数：descriptor（字段描述符，形如 `L类;->字段:类型`；给这个就不用 class/field）或 class + field（字段名）、" +
                "limit（可选，限制读写方条数；不传返回全部）",
    ) { request ->
        withContext(Dispatchers.IO) {
            toolCall {
                DexSearchBridge.dumpFieldJson(
                    clazz = request.arguments.stringArg("class"),
                    field = request.arguments.stringArg("field"),
                    descriptor = request.arguments.stringArg("descriptor"),
                    limit = request.arguments.intArgOrNull("limit"),
                )
            }
        }
    }

    addDouMcpTool(
        name = "findCallers",
        description =
            "找出静态调用某方法的方法（DexKit 调用关系）。参数：class、method、signature（可选，参数签名消歧）、" +
                "limit（可选；不传返回全部结果）。输出调用者清单 + 目标方法信息",
    ) { request ->
        val clazz = request.arguments.stringArg("class") ?: return@addDouMcpTool errorResult("缺少 class 参数")
        val method = request.arguments.stringArg("method") ?: return@addDouMcpTool errorResult("缺少 method 参数")
        withContext(Dispatchers.IO) {
            toolCall {
                DexSearchBridge.findCallersJson(
                    clazz = clazz,
                    method = method,
                    signature = request.arguments.stringArg("signature"),
                    limit = request.arguments.intArgOrNull("limit"),
                )
            }
        }
    }

    addDouMcpTool(
        name = "findStringUsage",
        description =
            "找出**使用某个字符串**的方法：找 URL、埋点 key、提示语等。" +
                "参数：string（要搜的字符串）、stringMatch（StringMatchType 枚举名，默认 Contains；Equals 更快）、" +
                "package（可选，限定包范围）、limit（可选；不传返回全部结果）",
    ) { request ->
        val string = request.arguments.stringArg("string") ?: return@addDouMcpTool errorResult("缺少 string 参数")
        withContext(Dispatchers.IO) {
            toolCall {
                DexSearchBridge.findStringUsageJson(
                    string = string,
                    pkg = request.arguments.stringArg("package"),
                    stringMatch = request.arguments.stringArg("stringMatch"),
                    limit = request.arguments.intArgOrNull("limit"),
                )
            }
        }
    }

    addDouMcpTool(
        name = "dumpClass",
        description =
            "打印类的完整结构（DexKit 读 dex，不加载类）：继承链、接口、注解、字段表、方法签名表。" +
                "参数：class（类名或描述符）、limit（可选，限制方法表条数；不传返回全部）。" +
                "提醒：大类的字段/方法表可能非常长",
    ) { request ->
        val clazz = request.arguments.stringArg("class") ?: return@addDouMcpTool errorResult("缺少 class 参数")
        withContext(Dispatchers.IO) {
            toolCall {
                DexSearchBridge.dumpClassJson(
                    clazz = clazz,
                    limit = request.arguments.intArgOrNull("limit"),
                )
            }
        }
    }

    addDouMcpTool(
        name = "dumpMethod",
        description =
            "打印单个方法的静态细节（DexKit）：签名、修饰符、原始 DEX 标志、参数名、用到的字符串/数字/字段、" +
                "**调用了哪些方法**（invokes）与**被谁调用**（callers）、smali 指令助记符序列。" +
                "参数：descriptor（方法描述符，形如 `L类;->方法(参数)返回`；给这个就不用 class/method）或 class + method + signature（可选，消歧）、" +
                "limit（可选，限制 invokes/callers/usingStrings 等条数；不传返回全部）。" +
                "注意 DexKit 只给指令助记符、给不了带操作数的 smali，需要真 smali 请在 PC 上反编译",
    ) { request ->
        withContext(Dispatchers.IO) {
            toolCall {
                DexSearchBridge.dumpMethodJson(
                    clazz = request.arguments.stringArg("class"),
                    method = request.arguments.stringArg("method"),
                    signature = request.arguments.stringArg("signature"),
                    descriptor = request.arguments.stringArg("descriptor"),
                    limit = request.arguments.intArgOrNull("limit"),
                )
            }
        }
    }
}
