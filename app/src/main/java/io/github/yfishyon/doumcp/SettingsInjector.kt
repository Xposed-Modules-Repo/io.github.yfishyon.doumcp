package io.github.yfishyon.doumcp

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.yfishyon.doumcp.core.DouToastHelper
import io.github.yfishyon.doumcp.core.ModLog
import java.lang.reflect.Method

/**
 * 抖音设置页注入「抖M设置」入口。
 *
 * 定位宿主设置 Activity 的 onCreate，在原生条目模板处插入同款样式的入口项
 * （tag 标记防重复注入），点击弹出端口/密钥配置对话框。
 */
object SettingsInjector {
    private const val SETTING_ACTIVITY = "com.ss.android.ugc.aweme.setting.ui.DouYinSettingNewVersionActivity"
    private const val COMMON_ITEM_VIEW = "com.bytedance.ies.dmt.ui.common.views.CommonItemView"
    private const val MODULE_ENTRY_TAG = "doumcp_settings_entry"
    private const val MODULE_PACKAGE = "io.github.yfishyon.doumcp"
    private const val DEFAULT_ICON_SIZE_PX = 48

    /**
     * 定位宿主设置页的 onCreate 方法（hook 目标）。
     *
     * @param classLoader 宿主 ClassLoader
     * @return 目标 Method；设置页类不存在时返回 null
     */
    fun targetCreateMethod(classLoader: ClassLoader): Method? {
        val activityClass =
            runCatching {
                Class.forName(SETTING_ACTIVITY, false, classLoader)
            }.getOrNull() ?: return null
        return activityClass.declaredMethods.firstOrNull {
            it.name == "onCreate" && it.parameterCount == 1 &&
                it.parameterTypes[0] == android.os.Bundle::class.java
        }
    }

    /**
     * 向设置页注入入口项（已注入则跳过）。
     *
     * @param activity 宿主设置页 Activity
     */
    fun injectEntry(activity: Activity) {
        val itemViewClass =
            runCatching {
                Class.forName(COMMON_ITEM_VIEW, false, activity.classLoader)
            }.getOrNull() ?: return

        val contentView = activity.findViewById<View>(android.R.id.content) ?: return
        val template = findAllViews(contentView, itemViewClass).firstOrNull() ?: return
        val parent = template.parent as? ViewGroup ?: return
        if (findModuleEntry(parent) != null) return

        val entry =
            itemViewClass
                .getDeclaredConstructor(Context::class.java)
                .newInstance(activity) as View
        entry.tag = MODULE_ENTRY_TAG

        cloneVisualState(template, entry)
        setLeftText(entry, "抖M设置")
        setModuleIcon(entry, activity)

        entry.setOnClickListener { showConfigDialog(activity) }
        parent.addView(entry, parent.indexOfChild(template))
    }

    private fun findModuleEntry(parent: ViewGroup): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.tag == MODULE_ENTRY_TAG) return child
        }
        return null
    }

    /** 从模板条目复制布局参数、背景与右侧图标，使入口项与原生条目外观一致 */
    private fun cloneVisualState(
        template: View,
        entry: View,
    ) {
        template.layoutParams?.let { lp ->
            entry.layoutParams =
                when (lp) {
                    is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(lp)
                    else -> ViewGroup.LayoutParams(lp)
                }
        }
        template.background
            ?.constantState
            ?.newDrawable()
            ?.let { entry.background = it }

        val templateRight = rightIconView(template) ?: return
        val entryRight = rightIconView(entry) ?: return
        templateRight.drawable?.constantState?.newDrawable()?.let {
            entryRight.setImageDrawable(it)
        }
    }

    private fun setLeftText(
        entry: View,
        text: String,
    ) {
        runCatching {
            entry.javaClass.getMethod("setLeftText", CharSequence::class.java).invoke(entry, text)
        }
    }

    /** 左图标位设为模块图标（资源取自模块 APK，转位图后设置） */
    private fun setModuleIcon(
        entry: View,
        context: Context,
    ) {
        runCatching {
            val icon =
                leftIconView(entry) ?: run {
                    ModLog.w("图标：未找到左图标 view")
                    return
                }
            val moduleRes = context.packageManager.getResourcesForApplication(MODULE_PACKAGE)
            val drawable = moduleRes.getDrawable(R.drawable.ic_doumcp, null)
            icon.setImageBitmap(drawableToBitmap(drawable))
            icon.visibility = View.VISIBLE
        }.onFailure { ModLog.w("图标：设置失败 $it") }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE_PX
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_ICON_SIZE_PX
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
        }
    }

    private fun leftIconView(item: View): ImageView? = iconView(item, "getIvwLeft")

    private fun rightIconView(item: View): ImageView? = iconView(item, "getIvwRight")

    private fun iconView(
        item: View,
        accessor: String,
    ): ImageView? =
        runCatching {
            item.javaClass.getMethod(accessor).invoke(item) as? ImageView
        }.getOrNull()

    /** 端口/密钥配置对话框（保存后 toast 提示重启抖音生效） */
    private fun showConfigDialog(activity: Activity) {
        val context = activity
        runCatching {
            val padding = (12 * context.resources.displayMetrics.density).toInt()

            val portInput =
                EditText(context).apply {
                    hint = "端口（默认 19320）"
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(ModulePrefs.getPort(context).toString())
                    setPadding(padding, padding, padding, padding)
                }
            val tokenInput =
                EditText(context).apply {
                    hint = "鉴权密钥（留空不鉴权）"
                    setText(ModulePrefs.getToken(context))
                    setPadding(padding, padding, padding, padding)
                }
            val container =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padding, padding, padding, padding)
                    addView(portInput)
                    addView(tokenInput)
                }

            AlertDialog
                .Builder(context)
                .setTitle("抖M设置")
                .setMessage("修改后需要重启抖音生效")
                .setView(container)
                .setPositiveButton("保存") { _, _ ->
                    val port = portInput.text.toString().toIntOrNull()
                    if (port != null && port in 1024..65535) {
                        ModulePrefs.setPort(context, port)
                    }
                    ModulePrefs.setToken(context, tokenInput.text.toString().trim())
                    DouToastHelper.show(context, "抖M：已保存，重启抖音后生效")
                }.setNegativeButton("取消", null)
                .show()
        }.onFailure { ModLog.w("设置对话框打开失败: $it") }
    }

    private fun findAllViews(
        root: View,
        targetClass: Class<*>,
    ): List<View> {
        val result = mutableListOf<View>()
        if (targetClass.isInstance(root)) {
            result.add(root)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                result.addAll(findAllViews(root.getChildAt(i), targetClass))
            }
        }
        return result
    }
}
