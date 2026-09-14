package com.example.gemdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 钱包列表。
 *
 * 这个页面存在的意义：**Rust 不管列表**。
 * GemKeystore 的 9 个方法里没有任何 list/getAll，每个方法都要求传入 keystoreId ——
 * App 不自己记，生成完就再也找不回来了。
 */
@Composable
fun WalletScreen(
    modifier: Modifier = Modifier,
    autoGenerate: Boolean = false,
    autoOpenDetail: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf(emptyList<WalletEntry>()) }
    var selected by remember { mutableStateOf<WalletEntry?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun reload() { entries = WalletDatabase.get(context).all() }

    fun generate() {
        busy = true
        error = null
        scope.launch {
            try {
                // Argon2id 很重（19 MiB / 2 轮），必须离开主线程
                withContext(Dispatchers.IO) { WalletFactory.create(context) }
                reload()
            } catch (e: Throwable) {
                error = "生成失败: $e"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        reload()
        if (autoGenerate) generate()
        if (autoOpenDetail) selected = WalletDatabase.get(context).all().firstOrNull()
    }

    selected?.let { entry ->
        WalletDetailSheet(
            entry = entry,
            onDismiss = { selected = null },
            onChanged = { reload() },
            onDeleted = { reload(); selected = null },
        )
        return
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("钱包", style = MaterialTheme.typography.headlineMedium)

        Button(
            onClick = { generate() },
            enabled = !busy,
            modifier = Modifier.padding(vertical = 12.dp),
        ) { Text(if (busy) "生成中…" else "＋ 生成新钱包") }

        error?.let { Text(it, color = Color.Red, style = MaterialTheme.typography.bodySmall) }

        Text(
            "每个钱包是一个助记词，下挂多条链的派生地址。" +
                "清单存在 SQLite（wallets + wallets_accounts），不含助记词 —— " +
                "点进详情时才从加密的 keystore 文件现场解出来。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )

        Text(
            "钱包（${entries.size}）",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )

        if (entries.isEmpty()) {
            Text("还没有钱包", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries, key = { it.walletId }) { entry ->
                WalletRow(entry) { selected = entry }
            }
        }
    }
}

/** 每行直接把该钱包的全部派生地址摊开，不用点进详情才看得到。 */
@Composable
private fun WalletRow(entry: WalletEntry, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                entry.walletId,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )

            entry.accounts.forEach { a ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        a.chain,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(76.dp),
                    )
                    Column {
                        Text(
                            a.address,
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                        Text(
                            a.derivationPath,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.LightGray,
                        )
                    }
                }
            }

            Text(
                "${entry.accounts.size} 条链 · 同一助记词 · " +
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.createdAt)),
                fontSize = 10.sp,
                color = Color.Gray,
            )
        }
    }
}

/** 供详情页复用的黄色警示条 */
@Composable
fun DemoBanner(text: String) {
    Text(
        "⚠️ DEMO ONLY — $text",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF3C4), RoundedCornerShape(8.dp))
            .padding(10.dp),
    )
}
