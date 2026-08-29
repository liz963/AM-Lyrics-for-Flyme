package com.amlyric.flyme

import android.annotation.SuppressLint
import com.amlyric.flyme.hook.AppleMusicHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed（API 102）模块入口。
 *
 * 与 legacy API 的区别：
 *  - 入口类继承 [XposedModule]，由 META-INF/xposed/java_init.list 声明，
 *    框架自动调用 attachFramework()，构造函数保持无参。
 *  - 所有初始化放到 [onModuleLoaded] 之后进行。
 *  - 作用域由 META-INF/xposed/scope.list（staticScope=true）固定为
 *    com.apple.android.music；注入后进程内加载的其它包也会触发回调，
 *    因此这里必须按包名 + isFirstPackage 过滤。
 */
class HookEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XLog.attach(this)
        XLog.i("module loaded: framework=$frameworkName $frameworkVersion, api=$apiVersion")
    }

    @SuppressLint("NewApi") // onPackageLoaded 仅在 API 29+ 触发，defaultClassLoader 必然可用
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != AppleMusicHooks.TARGET_PACKAGE) return
        if (!param.isFirstPackage) return

        runCatching {
            AppleMusicHooks.install(this, param.defaultClassLoader)
        }.onFailure {
            XLog.e("install hooks failed", it)
        }
    }
}
