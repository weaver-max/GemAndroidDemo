package com.example.gemdemo

import android.content.Context
import java.io.File

/**
 * 无头自检，对应 iOS 的 `-selftest`。
 *
 *   adb shell am start -n com.example.gemdemo/.MainActivity --ez selftest true
 *   adb shell run-as com.example.gemdemo cat files/selftest.txt
 *
 * 结果写文件而不是 Log —— logcat 会被系统日志淹没，抓取也不稳。
 */
object SelfTest {

    fun run(context: Context) {
        var failed = 0
        val lines = mutableListOf<String>()

        fun check(name: String, ok: Boolean, detail: String = "") {
            lines += "${if (ok) "PASS" else "FAIL"}  $name${if (detail.isEmpty()) "" else "  — $detail"}"
            if (!ok) failed++
        }

        try {
            val db = WalletDatabase.get(context)
            val before = db.all().size

            // 1. 生成
            val w = WalletFactory.create(context)
            check("生成钱包", db.all().size == before + 1)
            check("默认派生 3 条链", w.accounts.size == 3, "实际 ${w.accounts.size}")

            // 2. keystoreId 由 walletId 推导，且文件确实落盘
            val (exists, name, size) = WalletFactory.fileInfo(context, w.keystoreId)
            check("keystore 文件落盘", exists, "$name $size 字节")

            // 3. 助记词现场解密 —— 验证推导出的 keystoreId 是对的
            val words = WalletFactory.recoveryPhrase(context, w.keystoreId)
            check("助记词解密", words.size == 12, "${words.size} 个词")

            // 4. 同一助记词派生新链，助记词不能变
            val added = WalletFactory.addChains(context, w, listOf("polygon", "tron"))
            check("再派生 2 条链", added.accounts.size == 5, "实际 ${added.accounts.size}")
            check("助记词未变", words == WalletFactory.recoveryPhrase(context, added.keystoreId))
            check("walletId 未变", added.walletId == w.walletId)

            // 5. 地址与派生路径一一对应。
            //    ⚠️ 不能断言「地址互不相同」—— EVM 系列链共用 m/44'/60'/0'/0/0，
            //    ethereum 和 polygon 本来就是同一个地址，这是正确行为。
            val byPath = added.accounts.groupBy { it.derivationPath }
            check("同派生路径 → 同地址", byPath.values.all { g -> g.map { it.address }.toSet().size == 1 })
            check("异派生路径 → 异地址",
                byPath.values.map { it.first().address }.toSet().size == byPath.size,
                "${byPath.size} 条不同路径")
            val evm = added.accounts.filter { it.derivationPath == "m/44'/60'/0'/0/0" }
            check("EVM 链共用地址",
                evm.size < 2 || evm.map { it.address }.toSet().size == 1,
                evm.joinToString("/") { it.chain })

            // M1 的约定：index 恒为 0。core 支持同链多账户后这条会失败 ——
            // 那正是提醒去把真实 index 填进来的时刻。
            check("account index 恒为 0（M1）", added.accounts.all { it.index == 0 })

            // 6. 数据库不含秘密。
            //
            // ⚠️ 不能拿单个词去子串匹配整个文件 —— SQLite 会把建表语句存进
            //    sqlite_master，schema 里的 "index" "address" 这些恰好都在 BIP39
            //    词表里，必然误报。改用连续两词：助记词真泄漏时一定是整串落盘，
            //    而 schema 的英文不可能凑出助记词里的相邻词对。
            val dbFile = File(db.databaseFilePath(context))
            val raw = if (dbFile.exists()) String(dbFile.readBytes(), Charsets.ISO_8859_1) else ""
            val pairs = words.zipWithNext { a, b -> "$a $b" }
            val leaked = pairs.filter { raw.contains(it) }
            check("数据库不含助记词", leaked.isEmpty(),
                if (leaked.isEmpty()) "${dbFile.length()} 字节 / 查了 ${pairs.size} 组词对"
                else "🔴 命中 $leaked")
            check("数据库不含 keystoreId", !raw.contains(w.keystoreId))

            // 7. 删除：文件与清单都要清
            WalletFactory.delete(context, added)
            check("删除后清单移除", db.all().none { it.walletId == w.walletId })
            check("删除后文件移除", !WalletFactory.fileInfo(context, w.keystoreId).first)

        } catch (e: Throwable) {
            check("未抛异常", false, "$e")
        }

        lines += "DONE failed=$failed"
        File(context.filesDir, "selftest.txt").writeText(lines.joinToString("\n"))
    }
}
