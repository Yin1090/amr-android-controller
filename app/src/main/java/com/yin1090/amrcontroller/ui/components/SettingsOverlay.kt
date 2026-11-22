package com.yin1090.amrcontroller.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yin1090.amrcontroller.domain.Kinematics
import com.yin1090.amrcontroller.model.CockpitUiState
import com.yin1090.amrcontroller.model.ConnectionConfig
import com.yin1090.amrcontroller.model.PidGains
import com.yin1090.amrcontroller.model.ValidationResult
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
fun SettingsOverlay(
    state: CockpitUiState,
    onApplyAll: (host: String, apiPort: Int, camPort: Int, rpmMax: Int, pid: PidGains) -> Unit,
    onApplyPidOnly: (PidGains) -> Unit,
    onResetMap: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var hostText by remember(state.config.host) { mutableStateOf(state.config.host) }
    var apiPortText by remember(state.config.apiPort) { mutableStateOf(state.config.apiPort.toString()) }
    var camPortText by remember(state.config.camPort) { mutableStateOf(state.config.camPort.toString()) }
    var rpmText by remember(state.desiredRpm) { mutableStateOf(state.desiredRpm.toString()) }
    var kpText by remember(state.pidGains.kp) { mutableStateOf(state.pidGains.kp.toString()) }
    var kiText by remember(state.pidGains.ki) { mutableStateOf(state.pidGains.ki.toString()) }
    var kdText by remember(state.pidGains.kd) { mutableStateOf(state.pidGains.kd.toString()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TacticalBg.copy(alpha = 0.88f))
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(12.dp))
                .background(TacticalCard)
                .border(1.dp, TacticalBorder, RoundedCornerShape(12.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "COCKPIT SETTINGS & PID",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TacticalCyan
                )
                OutlinedButton(onClick = onClose, border = BorderStroke(1.dp, TacticalBorder)) {
                    Text("CLOSE", color = TacticalTextPrimary, fontSize = 11.sp)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsField(
                    label = "Host",
                    value = hostText,
                    placeholder = ConnectionConfig.DEFAULT_HOST_HINT,
                    onValueChange = { hostText = it },
                    modifier = Modifier.weight(2f)
                )
                SettingsField(
                    label = "API Port",
                    value = apiPortText,
                    keyboardType = KeyboardType.Number,
                    onValueChange = { apiPortText = it.filter(Char::isDigit) },
                    modifier = Modifier.weight(1f)
                )
                SettingsField(
                    label = "Cam Port",
                    value = camPortText,
                    keyboardType = KeyboardType.Number,
                    onValueChange = { camPortText = it.filter(Char::isDigit) },
                    modifier = Modifier.weight(1f)
                )
                SettingsField(
                    label = "Target RPM (40-135)",
                    value = rpmText,
                    keyboardType = KeyboardType.Number,
                    onValueChange = { rpmText = it.filter(Char::isDigit) },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsField(
                    label = "PID Kp (0.5)",
                    value = kpText,
                    keyboardType = KeyboardType.Decimal,
                    onValueChange = { kpText = it },
                    modifier = Modifier.weight(1f)
                )
                SettingsField(
                    label = "PID Ki (1.5)",
                    value = kiText,
                    keyboardType = KeyboardType.Decimal,
                    onValueChange = { kiText = it },
                    modifier = Modifier.weight(1f)
                )
                SettingsField(
                    label = "PID Kd (0.01)",
                    value = kdText,
                    keyboardType = KeyboardType.Decimal,
                    onValueChange = { kdText = it },
                    modifier = Modifier.weight(1f)
                )
            }

            if (validationError != null) {
                Text(text = validationError!!, color = TacticalRedBright, fontSize = 12.sp)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val kp = kpText.toFloatOrNull()
                        val ki = kiText.toFloatOrNull()
                        val kd = kdText.toFloatOrNull()
                        if (kp == null || ki == null || kd == null || !PidGains(kp, ki, kd).isValid()) {
                            validationError = "PID values must be non-negative decimals."
                        } else {
                            validationError = null
                            onApplyPidOnly(PidGains(kp, ki, kd))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TacticalBlue),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("APPLY PID", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onResetMap,
                    border = BorderStroke(1.dp, TacticalAmber),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("RESET MAP", fontFamily = FontFamily.Monospace, color = TacticalAmber, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        val apiP = apiPortText.toIntOrNull()
                        val camP = camPortText.toIntOrNull()
                        val rpm = rpmText.toIntOrNull()
                        val kp = kpText.toFloatOrNull()
                        val ki = kiText.toFloatOrNull()
                        val kd = kdText.toFloatOrNull()
                        val candidateCfg = if (apiP != null && camP != null) {
                            ConnectionConfig(host = hostText, apiPort = apiP, camPort = camP)
                        } else {
                            null
                        }
                        val cfgValidation = candidateCfg?.validate()
                        val candidatePid = if (kp != null && ki != null && kd != null) {
                            PidGains(kp, ki, kd)
                        } else {
                            null
                        }
                        when {
                            candidateCfg == null -> {
                                validationError = "API and Camera ports must be in 1..65535."
                            }
                            cfgValidation is ValidationResult.Invalid -> {
                                validationError = cfgValidation.message
                            }
                            rpm == null -> {
                                validationError = "Target RPM must be a valid integer (40..135)."
                            }
                            candidatePid == null || !candidatePid.isValid() -> {
                                validationError = "PID values must be finite non-negative decimals."
                            }
                            else -> {
                                validationError = null
                                onApplyAll(
                                    candidateCfg.normalizedHost,
                                    apiP!!,
                                    camP!!,
                                    Kinematics.clampRpm(rpm),
                                    candidatePid
                                )
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TacticalBlue),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("SAVE & APPLY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }

            // Kinematics & Safety Reference Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(TacticalTile)
                    .border(1.dp, TacticalBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "KINEMATICS & SAFETY REFERENCE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = TacticalCyan
                )
                Text(
                    text = "R = 0.0366 m  |  B = 0.096 m  |  K = 0.003833 m/s/RPM",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TacticalTextPrimary
                )
                Text(
                    text = "v = RPM * K (40..135 RPM)  |  ω = min(1.80, 0.60 * v / B)  |  Diagonal factor = 0.707",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TacticalTextMuted
                )
                Text(
                    text = "Interlock: 8-zone < 40 cm auto-halt | LiDAR age < 0.5 s | Watchdog > 1500 ms",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = TacticalTextMuted
                )
            }
        }
    }
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        placeholder = placeholder?.let { { Text(it, fontSize = 11.sp, color = TacticalTextMuted) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TacticalBlue,
            unfocusedBorderColor = TacticalBorder,
            focusedTextColor = TacticalTextPrimary,
            unfocusedTextColor = TacticalTextPrimary,
            focusedLabelColor = TacticalCyan,
            unfocusedLabelColor = TacticalTextMuted
        ),
        modifier = modifier
    )
}
