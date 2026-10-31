package cn.safebank.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.safebank.app.AppContainer
import cn.safebank.app.ui.screens.actions.ActionsRoute
import cn.safebank.app.ui.screens.audit.AuditRoute
import cn.safebank.app.ui.screens.cards.CardsRoute
import cn.safebank.app.ui.screens.chat.ChatRoute
import cn.safebank.app.ui.screens.finance.FinanceRoute
import cn.safebank.app.ui.screens.mandates.MandatesRoute
import cn.safebank.app.ui.screens.more.MoreRoute
import cn.safebank.app.ui.screens.savings.SavingsRoute
import cn.safebank.app.ui.screens.savings.WorkflowDetailRoute
import cn.safebank.app.ui.screens.settings.SettingsRoute
import cn.safebank.app.ui.screens.statement.StatementRoute
import cn.safebank.app.ui.screens.statement_extras.StatementExtrasRoute
import cn.safebank.app.ui.screens.tools.ToolsRoute
import cn.safebank.app.ui.screens.users.UsersRoute
import cn.safebank.app.ui.theme.SafeBankAppTheme

/**
 * 应用外壳：主题 + 底部导航 + 返回栈路由。
 * 页面实现位于 ui/screens 下的各个包，各自负责内容与二级页标题栏。
 */
@Composable
fun SafeBankApp(container: AppContainer) {
    SafeBankAppTheme {
        val navigator = remember { AppNavigator() }
        BackHandler(enabled = navigator.canGoBack) { navigator.pop() }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (navigator.current.isTab) {
                    AppBottomBar(navigator)
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .statusBarsPadding(),
            ) {
                ScreenHost(navigator = navigator, container = container)
            }
        }
    }
}

@Composable
private fun AppBottomBar(navigator: AppNavigator) {
    NavigationBar {
        AppDestination.tabs.forEach { tab ->
            NavigationBarItem(
                selected = navigator.current == tab,
                onClick = { navigator.selectTab(tab) },
                icon = { Icon(tabIcon(tab), contentDescription = tab.title) },
                label = { Text(tab.title) },
            )
        }
    }
}

private fun tabIcon(destination: AppDestination): ImageVector = when (destination) {
    AppDestination.Chat -> Icons.Filled.Email
    AppDestination.Statement -> Icons.Filled.List
    AppDestination.Mandates -> Icons.Filled.Lock
    AppDestination.Savings -> Icons.Filled.DateRange
    AppDestination.More -> Icons.Filled.MoreVert
    else -> Icons.Filled.MoreVert
}

@Composable
private fun ScreenHost(navigator: AppNavigator, container: AppContainer) {
    when (val destination = navigator.current) {
        AppDestination.Chat -> ChatRoute(navigator, container)
        AppDestination.Statement -> StatementRoute(navigator, container)
        AppDestination.Mandates -> MandatesRoute(navigator, container)
        AppDestination.Savings -> SavingsRoute(navigator, container)
        AppDestination.More -> MoreRoute(navigator, container)
        AppDestination.Transfer -> ToolsRoute(navigator, container)
        AppDestination.Actions -> ActionsRoute(navigator, container)
        AppDestination.Audit -> AuditRoute(navigator, container)
        AppDestination.Settings -> SettingsRoute(navigator, container)
        AppDestination.Finance -> FinanceRoute(navigator, container)
        AppDestination.Cards -> CardsRoute(navigator, container)
        AppDestination.StatementExtras -> StatementExtrasRoute(navigator, container)
        AppDestination.Users -> UsersRoute(navigator, container)
        is AppDestination.WorkflowDetail -> WorkflowDetailRoute(navigator, container, destination.workflowId)
    }
}

/**
 * 二级页面的通用标题栏（各页面可选使用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailTopBar(navigator: AppNavigator, title: String) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (navigator.canGoBack) {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        },
    )
}

/**
 * 占位页：脚手架阶段使用。负责页面的同学把它替换成真实实现。
 */
@Composable
fun PlaceholderScreen(navigator: AppNavigator, title: String, hint: String = "该页面正在实现中") {
    Column(modifier = Modifier.fillMaxSize()) {
        DetailTopBar(navigator, title)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "$title\n$hint",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}
