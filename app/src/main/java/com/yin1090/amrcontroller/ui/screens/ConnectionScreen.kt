package com.yin1090.amrcontroller.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yin1090.amrcontroller.model.ConnectionConfig
import com.yin1090.amrcontroller.ui.state.ConnectionFormState
import com.yin1090.amrcontroller.ui.theme.TacticalAmber
import com.yin1090.amrcontroller.ui.theme.TacticalBg
import com.yin1090.amrcontroller.ui.theme.TacticalBlue
import com.yin1090.amrcontroller.ui.theme.TacticalBorder
import com.yin1090.amrcontroller.ui.theme.TacticalCard
import com.yin1090.amrcontroller.ui.theme.TacticalCyan
import com.yin1090.amrcontroller.ui.theme.TacticalRedBright
import com.yin1090.amrcontroller.ui.theme.TacticalTextMuted
import com.yin1090.amrcontroller.ui.theme.TacticalTextPrimary
import com.yin1090.amrcontroller.ui.theme.TacticalTile

@Composable
fun ConnectionScreen(
    formState: ConnectionFormState,
    onHostChange: (String) -> Unit,
    onApiPortChange: (String) -> Unit,
    onCamPortChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    onOfflineDemoClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalBg)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = TacticalCard),
            border = BorderStroke(1.dp, TacticalBorder)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "AMR TACTICAL LINK",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TacticalTextPrimary
                        )
                        Text(
                            text = "Differential-Drive Mobile Telemetry & Control",
                            fontSize = 12.sp,
                            color = TacticalTextMuted
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(TacticalTile, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "ROS 2 REST",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TacticalCyan
                        )
                    }
                }

                OutlinedTextField(
                    value = formState.host,
                    onValueChange = onHostChange,
                    label = { Text("Robot Host / IP") },
                    placeholder = {
                        Text(
                            text = ConnectionConfig.DEFAULT_HOST_HINT,
                            color = TacticalTextMuted
                        )
                    },
                    singleLine = true,
                    enabled = !formState.busy,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TacticalBlue,
                        unfocusedBorderColor = TacticalBorder,
                        focusedTextColor = TacticalTextPrimary,
                        unfocusedTextColor = TacticalTextPrimary,
                        focusedLabelColor = TacticalCyan,
                        unfocusedLabelColor = TacticalTextMuted
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = formState.apiPortText,
                        onValueChange = onApiPortChange,
                        label = { Text("API Port") },
                        singleLine = true,
                        enabled = !formState.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TacticalBlue,
                            unfocusedBorderColor = TacticalBorder,
                            focusedTextColor = TacticalTextPrimary,
                            unfocusedTextColor = TacticalTextPrimary,
                            focusedLabelColor = TacticalCyan,
                            unfocusedLabelColor = TacticalTextMuted
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = formState.camPortText,
                        onValueChange = onCamPortChange,
                        label = { Text("Camera Port") },
                        singleLine = true,
                        enabled = !formState.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TacticalBlue,
                            unfocusedBorderColor = TacticalBorder,
                            focusedTextColor = TacticalTextPrimary,
                            unfocusedTextColor = TacticalTextPrimary,
                            focusedLabelColor = TacticalCyan,
                            unfocusedLabelColor = TacticalTextMuted
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                if (!formState.errorMessage.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TacticalRedBright.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = formState.errorMessage,
                            color = TacticalRedBright,
                            fontSize = 12.sp
                        )
                    }
                }

                if (!formState.warningBanner.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(TacticalAmber.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = formState.warningBanner,
                            color = TacticalAmber,
                            fontSize = 12.sp
                        )
                    }
                }

                Button(
                    onClick = onConnectClick,
                    enabled = !formState.busy,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TacticalBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    if (formState.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = TacticalTextPrimary
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text("PROBING /api/telemetry (12s)...")
                    } else {
                        Text("CONNECT", fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = onOfflineDemoClick,
                    enabled = !formState.busy,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, TacticalBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text("OFFLINE DEMO", color = TacticalCyan, fontWeight = FontWeight.SemiBold)
                }

                Text(
                    text = "Probe timeout: 12s • HTTP 200 saves config • HTTP 404 warns without saving",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TacticalTextMuted
                )
            }
        }
    }
}
