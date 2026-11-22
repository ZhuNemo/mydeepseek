package com.simplechat.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplechat.app.data.AppLanguage
import com.simplechat.app.data.LocaleHelper
import com.simplechat.app.ui.RootScreen
import com.simplechat.app.ui.lock.AppLockGate
import com.simplechat.app.ui.common.LocalUseSimpleCatIcon
import com.simplechat.app.ui.theme.AccentColor
import com.simplechat.app.ui.theme.ApplySystemBarAppearance
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // composable 的 `stringResource` 走这里的 resources，所以语言也得在这包一层。
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 诊断日志的第一条：从进程启动起算，排查时能看出"这一场"是什么时候开始的
        com.simplechat.app.data.Diagnostics.log(
            "APP",
            "onCreate ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "android=${android.os.Build.VERSION.SDK_INT} device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        )

        val settingsStore = (application as App).container.settingsStore
        val systemFontScale = resources.configuration.fontScale.coerceAtLeast(0.01f)

        setContent {
            val stored by settingsStore.flow.collectAsStateWithLifecycle(
                initialValue = null,
            )

            val current = stored

            // 设置里换了语言：改完缓存整页重建，两个 context（App / Activity）重包一遍。
            // 首帧 `current` 还是 null（异步未到），那不是"语言变了"，不能重建。
            LaunchedEffect(current?.languageId) {
                if (current == null) return@LaunchedEffect
                val language = AppLanguage.fromId(current.languageId)
                if (language != LocaleHelper.currentLanguage(this@MainActivity)) {
                    LocaleHelper.onLanguageChanged(language)
                    recreate()
                }
            }

            LaunchedEffect(current?.useSimpleCatIcon) {
                current?.let { updateLauncherIcon(it.useSimpleCatIcon) }
            }

            val themeMode = runCatching {
                ThemeMode.valueOf(current?.themeModeId ?: ThemeMode.SYSTEM.name)
            }.getOrDefault(ThemeMode.SYSTEM)

            // 存的是枚举名；认不出来（比如回滚到旧版本后又被改坏）就回落到蓝色
            val accent = runCatching {
                AccentColor.valueOf(current?.accentColorId ?: AccentColor.BLUE.name)
            }.getOrDefault(AccentColor.BLUE)

            // 跟随系统时交给系统（倍率传 1 即"原样"）；不跟随时用用户值**替换**系统倍率，
            // 所以要先除掉系统那一层，否则会和系统设置相乘。
            val fontScale = when {
                current == null -> 1f
                current.followSystemFont -> 1f
                else -> current.fontScale / systemFontScale
            }

            SimpleChatTheme(
                themeMode = themeMode,
                accent = accent,
                fontScale = fontScale,
                pureBlack = current?.pureBlack ?: false,
            ) {
                ApplySystemBarAppearance()
                CompositionLocalProvider(LocalUseSimpleCatIcon provides (current?.useSimpleCatIcon ?: true)) {
                    /*
                     * 应用锁包在**最外层**：它要盖住所有页面（对话、设置、保险箱），
                     * 而不只是某一个入口。冷启动与回前台的重新上锁都由它判断。
                     */
                    AppLockGate(settingsStore) { unlocked ->
                        RootScreen(appLockUnlocked = unlocked)
                    }
                }
            }
        }
    }

    internal fun applyLauncherIconAndExit(useSimpleCat: Boolean) {
        updateLauncherIcon(useSimpleCat)
        finishAndRemoveTask()
    }

    private fun updateLauncherIcon(useSimpleCat: Boolean) {
        val classic = ComponentName(packageName, "com.simplechat.app.ClassicLauncher")
        val cat = ComponentName(packageName, "com.simplechat.app.SimpleCatLauncher")
        val manager = packageManager
        val selected = if (useSimpleCat) cat else classic
        val unselected = if (useSimpleCat) classic else cat
        val enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val disabled = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val default = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        val flags = PackageManager.DONT_KILL_APP

        val changes = listOf(selected to enabled, unselected to disabled).filter { (component, target) ->
            val state = manager.getComponentEnabledSetting(component)
            state != target && !(state == default && (component == cat) == (target == enabled))
        }
        if (changes.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.setComponentEnabledSettings(changes.map { (component, state) ->
                PackageManager.ComponentEnabledSetting(component, state, flags)
            })
        } else {
            // Keep a launcher entry available while switching on older Android versions.
            changes.forEach { (component, state) ->
                manager.setComponentEnabledSetting(component, state, flags)
            }
        }
    }
}
