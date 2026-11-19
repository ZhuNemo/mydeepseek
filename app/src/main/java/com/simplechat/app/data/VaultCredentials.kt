package com.simplechat.app.data

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 保险箱的凭据校验（PIN 与密保答案）。
 *
 * ### 为什么用 PBKDF2 而不是直接存哈希
 *
 * 六位数字只有 100 万种可能 —— 一条 SHA-256 在这种空间里等于明文：拿哈希跑一遍
 * 密码表（一台笔记本几秒钟）就能反推。PBKDF2 用**迭代**把每次尝试变贵，
 * 十几万次迭代下同样的穷举要跑几个小时到几天，配合随机 salt 让彩虹表失效。
 *
 * 迭代次数取 12 万：中端机单次约 150–250ms，用户感受不到（一次解锁只算一次），
 * 而暴力破解的代价被放大了同样的倍数。
 *
 * ### 为什么比对要用常量时间
 *
 * `String.equals` 在第一个不同字节就返回，耗时与"猜对了几个字符"相关。
 * 本地攻击者没有这么精确的计时手段，但这个函数只多几行，没有不做的理由。
 *
 * ⚠️ 这一层**不加密任何数据**。它只回答"输入对不对"，保险箱内容是明文存储的
 * （见 `README` 的「保险箱」一节）。
 */
object VaultCredentials {

    /** PBKDF2 迭代次数。改这个值会让已存的哈希失效 —— 别轻易动。 */
    private const val Iterations = 120_000

    private const val KeyBits = 256

    private const val SaltBytes = 16

    /** 生成一个随机 salt（Base64，无换行）。 */
    fun newSalt(): String {
        val bytes = ByteArray(SaltBytes)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** 把凭据 + salt 折算成可存储的哈希（Base64）。 */
    fun hash(secret: String, saltBase64: String): String {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val spec = PBEKeySpec(secret.trim().toCharArray(), salt, Iterations, KeyBits)
        return try {
            val bytes = SecretKeyFactory
                .getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            // 让 JCE 立刻清掉内部那份 char[]，别把它留在堆里等到 GC
            spec.clearPassword()
        }
    }

    /** 常量时间比对。 */
    fun matches(secret: String, saltBase64: String, expectedHash: String): Boolean {
        if (saltBase64.isBlank() || expectedHash.isBlank()) return false
        val actual = hash(secret, saltBase64)
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8),
        )
    }
}
