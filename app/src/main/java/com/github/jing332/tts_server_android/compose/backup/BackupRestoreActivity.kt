package com.github.jing332.tts_server_android.compose.backup

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Output
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.jing332.common.utils.FileUtils.readBytes
import com.github.jing332.compose.widgets.AppDialog
import com.github.jing332.compose.widgets.LoadingDialog
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.compose.ComposeActivity
import com.github.jing332.tts_server_android.compose.settings.BasePreferenceWidget
import com.github.jing332.tts_server_android.compose.theme.AppTheme
import com.github.jing332.tts_server_android.conf.AppConfig
import com.github.jing332.tts_server_android.ui.AppActivityResultContracts
import com.github.jing332.tts_server_android.ui.view.AppDialogs.displayErrorDialog
import com.thegrizzlylabs.sardineandroid.model.DavResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupRestoreActivity : ComposeActivity() {
    companion object {
        const val TAG = "BackupRestoreActivity"
    }

    private var showFromFileRestoreDialog = mutableStateOf<ByteArray?>(null)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val vm: BackupRestoreViewModel = viewModel()
                var showBackupDialog by remember { mutableStateOf(false) }
                var showRestoreMenu by remember { mutableStateOf(false) }
                var showWebDavSettings by remember { mutableStateOf(false) }
                var showUrlInputDialog by remember { mutableStateOf(false) }
                var showWebDavListDialog by remember { mutableStateOf(false) }
                var isLoading by remember { mutableStateOf(false) }

                if (isLoading) {
                    LoadingDialog(onDismissRequest = { isLoading = false })
                }

                if (showBackupDialog) {
                    BackupDialog(onDismissRequest = { showBackupDialog = false })
                }

                // 恢复菜单 (Bottom Sheet)
                if (showRestoreMenu) {
                    ModalBottomSheet(onDismissRequest = { showRestoreMenu = false }) {
                        Column(Modifier.padding(bottom = 32.dp)) {
                            // 1. 从本地文件恢复
                            // 👇👇👇 修正：这里的 result 是 Pair<IRequestData?, Uri?> 👇👇👇
                            val filePicker = rememberLauncherForActivityResult(contract = AppActivityResultContracts.filePickerActivity()) { result ->
                                showRestoreMenu = false
                                // 如果返回的 Uri 不为空
                                result?.second?.let { uri ->
                                    // 直接读取 Uri 对应的字节流
                                    showFromFileRestoreDialog.value = uri.readBytes(this@BackupRestoreActivity)
                                }
                            }
                            ListItem(
                                modifier = Modifier.clickable { filePicker.launch(null) },
                                headlineContent = { Text(stringResource(R.string.file_picker_mode_system)) },
                                leadingContent = { Icon(Icons.Default.FolderOpen, null) }
                            )

                            // 2. 从直链恢复
                            ListItem(
                                modifier = Modifier.clickable {
                                    showRestoreMenu = false
                                    showUrlInputDialog = true
                                },
                                headlineContent = { Text(stringResource(R.string.restore_from_url_net)) },
                                leadingContent = { Icon(Icons.Default.Link, null) }
                            )

                            // 3. 从 WebDAV 恢复
                            val context = LocalContext.current
                            val notConfiguredStr = stringResource(R.string.config_webdav_first)
                            ListItem(
                                modifier = Modifier.clickable {
                                    showRestoreMenu = false
                                    // 检查配置
                                    if (AppConfig.webDavUrl.value.isBlank()) {
                                        Toast.makeText(context, notConfiguredStr, Toast.LENGTH_SHORT).show()
                                        showWebDavSettings = true
                                    } else {
                                        showWebDavListDialog = true
                                    }
                                },
                                headlineContent = { Text(stringResource(R.string.restore_from_webdav)) },
                                leadingContent = { Icon(Icons.Default.CloudDownload, null) }
                            )
                        }
                    }
                }

                // URL 输入弹窗
                if (showUrlInputDialog) {
                    var url by remember { mutableStateOf("") }
                    val scope = rememberCoroutineScope()
                    AppDialog(
                        onDismissRequest = { showUrlInputDialog = false },
                        title = { Text(stringResource(R.string.import_from_url)) },
                        content = {
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                label = { Text("URL") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        buttons = {
                            TextButton(onClick = {
                                if (url.isBlank()) return@TextButton
                                showUrlInputDialog = false
                                isLoading = true
                                scope.launch {
                                    runCatching {
                                        val bytes = vm.downloadFromUrl(url)
                                        showFromFileRestoreDialog.value = bytes
                                    }.onFailure {
                                        displayErrorDialog(it)
                                    }
                                    isLoading = false
                                }
                            }) { Text(stringResource(R.string.confirm)) }
                            TextButton(onClick = { showUrlInputDialog = false }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }

                // WebDAV 设置弹窗
                if (showWebDavSettings) {
                    WebDavSettingsDialog(
                        onDismissRequest = { showWebDavSettings = false },
                        vm = vm
                    )
                }

                // WebDAV 文件列表弹窗
                if (showWebDavListDialog) {
                    WebDavListDialog(
                        onDismissRequest = { showWebDavListDialog = false },
                        vm = vm,
                        onFileSelected = { bytes ->
                            showFromFileRestoreDialog.value = bytes
                        }
                    )
                }

                // 最终的恢复确认弹窗 (核心逻辑)
                if (showFromFileRestoreDialog.value != null) {
                    RestoreDialog(
                        bytes = showFromFileRestoreDialog.value!!,
                        onDismissRequest = { showFromFileRestoreDialog.value = null }
                    )
                }

                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text(stringResource(id = R.string.backup_restore)) },
                        navigationIcon = {
                            IconButton(onClick = { finish() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    stringResource(id = R.string.nav_back)
                                )
                            }
                        })
                }) { padding ->
                    Column(Modifier.padding(padding)) {
                        // 1. 备份按钮
                        BasePreferenceWidget(
                            onClick = { showBackupDialog = true },
                            title = { Text(stringResource(id = R.string.backup)) },
                            icon = { Icon(Icons.Default.Output, null) }
                        )

                        // 2. 恢复按钮
                        BasePreferenceWidget(
                            onClick = { showRestoreMenu = true },
                            title = { Text(stringResource(id = R.string.restore)) },
                            icon = { Icon(Icons.AutoMirrored.Filled.Input, null) }
                        )

                        // 3. WebDAV 设置入口
                        BasePreferenceWidget(
                            onClick = { showWebDavSettings = true },
                            title = { Text(stringResource(R.string.webdav_settings)) },
                            subTitle = { Text(if (AppConfig.webDavUrl.value.isBlank()) stringResource(R.string.not_configured) else AppConfig.webDavUrl.value) },
                            icon = { Icon(Icons.Default.Settings, null) }
                        )
                    }
                }
            }
        }
        restoreFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        restoreFromIntent(intent)
    }

    // 👇👇👇 修正：这里的参数依旧接收系统传来的 Intent 👇👇👇
    private fun restoreFromIntent(intent: Intent?) {
        intent?.data?.let { uri ->
            showFromFileRestoreDialog.value = uri.readBytes(this)
        }
        intent?.data = null // 处理完后清空 data 防止重复触发
    }

    @Composable
    fun WebDavSettingsDialog(onDismissRequest: () -> Unit, vm: BackupRestoreViewModel) {
        var url by remember { mutableStateOf(AppConfig.webDavUrl.value) }
        var user by remember { mutableStateOf(AppConfig.webDavUser.value) }
        var pass by remember { mutableStateOf(AppConfig.webDavPass.value) }
        var path by remember { mutableStateOf(AppConfig.webDavPath.value) }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val successStr = stringResource(R.string.connection_success)

        AppDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(stringResource(R.string.webdav_settings)) },
            content = {
                Column {
                    OutlinedTextField(
                        value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.server_address) + " (http(s)://...)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                    OutlinedTextField(
                        value = user, onValueChange = { user = it }, label = { Text(stringResource(R.string.account)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it }, label = { Text(stringResource(R.string.password)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                    OutlinedTextField(
                        value = path, onValueChange = { path = it }, label = { Text(stringResource(R.string.backup_folder)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }
            },
            buttons = {
                TextButton(onClick = {
                    AppConfig.webDavUrl.value = url
                    AppConfig.webDavUser.value = user
                    AppConfig.webDavPass.value = pass
                    AppConfig.webDavPath.value = path

                    scope.launch {
                        runCatching {
                            vm.testWebDav()
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, successStr, Toast.LENGTH_SHORT).show()
                                onDismissRequest()
                            }
                        }.onFailure {
                            context.displayErrorDialog(it)
                        }
                    }
                }) { Text(stringResource(R.string.save_and_test)) }
                TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    @Composable
    fun WebDavListDialog(onDismissRequest: () -> Unit, vm: BackupRestoreViewModel, onFileSelected: (ByteArray) -> Unit) {
        var list by remember { mutableStateOf<List<DavResource>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        // 加载列表
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching {
                list = vm.getWebDavBackupFiles()
            }.onFailure {
                context.displayErrorDialog(it)
                onDismissRequest()
            }
            isLoading = false
        }

        if (isLoading) {
            LoadingDialog(onDismissRequest = onDismissRequest)
        } else {
            AlertDialog(
                onDismissRequest = onDismissRequest,
                title = { Text(stringResource(R.string.select_cloud_backup)) },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn {
                        if (list.isEmpty()) {
                            item { Text(stringResource(R.string.empty_folder)) }
                        }
                        items(list.size) { index ->
                            val item = list[index]
                            ListItem(
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        isLoading = true
                                        runCatching {
                                            val bytes = vm.downloadFromWebDav(item.name)
                                            onFileSelected(bytes)
                                            onDismissRequest()
                                        }.onFailure {
                                            context.displayErrorDialog(it)
                                        }
                                        isLoading = false
                                    }
                                },
                                headlineContent = { Text(item.name) },
                                supportingContent = { Text(com.github.jing332.common.utils.FileUtils.formatFileSize(item.contentLength)) },
                                leadingContent = { Icon(Icons.Default.Cloud, null) }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }
}
