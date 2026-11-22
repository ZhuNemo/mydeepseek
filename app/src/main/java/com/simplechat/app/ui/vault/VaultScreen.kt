package com.simplechat.app.ui.vault

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.lock.PinLockScreen
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.launch

/**
 * 保险箱的阶段。
 *
 * 用状态机而不是几个布尔量：这几个状态互斥，而且"从哪个状态来"决定返回键该退到哪。
 * 三个独立的 `Boolean` 会立刻出现"同时是解锁中和设置中"这种不可能的组合。
 */
private enum class VaultStage { SET_PIN, CONFIRM_PIN, UNLOCK, RESET, LIST }

/** PIN 长度。六位是"能记住"和"猜不完"之间最常用的折中。 */
private const val PinLength = 6

/**
 * 对话保险箱。
 *
 * ⚠️ **它是"界面隐藏"，不是加密。** 会话在数据库里仍是明文 ——
 * 挡住的是"别人拿着你已经解锁的手机翻列表"，挡不住 root / 备份 / 取证工具。
 * 这条边界必须对用户说清楚，所以页脚常驻一行提示。
 *
 * 解锁有两条路，语义刻意不同：
 * - **跟随屏幕锁定**：交给系统 `KeyguardManager` 验设备凭据，指纹 / 图案 / PIN 都行，
 *   本应用既看不到也不需要保存任何东西；
 * - **本应用的 PIN**：PBKDF2 哈希存在本地，只有本应用认。
 */
@Composable
fun VaultScreen(
    stored: StoredSettings,
    conversations: List<ConversationEntity>,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onVerifyPin: suspend (String) -> Boolean,
    onSetPin: suspend (String) -> Unit,
    onVerifyAnswer: suspend (String) -> Boolean,
    /**
     * 整机应用锁已经解开时传 true：进入保险箱不再要求再输一次。
     *
     * 两者共用同一把 PIN —— 同一把密码连输两遍不增加安全性，只是摩擦。
     * 整机锁没开时保持默认 false，保险箱仍单独解锁。
     */
    preUnlocked: Boolean = false,
) {
    val colors = LocalChatColors.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var stage by remember(preUnlocked) {
        mutableStateOf(
            when {
                preUnlocked -> VaultStage.LIST
                stored.hasVaultPin -> VaultStage.UNLOCK
                else -> VaultStage.SET_PIN
            },
        )
    }
    var firstPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }

    /*
     * 系统凭据验证。`createConfirmDeviceCredentialIntent` 会弹出**系统自己的**
     * 解锁界面，结果只回一个 resultCode —— 本应用拿不到任何凭据。
     */
    val lockLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                error = null
                stage = VaultStage.LIST
            }
            // 用户自己取消不算错误，别弹红字
            Activity.RESULT_CANCELED -> Unit
            else -> error = Res.get(R.string.vault_screen_lock_failed)
        }
    }

    fun tryScreenLock() {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isDeviceSecure) {
            error = Res.get(R.string.vault_screen_lock_unavailable)
            return
        }
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            Res.get(R.string.vault_title),
            Res.get(R.string.vault_unlock),
        )
        if (intent == null) {
            error = Res.get(R.string.vault_screen_lock_unavailable)
        } else {
            lockLauncher.launch(intent)
        }
    }

    /**
     * 校验并推进阶段。
     *
     * ⚠️ 必须挂在**提交回调**里，不能用 `LaunchedEffect(entry)`：后者按"值有没有变"
     * 触发，用户输错后重输同一个密码时 `entry` 与上一次完全相同，Effect 不会重跑 ——
     * 表现就是"第二次输对了却没反应"。
     */
    fun submit(pin: String) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                when (stage) {
                    VaultStage.SET_PIN -> {
                        firstPin = pin
                        stage = VaultStage.CONFIRM_PIN
                    }

                    VaultStage.CONFIRM_PIN -> {
                        if (pin == firstPin) {
                            onSetPin(pin)
                            stage = VaultStage.LIST
                        } else {
                            firstPin = ""
                            error = Res.get(R.string.vault_pin_mismatch)
                            stage = VaultStage.SET_PIN
                        }
                    }

                    VaultStage.UNLOCK -> {
                        if (onVerifyPin(pin)) {
                            stage = VaultStage.LIST
                        } else {
                            error = Res.get(R.string.vault_wrong_pin)
                        }
                    }

                    else -> Unit
                }
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground),
    ) {
        Spacer(Modifier.statusBarsPadding())

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.ChevronLeft,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResourceTitle(stage),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            if (stage == VaultStage.LIST) {
                TextButton(onClick = { stage = VaultStage.UNLOCK; error = null }) {
                    Text(Res.get(R.string.vault_lock))
                }
            }
        }

        when (stage) {
            VaultStage.LIST -> VaultList(
                conversations = conversations,
                onOpen = onOpenConversation,
            )

            VaultStage.RESET -> ResetPanel(
                question = stored.vaultQuestion,
                answer = answer,
                error = error,
                onAnswerChange = { answer = it },
                onCancel = { stage = VaultStage.UNLOCK; error = null; answer = "" },
                onSubmit = {
                    scope.launch {
                        busy = true
                        val ok = onVerifyAnswer(answer)
                        busy = false
                        if (ok) {
                            answer = ""
                            error = null
                            firstPin = ""
                            // 答案对了 → 直接进设置新 PIN，而不是放行
                            stage = VaultStage.SET_PIN
                        } else {
                            error = Res.get(R.string.vault_wrong_answer)
                        }
                    }
                },
            )

            else -> PinLockScreen(
                title = stringResourceTitle(stage),
                subtitle = if (stage == VaultStage.SET_PIN) {
                    Res.get(R.string.lock_pin_hint)
                } else {
                    null
                },
                error = error,
                busy = busy,
                onPinComplete = { pin -> submit(pin) },
                footer = when {
                    stage == VaultStage.SET_PIN || stage == VaultStage.CONFIRM_PIN -> null
                    else -> {
                        {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = ::tryScreenLock) {
                                    Text(Res.get(R.string.vault_use_screen_lock))
                                }
                                if (stored.hasVaultRecovery) {
                                    TextButton(
                                        onClick = {
                                            error = null
                                            stage = VaultStage.RESET
                                        },
                                    ) {
                                        Text(Res.get(R.string.vault_forgot))
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun stringResourceTitle(stage: VaultStage): String = when (stage) {
    VaultStage.SET_PIN -> Res.get(R.string.vault_set_pin)
    VaultStage.CONFIRM_PIN -> Res.get(R.string.vault_confirm_pin)
    VaultStage.UNLOCK -> Res.get(R.string.vault_unlock)
    VaultStage.RESET -> Res.get(R.string.vault_recovery_title)
    VaultStage.LIST -> Res.get(R.string.vault_title)
}

// ══════════════════════════════════════════════════════════
//  PIN 输入
// ══════════════════════════════════════════════════════════

@Composable
private fun ResetPanel(
    question: String,
    answer: String,
    error: String?,
    onAnswerChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = LocalChatColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(12.dp))

        Text(
            text = Res.get(R.string.vault_recovery_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.placeholder,
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = question,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = Res.get(R.string.vault_answer),
            style = MaterialTheme.typography.labelMedium,
            color = colors.placeholder,
        )

        Spacer(Modifier.height(6.dp))

        OutlinedTextField(
            value = answer,
            onValueChange = onAnswerChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text(Res.get(R.string.vault_answer_placeholder)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(Res.get(R.string.vault_recovery_ok))
        }

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(Res.get(R.string.action_cancel))
        }
    }
}

// ══════════════════════════════════════════════════════════
//  列表
// ══════════════════════════════════════════════════════════

@Composable
private fun VaultList(
    conversations: List<ConversationEntity>,
    onOpen: (String) -> Unit,
) {
    val colors = LocalChatColors.current

    if (conversations.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = AppIcons.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = Res.get(R.string.vault_empty),
                style = MaterialTheme.typography.titleSmall,
                color = colors.placeholder,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = Res.get(R.string.vault_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.placeholder,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items = conversations, key = { it.id }) { conversation ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpen(conversation.id) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = conversation.title.ifBlank { Res.get(R.string.conversation_default_title) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = AppIcons.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}
