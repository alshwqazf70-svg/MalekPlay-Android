@Composable
fun SendFileScreen(
    onBack: () -> Unit,
    viewModel: UltraShareViewModel = hiltViewModel()
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("فيديو", "موسيقى", "صور")

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }
        // هنا يمكنك استخدام LazyVerticalGrid لعرض الملفات بناءً على التبويب
        // (سيتم ربطها بـ MediaStore لاحقاً)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("قائمة ${tabs[selectedTab]} (قيد التطوير)")
        }
    }
}
