package io.github.yfishyon.doumcp

import io.github.yfishyon.doumcp.core.HostRuntime
import io.github.yfishyon.doumcp.core.ModLog
import io.github.yfishyon.doumcp.core.ResolvedCache
import io.github.yfishyon.doumcp.features.account.resolver.AccountResolver
import io.github.yfishyon.doumcp.features.comment.resolver.CommentResolver
import io.github.yfishyon.doumcp.features.im.resolver.ImResolver
import io.github.yfishyon.doumcp.features.search.resolver.SearchResolver
import io.github.yfishyon.doumcp.features.video.resolver.VideoResolver

/**
 * 启动适配：当前模块版本 + 宿主版本没有适配记录时，在后台把全部定位跑一遍并写入缓存。
 *
 * 已适配则跳过；定位结果在首次使用时从缓存还原，不建 DexKit 索引。
 * 未定位到的项记降级日志，首次使用时仍会重新定位。
 */
object FeatureWarmup {
    /** 适配记录占用的缓存 feature 名（与实际定位 feature 不重名） */
    private const val MARK = "__adapted__"

    /** 需要预热的定位项：feature 名 + 定位动作 */
    private val targets: List<Pair<String, () -> Any?>> =
        listOf(
            "account_switch" to { AccountResolver.resolveSwitchMethod() },
            "profile_fetch" to { AccountResolver.resolveProfileFetchMethod() },
            "video_detail" to { VideoResolver.resolveDetailMethod() },
            "comment_reply" to { CommentResolver.resolveReplyFetcher() },
            "search_provider" to { SearchResolver.resolveApiProvider() },
            "search_call" to { SearchResolver.resolveGenericCall() },
            "im_user_fetch" to { ImResolver.resolveUserFetch() },
        )

    /** 无适配记录时全量定位并写记录；定位结果走 [ResolvedCache]，按模块版本 + 宿主版本隔离。 */
    fun warmUpIfNeeded() {
        val context = HostRuntime.context ?: return
        val versionCode = HostRuntime.versionCode
        val markKey = ResolvedCache.versionedKey(versionCode, MARK)
        if (ResolvedCache.load(context, markKey) != null) {
            ModLog.i("适配：当前版本已有适配记录，跳过")
            return
        }

        ModLog.i("适配：开始全量定位（${targets.size} 项）")
        var failed = 0
        targets.forEach { (name, resolve) ->
            runCatching { resolve() }
                .onSuccess {
                    if (it == null) {
                        failed++
                        ModLog.w("适配：$name 未定位到")
                    }
                }.onFailure {
                    failed++
                    ModLog.e("适配：$name 定位异常", it)
                }
        }

        ResolvedCache.save(context, markKey, BuildConfig.VERSION_NAME)
        ModLog.i("适配：完成 ${targets.size - failed}/${targets.size} 项")
    }
}