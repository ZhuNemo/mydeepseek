package com.simplechat.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        PresetEntity::class,
        ConversationPresetEntity::class,
        CompactionEntity::class,
        DraftEntity::class,
        AttachmentRefEntity::class,
    ],
    // v2：messages 从「槽位」改成「树」（slotIndex → parentId）
    // v3：messages 增加 attachments（图片 / 纯文本附件）
    // v4：messages 增加 model（记下这条是哪个模型写的）
    // v5：新增 drafts（未发送的输入草稿）
    // v6：drafts 增加 title（「新对话」的待用标题）
    // v7：messages 增加 timeline / stopReason（Anthropic 兼容通道的内容块时间线）
    // v8：新增 attachment_refs（落盘图片的引用，删会话时据此清理文件）
    // v9：附件可被多个会话引用（分叉后删除原会话不能丢图）
    /*
     * 版本 14 = 上游的 13 + 本仓库自己的 v10（保险箱标记）。
     *
     * 两条版本线在这里交汇：上游 9→10 加 `tokens_cache_hit`，我们把自己的
     * `hidden` 放到 13→14 —— 这样从任何一边升上来的库都能走通。
     */
    version = 14,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao

    abstract fun messageDao(): MessageDao

    abstract fun presetDao(): PresetDao

    abstract fun conversationPresetDao(): ConversationPresetDao

    abstract fun compactionDao(): CompactionDao

    abstract fun draftDao(): DraftDao

    abstract fun attachmentRefDao(): AttachmentRefDao

    companion object {

        /**
         * v2 → v3：只加一列，纯增量。
         *
         * ⚠️ **每次改 schema 都必须配一条显式 Migration。**
         *
         * 这里原本是 `fallbackToDestructiveMigration(dropAllTables = true)` ——
         * 开发期图省事，但它意味着"用户升级一次 App，全部对话凭空消失"。
         * 用户真机上已经有成篇的稿子，这个代价不能接受。
         * 宁可让它崩（看得见、能修），也不能让它静默清库。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN attachments TEXT")
            }
        }

        /**
         * v3 → v4：加一列 `model`，纯增量。
         *
         * 只给**新生成的消息**写得进去 —— 老消息这一列是 NULL，
         * 界面上就不显示模型名（而不是显示一个猜的），这是刻意的：
         * 老会话里可能混着好几个模型，硬填一个反而是假信息。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN model TEXT")
            }
        }

        /**
         * v4 → v5：新增 `drafts` 表，纯增量。
         *
         * 会话里没发出去的输入草稿。⚠️ SQL 必须和 Room 生成的 schema **逐字对齐**
         * （列序、NOT NULL、主键写法都算），否则升级时的 schema 校验会当场崩。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drafts` (" +
                        "`conversationId` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`conversationId`))"
                )
            }
        }

        /**
         * v5 → v6：`drafts` 加一列 `title`，纯增量。
         *
         * 「新对话」的待用标题 —— 用户在发出第一条消息之前就起好了名，而那时会话行
         * 还不存在。与草稿同一个处境，一起记在 `drafts` 里。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drafts ADD COLUMN title TEXT")
            }
        }

        /**
         * v6 → v7：`messages` 加两列，纯增量。
         *
         * - `timeline`：Anthropic 兼容通道的内容块时间线（JSON），null 表示这条回复
         *   没有结构（OpenAI 兼容通道、或升级前的老消息）。
         * - `stopReason`：结束原因，续写能力与安全拒绝的收尾都依赖它。
         *
         * 老消息两列都是 NULL —— 界面照旧走「思考栏 + 正文」的老渲染，
         * 不需要回填，也无从回填（信息当初就没收到）。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN timeline TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN stopReason TEXT")
            }
        }

        /**
         * v7 → v8：新增落盘图片的引用表。
         *
         * 只加表、不动老数据。**旧的图片仍在 `messages.attachments` 里内联**
         * （历史数据不迁移，读写两条路并存），所以升级不会丢图，也不会变大。
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `attachment_refs` (" +
                        "`path` TEXT NOT NULL, " +
                        "`conversationId` TEXT NOT NULL, " +
                        "PRIMARY KEY(`path`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_attachment_refs_conversationId` " +
                        "ON `attachment_refs` (`conversationId`)",
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE `attachment_refs_new` (`path` TEXT NOT NULL, `conversationId` TEXT NOT NULL, PRIMARY KEY(`path`, `conversationId`))")
                db.execSQL("INSERT INTO attachment_refs_new(path, conversationId) SELECT path, conversationId FROM attachment_refs")
                db.execSQL("DROP TABLE attachment_refs")
                db.execSQL("ALTER TABLE attachment_refs_new RENAME TO attachment_refs")
                db.execSQL("CREATE INDEX `index_attachment_refs_conversationId` ON attachment_refs(`conversationId`)")
            }
        }

        /** v9 → v10：只加一列，老消息保持 null（= 那轮没有服务端用量，界面按"未知"处理）。 */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN tokens_cache_hit INTEGER")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imageGeneration INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imageRequest TEXT")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN memoryEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE drafts ADD COLUMN memoryEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v13 → v14：会话加「保险箱」标记（本仓库自己的那一列）。
         *
         * ⚠️ **必须容忍列已存在。** 本仓库从上游合并之前，自己的数据库版本号
         * 就叫 10，而那一版已经把 `hidden` 加进去了。这次合并把版本号带到 14，
         * 老用户从 10 一路升上来时，这条 13→14 会第二次碰到同一列 ——
         * 直接 `ALTER TABLE ... ADD COLUMN` 会报 `duplicate column name: hidden`，
         * 迁移失败等于启动即崩。所以先查 `PRAGMA table_info`，有就跳过。
         *
         * ⚠️ **同时补 `messages.tokens_cache_hit`。** 这一列在上游是 9→10 加的，
         * 但本仓库自己的 version 10 加的是 `hidden`，老库里并没有它。老用户从
         * 本仓库的 10 升上来时，9→10 那条不会执行，于是到 v14 校验 schema 时
         * 发现少一列 → 启动即崩。这条 13→14 是 version-10 老库必经的一步，
         * 在这里补上（同样带守卫，重复执行也不出错）。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("conversations", "hidden")) {
                    db.execSQL("ALTER TABLE conversations ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
                }
                if (!db.hasColumn("messages", "tokens_cache_hit")) {
                    db.execSQL("ALTER TABLE messages ADD COLUMN tokens_cache_hit INTEGER")
                }
            }
        }

        /** 这一列在不在（迁移里用来做"可重复执行"的守卫）。 */
        private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
            query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == column) return true
                }
                false
            }
        fun build(context: Context, name: String = "simplechat.db"): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                name,
            )
                .addMigrations(
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                )
                .build()
    }
}
