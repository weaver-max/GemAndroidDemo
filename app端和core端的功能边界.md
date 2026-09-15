# App 端和 Core 端的功能边界

哪些事 Rust 做，哪些事 Android 做。**以及为什么这么切。**

一句话概括：**Rust 只管密码学与链上协议，其余全归平台。**

具体说：后端 API 你直连，本地存储你全包，网络 I/O 你来发；
Rust 负责的是私钥、签名、链上请求的构造与解析，以及若干双端必须一致的业务规则。

本文结论均为实测，验证命令附在各节。环境：`gemstone 2.114.10` (AAR) · 2026-09-14

---

## 0. 速查表

| | Rust (core) | Android (你) |
|---|---|---|
| 🔴 **交易历史 / 代币发现** | — | ❗**后端直连**（链上 RPC 查不了） |
| **其余后端 API**（资产/价格/配置/设备/订阅） | — | ❗**全部直连，Rust 不参与** |
| 交易风险扫描 | 唯一经过 Rust 的后端调用 | 发出请求 |
| **链上 RPC**（余额/手续费预估/nonce/广播/交易状态） | 构造请求、解析响应 | ❗**真的发出去** |
| WebSocket | 造订阅报文、解析消息 | ❗**开连接、维持、重连** |
| **密钥文件** | ❗**读写加密的 keystore** | 提供目录路径 |
| 私钥派生 / 签名 | ❗**全部** | 拿签名结果 |
| **自有功能**（地址簿/节点/偏好/搜索） | — | ❗**完全闭环，core 不参与** |
| **所有数据的存储与查询** | — | ❗**全部（SQLite）** |
| 偏好设置 | 决定存什么 key | ❗**必须实现两套**（普通 + 安全） |
| UI、生物识别、备份策略 | — | ❗**全部** |

❗ = 这一方**必须**实现，缺了另一方跑不起来。

---

## 1. 接口的两个方向：谁调用谁

生成的绑定里有 22 个 interface/protocol，看起来都像"回调接口"，**其实只有 2 个是你实现的。**

### 🔵 你实现，Rust 调用（只有这两个）

| 接口 | 你提供 |
|---|---|
| `AlienProvider` | 发 HTTP |
| `GemPreferences` | 键值读写 |

**不实现这两个，core 跑不起来。**

### ⚪ Rust 对象，你只能调用（其余全部）

```
GemKeystore      GemMnemonic      GemSwapper       GemGateway
Explorer         Config           BalanceCalculator
CryptoFiatConverter               MessageSigner    Perpetual
Hyperliquid      WalletConnect    PriceAlertFormatter
PriceChangeCalculator             AutocloseValidator
GemServiceStatus WalletConnectSimulationClient     AlienResponse
```

UniFFI 会给每个 `uniffi::Object` 自动生成一个同名接口，**方向是平台 → Rust**。
你不需要也不应该实现它们。

> ⚠️ 命名差异：Swift 侧后缀是 `Protocol`（`GemKeystoreProtocol`），
> Kotlin 侧是 `Interface`（`GemKeystoreInterface`）。
> 看到这两个后缀就知道是 Rust 对象，不是给你实现的。

### 怎么一眼分辨

带后缀 = Rust 对象。不带 = 你要实现的。

要严格验证，查生成物里的 callback vtable —— 这是 UniFFI 编译期产物，绕不过去：

```bash
grep -oE "UniffiVTableCallbackInterface[A-Za-z]+" <绑定文件> | sort -u
# 双端输出一致，且只有两条：
#   UniffiVTableCallbackInterfaceAlienProvider
#   UniffiVTableCallbackInterfaceGemPreferences
```

---

## 2. 数据往哪走

### 2.1 四个来源，只有一个不在你手里

```
后端 API  ──直连──►  你 ──► SQLite     🔴 交易历史 / 代币发现
                            ↑             资产 / 价格 / 配置 / 设备 / 订阅
                            │             ❗Rust 完全不参与
                            │
链上 RPC  ──►  Rust 解析 ────┤             余额 / 手续费预估 / nonce
              ↑             │             广播 / 交易状态 / 质押
       请求经你的 AlienProvider 发出
                            │
你自己闭环 ─────────────────┤             钱包表 / 账户表 / 地址簿 / 自定义节点
                                           偏好 / 钱包名与排序 / 搜索
                                           ❗存储、更新、查询全是你写

keystore  ──►  Rust 独占                   加密的助记词
```

> 🔴 **交易历史和代币发现在后端，不在链上。**
> 以太坊 RPC 没有「查某地址全部交易」的接口，也不能反查持仓 —— 必须靠后端索引。
> 详见 [App 的数据从哪来 §1](app数据来源.md)。

**🔴 后端数据的绝大部分由你直连获取，Rust 从头到尾没见过。**

gem 主 App 的 `GemAPI` 包里这些全是 App 自己发、自己解析、自己入库：

```
getAssets / getAsset / getCharts / getConfig / getAddressNames
getBuyableFiatAssets / addPriceAlerts / addSubscriptions
addDevice / getDevice / isDeviceRegistered / getNodeAuthToken / createReferral …
```

经过 Rust 的后端调用**只有一个**：

```rust
// core/gemstone/src/api_client/mod.rs —— 整个文件就这一个公开方法
pub async fn scan_transaction(&self, payload) -> Result<ScanTransaction, String>
```

交易风险扫描。这也是 `GemGateway(...)` 要传 `apiUrl` 的唯一原因 ——
不调 `getTransactionScan()` 的话传占位符都行。

> ⚠️ **排期时注意**：后端 API 对接的工作量和 core 无关，
> 不能因为"有 Rust 核心库"就从双端工作量里扣掉。
> 资产、价格、行情、设备注册、订阅这些接口，双端各要写一遍。

### 2.2 你和 Rust 之间：三个调用方向

```
① Rust 主动调你 —— 只有 §1 那两个接口
   AlienProvider   ──►  你发 HTTP
   GemPreferences  ──►  你读写键值

② 你调 Rust，把数据当参数喂进去   ← 关系型数据走这条
   totalFiatValue(balances: [AssetFiatValue])      ← 余额是你查好传进来的
   encodeGetAccounts(chain, accounts: [Account])   ← 账户列表你传进来
   calculateTransferAmount(input)

③ 你自己闭环，Rust 完全不参与
   后端 API 对接、排序 SQL、分页、搜索、列表刷新、UI 状态
```

### 🔴 Rust 不是不需要关系型数据

而是**数据由你当参数传进去**。Rust 对数据库无状态、不感知表结构。

`totalFiatValue(balances)` 就是典型：你从库里捞出余额列表 → 传给 Rust 算总资产
→ 拿回 `TotalFiatValue` → 你决定显示还是入库。

这比"把 repository 抽象成 FFI 接口"好在四点：

| | 参数传入（现状） | repository 抽象 |
|---|---|---|
| 查询语句 | 你写，想怎么 join 怎么 join | 受 trait 签名限制 |
| 响应式 | ✅ `ValueObservation` / `Flow` 照常 | ❌ 只能拿快照 |
| FFI 往返 | 只在需要计算时过一次 | 每次查询都过 |
| 可测性 | 纯函数，单测不用 mock | 持有回调，要 mock |

> 📌 上游从未把 repository/storage 抽象成 FFI 接口。
> 如果有人提议"查询经过 core 统一规范"，**这条要挡住** ——
> 响应式查询会废掉，而那正是当初把数据库留在平台侧的首要原因。

### 分辨纯函数还是会回调你

看是不是 `async` / `suspend`：

- **纯函数**：`totalFiatValue` `calculateTransferAmount` `validateAddress` —— 进什么出什么
- **会回调你**：`getBalanceTokens` `getBalanceEarn` —— 内部要发 HTTP，走 ① 那条路

---

## 3. 网络：一个字节都不由 Rust 发出

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

## 4. 存储：只有一类文件归 Rust

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

Rust 不决定放哪。选目录时注意备份策略，见 §7。

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

## 5. `GemPreferences`：一个"半 Rust"的存储

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

### 🔴 这是硬性要求，不是可选项

`GemGateway` 的构造函数**要求四个参数**，少一个编译都过不了：

```
GemGateway(provider, preferences, securePreferences, apiUrl)
                     ^^^^^^^^^^^  ^^^^^^^^^^^^^^^^^
                     两个都必须给，且必须是两个不同的实现
```

**没有 `GemGateway` 就查不了余额、发不了交易。**
所以只要做链上功能，`GemPreferences` 就和 `AlienProvider` 一样是必做项。

### 谁在用它

实测追下来，**目前只有 HyperCore（永续合约）这条链会真正读写**：

| 用途 | 走哪套 |
|---|---|
| agent 私钥（代理签名用，需跨会话复用） | 🔴 `securePreferences` |
| 预加载缓存标记 | `preferences` |

其余链（BTC / EVM / Solana / Sui…）构造时根本不传 preferences。

> ⚠️ **但别因此糊弄。** 接口必须实现、必须传，而且从一开始就该按正确方式写 ——
> 普通那套用 SharedPreferences，安全那套用 EncryptedSharedPreferences。
> 几十行的事，等哪天上永续合约再补容易漏，而漏的后果是**私钥明文落盘**。

两个 demo 只演示钱包生成（不碰 `GemGateway`），所以没实现它 ——
这也是为什么 demo 里没有余额查询。

---

## 6. 接入顺序：先做什么后做什么

前两步是**前置工作，不是可选的** —— 不做就什么都跑不起来。

```
1. 实现 AlienProvider          ❗不做，core 发不出任何请求
2. 实现 GemPreferences ×2      ❗不做，GemGateway 构造不出来
                                 （普通 + 安全各一个，不能指向同一个）
3. 建单例 gateway / keystore
4. 直接调方法（都是 async）
5. 结果自己存 SQLite
```

**第 1、2 步都是硬性前置**：`AlienProvider` 缺了发不出请求，
`GemPreferences` 缺了连 `GemGateway` 都构造不出来 —— 编译期就过不去。

### 第 1、2 步的产出

```kotlin
val provider = NativeProvider()                    // 你写的 AlienProvider
val prefs = SharedPrefsPreferences(context)        // 普通
val securePrefs = EncryptedPreferences(context)    // 🔴 安全，别和上面共用

// 单例，App 启动时建一次
val gateway = GemGateway(provider, prefs, securePrefs, apiUrl)
val keystore = GemKeystore(keystoreDir)
```

### 第 4 步长什么样

```kotlin
// 查余额（都是 suspend）
val balance = gateway.getBalanceCoin("ethereum", addr)
val tokens = gateway.getBalanceTokens("ethereum", addr, idsFromDB)  // ← 来自后端

// 发一笔交易：gateway 与 keystore 配合
val preload = gateway.getTransactionPreload("ethereum", input)
val fees = gateway.getFeeRates("ethereum", inputType)
val signed = keystore.sign(keystoreId, "ethereum", signerInput, password)  // ← 唯一碰私钥的一步
val hash = gateway.transactionBroadcast("ethereum", signed, options)
val status = gateway.getTransactionStatus("ethereum", request)
```

**签名走 `GemKeystore`，其余走 `GemGateway`** —— 两个独立入口，职责不重叠。

### ⚠️ 两个 demo 停在第 1 步

demo 只实现了 `AlienProvider`，**没实现 `GemPreferences`**，
所以接不了 `GemGateway` —— 这就是为什么里面只有钱包生成、没有余额查询。

你们真要做链上功能时，第 2 步是最早会碰到的一件事。

---

## 7. 你独有的责任

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

## 8. 一句话记住

> **Rust 管密码学和链上协议，你管其余一切。**
>
> 后端 API 直连你发、本地数据库你建、网络 I/O 你执行；
> Rust 只在两件事上不可替代：私钥不能过 FFI（所以 keystore 归它），
> 以及双端必须一致的规则（不一致就是 bug）。

---

*本文档所有结论均为 2026-09-14 实测，验证命令见各节。
[iOS 版对照](https://github.com/weaver-max/GemIOSDemo/blob/main/app端和core端的功能边界.md)。*
