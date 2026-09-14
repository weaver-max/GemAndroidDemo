package com.example.gemdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 钱包详情：walletId / keystoreId / 助记词 / 全部派生地址。
 *
 * 助记词不是从清单里读的 —— 清单里根本没有。
 * 进页面时调 exportRecoveryPhrase，从加密的 keystore 文件现场解出来。
 */
@Composable
fun WalletDetailSheet(
    entry: WalletEntry,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf(entry) }
    var words by remember { mutableStateOf(emptyList<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(entry.walletId) {
        busy = true
        try {
            // Argon2id 解密同样很重，必须离开主线程
            words = withContext(Dispatchers.IO) {
                WalletFactory.recoveryPhrase(context, entry.keystoreId)
            }
        } catch (e: Throwable) {
            error = "解密失败: $e"
        } finally {
            busy = false
        }
    }

    val remaining = WalletFactory.extraChains - current.accounts.map { it.chain }.toSet()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("钱包详情", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onDismiss) { Text("完成") }
        }

        DemoBanner("真实产品展示助记词前必须过生物识别")

        Field("walletId", current.walletId, mono = true)
        Field("keystoreId（由 walletId 派生，不存盘）", current.keystoreId, mono = true)

        // ── 助记词 ──
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("助记词", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Text("由 keystore 现场解密", fontSize = 10.sp, color = Color.Gray)
            }
            when {
                busy -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.padding(2.dp))
                    Text("解密中…", style = MaterialTheme.typography.bodySmall)
                }
                error != null -> Text(error!!, color = Color.Red,
                    style = MaterialTheme.typography.bodySmall)
                else -> Text(
                    words.joinToString(" "),
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x14000000), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }
        }

        // ── 派生地址 ──
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("派生地址（${current.accounts.size}）",
                    style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Text("同一助记词", fontSize = 10.sp, color = Color.Gray)
            }
            current.accounts.forEach { a ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x0D000000), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                ) {
                    Text(a.chain, style = MaterialTheme.typography.labelMedium)
                    Text(a.address, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(a.derivationPath, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace, color = Color.Gray)
                }
            }

            Button(
                onClick = {
                    adding = true
                    scope.launch {
                        try {
                            current = withContext(Dispatchers.IO) {
                                WalletFactory.addChains(context, current, remaining.take(3))
                            }
                            onChanged()
                        } catch (e: Throwable) {
                            error = "派生失败: $e"
                        } finally {
                            adding = false
                        }
                    }
                },
                enabled = !adding && remaining.isNotEmpty(),
            ) { Text(if (adding) "派生中…" else "＋ 再派生 3 条链") }
        }

        // ── keystore 文件 ──
        val (exists, name, size) = WalletFactory.fileInfo(context, current.keystoreId)
        Column {
            Text(
                if (exists) "✓ keystore 文件存在" else "✗ 文件未找到",
                color = if (exists) Color(0xFF2E7D32) else Color.Red,
                style = MaterialTheme.typography.labelMedium,
            )
            Text("$name  $size 字节", fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, color = Color.Gray)
        }

        Button(
            onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { WalletFactory.delete(context, current) }
                        onDeleted()
                    } catch (e: Throwable) {
                        error = "删除失败: $e"
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
        ) { Text("删除钱包") }
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Text(
            value,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
    }
}
