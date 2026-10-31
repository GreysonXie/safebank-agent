package cn.safebank.app.ui.screens.users

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.safebank.app.AppContainer
import cn.safebank.app.data.model.AccountVo
import cn.safebank.app.data.model.ManagedUserRequest
import cn.safebank.app.data.model.ManagedUserVo
import cn.safebank.app.data.model.UserProfileRequest
import cn.safebank.app.data.model.UserProfileVo
import cn.safebank.app.data.util.Formatters
import cn.safebank.app.ui.components.BannerTone
import cn.safebank.app.ui.components.FormField
import cn.safebank.app.ui.components.InfoBanner
import cn.safebank.app.ui.components.LoadingRow
import cn.safebank.app.ui.components.SecondaryButton
import cn.safebank.app.ui.components.SectionCard
import cn.safebank.app.ui.navigation.AppNavigator
import cn.safebank.app.ui.navigation.DetailTopBar
import kotlinx.coroutines.launch

/**
 * 用户与联系人（t020）：
 * - `GET /api/users` 联系人/账户列表
 * - `GET/POST /api/users/profile` 个人资料
 * - `POST /api/users`、`POST /api/users/{id}` 新增/更新联系人
 */
@Composable
fun UsersRoute(navigator: AppNavigator, container: AppContainer) {
    DetailTopBar(navigator, "用户与联系人")
    val scope = rememberCoroutineScope()
    val repo = container.repository

    var users by remember { mutableStateOf<List<ManagedUserVo>>(emptyList()) }
    var profile by remember { mutableStateOf<UserProfileVo?>(null) }
    var accounts by remember { mutableStateOf<List<AccountVo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 个人资料表单
    var profileName by remember { mutableStateOf("") }
    var profilePhone by remember { mutableStateOf("") }
    var profileGender by remember { mutableStateOf("UNKNOWN") }

    // 联系人表单
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("UNKNOWN") }
    var note by remember { mutableStateOf("") }
    var savingsTarget by remember { mutableStateOf(false) }
    var accountId by remember { mutableStateOf<String?>(null) }
    var initialBalanceYuan by remember { mutableStateOf("0") }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        loading = true
        error = null
        scope.launch {
            repo.ensureSession(false)
            repo.users().onSuccess { users = it }.onFailure { error = friendly(it) }
            repo.userProfile().onSuccess {
                profile = it
                profileName = it.name
                profilePhone = it.phone
                profileGender = it.gender.ifBlank { "UNKNOWN" }
            }
            repo.accounts().onSuccess { accounts = it }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun saveProfile() {
        if (profileName.isBlank()) { error = "请填写姓名。"; return }
        busy = true
        error = null
        scope.launch {
            repo.saveUserProfile(UserProfileRequest(name = profileName.trim(), phone = profilePhone.trim(), gender = profileGender))
                .onSuccess { profile = it; notice = "个人资料已保存。" }
                .onFailure { error = friendly(it) }
            busy = false
        }
    }

    fun saveContact() {
        if (name.isBlank()) { error = "请填写联系人姓名。"; return }
        val balanceMinor = Formatters.parseAmountToMinor(initialBalanceYuan) ?: run {
            error = "初始余额应为数字（元）。"
            return
        }
        busy = true
        error = null
        val request = ManagedUserRequest(
            name = name.trim(),
            phone = phone.trim(),
            gender = gender,
            note = note.trim(),
            savingsTarget = savingsTarget,
            accountId = accountId,
            initialBalanceMinor = balanceMinor,
        )
        scope.launch {
            val result = if (editingId == null) repo.createUser(request) else repo.updateUser(editingId!!, request)
            result.onSuccess {
                notice = if (editingId == null) "联系人已创建。" else "联系人已更新。"
                editingId = null
                name = ""; phone = ""; note = ""; savingsTarget = false; accountId = null; initialBalanceYuan = "0"
                reload()
            }.onFailure { error = friendly(it) }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) LoadingRow("正在加载用户与联系人…")
        error?.let { InfoBanner(text = it, tone = BannerTone.Error) }
        notice?.let { InfoBanner(text = it, tone = BannerTone.Success) }

        SectionCard(title = "个人资料", subtitle = "仅用于本地演示；手机号留空或 11 位数字。") {
            FormField(label = "姓名", value = profileName, onValueChange = { profileName = it })
            Spacer(Modifier.height(6.dp))
            FormField(label = "手机号（可选）", value = profilePhone, onValueChange = { profilePhone = it.filter(Char::isDigit).take(11) })
            Spacer(Modifier.height(6.dp))
            GenderChips(selected = profileGender, onSelect = { profileGender = it })
            Spacer(Modifier.height(8.dp))
            SecondaryButton(text = "保存个人资料", onClick = { saveProfile() }, enabled = !busy)
            profile?.let {
                Text("当前：${it.name} · ${it.phone.ifBlank { "未填手机" }} · ${genderLabel(it.gender)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionCard(title = if (editingId == null) "新增联系人" else "编辑联系人") {
            FormField(label = "姓名", value = name, onValueChange = { name = it })
            Spacer(Modifier.height(6.dp))
            FormField(label = "手机号（可选）", value = phone, onValueChange = { phone = it.filter(Char::isDigit).take(11) })
            Spacer(Modifier.height(6.dp))
            GenderChips(selected = gender, onSelect = { gender = it })
            Spacer(Modifier.height(6.dp))
            FormField(label = "备注", value = note, onValueChange = { note = it })
            Spacer(Modifier.height(6.dp))
            Text("归属账户（可选）", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { accountId = null }, label = { Text("不绑定") })
                accounts.forEach { acc ->
                    AssistChip(onClick = { accountId = acc.id }, label = { Text(acc.label) })
                }
            }
            Spacer(Modifier.height(6.dp))
            FormField(label = "初始余额（元）", value = initialBalanceYuan, onValueChange = { initialBalanceYuan = it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = savingsTarget, onCheckedChange = { savingsTarget = it })
                Text("作为储蓄目标联系人", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            SecondaryButton(
                text = if (editingId == null) "创建联系人" else "保存修改",
                onClick = { saveContact() },
                enabled = !busy,
            )
            if (editingId != null) {
                Spacer(Modifier.height(4.dp))
                SecondaryButton(text = "取消编辑", onClick = {
                    editingId = null
                    name = ""; phone = ""; note = ""; savingsTarget = false; accountId = null; initialBalanceYuan = "0"
                })
            }
        }

        SectionCard(title = "联系人列表", subtitle = "共 ${users.size} 条") {
            if (users.isEmpty() && !loading) {
                Text("暂无联系人。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            users.forEach { u ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text("${u.name}${if (u.ownAccount) "（本人）" else ""}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${u.phone.ifBlank { "无手机号" }} · ${genderLabel(u.gender)} · ${u.note.ifBlank { "无备注" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (u.accountLabel.isNotBlank()) {
                        Text(
                            "账户 ${u.accountLabel} · 余额 ${Formatters.money(u.balanceMinor)}" + if (u.savingsTarget) " · 储蓄目标" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!u.ownAccount) {
                        SecondaryButton(text = "编辑", onClick = {
                            editingId = u.id
                            name = u.name
                            phone = u.phone
                            gender = u.gender.ifBlank { "UNKNOWN" }
                            note = u.note
                            savingsTarget = u.savingsTarget
                            accountId = u.accountId.ifBlank { null }
                            initialBalanceYuan = "0"
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun GenderChips(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("UNKNOWN" to "未说明", "MALE" to "男", "FEMALE" to "女", "UNDISCLOSED" to "不透露").forEach { (value, label) ->
            AssistChip(onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
    Text("当前：${genderLabel(selected)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun genderLabel(gender: String): String = when (gender) {
    "MALE" -> "男"
    "FEMALE" -> "女"
    "UNDISCLOSED" -> "不透露"
    else -> "未说明"
}

private fun friendly(t: Throwable): String {
    val code = (t as? cn.safebank.app.data.network.ApiException)?.code
    return buildString {
        append(t.message ?: "请求失败")
        if (!code.isNullOrBlank()) append("（").append(code).append("）")
    }
}
