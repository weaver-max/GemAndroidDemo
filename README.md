# GemAndroidDemo

Android 怎么调用 Rust 编译出来的 gem 核心库。一个能跑的最小例子。

[iOS 版在这里](https://github.com/weaver-max/GemIOSDemo) —— 两边功能完全对等。

```bash
export ANDROID_HOME=/path/to/android-sdk
export GITHUB_ACTOR=你的GitHub用户名
export GITHUB_TOKEN=带read:packages的token

./build.sh          # 编译 + 装进模拟器 + 启动
./build.sh --clean  # 顺便清空钱包数据
```

App 有两页：**钱包**（生成、看地址、看助记词）和 **FFI**（版本号、网络回调）。

---

## 1. 接入

### 🔴 GitHub Packages 必须鉴权

即使仓库是 public 也一样。不配凭据会拿到 **401 Unauthorized**——
注意不是 404，很容易误判成「包没发上去」。

`settings.gradle`：

```groovy
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/weaver-max/wallet")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")   // 需要 read:packages
            }
        }
    }
}
```

`app/build.gradle`：

```groovy
implementation "com.gemwallet.gemstone:gemstone:2.114.10@aar"
implementation "net.java.dev.jna:jna:5.18.1@aar"
```

然后 `import uniffi.gemstone.*` 就能用了。不需要装 Rust。

> ⚠️ 只能用 `core/`（MIT）。gem 仓库里的 `ios/` 和 `android/` 是 GPL-3.0，
> 抄过去你整个 App 都得开源。

### 三条构建硬约束

```groovy
android {
    compileSdk 37          // 🔴 不能低于 AAR 的 compileSdk
    compileSdkMinor 0      // 🔴 Android 37 起用 major.minor，没有裸 android-37
    defaultConfig { minSdk 28 }   // 🔴 不能低于 AAR 的 minSdk
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}
```

低于任何一条都是**编译期**报错。特别是 `jvmTarget` —— AAR 是 JVM 17 编的，
停在 1.8 时凡是内联 AAR 里的方法都会挂。

> AGP 9 起 Kotlin 支持已内置：**不要**再加 `org.jetbrains.kotlin.android` 插件，
> 会报 `Cannot add extension with name 'kotlin'`。
> `android { kotlinOptions {} }` 也已移除，改用顶层 `kotlin { compilerOptions {} }`。

---

## 2. 怎么调

### 直接调函数

```kotlin
import uniffi.gemstone.*

libVersion()                                   // "2.114.10"
validateAddress(addr, "ethereum")              // 地址合法吗
checksumAddress(addr, "ethereum")              // EIP-55 大小写
shortAddress(addr, "ethereum")                 // "0x1234…abcd"
```

### 创建对象

```kotlin
val words = GemMnemonic().generate(12u)        // 注意是 UByte

val keystore = GemKeystore(某个目录)
val wallet = keystore.createStore(
    GemImportType.MulticoinPhrase(words, listOf("ethereum", "solana")),
    "用户密码".toByteArray(),
)
wallet.walletId    // multicoin_0x1C20…
wallet.accounts    // 每条链一个地址
```

### 实现接口让 Rust 回调你

Rust 不自己发网络请求、不自己存偏好设置，它定义接口让你实现：

| 接口 | 你提供 | 为什么不让 Rust 做 |
|---|---|---|
| `AlienProvider` | 发 HTTP | 复用平台的连接池、代理、证书校验 |
| `GemPreferences` | 键值存储 | 复用 SharedPreferences / Keystore |

完整版见 [FfiScreen.kt](app/src/main/java/com/example/gemdemo/FfiScreen.kt)。

---

## 3. 六个容易踩的坑

### 链名是字符串，不是枚举

```kotlin
public typealias Chain = kotlin.String

validateAddress(addr, "ethereum")   // ✅
```

全小写无下划线：`"ethereum"` `"smartchain"` `"solana"`。
**拼错不会报编译错误，只在运行时失败。** 建议自己包一层 enum 兜底。

### 错误类型叫 `AlienException` 不是 `AlienError`

UniFFI 的 Kotlin 绑定会给错误类型加 `Exception` 后缀，和 Swift 侧不一样：

| Swift | Kotlin |
|---|---|
| `AlienError.RequestError(msg:)` | `AlienException.RequestException(msg)` |

照着 iOS 文档写会编不过。

### 密码是 `ByteArray` 不是 `String`

```kotlin
val password = "用户密码".toByteArray()
```

### 私钥不过 FFI

签名在 Rust 内部完成，只返回签名结果。Kotlin 侧拿不到私钥。
只有用户主动导出时例外（`exportPrivateKey` / `exportRecoveryPhrase`），
这两个调用前必须过生物识别。

### `AlienResponse` 读不出内容

它是 `uniffi::Object`，只有构造函数，没有 getter：

```kotlin
val resp = AlienResponse(200.toUShort(), bytes)
resp.status   // ❌ 没这个属性
```

它是给 Rust 消费的。要记状态码就在构造之前记。

### `createStore` 很慢

Argon2id 要 19 MiB 内存、跑 2 轮，必须 `withContext(Dispatchers.IO)`。

---

## 4. 钱包清单得你自己存

`GemKeystore` 一共 9 个方法，**没有 list、没有 getAll**，而且除了 `createStore`
每个都要求你传 `keystoreId`。

Rust 从不告诉你有哪些钱包，它只按 id 干活。**你不自己记，生成完就找不回来了。**

| | 存什么 | 存哪 |
|---|---|---|
| Rust | 加密后的助记词 | `<baseDir>/<keystoreId>.json`，一钱包一文件 |
| 你 | 钱包清单、地址、名字、排序 | 数据库 |

本项目的表（照搬 gem 主 App 的设计）：

```sql
CREATE TABLE wallets (
    id          TEXT PRIMARY KEY NOT NULL,   -- walletId
    created_at  INTEGER NOT NULL
);
CREATE TABLE wallets_accounts (
    wallet_id       TEXT NOT NULL REFERENCES wallets(id) ON DELETE CASCADE,
    chain           TEXT NOT NULL,
    address         TEXT NOT NULL,
    derivation_path TEXT NOT NULL,
    account_index   INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (wallet_id, chain, account_index)
);
```

`account_index` **M1 恒为 0**。后续 core 支持同链多账户时，结构不用改，
把真实 index 填进来即可。主键从一开始就带上它 —— 只写 `(wallet_id, chain)`
将来会撞主键。

**表里没有助记词和私钥的位置。** 这比写个检查函数更可靠 —— 想存秘密得先改表结构。

助记词在详情页是调 `exportRecoveryPhrase(keystoreId, password)` 现场解出来的，
所以杀掉 App 重启照样能看到。

> 本项目用 `SQLiteOpenHelper`（框架自带，零依赖）。真实项目请用 **Room** ——
> 它的 DAO 可以返回 `Flow`，数据一变 UI 自动刷新。这才是清单该放数据库而不是
> SharedPreferences 的真正理由。

---

## 5. 一个助记词，多条链，但同链只有一个地址

```
ethereum  0x661fBA7D310Aab0…   m/44'/60'/0'/0/0
solana    4jmCiZfZw183xcFfq…   m/44'/501'/0'/0'
bitcoin   bc1qj8kec602cl7f6…   m/84'/0'/0'/0/0
```

EVM 系列链（ethereum / polygon / arbitrum…）**共用同一个地址**，因为派生路径相同。

### 🔴 做不了 MetaMask 那种「添加账户」

同一条链上派生 Account 1 / 2 / 3，**gem 目前做不到**。不是没导出，是 core 里就没有：

```rust
// core/crates/gem_derivation/src/private_key/path.rs
pub fn default_derivation_path(chain: Chain) -> &'static str {
    match chain {
        Chain::Ethereum | ... => "m/44'/60'/0'/0/0",   // 编译期常量
```

路径是写死的字符串，整个 crate 没有任何函数接受 index 参数。

要支持的话改动不小 —— `walletId` 现在**就是** index 0 的以太坊地址，
加索引等于改钱包身份模型，还要动 keystore 文件格式。

---

## 6. 跑起来和验证

```bash
./build.sh
```

会自动编译（含网络重试）、启模拟器、安装、启动，并检查 APK 里确实有
`libgemstone.so`——**编译通过只证明坐标能解析，不证明 native 库在包里**。

无头自检：

```bash
adb shell am start -n com.example.gemdemo/.MainActivity --ez selftest true
sleep 25
adb shell run-as com.example.gemdemo cat files/selftest.txt
```

跑 15 项：生成、多链派生、落盘、助记词解密、再派生后助记词与 walletId 不变、
派生路径与地址的对应关系、`index` 恒为 0、数据库不含秘密、删除后文件与清单同步清理。

其他开关（`--ez` 传布尔值）：`autowallet` 生成一个 · `detail` 打开详情 ·
`ffi` 落在 FFI 页。

> Android 有 `adb shell input tap`，本来不需要这些开关。保留是因为
> 按坐标点击不稳（分辨率一变就失效），用 Intent extra 更可靠。

---

## 7. 与 iOS 版的差异

| | Android | iOS |
|---|---|---|
| 制品来源 | GitHub Packages（**要鉴权**） | GitHub Releases（不要） |
| 错误类型 | `AlienException.RequestException` | `AlienError.RequestError` |
| 密码类型 | `ByteArray` | `Data` |
| 词数参数 | `12u`（UByte） | `12`（UInt8） |
| 数据库 | `SQLiteOpenHelper` → 真实项目用 Room | `libsqlite3` → 真实项目用 GRDB |
| 注入点击 | `adb shell input tap` 可用 | ❌ 没有等价命令 |
| 架构 | AAR 含 3 个 ABI | 只有 arm64，Intel Mac 不可用 |

---

## 文件

```
settings.gradle          GitHub Packages 源与凭据
app/build.gradle         三条构建硬约束
MainActivity.kt          Tab 容器
FfiScreen.kt             libVersion + AlienProvider 实现
WalletScreen.kt          钱包列表
WalletDetailSheet.kt     钱包详情（助记词现场解密）
WalletStore.kt           清单接口 + 创建/解密/删除
WalletDatabase.kt        SQLite
SelfTest.kt              无头自检
build.sh                 一键构建运行
```

更多背景见 gem 仓库根目录的 `安卓如何使用gem从0开发钱包.md`、
`安卓跑apk出现的问题以及解决.md`、`gem私钥管理.md`。
