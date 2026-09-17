package com.amlyric.flyme.util

import java.lang.reflect.Method

/**
 * 轻量反射工具，替代 legacy API 的 XposedHelpers.callMethod。
 *
 * libxposed 新 API 明确不再提供 XposedHelpers 这类便捷封装，
 * 这里按「本类 + 父类逐级 declaredMethods，最后兜底 public methods」
 * 的顺序查找，与 callMethod 的 findMethodBestMatch 行为等价，
 * 且任何失败都返回 null，绝不向宿主抛异常。
 */
object Reflect {

    fun call(target: Any?, method: String, vararg args: Any?): Any? {
        if (target == null) return null
        return runCatching {
            findMethod(target.javaClass, method, args.size)
                ?.invoke(target, *args)
        }.getOrNull()
    }

    /** 取字符串属性；拿不到（方法不存在 / 类型不符）返回 null */
    fun string(target: Any?, method: String): String? = call(target, method) as? String

    /**
     * 取数值属性（兼容 Long/Int/其它 Number）；拿不到返回 0。
     * 注意：返回值 0 与「真的取到 0」不可区分，调用方如需区分请自行用 [call] 判空。
     */
    fun long(target: Any?, method: String): Long =
        when (val v = call(target, method)) {
            is Long -> v
            is Int -> v.toLong()
            is Number -> v.toLong()
            else -> 0L
        }

    private fun findMethod(cls: Class<*>, name: String, argc: Int): Method? {
        var c: Class<*>? = cls
        while (c != null) {
            val m = c.declaredMethods.firstOrNull { it.name == name && it.parameterCount == argc }
            if (m != null) {
                m.isAccessible = true
                return m
            }
            c = c.superclass
        }
        // 兜底：接口默认方法 / 代理暴露的 public 方法
        return cls.methods.firstOrNull { it.name == name && it.parameterCount == argc }
    }
}
