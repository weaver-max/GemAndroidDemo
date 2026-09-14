package com.example.gemdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {

    init {
        System.loadLibrary("gemstone")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 无头自检，见 SelfTest.kt。对应 iOS 的 -selftest 启动参数：
        //   adb shell am start -n com.example.gemdemo/.MainActivity --ez selftest true
        if (intent?.getBooleanExtra("selftest", false) == true) {
            SelfTest.run(this)
        }

        // Android 有 `adb shell input tap`，不像 iOS 那样必须靠启动参数触发。
        // 但保留这几个开关，自动化验证更稳（不依赖坐标）。
        val autoGenerate = intent?.getBooleanExtra("autowallet", false) == true
        val autoDetail = intent?.getBooleanExtra("detail", false) == true
        val startOnFfi = intent?.getBooleanExtra("ffi", false) == true

        setContent {
            MaterialTheme {
                // 默认落在钱包页 —— 这是 demo 的主场
                var tab by remember { mutableIntStateOf(if (startOnFfi) 0 else 1) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Text("⇄") },
                                label = { Text("FFI") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = { Text("🔑") },
                                label = { Text("钱包") },
                            )
                        }
                    }
                ) { inner ->
                    when (tab) {
                        0 -> FfiScreen(Modifier.padding(inner))
                        else -> WalletScreen(
                            modifier = Modifier.padding(inner),
                            autoGenerate = autoGenerate,
                            autoOpenDetail = autoDetail,
                        )
                    }
                }
            }
        }
    }
}
