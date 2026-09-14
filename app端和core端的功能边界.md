# App 端和 Core 端的功能边界

哪些事 Rust 做，哪些事 Android 做。**以及为什么这么切。**

一句话概括：**Rust 负责计算与协议，平台负责 I/O。**

本文结论均为实测，验证命令附在各节。环境：`gemstone 2.114.10` (AAR) · 2026-09-14

---

## 0. 速查表

| | Rust (core) | Android (你) |
|---|---|---|
| **网络** | 构造请求、解析响应 | ❗**真的发出去** |
| WebSocket | 造订阅报文、解析消息 | ❗**开连接、维持、重连** |
| **密钥文件** | ❗**读写加密的 keystore** | 提供目录路径 |
| 私钥派生 / 签名 | ❗**全部** | 拿签名结果 |
| 钱包清单、交易、余额 | — | ❗**全部（数据库）** |
| 偏好设置 | 决定存什么 key | ❗**提供存储介质（两套）** |
| UI、生物识别、备份策略 | — | ❗**全部** |

❗ = 这一方**必须**实现，缺了另一方跑不起来。

---

## 1. 网络：一个字节都不由 Rust 发出

### 实测证据

```bash
$ unzip -p app-debug.apk lib/arm64-v8a/libgemstone.so > libg.so
$ strings libg.so | grep -ci reqwest
0
```

打进 APK 的 `.so` 里 **HTTP 客户端符号数是 0** ——
不是"能用但没用"，是压根没编译进来。

原因在 `core/gemstone/Cargo.toml`：

```toml
[features]
default = []
reqwest_provider = ["swapper/reqwest_provider"]   # 移动端不开
```

同一份 core 也给服务端（`apps/api`）用，那边开 `reqwest_provider` 注入真 reqwest。
移动端注入你写的 `AlienProvider`。链的业务逻辑是同一份，不关心谁在发包。

### 数据怎么流

```
Rust  ──►  构造 AlienTarget（url / method / headers / body）
           ↓ 过 FFI
你    ──►  OkHttp / HttpURLConnection 真的发出去
           ↓ 过 FFI
Rust  ──►  拿 AlienResponse，解析、走业务逻辑
```

### 你必须实现

```kotlin
class NativeProvider : AlienProvider {
    override fun getEndpoint(chain: Chain): String =
        "https://ethereum.publicnode.com"      // 每条链的默认节点

    override suspend fun request(target: AlienTarget): AlienResponse =
        withContext(Dispatchers.IO) {
            val conn = (URL(target.url).openConnection() as HttpURLConnection).apply {
                requestMethod = alienMethodToString(target.method)
                target.headers?.forEach { (k, v) -> setRequestProperty(k, v) }
                target.body?.let { doOutput = true; outputStream.use { o -> o.write(it) } }
            }
            val status = conn.responseCode
            val bytes = conn.inputStream.use { it.readBytes() }
            AlienResponse(status.toUShort(), bytes)
        }
}
```

**不实现这个，core 发不出任何请求。** 完整版见
[FfiScreen.kt](app/src/main/java/com/example/gemdemo/FfiScreen.kt)。

> ⚠️ demo 用 `HttpURLConnection` 是为了零依赖。
> **真实项目建议换 OkHttp** —— 能直接复用你们已有的拦截器、重试、日志、
> 证书固定那一套，core 完全不感知。

### WebSocket 也是同一套

我一开始以为长连接会是例外，查下来不是。库里所有 WebSocket 相关导出只有两个：

```kotlin
fun websocketRequest(method, subscription): String              // 造订阅报文
fun parseWebsocketData(data: ByteArray, mode): GemHyperliquidSocketMessage
```

Rust 侧实现就是 `serde_json::to_string(...)` —— **纯序列化**。
连接的建立、心跳、断线重连、进程保活，全是你的事。

### 为什么这么设计

OkHttp 的连接池、HTTP/2、系统代理、证书固定、`NetworkCallback` 网络状态感知 ——
这些平台已经做得很好。Rust 再实现一遍只会更差，还让 `.so` 大一圈
（reqwest + rustls 静态链接进来是好几 MB × 3 个 ABI）。

---

## 2. 存储：只有一类文件归 Rust

### Rust 唯一持有的存储

```
<你传的 baseDir>/<keystoreId>.json
```

在 demo 里是 `/data/data/com.example.gemdemo/files/GemKeystore/`。

**一个钱包一个文件。** 内容是加密后的助记词：

```json
{
  "version": 4,
  "id": "40929960-7b66-5d96-9920-88bf13b2f4d9",
  "kind": "mnemonic",
  "crypto": {
    "kdf":    { "algorithm": "argon2id", "memory_kib": 19456,
                "iterations": 2, "parallelism": 1, "salt": "…", "output_len": 32 },
    "cipher": { "algorithm": "aes-256-gcm", "nonce": "…", "tag_len": 16 },
    "ciphertext": "099acaaa…"
  }
}
```

注意 `"kind": "mnemonic"` —— **存的是助记词，不是私钥**。
私钥每次签名时现场派生，用完 `Zeroizing` 清零，从不落盘。

自己看一眼：

```bash
adb shell run-as com.example.gemdemo cat files/GemKeystore/<id>.json
```

`baseDir` 是**你决定的**：

```kotlin
val keystore = GemKeystore(File(context.filesDir, "GemKeystore").absolutePath)
```

Rust 不决定放哪。选目录时注意备份策略，见 §4。

### 除此之外 Rust 不碰任何文件

把 `core/gemstone/src/` 和 `gem_keystore` 全扫过，非测试代码里的文件操作只有两类：

```
file_keystore.rs      keystore 文件的读写删
keystore.rs:115       迁移后删除 v3 旧文件
```

**不碰数据库、不写缓存文件、不管日志落盘。**

### 🔴 钱包清单必须你自己存

`GemKeystore` 一共 9 个方法（`createStore` `sign` `delete` `exportPrivateKey`
`exportRecoveryPhrase` `addAccounts` `previewImport` `signAuth` `migrateV3`），
**没有 list、没有 getAll**，而且除 `createStore` 外每个都要求你传 `keystoreId`。

Rust 从不告诉你有哪些钱包，它只按 id 干活。**你不自己记，生成完就找不回来了。**

这不是遗漏，是刻意的：清单要驱动 UI，Room 的 DAO 可以返回 `Flow`
（数据一变界面自动刷新）。清单放 Rust 里就拿不到这个能力，
整个数据流都得退回手动轮询。

表结构参考 [README §4](README.md#4-钱包清单得你自己存)。

---

## 3. `GemPreferences`：一个"半 Rust"的存储

这是最容易误解的一处。

它是**你实现的接口**，但**调用方是 Rust** —— Rust 决定存什么 key、什么时候读写，
你只提供存储介质。所以说"存储都在原生端"在**机制上**对，在**控制权上**不完全对。

```kotlin
interface GemPreferences {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}
```

### 🔴 要传两个实例，别指向同一个

```kotlin
GemGateway(provider, preferences, securePreferences, apiUrl)
```

| 参数 | 应该用 |
|---|---|
| `preferences` | `SharedPreferences` |
| `securePreferences` | **Android Keystore 加持的存储**（如 `EncryptedSharedPreferences`）|

第二个是给敏感数据用的。两个都传普通 `SharedPreferences` 能跑，
但等于把该加密的东西明文写进了 XML —— root 设备上可直接读。

> 两个 demo 里都没用到（只演示钱包生成），但接入 `GemGateway` 做真实链上操作时
> **必须提供**。

---

## 4. 你独有的责任

这些 core 完全不管，漏了不会报错，但会出事：

**备份策略。** `filesDir` 默认会进 Android 自动备份，keystore 文件会被传到
Google Drive。必须关掉：

```xml
<application android:allowBackup="false" ...>
```

demo 的 Manifest 里已经这么写了。

**密码来源与保护。** demo 硬编码 `demo-password`。真实产品必须用户输入，
并用 Android Keystore + `BiometricPrompt` 保护。

**导出前的生物识别。** `exportRecoveryPhrase` / `exportPrivateKey` 是仅有的两个
把秘密交到你手里的接口，调用前必须过 `BiometricPrompt`。

**删钱包要删两处。** 只删数据库记录会留下孤儿 keystore 文件。
demo 的 `delete()` 是文件与清单一起清的，自检里有对应断言。

**UI 上的秘密展示。** 助记词上屏时应设 `FLAG_SECURE` 防截图与最近任务预览：

```kotlin
window.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE)
```

demo 没做，因为它就是要给你看。

---

## 5. 一句话记住

> **Rust 管密码学和协议，你管 I/O 和展示。**
>
> 唯一的例外是 keystore 文件 —— 因为私钥不能过 FFI，
> 加解密必须在 Rust 内部闭环完成。

---

*本文档所有结论均为 2026-09-14 实测，验证命令见各节。
[iOS 版对照](https://github.com/weaver-max/GemIOSDemo/blob/main/app端和core端的功能边界.md)。*
