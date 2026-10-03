@file:OptIn(org.luckypray.dexkit.annotations.DexKitExperimentalApi::class)

package io.github.yfishyon.doumcp.features.devtool.bridge

import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.OpCodeMatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.enums.UsingType
import org.luckypray.dexkit.query.matchers.base.AccessFlagsMatcher
import org.luckypray.dexkit.query.matchers.base.StringMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/**
 * DexKit 静态查询桥：按**结构**（字段/方法签名、修饰符/原始标志、数量、继承关系、字段读写、调用关系）
 * 或字符串锚点定位。
 *
 * 匹配方式用 `StringMatchType` 枚举名，**默认 `Contains`**；速度 `Equals` > `StartsWith` > `EndsWith` >
 * `Contains` > `SimilarRegex`。结构条件（类型/修饰符/数量）通常比字符串匹配更快。
 *
 * 性能要点：给定 `class` 时用 `searchInClass` 类内限定（`declaredClass` 是全表逐条比对，慢几十倍）。
 * 桥与业务定位共用同一实例，不重复建索引。结果默认全量返回，可用 `limit` 限量防爆炸。
 */
object DexSearchBridge {
    // ==================== 桥信息 ====================

    fun infoJson(): String {
        val bridge = DexKitSupport.bridgeIfReady()
        val json =
            JSONObject()
                .put("ok", true)
                .put("hostApk", DexKitSupport.apkPath ?: JSONObject.NULL)
                .put("hostVersion", DexKitSupport.hostVersion)
                .put("process", HostRuntime.processName)
                .put("bridgeReady", bridge != null)
        if (bridge != null) {
            json.put("valid", runCatching { bridge.isValid }.getOrDefault(false))
            json.put("dexCount", runCatching { bridge.getDexNum() }.getOrDefault(0))
        } else {
            json.put("hint", "桥未建（缓存全命中或尚未查询）；首次搜索会构建索引，耗时较长")
        }
        return json.toString()
    }

    /** 初始化 DexKit 全量缓存（占大量内存与时间，仅编译期/批量检索前用一次）。 */
    private val fullCacheInitialized =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    fun initFullCacheJson(): String {
        val bridge = requireBridge()
        if (!fullCacheInitialized.compareAndSet(false, true)) {
            return JSONObject().put("ok", true).put("alreadyInitialized", true).toString()
        }
        val startNs = System.nanoTime()
        runCatching { bridge.initFullCache() }
            .onFailure {
                fullCacheInitialized.set(false)
                throw it
            }
        return JSONObject()
            .put("ok", true)
            .put("costMs", (System.nanoTime() - startNs) / 1_000_000.0)
            .put("hint", "已初始化全量缓存；占用大量内存，仅建议在批量检索前调用一次")
            .toString()
    }

    // ==================== 搜类 ====================

    fun findClassesJson(
        name: String?,
        nameMatch: String?,
        pkg: String?,
        excludePkg: String?,
        superClass: String?,
        iface: String?,
        source: String?,
        annotation: String?,
        modifiers: Int?,
        modifiersExact: Boolean,
        accessFlags: Int?,
        accessFlagsExact: Boolean,
        fieldName: String?,
        fieldType: String?,
        methodName: String?,
        methodReturn: String?,
        methodParams: List<String?>?,
        usingStrings: List<String>,
        usingStringMatch: String?,
        excludeStrings: List<String>,
        methodCountMin: Int?,
        methodCountMax: Int?,
        fieldCountMin: Int?,
        fieldCountMax: Int?,
        interfaceCountMin: Int?,
        interfaceCountMax: Int?,
        first: Boolean,
        limit: Int?,
    ): String {
        val bridge = requireBridge()
        requireNonZeroFlags(modifiers, accessFlags)
        val strings = requireAnchors(usingStrings)
        val excludes = requireAnchors(excludeStrings)
        val stringMatch = matchType(usingStringMatch)
        val hasField = !fieldName.isNullOrBlank() || !fieldType.isNullOrBlank()
        val hasMethod = !methodName.isNullOrBlank() || !methodReturn.isNullOrBlank() || methodParams != null
        val hasMatcher =
            !name.isNullOrBlank() || !superClass.isNullOrBlank() || !iface.isNullOrBlank() ||
                !source.isNullOrBlank() || !annotation.isNullOrBlank() || modifiers != null ||
                accessFlags != null || hasField || hasMethod || strings.isNotEmpty() || excludes.isNotEmpty() ||
                methodCountMin != null || methodCountMax != null ||
                fieldCountMin != null || fieldCountMax != null ||
                interfaceCountMin != null || interfaceCountMax != null

        if (!hasMatcher && pkg.isNullOrBlank() && excludePkg.isNullOrBlank()) {
            throw IllegalStateException("findClasses 至少需要一个条件，或给 package / excludePackage 限定范围")
        }
        val results =
            bridge.findClass {
                if (first) findFirst = true
                pkg?.takeIf { it.isNotBlank() }?.let { searchPackages(it) }
                excludePkg?.takeIf { it.isNotBlank() }?.let { excludePackages(it) }
                if (hasMatcher) {
                    matcher {
                        name?.takeIf { it.isNotBlank() }?.let { className(it, matchType(nameMatch)) }
                        superClass?.takeIf { it.isNotBlank() }?.let { superClass(it, StringMatchType.Equals) }
                        iface?.takeIf { it.isNotBlank() }?.let { addInterface(it, StringMatchType.Equals) }
                        source?.takeIf { it.isNotBlank() }?.let { this.source = it }
                        annotation?.takeIf { it.isNotBlank() }?.let { ann -> addAnnotation { type(ann) } }
                        modifiers?.let { modifiers(AccessFlagsMatcher(it, flagMatch(modifiersExact))) }
                        accessFlags?.let { accessFlags(it, flagMatch(accessFlagsExact)) }
                        if (strings.isNotEmpty()) usingStrings(strings, stringMatch, false)
                        excludes.forEach { one -> addNoneOf { usingStrings(listOf(one), stringMatch, false) } }
                        if (hasField) {
                            addField {
                                fieldName?.takeIf { it.isNotBlank() }?.let { this.name = it }
                                fieldType?.takeIf { it.isNotBlank() }?.let { this.type = it }
                            }
                        }
                        if (hasMethod) {
                            addMethod {
                                methodName?.takeIf { it.isNotBlank() }?.let { name(it, StringMatchType.Equals) }
                                methodReturn?.takeIf { it.isNotBlank() }?.let { returnType(it, StringMatchType.Equals) }
                                methodParams?.let { paramTypes(*it.toTypedArray()) }
                            }
                        }
                        if (methodCountMin != null || methodCountMax != null) {
                            methodCount(methodCountMin ?: 0, methodCountMax ?: Int.MAX_VALUE)
                        }
                        if (fieldCountMin != null || fieldCountMax != null) {
                            fieldCount(fieldCountMin ?: 0, fieldCountMax ?: Int.MAX_VALUE)
                        }
                        if (interfaceCountMin != null || interfaceCountMax != null) {
                            interfaceCount(interfaceCountMin ?: 0, interfaceCountMax ?: Int.MAX_VALUE)
                        }
                    }
                }
            }

        val page = limited(results, limit)
        val arr = JSONArray()
        for (clazz in page) arr.put(classBrief(clazz))
        return result(results.size, arr).put("first", first).toString()
    }

    // ==================== 搜方法 ====================

    fun findMethodsJson(
        name: String?,
        nameMatch: String?,
        clazz: String?,
        pkg: String?,
        excludePkg: String?,
        returnType: String?,
        paramTypes: List<String?>?,
        paramCount: Int?,
        protoShorty: String?,
        modifiers: Int?,
        modifiersExact: Boolean,
        accessFlags: Int?,
        accessFlagsExact: Boolean,
        annotation: String?,
        callerClass: String?,
        callerMethod: String?,
        invokeClass: String?,
        invokeMethod: String?,
        usingFieldName: String?,
        usingFieldType: String?,
        usingFieldUsage: String?,
        usingStrings: List<String>,
        usingStringMatch: String?,
        excludeStrings: List<String>,
        usingNumbers: List<Number>,
        opNames: List<String>,
        first: Boolean,
        limit: Int?,
    ): String {
        val bridge = requireBridge()
        requireNonZeroFlags(modifiers, accessFlags)
        val classData = clazz?.takeIf { it.isNotBlank() }?.let { requireClassData(bridge, it) }
        val strings = requireAnchors(usingStrings)
        val excludes = requireAnchors(excludeStrings)
        val ops = opNames.filter { it.isNotEmpty() }
        if (opNames.isNotEmpty() && ops.isEmpty()) throw IllegalStateException("opNames 不能全为空")
        val stringMatch = matchType(usingStringMatch)
        val hasCaller = !callerClass.isNullOrBlank() || !callerMethod.isNullOrBlank()
        val hasInvoke = !invokeClass.isNullOrBlank() || !invokeMethod.isNullOrBlank()
        if (usingFieldUsage != null && usingFieldName.isNullOrBlank() && usingFieldType.isNullOrBlank()) {
            throw IllegalStateException("usingFieldUsage 需配合 usingFieldName / usingFieldType 使用")
        }
        val hasUsingField = !usingFieldName.isNullOrBlank() || !usingFieldType.isNullOrBlank()
        val hasMatcher =
            !name.isNullOrBlank() || !returnType.isNullOrBlank() ||
                paramTypes != null || paramCount != null || !protoShorty.isNullOrBlank() ||
                modifiers != null || accessFlags != null ||
                !annotation.isNullOrBlank() || hasCaller || hasInvoke || hasUsingField ||
                strings.isNotEmpty() || excludes.isNotEmpty() || usingNumbers.isNotEmpty() || ops.isNotEmpty()

        if (!hasMatcher && classData == null && pkg.isNullOrBlank() && excludePkg.isNullOrBlank()) {
            throw IllegalStateException("findMethods 至少需要一个条件，或给 class / package / excludePackage 限定范围")
        }
        val results =
            bridge.findMethod {
                if (first) findFirst = true
                // 类内限定：比 declaredClass 全表比对快几十倍；与 package 可并存（AND）
                classData?.let { searchInClass(listOf(it)) }
                pkg?.takeIf { it.isNotBlank() }?.let { searchPackages(it) }
                excludePkg?.takeIf { it.isNotBlank() }?.let { excludePackages(it) }
                if (hasMatcher) {
                    matcher {
                        name?.takeIf { it.isNotBlank() }?.let { name(it, matchType(nameMatch)) }
                        returnType?.takeIf { it.isNotBlank() }?.let { returnType(it, StringMatchType.Equals) }
                        paramTypes?.let { paramTypes(*it.toTypedArray()) }
                        paramCount?.let { paramCount(it) }
                        protoShorty?.takeIf { it.isNotBlank() }?.let { protoShorty(it) }
                        modifiers?.let { modifiers(AccessFlagsMatcher(it, flagMatch(modifiersExact))) }
                        accessFlags?.let { accessFlags(it, flagMatch(accessFlagsExact)) }
                        annotation?.takeIf { it.isNotBlank() }?.let { ann -> addAnnotation { type(ann) } }
                        if (hasCaller) {
                            addCaller {
                                callerMethod?.takeIf { it.isNotBlank() }?.let { name(it, StringMatchType.Equals) }
                                callerClass?.takeIf { it.isNotBlank() }?.let { declaredClass(it, StringMatchType.Equals) }
                            }
                        }
                        if (hasInvoke) {
                            addInvoke {
                                invokeMethod?.takeIf { it.isNotBlank() }?.let { name(it, StringMatchType.Equals) }
                                invokeClass?.takeIf { it.isNotBlank() }?.let { declaredClass(it, StringMatchType.Equals) }
                            }
                        }
                        if (hasUsingField) {
                            addUsingField {
                                usingFieldName?.takeIf { it.isNotBlank() }?.let { name(it, StringMatchType.Equals) }
                                usingFieldType?.takeIf { it.isNotBlank() }?.let { type(it, StringMatchType.Equals) }
                                usingType(usingTypeOf(usingFieldUsage))
                            }
                        }
                        if (strings.isNotEmpty()) usingStrings(strings, stringMatch, false)
                        excludes.forEach { one -> addNoneOf { usingStrings(listOf(one), stringMatch, false) } }
                        if (usingNumbers.isNotEmpty()) usingNumbers(*usingNumbers.toTypedArray())
                        if (ops.isNotEmpty()) opNames(ops, OpCodeMatchType.Contains)
                    }
                }
            }

        val page = limited(results, limit)
        val arr = JSONArray()
        for (method in page) arr.put(methodBrief(method))
        return result(results.size, arr).put("first", first).toString()
    }

    // ==================== 搜字段 ====================

    fun findFieldsJson(
        name: String?,
        clazz: String?,
        type: String?,
        pkg: String?,
        excludePkg: String?,
        modifiers: Int?,
        modifiersExact: Boolean,
        accessFlags: Int?,
        accessFlagsExact: Boolean,
        annotation: String?,
        readByMethod: String?,
        writeByMethod: String?,
        first: Boolean,
        limit: Int?,
    ): String {
        val bridge = requireBridge()
        requireNonZeroFlags(modifiers, accessFlags)
        if (clazz.isNullOrBlank() && name.isNullOrBlank() && type.isNullOrBlank()) {
            throw IllegalStateException("findFields 至少需要 class、name、type 之一")
        }
        val classData = clazz?.takeIf { it.isNotBlank() }?.let { requireClassData(bridge, it) }

        val results: List<FieldData> =
            bridge.findField {
                if (first) findFirst = true
                classData?.let { searchInClass(listOf(it)) }
                pkg?.takeIf { it.isNotBlank() }?.let { searchPackages(it) }
                excludePkg?.takeIf { it.isNotBlank() }?.let { excludePackages(it) }
                matcher {
                    name?.takeIf { it.isNotBlank() }?.let { this.name = it }
                    type?.takeIf { it.isNotBlank() }?.let { this.type = it }
                    modifiers?.let { modifiers(AccessFlagsMatcher(it, flagMatch(modifiersExact))) }
                    accessFlags?.let { accessFlags(it, flagMatch(accessFlagsExact)) }
                    annotation?.takeIf { it.isNotBlank() }?.let { ann -> addAnnotation { type(ann) } }
                    readByMethod?.takeIf { it.isNotBlank() }?.let { method ->
                        addReadMethod { name(method, StringMatchType.Equals) }
                    }
                    writeByMethod?.takeIf { it.isNotBlank() }?.let { method ->
                        addWriteMethod { name(method, StringMatchType.Equals) }
                    }
                }
            }

        val page = limited(results, limit)
        val arr = JSONArray()
        for (field in page) arr.put(fieldBrief(field))
        return result(results.size, arr).put("first", first).toString()
    }

    // ==================== 字段细节（含谁读谁写） ====================

    fun dumpFieldJson(
        clazz: String?,
        field: String?,
        descriptor: String?,
        limit: Int?,
    ): String {
        val bridge = requireBridge()
        val className = clazz?.takeIf { it.isNotBlank() }
        val fieldName = field?.takeIf { it.isNotBlank() }
        val target =
            descriptor?.takeIf { it.isNotBlank() }?.let { bridge.getFieldData(it) }
                ?: className?.let { owner ->
                    fieldName?.let { name ->
                        requireClassData(bridge, owner).findField { matcher { this.name = name } }.firstOrNull()
                    }
                }
                ?: throw IllegalStateException(
                    "字段不存在：需要 descriptor（L类;->字段:类型）或 class + field；DexKit 未命中时返回 null",
                )

        val readers = limited(target.readers, limit)
        val writers = limited(target.writers, limit)
        val readerArr = JSONArray()
        for (method in readers) readerArr.put(methodBrief(method))
        val writerArr = JSONArray()
        for (method in writers) writerArr.put(methodBrief(method))

        return JSONObject()
            .put("ok", true)
            .put("resolvedBy", if (!descriptor.isNullOrBlank()) "descriptor" else "class+field")
            .put("class", target.className)
            .put("name", target.fieldName)
            .put("type", target.typeName)
            .put("descriptor", target.descriptor)
            .put("modifiers", Modifier.toString(target.modifiers))
            .put("accessFlags", target.accessFlags)
            .put("annotations", texts(target.annotations.map { it.toString() }))
            .put("readerCount", target.readers.size)
            .put("readers", readerArr)
            .put("writerCount", target.writers.size)
            .put("writers", writerArr)
            .toString()
    }

    // ==================== 找静态调用者 ====================

    fun findCallersJson(
        clazz: String,
        method: String,
        signature: String?,
        limit: Int?,
    ): String {
        val bridge = requireBridge()
        val target = methodData(bridge, clazz, method, signature)
        val callers = target.callers
        val page = limited(callers, limit)
        val arr = JSONArray()
        for (caller in page) arr.put(methodBrief(caller))
        return result(callers.size, arr)
            .put("target", methodBrief(target))
            .toString()
    }

    // ==================== 找字符串用处 ====================

    fun findStringUsageJson(
        string: String,
        pkg: String?,
        stringMatch: String?,
        limit: Int?,
    ): String {
        if (string.isBlank()) throw IllegalStateException("string 不能为空")
        val bridge = requireBridge()
        val results =
            bridge.findMethod {
                pkg?.takeIf { it.isNotBlank() }?.let { searchPackages(it) }
                matcher { usingStrings(listOf(string), matchType(stringMatch), false) }
            }
        val page = limited(results, limit)
        val arr = JSONArray()
        for (method in page) arr.put(methodBrief(method))
        return result(results.size, arr).put("string", string).toString()
    }

    // ==================== 类结构 ====================

    fun dumpClassJson(
        clazz: String,
        limit: Int?,
    ): String {
        val bridge = requireBridge()
        val classData = requireClassData(bridge, clazz)

        val ancestors = JSONArray()
        var superClass = classData.superClass
        while (superClass != null) {
            ancestors.put(superClass.name)
            superClass = superClass.superClass
        }

        val interfaces = JSONArray()
        for (iface in classData.interfaces) interfaces.put(iface.name)

        val fields = JSONArray()
        for (field in classData.fields) fields.put(fieldBrief(field))

        val methodPage = limited(classData.methods, limit)
        val methods = JSONArray()
        for (method in methodPage) methods.put(methodBrief(method))

        return JSONObject()
            .put("ok", true)
            .put("name", classData.name)
            .put("descriptor", classData.descriptor)
            .put("modifiers", Modifier.toString(classData.modifiers))
            .put("accessFlags", classData.accessFlags)
            .put("sourceFile", classData.sourceFile)
            .put("ancestors", ancestors)
            .put("interfaces", interfaces)
            .put("annotations", texts(classData.annotations.map { it.toString() }))
            .put("fieldCount", classData.fieldCount)
            .put("fields", fields)
            .put("methodCount", classData.methodCount)
            .put("methods", methods)
            .toString()
    }

    // ==================== 方法细节 ====================

    fun dumpMethodJson(
        clazz: String?,
        method: String?,
        signature: String?,
        descriptor: String?,
        limit: Int?,
    ): String {
        val bridge = requireBridge()
        val className = clazz?.takeIf { it.isNotBlank() }
        val methodName = method?.takeIf { it.isNotBlank() }
        val target =
            descriptor?.takeIf { it.isNotBlank() }?.let { bridge.getMethodData(it) }
                ?: className?.let { owner ->
                    methodName?.let { name -> methodData(bridge, owner, name, signature) }
                }
                ?: throw IllegalStateException(
                    "方法不存在：需要 descriptor（L类;->方法(参数)返回）或 class + method；DexKit 未命中时返回 null",
                )

        val invokes = JSONArray()
        for (invoked in limited(target.invokes, limit)) {
            invokes.put(
                JSONObject()
                    .put("class", invoked.className)
                    .put("name", invoked.name)
                    .put("sign", invoked.methodSign),
            )
        }

        val callers = JSONArray()
        for (caller in limited(target.callers, limit)) callers.put(methodBrief(caller))

        val usingFields = JSONArray()
        for (used in limited(target.usingFields, limit)) usingFields.put(used.toString())

        val numbers = JSONArray()
        for (number in limited(target.usingNumbers, limit)) numbers.put(number.toString())

        val ops = JSONArray()
        for (name in limited(target.opNames, limit)) ops.put(name)

        return JSONObject()
            .put("ok", true)
            .put("resolvedBy", if (!descriptor.isNullOrBlank()) "descriptor" else "class+method")
            .put("class", target.className)
            .put("name", target.name)
            .put("sign", target.methodSign)
            .put("descriptor", target.descriptor)
            .put("modifiers", Modifier.toString(target.modifiers))
            .put("accessFlags", target.accessFlags)
            .put("returnType", target.returnTypeName)
            .put("paramTypes", JSONArray(target.paramTypeNames))
            .put("paramNames", JSONArray(target.paramNames ?: emptyList<String>()))
            .put("isConstructor", target.isConstructor)
            .put("annotations", texts(target.annotations.map { it.toString() }))
            .put("opCodeCount", target.opCodes.size)
            .put("opNames", ops)
            .put("usingStringCount", target.usingStrings.size)
            .put("usingStrings", JSONArray(limited(target.usingStrings, limit)))
            .put("usingNumbers", numbers)
            .put("usingFields", usingFields)
            .put("invokeCount", target.invokes.size)
            .put("invokes", invokes)
            .put("callerCount", target.callers.size)
            .put("callers", callers)
            .toString()
    }

    // ==================== 批量字符串搜索 ====================

    /**
     * 一次调用检索多组字符串锚点（DexKit 批量接口），比逐组查询高效。
     *
     * @param groups 组名 → 字符串列表。**组内为 AND**：目标需用到该组全部字符串；不同组互相独立。
     *               匹配方式由 [stringMatch] 决定，默认 `Contains`。
     * @param clazz 可选，限定在某个类内（方法批量用 `searchInClasses`，类批量用 `searchIn`）。
     */
    fun batchFindJson(
        target: String,
        groups: List<Pair<String, List<String>>>,
        pkg: String?,
        excludePkg: String?,
        clazz: String?,
        stringMatch: String?,
        limit: Int?,
    ): String {
        val bridge = requireBridge()
        if (groups.isEmpty()) throw IllegalStateException("groups 为空")
        val cleaned =
            groups.map { (groupName, strings) ->
                val filtered = strings.filter { it.isNotEmpty() }
                if (filtered.isEmpty()) throw IllegalStateException("分组 $groupName 没有有效字符串")
                groupName to filtered
            }
        val match = matchType(stringMatch)
        val targetKind = target.lowercase()
        if (targetKind !in listOf("class", "classes", "method", "methods")) {
            throw IllegalStateException("target 只能是 class/classes/method/methods，实际: $target")
        }
        val classData = clazz?.takeIf { it.isNotBlank() }?.let { requireClassData(bridge, it) }

        val result: Map<String, Pair<List<Any>, List<JSONObject>>> =
            when (targetKind) {
                "method", "methods" -> {
                    bridge
                        .batchFindMethodUsingStrings {
                            classData?.let { searchInClasses(listOf(it)) }
                            pkg?.takeIf { it.isNotBlank() }?.let { searchPackages(it) }
                            excludePkg?.takeIf { it.isNotBlank() }?.let { excludePackages(it) }
                            for ((groupName, strings) in cleaned) {
                                addSearchGroup {
                                    groupName(groupName)
                                    strings.forEach { add(StringMatcher(it, match, false)) }
                                }
                            }
                        }.mapValues { (_, list) -> list to limited(list, limit).map { methodBrief(it) } }
                }

                else -> {
                    bridge
                        .batchFindClassUsingStrings {
                            classData?.let { searchIn(listOf(it)) }
                            pkg?.takeIf { it.isNotBlank() }?.let { searchPackages(it) }
                            excludePkg?.takeIf { it.isNotBlank() }?.let { excludePackages(it) }
                            for ((groupName, strings) in cleaned) {
                                addSearchGroup {
                                    groupName(groupName)
                                    strings.forEach { add(StringMatcher(it, match, false)) }
                                }
                            }
                        }.mapValues { (_, list) -> list to limited(list, limit).map { classBrief(it) } }
                }
            }

        val groupsJson = JSONObject()
        for (groupName in result.keys.sorted()) {
            val (all, items) = result.getValue(groupName)
            val arr = JSONArray()
            for (item in items) arr.put(item)
            groupsJson.put(
                groupName,
                JSONObject().put("total", all.size).put("count", items.size).put("items", arr),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("target", target)
            .put("matchedStringMatch", match.name)
            .put("groups", groupsJson)
            .toString()
    }

    // ==================== 内部工具 ====================

    private fun requireBridge(): DexKitBridge =
        DexKitSupport.bridgeOrNull()
            ?: throw IllegalStateException("DexKit 未就绪：宿主 APK 尚未初始化或 dexkit so 加载失败")

    /** 字符串锚点：传了但全是空串时明确报错（避免静默退化成全量查询）。 */
    private fun requireAnchors(values: List<String>): List<String> {
        val filtered = values.filter { it.isNotEmpty() }
        if (values.isNotEmpty() && filtered.isEmpty()) {
            throw IllegalStateException("字符串锚点不能全为空")
        }
        return filtered
    }

    /** DexKit 的 AccessFlagsMatcher 要求掩码非 0，提前给人话报错。 */
    private fun requireNonZeroFlags(
        modifiers: Int?,
        accessFlags: Int?,
    ) {
        if (modifiers == 0) throw IllegalStateException("modifiers 不能为 0（Java 修饰符位掩码）")
        if (accessFlags == 0) throw IllegalStateException("accessFlags 不能为 0（原始 DEX 标志掩码）")
    }

    private fun requireClassData(
        bridge: DexKitBridge,
        clazz: String,
    ): ClassData {
        if (clazz.isBlank()) throw IllegalStateException("class 不能为空")
        return bridge.getClassData(clazz) ?: throw IllegalStateException("类不在 dex 中: $clazz")
    }

    /**
     * 匹配方式：直接用 DexKit 的 `StringMatchType` 枚举名，**默认 `Contains`**。
     * 速度（快到慢）：`Equals` > `StartsWith` > `EndsWith` > `Contains` > `SimilarRegex`。
     */
    private fun matchType(name: String?): StringMatchType =
        when (name?.trim()?.lowercase()) {
            null, "", "contains" -> {
                StringMatchType.Contains
            }

            "equals" -> {
                StringMatchType.Equals
            }

            "startswith" -> {
                StringMatchType.StartsWith
            }

            "endswith" -> {
                StringMatchType.EndsWith
            }

            "similarregex" -> {
                StringMatchType.SimilarRegex
            }

            else -> {
                throw IllegalStateException(
                    "不支持的匹配方式: $name（StringMatchType 取值：Contains(默认) / Equals / StartsWith / EndsWith / SimilarRegex）",
                )
            }
        }

    /** 原始 DEX 标志的包含/精确匹配。 */
    private fun flagMatch(exact: Boolean): MatchType = if (exact) MatchType.Equals else MatchType.Contains

    /** 字段用途：any(默认，读或写) / read / write。 */
    private fun usingTypeOf(name: String?): UsingType =
        when (name?.trim()?.lowercase()) {
            null, "", "any" -> UsingType.Any
            "read" -> UsingType.Read
            "write" -> UsingType.Write
            else -> throw IllegalStateException("不支持的 usingFieldUsage: $name（any(默认) / read / write）")
        }

    private fun methodData(
        bridge: DexKitBridge,
        clazz: String,
        method: String,
        signature: String?,
    ): MethodData {
        val classData = requireClassData(bridge, clazz)
        val candidates = classData.methods.filter { it.name == method }
        if (candidates.isEmpty()) throw IllegalStateException("方法不存在: $clazz.$method")
        val matched =
            if (signature.isNullOrBlank()) {
                candidates
            } else {
                candidates.filter { it.methodSign == signature }
            }
        if (matched.isEmpty()) {
            throw IllegalStateException(
                "参数不匹配: $clazz.$method，候选签名：${candidates.joinToString("; ") { it.methodSign }}",
            )
        }
        if (matched.size > 1) {
            throw IllegalStateException(
                "方法不唯一: $clazz.$method，请指定 signature 消歧，" +
                    "候选签名：${matched.joinToString("; ") { it.methodSign }}",
            )
        }
        return matched.first()
    }

    private fun classBrief(clazz: ClassData): JSONObject {
        val interfaces = JSONArray()
        for (iface in clazz.interfaces) interfaces.put(iface.name)
        return JSONObject()
            .put("name", clazz.name)
            .put("descriptor", clazz.descriptor)
            .put("super", clazz.superClass?.name ?: JSONObject.NULL)
            .put("interfaces", interfaces)
            .put("methodCount", clazz.methodCount)
            .put("fieldCount", clazz.fieldCount)
            .put("sourceFile", clazz.sourceFile)
            .put("modifiers", Modifier.toString(clazz.modifiers))
            .put("accessFlags", clazz.accessFlags)
    }

    private fun methodBrief(method: MethodData): JSONObject =
        JSONObject()
            .put("class", method.className)
            .put("name", method.name)
            .put("sign", method.methodSign)
            .put("returnType", method.returnTypeName)
            .put("paramTypes", JSONArray(method.paramTypeNames))
            .put("modifiers", Modifier.toString(method.modifiers))
            .put("accessFlags", method.accessFlags)

    private fun fieldBrief(field: FieldData): JSONObject =
        JSONObject()
            .put("class", field.className)
            .put("name", field.fieldName)
            .put("type", field.typeName)
            .put("modifiers", Modifier.toString(field.modifiers))
            .put("accessFlags", field.accessFlags)

    private fun texts(values: List<String>): JSONArray {
        val arr = JSONArray()
        for (value in values) arr.put(value)
        return arr
    }

    /** 可选限量：limit 为空或非正时返回全部。 */
    private fun <T> limited(
        list: List<T>,
        limit: Int?,
    ): List<T> = if (limit != null && limit > 0) list.take(limit) else list

    /** 结果外壳：总数 + 内容。 */
    private fun result(
        total: Int,
        items: JSONArray,
    ): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("total", total)
            .put("items", items)
}
