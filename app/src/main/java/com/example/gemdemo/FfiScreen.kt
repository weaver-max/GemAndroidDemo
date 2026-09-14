package com.example.gemdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.gemstone.AlienException
import uniffi.gemstone.AlienHttpMethod
import uniffi.gemstone.AlienProvider
import uniffi.gemstone.AlienResponse
import uniffi.gemstone.AlienTarget
import uniffi.gemstone.Chain
import uniffi.gemstone.alienMethodToString
import uniffi.gemstone.libVersion
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kotlin 侧实现 AlienProvider，由 Rust 反向调用。
 * 对应 iOS 示例的 NativeProvider（Swift）。
 */
class NativeProvider(
    private val onResult: (String) -> Unit,
) : AlienProvider {

    override fun getEndpoint(chain: Chain): String = "https://ethereum.publicnode.com"

    override suspend fun request(target: AlienTarget): AlienResponse = withContext(Dispatchers.IO) {
        val url = try {
            URL(target.url)
        } catch (e: Throwable) {
            // ⚠️ Kotlin 侧叫 AlienException.RequestException，
            //    Swift 侧是 AlienError.RequestError —— UniFFI 的 Kotlin 绑定
            //    会给错误类型加 Exception 后缀，两端命名不通用。
            throw AlienException.RequestException("invalid url: ${target.url}")
        }

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = alienMethodToString(target.method)
            target.headers?.forEach { (k, v) -> setRequestProperty(k, v) }
            target.body?.let { body ->
                doOutput = true
                outputStream.use { it.write(body) }
            }
        }

        val status = conn.responseCode
        val bytes = try {
            conn.inputStream.use { it.readBytes() }
        } catch (e: Throwable) {
            conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
        } finally {
            conn.disconnect()
        }

        // 🔴 AlienResponse 是不透明对象（uniffi::Object），构造之后读不出内容，
        //    所以要记状态码只能在包装前记。
        onResult("${target.url}\n-> $status, ${bytes.size} bytes")

        AlienResponse(status.toUShort(), bytes)
    }
}

@Composable
fun FfiScreen(modifier: Modifier = Modifier) {
    var result by remember { mutableStateOf("尚未请求") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val provider = remember { NativeProvider { result = it } }

    fun fetch() {
        busy = true
        result = "请求中…"
        scope.launch {
            try {
                // 返回值交给 Rust 消费，Kotlin 侧读不出内容
                provider.request(
                    AlienTarget(
                        url = "https://httpbin.org/get?foo=bar",
                        method = AlienHttpMethod.GET,
                        headers = mapOf("X-Header" to "X-Value"),
                        body = null,
                    )
                )
            } catch (e: Throwable) {
                result = "失败: $e"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { fetch() }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // 这一行来自 Rust，不是硬编码 —— 证明 FFI 正向调用通了
        Text("Gemstone lib version: ${libVersion()}", style = MaterialTheme.typography.titleMedium)

        Button(onClick = { fetch() }, enabled = !busy) { Text("Fetch Data") }

        Text(
            result,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}
