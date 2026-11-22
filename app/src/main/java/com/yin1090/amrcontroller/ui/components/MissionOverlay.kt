package com.yin1090.amrcontroller.ui.components

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yin1090.amrcontroller.model.ConnectionConfig
import com.yin1090.amrcontroller.ui.theme.TacticalAmber
import com.yin1090.amrcontroller.ui.theme.TacticalBg
import com.yin1090.amrcontroller.ui.theme.TacticalBorder
import com.yin1090.amrcontroller.ui.theme.TacticalCard
import com.yin1090.amrcontroller.ui.theme.TacticalCyan
import com.yin1090.amrcontroller.ui.theme.TacticalTextPrimary

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MissionOverlay(
    config: ConnectionConfig,
    missionUrl: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val webView = remember(missionUrl) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val host = request?.url?.host ?: return true
                    return !host.equals(config.normalizedHost, ignoreCase = true)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        errorMessage = error?.description?.toString() ?: "Failed to load mission dashboard"
                    }
                }
            }
            loadUrl(missionUrl)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TacticalBg.copy(alpha = 0.88f))
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(TacticalCard)
                .border(1.dp, TacticalBorder, RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TacticalBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MISSION DASHBOARD (/?compact=1)",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = TacticalCyan
                    )
                    Text(
                        text = missionUrl,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TacticalTextPrimary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            errorMessage = null
                            webView.reload()
                        },
                        border = BorderStroke(1.dp, TacticalBorder)
                    ) {
                        Text("RELOAD", color = TacticalTextPrimary, fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = onClose,
                        border = BorderStroke(1.dp, TacticalBorder)
                    ) {
                        Text("CLOSE", color = TacticalTextPrimary, fontSize = 11.sp)
                    }
                }
            }

            val err = errorMessage
            if (err != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(TacticalAmber.copy(alpha = 0.15f))
                        .padding(10.dp)
                ) {
                    Text(text = err, color = TacticalAmber, fontSize = 12.sp)
                }
            }

            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
