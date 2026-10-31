package com.hisabkhata.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hisabkhata.R
import com.hisabkhata.ui.cash.CashBookScreen
import com.hisabkhata.ui.cash.CashEntryEditorScreen
import com.hisabkhata.ui.backup.BackupScreen
import com.hisabkhata.ui.settings.SettingsScreen
import com.hisabkhata.ui.transfer.TransferHomeScreen
import com.hisabkhata.ui.transfer.ExportScreen
import com.hisabkhata.ui.transfer.ImportScreen
import com.hisabkhata.ui.loan.PeopleScreen
import com.hisabkhata.ui.loan.PersonEditorScreen
import com.hisabkhata.ui.loan.PersonLedgerScreen
import com.hisabkhata.ui.loan.LoanEntryEditorScreen
import kotlinx.coroutines.launch

private object Routes {
    const val CASH = "cash"
    const val LOAN = "loan"
    const val SETTINGS = "settings"
    const val NEW_CASH = "cash/new"
    const val EDIT_CASH = "cash/edit/{entryId}"
    const val NEW_PERSON = "person-create"
    const val PERSON_LEDGER = "person/{personId}"
    const val EDIT_PERSON = "person/{personId}/edit"
    const val NEW_LOAN_ENTRY = "person/{personId}/entry-create"
    const val EDIT_LOAN_ENTRY = "person/{personId}/entry/{entryId}/edit"
    const val TRANSFER = "transfer"
    const val EXPORT = "export"
    const val EXPORT_PERSON = "export/person/{personId}"
    const val IMPORT = "import"
    const val BACKUP = "backup"
    fun editCash(id: Long) = "cash/edit/$id"
    fun personLedger(id: Long) = "person/$id"
    fun editPerson(id: Long) = "person/$id/edit"
    fun newLoanEntry(personId: Long) = "person/$personId/entry-create"
    fun editLoanEntry(personId: Long, entryId: Long) = "person/$personId/entry/$entryId/edit"
    fun exportPerson(personId: Long) = "export/person/$personId"
}

private data class TopLevelDestination(val route: String, val label: Int, val icon: ImageVector)

@Composable
fun HisabKhataRoot() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val showDeleted: (Int, Boolean) -> Unit = { message, pop ->
        if (pop) navController.popBackStack()
        scope.launch { snackbarHostState.showSnackbar(context.getString(message)) }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val destinations = listOf(
        TopLevelDestination(Routes.CASH, R.string.nav_cash_book, Icons.AutoMirrored.Filled.List),
        TopLevelDestination(Routes.LOAN, R.string.nav_loan_ledger, Icons.Filled.Person),
        TopLevelDestination(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings)
    )
    val showBottomBar = destinations.any { it.route == currentRoute }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.label)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CASH,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding)
        ) {
            composable(Routes.CASH) {
                CashBookScreen(
                    onAdd = { navController.navigate(Routes.NEW_CASH) },
                    onEdit = { navController.navigate(Routes.editCash(it)) },
                    onOpenLinkedPerson = { navController.navigate(Routes.personLedger(it)) },
                    onExport = { navController.navigate(Routes.EXPORT) }
                )
            }
            composable(Routes.NEW_CASH) {
                CashEntryEditorScreen(onBack = { navController.popBackStack() }, onDeleted = { showDeleted(R.string.entry_deleted, true) })
            }
            composable(
                Routes.EDIT_CASH,
                arguments = listOf(navArgument("entryId") { type = NavType.LongType })
            ) {
                CashEntryEditorScreen(onBack = { navController.popBackStack() }, onDeleted = { showDeleted(R.string.entry_deleted, true) })
            }
            composable(Routes.LOAN) {
                PeopleScreen(
                    onAddPerson = { navController.navigate(Routes.NEW_PERSON) },
                    onOpenPerson = { navController.navigate(Routes.personLedger(it)) }
                )
            }
            composable(Routes.NEW_PERSON) {
                PersonEditorScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.PERSON_LEDGER, arguments = listOf(navArgument("personId") { type = NavType.LongType })) {
                PersonLedgerScreen(
                    onBack = { navController.popBackStack() },
                    onDeleted = { showDeleted(R.string.person_deleted, true) },
                    onEditPerson = { navController.navigate(Routes.editPerson(it)) },
                    onAddEntry = { navController.navigate(Routes.newLoanEntry(it)) },
                    onEditEntry = { personId, entryId -> navController.navigate(Routes.editLoanEntry(personId, entryId)) },
                    onExportPerson = { navController.navigate(Routes.exportPerson(it)) }
                )
            }
            composable(Routes.EDIT_PERSON, arguments = listOf(navArgument("personId") { type = NavType.LongType })) {
                PersonEditorScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.NEW_LOAN_ENTRY, arguments = listOf(navArgument("personId") { type = NavType.LongType })) {
                LoanEntryEditorScreen(onBack = { navController.popBackStack() }, onDeleted = { showDeleted(R.string.entry_deleted, true) })
            }
            composable(
                Routes.EDIT_LOAN_ENTRY,
                arguments = listOf(navArgument("personId") { type = NavType.LongType }, navArgument("entryId") { type = NavType.LongType })
            ) {
                LoanEntryEditorScreen(onBack = { navController.popBackStack() }, onDeleted = { showDeleted(R.string.entry_deleted, true) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onTransfer = { navController.navigate(Routes.TRANSFER) }, onBackup = { navController.navigate(Routes.BACKUP) })
            }
            composable(Routes.TRANSFER) {
                TransferHomeScreen(onBack = { navController.popBackStack() }, onExport = { navController.navigate(Routes.EXPORT) }, onImport = { navController.navigate(Routes.IMPORT) })
            }
            composable(Routes.EXPORT) { ExportScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.EXPORT_PERSON, arguments = listOf(navArgument("personId") { type = NavType.LongType })) {
                ExportScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.IMPORT) { ImportScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.BACKUP) { BackupScreen(onBack = { navController.popBackStack() }, onBackupDeleted = { showDeleted(R.string.backup_deleted, false) }) }
        }
    }
}
