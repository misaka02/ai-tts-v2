package com.aitts.v2.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DualRoleSection(
    dualRole: Boolean,
    onDualRoleChange: (Boolean) -> Unit,
    dialogueVoiceId: String,
    onDialogueChange: (String) -> Unit,
    maleVoiceId: String,
    onMaleChange: (String) -> Unit,
    femaleVoiceId: String,
    onFemaleChange: (String) -> Unit,
    elderVoiceId: String,
    onElderChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("启用多角色对白音色分流")
            Switch(checked = dualRole, onCheckedChange = onDualRoleChange)
        }
        if (dualRole) {
            OutlinedTextField(
                value = dialogueVoiceId,
                onValueChange = onDialogueChange,
                label = { Text("通用对白音色") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = maleVoiceId,
                onValueChange = onMaleChange,
                label = { Text("男声对白音色") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = femaleVoiceId,
                onValueChange = onFemaleChange,
                label = { Text("女声对白音色") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = elderVoiceId,
                onValueChange = onElderChange,
                label = { Text("长者/反派音色") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun CustomHttpSection(
    customPayload: String,
    onPayloadChange: (String) -> Unit,
    responsePath: String,
    onResponsePathChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = customPayload,
            onValueChange = onPayloadChange,
            label = { Text("JSON Body 模板") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = responsePath,
            onValueChange = onResponsePathChange,
            label = { Text("JSON Base64 提取路径 (留空表示二进制流)") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
