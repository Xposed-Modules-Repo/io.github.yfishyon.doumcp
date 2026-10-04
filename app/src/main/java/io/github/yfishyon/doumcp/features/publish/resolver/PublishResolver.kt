package io.github.yfishyon.doumcp.features.publish.resolver

import io.github.yfishyon.doumcp.core.DexKitSupport
import io.github.yfishyon.doumcp.core.HostRuntime
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 发布域的定位器。
 *
 * 发布作品的网络入口是宿主自己的 Retrofit 接口：参数是一整个表单 map、返回值是
 * Retrofit 调用对象（端点写在注解里，字符串检索看不到）。真人发布时最终调用的是
 * 一具体类上的包装方法，模块发送也走同一入口，与宿主自身发布同链路。
 *
 * 全部按「参数类型 + 返回类型 + 修饰符」结构匹配，只用未混淆的库类型
 * （java.util.LinkedHashMap / com.bytedance.retrofit2.Call）做锚点。
 */
object PublishResolver {
    private const val RETROFIT_CALL = "com.bytedance.retrofit2.Call"
    private const val FORM_MAP = "java.util.LinkedHashMap"

    /**
     * 发布收口方法：把完整表单 map 交给它、返回待执行的 Retrofit 调用。
     *
     * 同名签名的候选里只有它所在类是具体类，其余是接口/抽象方法——运行时按修饰符过滤。
     */
    fun resolvePublishCaller(): Method? =
        DexKitSupport.resolveCached("publish_create_caller") { dexKit ->
            dexKit
                .findMethod {
                    matcher {
                        paramTypes(FORM_MAP)
                        returnType = RETROFIT_CALL
                    }
                }.firstOrNull { !Modifier.isAbstract(it.modifiers) }
                ?.getMethodInstance(HostRuntime.requireClassLoader())
        }
}
