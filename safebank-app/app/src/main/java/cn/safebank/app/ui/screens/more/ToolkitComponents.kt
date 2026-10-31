package cn.safebank.app.ui.screens.more

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.safebank.app.data.util.Formatters
import cn.safebank.app.ui.navigation.AppNavigator
import cn.safebank.app.ui.navigation.DetailTopBar
import kotlinx.coroutines.delay

/**
 * 「工具与审计」5 个页面（更多 / 转账 / 代扣 / 审计 / 设置）共用的轻量组件。
 *
 * 公共组件库 `ui/components` 由 UI 基础负责人并行开发，本文件保持**自包含**，
 * 只依赖 Material3 与主题色槽位，因此不阻塞其他负责人，也不与其产生写作用域冲突。
 * 视觉令牌按 docs/ui-design-spec §3/§6：面板 10dp 圆角 + 1dp 描边、状态徽章 4dp、
 * 按钮最小高 44dp、正文 13sp、说明 12sp。状态色来自 §6.1 的业务扩展色。
 */

/** 语义色标签，用于状态徽章 / 提示条。 */
internal enum class Tone { Neutral, Info, Success, Warning, Danger }

internal data class ToneColors(val bg: Color, val fg: Color)

private val ToneNeutral = ToneColors(Color(0xFFF1F5F9), Color(0xFF526173))
private val ToneInfo = ToneColors(Color(0xFFEDF2FF), Color(0xFF1A4F99))
private val ToneSuccess = ToneColors(Color(0xFFE5F5EE), Color(0xFF176345))
private val ToneWarning = ToneColors(Color(0xFFFFF3D6), Color(0xFF785000))
private val ToneDanger = ToneColors(Color(0xFFFFF5F5), Color(0xFF8E2635))

internal fun toneColors(tone: Tone): ToneColors = when (tone) {
    Tone.Neutral -> ToneNeutral
    Tone.Info -> ToneInfo
    Tone.Success -> ToneSuccess
    Tone.Warning -> ToneWarning
    Tone.Danger -> ToneDanger
}

/** 页面标题（tab 页使用；二级页由 [DetailPageScaffold] 的标题栏负责）。 */
@Composable
internal fun PageTitle(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 白色面板：1dp 描边 + 10dp 圆角（对应 ui-spec 的 `.panel`）。 */
@Composable
internal fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (title != null) 6.dp else 0.dp),
                )
            }
            if (title != null || subtitle != null) {
                Spacer(modifier = Modifier.height(12.dp))
            }
            content()
        }
    }
}

/** 业务状态徽章（4dp 圆角、12sp）。 */
@Composable
internal fun StatusBadge(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val colors = toneColors(tone)
    Surface(modifier = modifier, shape = RoundedCornerShape(4.dp), color = colors.bg) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** 行内提示条（错误 / 说明 / 成功）。 */
@Composable
internal fun InfoBanner(text: String, tone: Tone = Tone.Info, modifier: Modifier = Modifier) {
    val colors = toneColors(tone)
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = colors.bg) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.fg,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** 空态：居中、13sp、#526173。 */
@Composable
internal fun EmptyStateBlock(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 加载行。 */
@Composable
internal fun LoadingRow(text: String = "正在加载…", modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

/** 参数两列（标签 96dp / 值自适应）。 */
@Composable
internal fun KeyValueRow(label: String, value: String, valueColor: Color? = null, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 统一表单输入（label 12sp、控件 14sp、8dp 圆角由 OutlinedTextField 提供）。 */
@Composable
internal fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    hint: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val placeholderText = placeholder
        val placeholderContent: (@Composable () -> Unit)? = if (placeholderText != null) {
            { Text(text = placeholderText, style = MaterialTheme.typography.bodyMedium) }
        } else {
            null
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            enabled = enabled,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4,
            isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            placeholder = placeholderContent,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 下拉选择（用 OutlinedButton + DropdownMenu，避免实验性下拉 API 的版本差异）。 */
@Composable
internal fun DropdownField(
    label: String,
    options: List<Pair<String, String>>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "请选择",
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedKey }?.second
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            OutlinedButton(
                onClick = { if (enabled) expanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = selectedLabel ?: placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedLabel != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "▾",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(text = option.second) },
                        onClick = {
                            expanded = false
                            onSelect(option.first)
                        },
                    )
                }
            }
        }
    }
}

/** 主按钮（最小高 44dp；loading 时禁用并显示「处理中…」）。 */
@Composable
internal fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled && !loading,
    ) {
        Text(text = if (loading) "处理中…" else text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 次按钮。 */
@Composable
internal fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 44.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(text = if (loading) "处理中…" else text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 可选中行（收款人 / 代扣 / 账户候选）。 */
@Composable
internal fun SelectableRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (selected) {
                Text(
                    text = "已选",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** 整行可点的确认勾选。 */
@Composable
internal fun ToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onCheckedChange(it) },
            enabled = enabled,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** 底部提示条（对应 Web 的 toast：4.2s 自动消失）。 */
@Composable
internal fun PageToast(message: String?, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(4200L)
            onDismiss()
        }
    }
    if (message != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** 折叠区（不使用 animation 库，直接条件渲染）。 */
@Composable
internal fun CollapsibleSection(
    title: String,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.heightIn(min = 44.dp),
        ) {
            Text(
                text = (if (expanded) "▾ " else "▸ ") + title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            content()
        }
    }
}

/** 二级页骨架：顶部返回栏 + 可滚动内容区；页面根节点是 Box 以便叠加 toast。 */
@Composable
internal fun DetailPageScaffold(
    navigator: AppNavigator,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(navigator = navigator, title = title)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            content = content,
        )
    }
}

/** 金额文本（用 Formatters.money，等宽语义由主题字体处理）。 */
@Composable
internal fun MoneyText(
    minor: Long?,
    signed: Boolean = false,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    modifier: Modifier = Modifier,
) {
    val text = when {
        minor == null -> "—"
        signed -> Formatters.moneySigned(minor)
        else -> Formatters.money(minor)
    }
    Text(text = text, style = style, color = MaterialTheme.colorScheme.onSurface, modifier = modifier)
}

/** 水平分隔线。 */
@Composable
internal fun ToolkitDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 8.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 顶部一行小字的操作区（例如「刷新」）。 */
@Composable
internal fun InlineActionRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}
