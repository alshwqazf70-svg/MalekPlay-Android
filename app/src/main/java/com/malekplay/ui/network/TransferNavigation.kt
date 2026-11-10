@Composable
fun TransferNavigation(
    navController: NavHostController,
    viewModel: UltraShareViewModel = hiltViewModel()
) {
    NavHost(navController, startDestination = "transfer_home") {
        composable("transfer_home") {
            TransferHomeScreen(
                onNavigateToSend = { navController.navigate("transfer_send") },
                onNavigateToReceive = { navController.navigate("transfer_receive") },
                onNavigateToHistory = { navController.navigate("transfer_history") },
                onNavigateToSettings = { navController.navigate("transfer_settings") },
                viewModel = viewModel
            )
        }
        composable("transfer_send") {
            SendFileScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }
        composable("transfer_receive") {
            ReceiveScreen(
                onBack = { navController.popBackStack() },
                viewModel = viewModel
            )
        }
        // ... شاشات السجل والإعدادات
    }
}
