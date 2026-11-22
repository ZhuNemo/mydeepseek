package com.simplechat.app.ui.lock

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.ui.common.AppIcons
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用锁的解锁方式。
 *
 * - [NONE]：不锁（默认）
 * - [PIN]：应用自己的 6 位数字密码（与保险箱**共用同一把**）
 * - [DEVICE]：交给系统锁屏验证（PIN / 图案 / 指纹 / 人脸都行）
 *
 * ### 为什么 DEVICE 走 `KeyguardManager` 而不是 `androidx.biometric`
 *
 * `BiometricPrompt` 要额外引一个库，而它的入口是 `FragmentActivity` —— 本应用的
 * Activity 是 `ComponentActivity`，接进去得改基类，风险远大于收益。
 * `createConfirmDeviceCredentialIntent` 是系统自带的"验证设备凭据"入口：
 * 用户在系统里设了什么就用什么，连"未录入指纹"这类分支都不用自己写。
 */
enum class AppLockType(val id: String) {
    NONE("NONE"),
    PIN("PIN"),
    DEVICE("DEVICE"),
    ;

    companion object {
        fun fromId(id: String?): AppLockType =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * 整机应用锁。**包在 `RootScreen` 外面**。
 *
 * ### 锁定时机
 *
 * - **冷启动**：一律要先解锁（`unlocked` 初始为 false）。
 * - **回前台**：`ON_STOP` 记下时间，`ON_START` 时与设置里的[超时]比较；
 *   没超过就继续用，超过了重新锁。ON_STOP 而不是 ON_PAUSE，是因为
 *   下拉通知栏、来电横幅的 ON_PAUSE 并不代表"人离开了"。
 * - **超时设"立即"**：任何一次离开前台都锁。
 *
 * ### 与保险箱的关系
 *
 * 两者共用同一把 PIN。整机锁开着时，解锁后进入保险箱**不再要求再输一次** ——
 * 同一把密码连输两遍不增加任何安全性，只是摩擦。整机锁关着时，
 * 保险箱保持原有的独立解锁（它防的是"手机借给别人时被顺手翻"）。
 *
 * @param content 解锁后要显示的内容，参数是"当前是否已解锁"。
 */
@Composable
fun AppLockGate(
    settingsStore: SettingsStore,
    content: @Composable (unlocked: Boolean) -> Unit,
) {
    val stored by settingsStore.flow.collectAsStateWithLifecycle(initialValue = null)
    val context = LocalContext.current

    /*
     * 首帧 `stored` 还是 null：这时**什么都不画**。
     *
     * 反过来的写法（先假设"不需要锁"直接画内容）会在冷启动时闪一下对话页，
     * 再被锁屏盖住 —— 对"我锁了应用"的人来说，那一帧就是泄露。
     */
    val settings = stored ?: return

    /*
     * 生效的解锁方式。
     *
     * 兜底：选了 PIN 但**从没设置过密码**（或密码被清掉）时不能锁 ——
     * 否则人会被自己的设置关在门外，只能清数据。
     */
    val deviceAvailable = remember(context) { context.hasDeviceLock() }
    val effectiveType = remember(settings.appLockTypeId, settings.hasVaultPin, deviceAvailable) {
        when (val chosen = AppLockType.fromId(settings.appLockTypeId)) {
            AppLockType.PIN -> if (settings.hasVaultPin) AppLockType.PIN else AppLockType.NONE
            /*
             * 选了"与屏幕锁定相同"但设备根本没设锁屏：
             * 有应用密码就退回 PIN，没有就只能不锁（否则谁都进不去）。
             */
            AppLockType.DEVICE -> when {
                deviceAvailable -> AppLockType.DEVICE
                settings.hasVaultPin -> AppLockType.PIN
                else -> AppLockType.NONE
            }
            AppLockType.NONE -> AppLockType.NONE
        }
    }

    var unlocked by remember { mutableStateOf(effectiveType == AppLockType.NONE) }
    var stoppedAt by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 设置里把锁关掉/换方式 → 立刻按新设置重置，不必等下一次重启
    LaunchedEffect(effectiveType, settings.appLockTimeoutSeconds) {
        if (effectiveType == AppLockType.NONE) unlocked = true
    }

    /*
     * 回前台时判断是否超时。`ON_START` 在首次组合后也会来一次 ——
     * 那时 `stoppedAt` 还是 0，条件不成立，不会误锁。
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, effectiveType, settings.appLockTimeoutSeconds) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> stoppedAt = System.currentTimeMillis()
                Lifecycle.Event.ON_START -> {
                    if (stoppedAt == 0L) return@LifecycleEventObserver
                    val elapsed = System.currentTimeMillis() - stoppedAt
                    val limit = settings.appLockTimeoutSeconds.coerceAtLeast(0) * 1000L
                    if (elapsed >= limit) {
                        unlocked = false
                        error = null
                    }
                    stoppedAt = 0L
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (unlocked || effectiveType == AppLockType.NONE) {
        content(unlocked = effectiveType != AppLockType.NONE)
        return
    }

    when (effectiveType) {
        AppLockType.PIN -> PinLockScreen(
            title = Res.get(R.string.lock_title_unlock),
            subtitle = Res.get(R.string.lock_subtitle_unlock, PinLength),
            error = error,
            busy = verifying,
            onPinComplete = { pin ->
                if (!verifying) {
                verifying = true
                error = null
                scope.launch {
                    // PBKDF2 要一两百毫秒，放 IO；结果回来前界面保持"验证中"
                    val ok = withContext(Dispatchers.IO) { settingsStore.verifyVaultPin(pin) }
                    verifying = false
                    if (ok) {
                        unlocked = true
                    } else {
                        error = Res.get(R.string.vault_wrong_pin)
                    }
                }
                }
            },
        )

        AppLockType.DEVICE -> DeviceLockScreen(
            onUnlocked = { unlocked = true },
            hasPin = settings.hasVaultPin,
        )

        AppLockType.NONE -> content(unlocked = false)
    }
}

/**
 * "与屏幕锁定相同"的解锁页。
 *
 * 只放一个按钮：进页面就自动弹一次系统验证（和系统应用锁的手感一致），
 * 被用户取消后不自动重弹，改成手动点。
 */
@Composable
private fun DeviceLockScreen(
    onUnlocked: () -> Unit,
    hasPin: Boolean,
) {
    val context = LocalContext.current
    val keyguard = remember(context) {
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }
    val deviceSecure = remember(keyguard) { keyguard?.isDeviceSecure == true }
    var prompted by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) onUnlocked()
    }

    fun prompt() {
        if (keyguard == null || !deviceSecure) return
        val intent: Intent? = keyguard.createConfirmDeviceCredentialIntent(
            Res.get(R.string.lock_title_unlock),
            Res.get(R.string.lock_device_prompt),
        )
        if (intent != null) launcher.launch(intent)
    }

    LaunchedEffect(deviceSecure) {
        if (deviceSecure && !prompted) {
            prompted = true
            prompt()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = AppIcons.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = Res.get(R.string.lock_title_unlock),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (deviceSecure) {
                Res.get(R.string.lock_device_hint)
            } else {
                Res.get(R.string.lock_device_unavailable)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (deviceSecure) {
            FilledTonalButton(
                onClick = { prompt() },
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
            ) {
                Text(Res.get(R.string.lock_use_device))
            }
        }
        if (!deviceSecure && !hasPin) {
            // 既没有系统锁屏、也没有应用密码：唯一出路是去系统设置里补一个
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_SECURITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
            ) {
                Text(Res.get(R.string.lock_open_security_settings))
            }
        }
    }
}

/** 判断设备是否设了锁屏（设置页用它决定"与屏幕锁定相同"能不能选）。 */
fun Context.hasDeviceLock(): Boolean =
    (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure == true

/** 打开系统安全设置（设置页里"设备未设锁屏"时的跳转）。 */
fun Context.openSecuritySettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 给"应用锁"设置页用的当前值摘要。 */
fun StoredSettings.appLockSummary(): AppLockType = AppLockType.fromId(appLockTypeId)
