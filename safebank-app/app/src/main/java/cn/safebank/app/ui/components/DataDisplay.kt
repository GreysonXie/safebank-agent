package cn.safebank.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.safebank.app.data.util.Formatters

/**
 * 数据展示类组件（ui-design-spec §1.5、§3.5、§6.2、§6.4）。
 *
 * 金额一律是**整数分（Long）**，由 [Formatters] 转 `¥1,234.00`；
 * 展示层禁止用 Double 参与金额计算（§6.8）。
 */

/**
 * 金额文本。
 *
 * @param minor 整数分
 * @param signed 是否带正负号（收入 +、支出 -）
 * @param color 不留空时跟随传入颜色（收入/支出/失败状态常用 `SafeBankColors`）
 * @param style 不留空时跟随 MaterialTheme.typography.titleSmall
 */
@Composable
fun MoneyText(
    minor: Long,
    signed: Boolean = false,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle? = null,
) {
    val baseStyle = style ?: MaterialTheme.typography.titleSmall
    val resolvedStyle = baseStyle.merge(TextStyle(fontFeatureSettings = "tnum"))
    val resolvedColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    Text(
        text = if (signed) Formatters.moneySigned(minor) else Formatters.money(minor),
        modifier = modifier,
        color = resolvedColor,
        style = resolvedStyle,
    )
}

/**
 * 两列参数行（对应 Web 的 `dl` 参数表，§3.6 第 4 条）：左侧 90dp 标签、右侧自适应值。
 * 值允许换行（长编号/长文案不截断，§7.6）。
 */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(90.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 表单字段容器：12sp 标签 + 7dp 间距 + 控件（§3.9）。
 * 字段之间的 14dp 间距由页面自己控制（`Column(verticalArrangement = spacedBy(...))`）。
 */
@Composable
fun LabeledField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 7.dp),
        )
        content()
    }
}

/**
 * 空态（§3.5）：居中、13sp、行高 1.9、内边距 32×14。
 *
 * @param title 第一行文案（13sp）
 * @param hint 第二行小字（12sp），可空
 */
@Composable
fun EmptyState(
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (hint != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 加载行：18dp 进度圈 + 13sp 文案（列表/面板内使用）。 */
@Composable
fun LoadingRow(
    text: String = "加载中…",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
