package com.example.gemdemo

import android.content.Context
import uniffi.gemstone.Chain
import uniffi.gemstone.GemImportType
import uniffi.gemstone.GemKeystore
import uniffi.gemstone.GemMnemonic
import uniffi.gemstone.keystoreIdForWallet
import java.io.File

/**
 * 一个账户 = 一条链上的派生地址。
 * 同一个钱包下的所有账户都来自**同一个助记词**，只是派生路径不同。
 */
data class AccountEntry(
    val chain: Chain,
    val address: String,
    val derivationPath: String,
    /**
     * BIP44 账户索引。
     *
     * 🔴 M1 恒为 0 —— core 的 `default_derivation_path(chain)` 返回编译期常量，
     *    一条链只能派生一个地址，做不了 MetaMask 那种 Account 1/2/3。
     *    字段先留着：core 支持后，数据结构与表结构都不用动，
     *    只需把 core 返回的真实 index 填进来。详见 README §5。
     */
    val index: Int = 0,
)

/**
 * 钱包列表项 = 一个助记词。
 *
 * 🔴 这里**没有助记词、没有私钥、没有密码**。
 *    Rust 只按 keystoreId 收活，从不告诉你有哪些钱包（GemKeystore 没有
 *    list/getAll 接口），所以清单必须 App 自己维护 ——
 *    但只放「找得回来」所需的最小信息，秘密留在加密的 keystore 文件里。
 */
data class WalletEntry(
    val walletId: String,
    val createdAt: Long,
    val accounts: List<AccountEntry>,
) {
    /**
     * 🔴 不存，现算。
     *    keystoreId 是 walletId 的 UUID v5 派生值（确定性），存进清单是冗余，
     *    冗余字段迟早会和真值不一致。需要时调 keystoreIdForWallet() 即可。
     */
    val keystoreId: String get() = keystoreIdForWallet(walletId)
}

/**
 * 钱包创建与读取。
 *
 * 全部是阻塞调用，**必须在后台线程跑** —— Argon2id 要 19 MiB 内存、2 轮迭代，
 * 放主线程会明显卡顿（严格模式下还会直接报 ANR 风险）。
 */
object WalletFactory {

    /** 建钱包时默认派生这几条链的地址，全部来自同一个助记词 */
    val defaultChains: List<Chain> = listOf("ethereum", "solana", "bitcoin")

    /**
     * 详情页可以再补的链。
     * ⚠️ 链名是裸字符串（`typealias Chain = kotlin.String`），拼错不报编译错误。
     *    core 也没导出「全量链列表」接口，只能照 Rust 侧 Chain 枚举手抄
     *    （strum serialize_all = "lowercase"，所以 SmartChain → "smartchain"）。
     */
    val extraChains: List<Chain> = listOf(
        "smartchain", "polygon", "arbitrum", "optimism", "base",
        "avalanchec", "cosmos", "tron", "ton", "sui", "aptos", "doge",
    )

    /**
     * 演示用固定密码。真实产品必须来自用户输入，
     * 并用 Android Keystore + 生物识别保护，绝不能硬编码。
     */
    val demoPassword: ByteArray = "demo-password".toByteArray()

    /** keystore 落盘位置。真实 App 应放在不参与自动备份的目录。 */
    fun keystoreDir(context: Context): String =
        File(context.filesDir, "GemKeystore").apply { mkdirs() }.absolutePath

    private fun keystore(context: Context) = GemKeystore(keystoreDir(context))

    /** 生成新钱包：一个助记词 → 多条链的派生地址 → keystore 落盘 → 记进清单 */
    fun create(context: Context, chains: List<Chain> = defaultChains): WalletEntry {
        val words = GemMnemonic().generate(12u)

        val wallet = keystore(context).createStore(
            GemImportType.MulticoinPhrase(words, chains),
            demoPassword,
        )

        val entry = WalletEntry(
            walletId = wallet.walletId,
            createdAt = System.currentTimeMillis(),
            // core 暂不返回 index，M1 固定 0（见 AccountEntry.index 注释）
            accounts = wallet.accounts.map {
                AccountEntry(it.chain, it.address, it.derivationPath, index = 0)
            },
        )

        // 把「keystoreId 由 walletId 派生」这条关系钉死：
        // core 若改了派生规则，这里立刻炸，而不是等到解密时报文件找不到。
        check(entry.keystoreId == wallet.keystoreId) {
            "keystoreIdForWallet 推导值与 createStore 返回值不一致：" +
                "${entry.keystoreId} vs ${wallet.keystoreId}"
        }

        WalletDatabase.get(context).upsert(entry)
        return entry
    }

    /**
     * 给已有钱包补链 —— 仍是同一个助记词派生出来的地址。
     *
     * ⚠️ gem 导出的 API 只支持「一条链一个地址」：
     *    addAccounts 只接受 chains，没有 index 参数，
     *    GemKeystoreAccount 也没有 index 字段。
     */
    fun addChains(context: Context, entry: WalletEntry, chains: List<Chain>): WalletEntry {
        val added = keystore(context).addAccounts(entry.keystoreId, demoPassword, chains)
        val known = entry.accounts.map { it.chain }.toSet()

        val updated = entry.copy(
            accounts = entry.accounts + added
                .filterNot { known.contains(it.chain) }
                .map { AccountEntry(it.chain, it.address, it.derivationPath, index = 0) }
        )
        WalletDatabase.get(context).upsert(updated)
        return updated
    }

    /**
     * 取回助记词。
     *
     * 🔴 重点：助记词**没有存在任何地方**，
     *    是现场从加密的 keystore 文件里解出来的。
     *    真实产品调这个之前必须过生物识别。
     */
    fun recoveryPhrase(context: Context, keystoreId: String): List<String> =
        keystore(context).exportRecoveryPhrase(keystoreId, demoPassword)

    /** 删除钱包：keystore 文件 + 清单记录都要清，否则会留下孤儿文件 */
    fun delete(context: Context, entry: WalletEntry) {
        keystore(context).delete(entry.keystoreId)
        WalletDatabase.get(context).remove(entry.walletId)
    }

    /** keystore 文件的实际路径与大小，用于演示「确实落盘了」 */
    fun fileInfo(context: Context, keystoreId: String): Triple<Boolean, String, Long> {
        val name = "$keystoreId.json"
        val f = File(keystoreDir(context), name)
        return Triple(f.exists(), name, if (f.exists()) f.length() else 0L)
    }
}
