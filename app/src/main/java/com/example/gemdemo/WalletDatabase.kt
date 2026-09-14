package com.example.gemdemo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite 持久化，表结构照搬 gem 主 App（`android/` 的 Room 实体）的设计：
 *   wallets            一个助记词一行
 *   wallets_accounts   一条链一行，外键挂到 wallets，级联删除
 *
 * 用 SQLiteOpenHelper 而不是 Room —— 框架自带，不引入注解处理器，
 * 本 demo 的构建流程不用为此复杂化。
 * 真实项目请用 Room：它提供 Flow 返回值（数据变了 UI 自动刷新），
 * 这正是「钱包清单该放数据库而不是 SharedPreferences」的核心理由。
 *
 * 🔴 表里没有助记词、私钥、密码的位置。
 *    固定 schema 比运行时校验更强 —— 想存秘密得先改表结构，
 *    那是个显式动作，不会手滑写进去。
 */
class WalletDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, "wallets.db", null, 1) {

    companion object {
        @Volatile private var instance: WalletDatabase? = null

        fun get(context: Context): WalletDatabase =
            instance ?: synchronized(this) {
                instance ?: WalletDatabase(context.applicationContext).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE wallets (
                id          TEXT PRIMARY KEY NOT NULL,
                created_at  INTEGER NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE wallets_accounts (
                wallet_id       TEXT NOT NULL REFERENCES wallets(id) ON DELETE CASCADE,
                chain           TEXT NOT NULL,
                address         TEXT NOT NULL,
                derivation_path TEXT NOT NULL,
                account_index   INTEGER NOT NULL DEFAULT 0,
                -- 🔴 主键必须带 account_index。M1 它恒为 0，但将来同链多账户时
                --    (wallet_id, chain) 会撞主键 —— 现在定对了以后就不用改表。
                PRIMARY KEY (wallet_id, chain, account_index)
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_accounts_wallet ON wallets_accounts(wallet_id);")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // demo 不做迁移；真实产品此处必须写迁移逻辑，不能 drop 重建
        db.execSQL("DROP TABLE IF EXISTS wallets_accounts")
        db.execSQL("DROP TABLE IF EXISTS wallets")
        onCreate(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)   // 默认关闭，不开级联删除不生效
    }

    // ── 读写 ────────────────────────────────────────────────

    fun all(): List<WalletEntry> {
        val db = readableDatabase
        val wallets = mutableListOf<Pair<String, Long>>()
        db.rawQuery("SELECT id, created_at FROM wallets ORDER BY created_at DESC", null).use { c ->
            while (c.moveToNext()) wallets += c.getString(0) to c.getLong(1)
        }

        return wallets.map { (id, createdAt) ->
            val accounts = mutableListOf<AccountEntry>()
            db.rawQuery(
                """
                SELECT chain, address, derivation_path, account_index FROM wallets_accounts
                WHERE wallet_id = ? ORDER BY chain, account_index
                """.trimIndent(),
                arrayOf(id)
            ).use { c ->
                while (c.moveToNext()) {
                    accounts += AccountEntry(
                        chain = c.getString(0),
                        address = c.getString(1),
                        derivationPath = c.getString(2),
                        index = c.getInt(3),
                    )
                }
            }
            WalletEntry(walletId = id, createdAt = createdAt, accounts = accounts)
        }
    }

    fun upsert(entry: WalletEntry) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "wallets", null,
                ContentValues().apply {
                    put("id", entry.walletId)
                    put("created_at", entry.createdAt)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
            entry.accounts.forEach { a ->
                db.insertWithOnConflict(
                    "wallets_accounts", null,
                    ContentValues().apply {
                        put("wallet_id", entry.walletId)
                        put("chain", a.chain)
                        put("address", a.address)
                        put("derivation_path", a.derivationPath)
                        put("account_index", a.index)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** wallets_accounts 有 ON DELETE CASCADE，删主表即可 */
    fun remove(walletId: String) {
        writableDatabase.delete("wallets", "id = ?", arrayOf(walletId))
    }

    fun databaseFilePath(context: Context): String =
        context.getDatabasePath("wallets.db").absolutePath
}
