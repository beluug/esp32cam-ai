@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.demo.bandbridge

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.demo.bandbridge.camera.CameraBlePermissions
import com.demo.bandbridge.image.LoadedImage
import com.demo.bandbridge.model.AppLog
import com.demo.bandbridge.model.ConnectionState
import com.demo.bandbridge.model.GestureState
import com.demo.bandbridge.model.SelectedImageState
import com.demo.bandbridge.model.SendHistoryItem
import com.demo.bandbridge.ui.DemoTab
import com.demo.bandbridge.ui.DemoViewModel
import android.graphics.BitmapFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BandBridgeApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BandBridgeApp(viewModel: DemoViewModel = viewModel()) {
    val context = LocalContext.current
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        viewModel.onLocalImagesSelected(uris)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshAllStates()
    }

    LaunchedEffect(Unit) {
        if (!CameraBlePermissions.hasRequiredPermissions(context)) {
            permissionLauncher.launch(CameraBlePermissions.requiredPermissions())
        } else {
            viewModel.refreshAllStates()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("公开拍摄辅助系统") }) },
        bottomBar = {
            NavigationBar {
                DemoTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = viewModel.currentTab == tab,
                        onClick = { viewModel.currentTab = tab },
                        icon = {},
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        when (viewModel.currentTab) {
            DemoTab.HOME -> HomeScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(padding),
                pickLocalImages = {
                    imagePickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                requestBlePermissions = {
                    permissionLauncher.launch(CameraBlePermissions.requiredPermissions())
                }
            )

            DemoTab.HISTORY -> HistoryScreen(viewModel, Modifier.padding(padding))
            DemoTab.SETTINGS -> SettingsScreen(viewModel, Modifier.padding(padding))
            DemoTab.DEBUG -> DebugScreen(viewModel.logs.toList(), Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeScreen(
    viewModel: DemoViewModel,
    modifier: Modifier,
    pickLocalImages: () -> Unit,
    requestBlePermissions: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ConnectionCard(viewModel.cameraState)
        InfoCard("相机参数", viewModel.cameraConfigSummary())
        ConnectionCard(viewModel.bandState)
        ConnectionCard(viewModel.keepAliveState)
        GestureCard(viewModel.gestureState)
        SelectedImageCard(viewModel.selectedImageState)
        PreviewImageCard(viewModel.latestPreviewImage)
        InfoCard("最近结果", viewModel.latestResult)
        InfoCard(
            "分析说明",
            "现在只保留自定义 AI。顺位固定最多 8 个，自定义 API 槽位可继续增加。板子重复分析同一张新拍前缓存图时，会自动从上次成功顺位的下一个顺位继续。"
        )

        Button(onClick = requestBlePermissions, modifier = Modifier.fillMaxWidth()) {
            Text("申请蓝牙权限")
        }

        Button(onClick = pickLocalImages, modifier = Modifier.fillMaxWidth()) {
            Text("选择本地测试图片（可多选）")
        }

        Button(onClick = viewModel::clickCapture, modifier = Modifier.fillMaxWidth()) {
            Text("拍照")
        }

        Button(
            onClick = viewModel::clickUpload,
            enabled = !viewModel.isAnalyzing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (viewModel.isAnalyzing) "分析中" else "上传分析")
        }

        Button(onClick = viewModel::clickTest, modifier = Modifier.fillMaxWidth()) {
            Text("刷新设备状态")
        }

        ManualPushSection(viewModel)
    }
}

@Composable
private fun ManualPushSection(viewModel: DemoViewModel) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("自定义文本发送到手环", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = viewModel.customBandText,
                onValueChange = viewModel::updateCustomBandText,
                label = { Text("输入要发给手环的文本") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4
            )
            Button(
                onClick = viewModel::pushCustomTextToBand,
                enabled = !viewModel.isSending,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (viewModel.isSending) "发送中" else "发送文本")
            }
        }
    }
}

@Composable
private fun HistoryScreen(viewModel: DemoViewModel, modifier: Modifier) {
    if (viewModel.history.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            InfoCard("历史记录", "这里会记录分析后发送到手环的结果。")
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(viewModel.history) { item ->
            HistoryCard(item)
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: DemoViewModel, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        InfoCard(
            "当前编辑位置",
            "${viewModel.currentEditingLabel()}\n现在只保留自定义 AI，API 槽位没有数量上限。"
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::addCustomEndpointSlot, modifier = Modifier.weight(1f)) {
                Text("新增 API 槽位")
            }
            Button(onClick = viewModel::removeCurrentEndpointSlot, modifier = Modifier.weight(1f)) {
                Text("删除当前槽位")
            }
        }

        Text("当前编辑的 API 槽位")
        IntChips(
            values = viewModel.currentEditableSlots(),
            selected = viewModel.settings.editSlot,
            onSelect = viewModel::updateEditSlot
        )

        OutlinedTextField(
            value = viewModel.settings.prompt,
            onValueChange = viewModel::updatePrompt,
            label = { Text("自定义 Prompt") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 6
        )

        OutlinedTextField(
            value = viewModel.settings.delayMs,
            onValueChange = viewModel::updateDelay,
            label = { Text("发送到手环前延时（毫秒）") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        InfoCard("相机参数", "当前：${viewModel.cameraConfigSummary()}\n连上相机板后可以把这组参数发到板子。")

        Text("相机分辨率")
        StringChips(
            values = viewModel.cameraFrameSizeOptions(),
            selected = viewModel.currentCameraFrameSize(),
            onSelect = viewModel::updateCameraFrameSize
        )

        OutlinedTextField(
            value = viewModel.currentCameraJpegQuality(),
            onValueChange = viewModel::updateCameraJpegQuality,
            label = { Text("JPEG压缩值（10最清晰，30最糊）") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        Button(onClick = viewModel::applyCameraConfig, modifier = Modifier.fillMaxWidth()) {
            Text("下发相机参数到板子")
        }

        OutlinedTextField(
            value = viewModel.currentEditingApiKey(),
            onValueChange = viewModel::updateCurrentEditingApiKey,
            label = { Text("${viewModel.currentEditingLabel()} Key") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation()
        )

        OutlinedTextField(
            value = viewModel.currentEditingModel(),
            onValueChange = viewModel::updateCurrentEditingModel,
            label = { Text("${viewModel.currentEditingLabel()} 模型") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = viewModel.currentEditingBaseUrl(),
            onValueChange = viewModel::updateCurrentEditingBaseUrl,
            label = { Text("${viewModel.currentEditingLabel()} Base URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(
            onClick = viewModel::testAiProvider,
            enabled = !viewModel.isAnalyzing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (viewModel.isAnalyzing) "测试中" else "测试当前 AI")
        }

        InfoCard("AI 测试状态", viewModel.aiTestStatus)

        Text("分析顺位（最多 8 个）")
        repeat(8) { index ->
            PriorityRouteCard(
                title = if (index == 0) "首选" else "备选${index}号",
                enabled = viewModel.priorityRouteEnabled(index),
                selectedSlot = viewModel.priorityRouteSlot(index),
                availableSlots = viewModel.priorityRouteSlots(),
                onEnabledChanged = { enabled -> viewModel.updatePriorityRouteEnabled(index, enabled) },
                onSlotSelected = { slot -> viewModel.updatePriorityRouteSlot(index, slot) },
                onClear = { viewModel.clearPriorityRoute(index) }
            )
        }
    }
}

@Composable
private fun PriorityRouteCard(
    title: String,
    enabled: Boolean,
    selectedSlot: Int,
    availableSlots: List<Int>,
    onEnabledChanged: (Boolean) -> Unit,
    onSlotSelected: (Int) -> Unit,
    onClear: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = onEnabledChanged)
            }

            if (enabled) {
                Text("使用哪个 API 槽位")
                IntChips(
                    values = availableSlots,
                    selected = selectedSlot,
                    onSelect = onSlotSelected
                )
            }

            Button(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                Text("清空这一顺位")
            }
        }
    }
}

@Composable
private fun DebugScreen(logs: List<AppLog>, modifier: Modifier) {
    if (logs.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            InfoCard("调试日志", "这里会记录手环、相机、AI、保活和系统状态。")
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(logs) { log ->
            InfoCard("${log.timeLabel} | ${log.source}", log.message)
        }
    }
}

@Composable
private fun StringChips(
    values: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.chunked(3).forEach { rowValues ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowValues.forEach { value ->
                    FilterChip(
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        label = { Text(value) }
                    )
                }
            }
        }
    }
}

@Composable
private fun IntChips(
    values: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.chunked(4).forEach { rowValues ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowValues.forEach { value ->
                    FilterChip(
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        label = { Text("API $value") }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(item: SendHistoryItem) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("${item.timeLabel} | ${item.title}", style = MaterialTheme.typography.titleMedium)
            Text(item.status, style = MaterialTheme.typography.bodySmall)
            Text(item.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SelectedImageCard(state: SelectedImageState) {
    InfoCard("当前测试图片队列", "${state.label}\n${state.detail}")
}

@Composable
private fun PreviewImageCard(image: LoadedImage?) {
    if (image == null) return
    val bitmap = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size) ?: return
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("最新拍照预览", style = MaterialTheme.typography.titleMedium)
            Text(image.label, style = MaterialTheme.typography.bodySmall)
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = image.label,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun GestureCard(state: GestureState) {
    InfoCard(state.title, state.detail)
}

@Composable
private fun ConnectionCard(state: ConnectionState) {
    InfoCard(state.title, state.detail)
}

@Composable
private fun InfoCard(title: String, value: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
