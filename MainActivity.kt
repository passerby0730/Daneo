package com.example.wordapp

import android.content.Intent
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.min
import kotlin.random.Random

data class WordPair(val eng: String, val kor: String, val id: Long = System.nanoTime())

private enum class Screen { WORDS, QUIZ }
private enum class QuizType { MC4, MATCHING }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Surface(Modifier.fillMaxSize()) { RootApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val wordList = remember { mutableStateListOf<WordPair>() }
    var folderName by remember { mutableStateOf("EBS 단어장") }
    var fileName by remember { mutableStateOf("Day 1") }
    val baseDir: File = remember { context.getExternalFilesDir(null) ?: context.filesDir }

    var inputText by remember { mutableStateOf("") }
    var editIndex by remember { mutableIntStateOf(-1) }

    var currentScreen by remember { mutableStateOf(Screen.WORDS) }

    // SAF: 불러오기
    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            context.contentResolver.openInputStream(uri)?.use { input ->
                val reader = BufferedReader(InputStreamReader(input))
                val loaded = mutableListOf<Pair<String, String>>()
                reader.forEachLine { line -> parseLineToPair(line)?.let(loaded::add) }
                wordList.clear()
                wordList.addAll(loaded.map { (e, k) -> WordPair(e, k) })
                editIndex = -1
                inputText = ""
            }
            getDisplayName(context, uri)?.let { name ->
                fileName = name.removeSuffix(".txt").removeSuffix(".TXT")
            }
            guessFolderNameFromUri(uri)?.let { guessed ->
                if (guessed.isNotBlank()) folderName = guessed
            }
        }
    }

    // SAF: 저장
    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                wordList.forEach { pair ->
                    output.write("${pair.eng} = ${pair.kor}\n".toByteArray())
                }
            }
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var showOverflow by remember { mutableStateOf(false) } // ⋮ 메뉴(단어장 화면에서만)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "카테고리",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                NavigationDrawerItem(
                    label = { Text("단어장") },
                    selected = currentScreen == Screen.WORDS,
                    onClick = {
                        currentScreen = Screen.WORDS
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                NavigationDrawerItem(
                    label = { Text("퀴즈") },
                    selected = currentScreen == Screen.QUIZ,
                    onClick = {
                        currentScreen = Screen.QUIZ
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch {
                                if (drawerState.isClosed) drawerState.open() else drawerState.close()
                            }
                        }) { Text("≡", style = MaterialTheme.typography.titleLarge) }
                    },
                    title = {
                        Text(
                            if (currentScreen == Screen.WORDS) "단어 V1.11" else "퀴즈",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        if (currentScreen == Screen.WORDS) {
                            AppOverflowMenu(
                                expanded = showOverflow,
                                onExpandedChange = { showOverflow = it },
                                onPickToLoad = {
                                    openDocLauncher.launch(arrayOf("text/plain", "text/*"))
                                },
                                onPickToSave = {
                                    createDocLauncher.launch("$fileName.txt")
                                },
                                onLoadFromAppFolder = {
                                    val loaded = loadWordList(folderName, fileName, baseDir)
                                    wordList.clear()
                                    wordList.addAll(loaded.map { (e, k) -> WordPair(e, k) })
                                },
                                onSaveToAppFolder = {
                                    saveWordList(
                                        folderName,
                                        fileName,
                                        wordList.map { it.eng to it.kor },
                                        baseDir
                                    )
                                },
                                onClearAll = { wordList.clear() }
                            )
                        }
                    }
                )
            }
        ) { inner ->
            val baseModifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)

            when (currentScreen) {
                Screen.WORDS -> WordsScreen(
                    modifier = baseModifier,
                    wordList = wordList,
                    inputText = inputText,
                    onInputTextChange = { inputText = it },
                    editIndex = editIndex,
                    setEditIndex = { editIndex = it },
                    folderName = folderName,
                    onFolderNameChange = { folderName = it },
                    fileName = fileName,
                    onFileNameChange = { fileName = it },
                    baseDir = baseDir
                )
                Screen.QUIZ -> QuizHostScreen(
                    modifier = baseModifier,
                    wholeList = wordList,
                    fileName = fileName,
                    onFileNameChange = { fileName = it }
                )
            }
        }
    }
}

/* ▷ 세 점(⋮) 메뉴 – 컴포저블로 분리 (비-컴포저블 컨텍스트 호출 방지) */
@Composable
private fun AppOverflowMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPickToLoad: () -> Unit,
    onPickToSave: () -> Unit,
    onLoadFromAppFolder: () -> Unit,
    onSaveToAppFolder: () -> Unit,
    onClearAll: () -> Unit
) {
    IconButton(onClick = { onExpandedChange(true) }) {
        Text("⋮", style = MaterialTheme.typography.titleLarge)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
        DropdownMenuItem(text = { Text("파일 선택해 불러오기") }, onClick = {
            onExpandedChange(false); onPickToLoad()
        })
        DropdownMenuItem(text = { Text("파일 선택해 저장") }, onClick = {
            onExpandedChange(false); onPickToSave()
        })
        HorizontalDivider()
        DropdownMenuItem(text = { Text("앱 폴더에서 불러오기") }, onClick = {
            onExpandedChange(false); onLoadFromAppFolder()
        })
        DropdownMenuItem(text = { Text("앱 폴더에 저장") }, onClick = {
            onExpandedChange(false); onSaveToAppFolder()
        })
        HorizontalDivider()
        DropdownMenuItem(text = { Text("전체 비우기") }, onClick = {
            onExpandedChange(false); onClearAll()
        })
    }
}

/* -------------------- 단어장 화면 -------------------- */

@Composable
private fun WordsScreen(
    modifier: Modifier,
    wordList: MutableList<WordPair>,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    editIndex: Int,
    setEditIndex: (Int) -> Unit,
    folderName: String,
    onFolderNameChange: (String) -> Unit,
    fileName: String,
    onFileNameChange: (String) -> Unit,
    baseDir: File
) {
    val reorderState = rememberReorderableLazyListState(
        onMove = { from, to ->
            val fromIndex = from.index - 1
            val toIndex = to.index - 1

            if (fromIndex !in wordList.indices || toIndex !in 0..wordList.size) {
                return@rememberReorderableLazyListState
            }

            val item = wordList.removeAt(fromIndex)
            wordList.add(toIndex, item)

            if (editIndex == fromIndex) {
                setEditIndex(toIndex)
            } else {
                if (fromIndex < editIndex && toIndex >= editIndex) {
                    setEditIndex(editIndex - 1)
                } else if (fromIndex > editIndex && toIndex <= editIndex) {
                    setEditIndex(editIndex + 1)
                }
            }
        },
        canDragOver = { draggedOver, _ -> draggedOver.index > 0 }
    )

    LazyColumn(
        modifier = modifier,
        state = reorderState.listState,
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "settings-panel") { // Stable key for the non-reorderable header
            Column {
                Text("단어 추가", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputTextChange,
                    label = { Text("예: apple 사과 water 물") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        val pairs = parsePairsFromInline(inputText)
                        if (pairs.isNotEmpty()) {
                            if (editIndex >= 0) {
                                val (e, k) = pairs.first()
                                val old = wordList[editIndex]
                                wordList[editIndex] = old.copy(eng = e, kor = k)
                                setEditIndex(-1)
                            } else {
                                wordList.addAll(pairs.map { (e, k) -> WordPair(e, k) })
                            }
                            onInputTextChange("")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (editIndex >= 0) "수정하기" else "추가하기") }

                Spacer(Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text("저장/불러오기 (앱 폴더)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = folderName,
                    onValueChange = onFolderNameChange,
                    label = { Text("폴더 이름 (예: EBS 단어장)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = fileName,
                    onValueChange = onFileNameChange,
                    label = { Text("파일 이름 (확장자 자동)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))

                Text(
                    text = "앱 폴더 경로: " + File(File(baseDir, folderName), "${fileName}.txt").absolutePath,
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(18.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text(
                    "단어 리스트 (항목을 길게 눌러 끌어서 순서 변경)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        items(items = wordList, key = { it.id }) { item ->
            ReorderableItem(reorderState, key = item.id) { dragging ->
                val bg =
                    if (dragging) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .reorderable(reorderState)
                        .detectReorderAfterLongPress(reorderState),
                    colors = CardDefaults.cardColors(containerColor = bg)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${item.eng} = ${item.kor}", style = MaterialTheme.typography.bodyLarge)
                        Row {
                            TextButton(onClick = {
                                onInputTextChange("${item.eng} ${item.kor}")
                                setEditIndex(wordList.indexOfFirst { it.id == item.id })
                            }) { Text("수정") }
                            TextButton(onClick = {
                                val idx = wordList.indexOfFirst { it.id == item.id }
                                if (idx >= 0) {
                                    wordList.removeAt(idx)
                                    if (editIndex == idx) { setEditIndex(-1); onInputTextChange("") }
                                    else if (editIndex > idx) setEditIndex(editIndex - 1)
                                }
                            }) { Text("삭제") }
                        }
                    }
                }
            }
        }
    }
}

/* -------------------- 퀴즈 호스트 -------------------- */

@Composable
private fun SelectedFilesView(fileNames: List<String>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val itemsToShow = if (expanded || fileNames.size <= 5) fileNames else fileNames.take(5)

    Column(modifier = modifier) {
        Text("선택된 파일:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        itemsToShow.forEach {
            Text("- $it", style = MaterialTheme.typography.bodySmall)
        }
        if (fileNames.size > 5) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "접기" else "...${fileNames.size - 5}개 더 보기")
            }
        }
    }
}

@Composable
private fun QuizHostScreen(
    modifier: Modifier,
    wholeList: List<WordPair>,
    fileName: String,
    onFileNameChange: (String) -> Unit
) {
    var quizType by remember { mutableStateOf(QuizType.MC4) }
    var selectedFileNames by remember { mutableStateOf<List<String>>(emptyList()) }

    val quizPool = remember { mutableStateListOf<WordPair>() }
    var questionCount by remember { mutableIntStateOf(min(10, wholeList.size.coerceAtLeast(1))) }

    LaunchedEffect(wholeList) {
        if (quizPool.isEmpty() && wholeList.isNotEmpty()) {
            quizPool.addAll(wholeList)
            questionCount = min(10, quizPool.size)
            selectedFileNames = emptyList() // 현재 리스트 사용 시 파일 목록 초기화
        }
    }

    val context = LocalContext.current
    val openMultiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            val merged = mutableListOf<WordPair>()
            val names = mutableListOf<String>()
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) { }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val reader = BufferedReader(InputStreamReader(input))
                    reader.forEachLine { line ->
                        parseLineToPair(line)?.let { (e, k) -> merged.add(WordPair(e, k)) }
                    }
                }
                getDisplayName(context, uri)?.let { name -> names.add(name) }
            }
            quizPool.clear()
            quizPool.addAll(merged)
            questionCount = min(questionCount.coerceAtLeast(10), quizPool.size)
            selectedFileNames = names

            // 오답 노트 파일 이름을 첫 번째 파일 기준으로 설정
            uris.firstOrNull()?.let { firstUri ->
                getDisplayName(context, firstUri)?.let { name ->
                    onFileNameChange(name.removeSuffix(".txt").removeSuffix(".TXT"))
                }
            }
        }
    }

    var started by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        if (!started) {
            Text("퀴즈 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (quizType == QuizType.MC4) {
                    Button(
                        onClick = { quizType = QuizType.MC4 },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("4지선다")
                    }
                } else {
                    OutlinedButton(onClick = { quizType = QuizType.MC4 }) {
                        Text("4지선다")
                    }
                }
                if (quizType == QuizType.MATCHING) {
                    Button(
                        onClick = { quizType = QuizType.MATCHING },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("매칭 표")
                    }
                } else {
                    OutlinedButton(onClick = { quizType = QuizType.MATCHING }) {
                        Text("매칭 표")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            Text("퀴즈 범위", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    quizPool.clear()
                    quizPool.addAll(wholeList)
                    questionCount = min(questionCount.coerceAtLeast(10), quizPool.size)
                    selectedFileNames = emptyList() // 현재 리스트 사용 시 파일 목록 초기화
                }) { Text("현재 리스트 사용") }

                OutlinedButton(onClick = {
                    openMultiLauncher.launch(arrayOf("text/plain", "text/*"))
                }) { Text("파일에서 범위 선택") }
            }

            Spacer(Modifier.height(8.dp))
            Text("현재 범위: ${quizPool.size} 단어" + if (selectedFileNames.isNotEmpty()) " (파일)" else "")

            if (selectedFileNames.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SelectedFilesView(
                    fileNames = selectedFileNames,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            val maxCount = quizPool.size.coerceAtLeast(1)
            Text("출제 개수: $questionCount / ${quizPool.size}")
            Slider(
                value = questionCount.toFloat(),
                onValueChange = { v -> questionCount = v.toInt().coerceIn(1, maxCount) },
                valueRange = 1f..maxCount.toFloat(),
                steps = (maxCount - 2).coerceAtLeast(0)
            )

            Spacer(Modifier.height(12.dp))
            Button(onClick = { started = true }, enabled = quizPool.size >= 4) { Text("퀴즈 시작") }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
        }

        if (started) {
            val selected = remember(quizPool, questionCount) {
                quizPool.shuffled(Random(System.currentTimeMillis())).take(questionCount)
            }
            when (quizType) {
                QuizType.MC4 -> MC4Quiz(selected, quizPool, onExit = { started = false }, fileName = fileName)
                QuizType.MATCHING -> MatchingQuiz(selected, onExit = { started = false }, fileName = fileName)
            }
        } else {
            Text("퀴즈를 시작하려면 범위를 고르고 ‘퀴즈 시작’을 누르세요.")
        }
    }
}

/* -------------------- ① 4지선다 -------------------- */

@Composable
private fun MC4Quiz(
    questions: List<WordPair>,
    pool: List<WordPair>,
    onExit: () -> Unit,
    fileName: String
) {
    var index by remember { mutableIntStateOf(0) }
    var selectedIdx by remember { mutableIntStateOf(-1) }
    var locked by remember { mutableStateOf(false) }
    val wrongList = remember { mutableStateListOf<WordPair>() }

    val current = questions.getOrNull(index)
    val options: List<String> = remember(index, current, pool) {
        if (current == null) emptyList()
        else {
            val distractors = pool.filter { it.kor != current.kor }.shuffled().take(3).map { it.kor }
            (distractors + current.kor).shuffled()
        }
    }

    if (current == null) {
        val context = LocalContext.current
        val createDocLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    wrongList.forEach { pair ->
                        output.write("${pair.eng} = ${pair.kor}\n".toByteArray())
                    }
                }
            }
        }

        val correctCount = questions.size - wrongList.size
        val percent = if (questions.isEmpty()) 0 else (correctCount * 100 / questions.size)
        Text("결과", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        DonutProgress(percentage = percent)
        Spacer(Modifier.height(12.dp))
        Text("정답률: $percent%  ($correctCount / ${questions.size})")
        Spacer(Modifier.height(12.dp))
        if (wrongList.isEmpty()) Text("모두 정답입니다! 👍") else {
            Text("틀린 단어", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            wrongList.forEach { Text("- ${it.eng} = ${it.kor}") }
        }
        Spacer(Modifier.height(12.dp))
        if (wrongList.isNotEmpty()) {
            Button(
                onClick = {
                    val cal = java.util.Calendar.getInstance()
                    val year = cal.get(java.util.Calendar.YEAR).toString().substring(2)
                    val month = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
                    val day = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
                    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                    val minute = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
                    val dateTime = "${year}${month}${day}_${hour}${minute}"
                    val defaultFileName = "${fileName}_${dateTime}_오답.txt"
                    createDocLauncher.launch(defaultFileName)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("틀린 단어 파일로 내보내기")
            }
            Spacer(Modifier.height(6.dp))
        }
        OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("끝내기") }
        return
    }

    Text("문제 ${index + 1} / ${questions.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
    Text("영어: ${current.eng}", style = MaterialTheme.typography.bodyLarge)
    if (locked) {
        val isCorrect = options.getOrNull(selectedIdx) == current.kor
        val color = if (isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        val msg = if (isCorrect) "정답!" else "오답!"
        Spacer(Modifier.height(6.dp))
        Text(msg, color = color)
    }
    Spacer(Modifier.height(8.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, text ->
            val isCorrect = text == current.kor
            val isSelected = selectedIdx == i

            // 버튼이 비활성화되었을 때의 색상을 결정합니다.
            val (disabledBg, disabledTxt) = when {
                isCorrect -> Color(0xFFEADDFF) to MaterialTheme.colorScheme.onSurface // 정답은 보라색
                isSelected -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer // 선택한 오답은 붉은색
                else -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface // 나머지는 기본색
            }

            Button(
                onClick = {
                    if (!locked) {
                        selectedIdx = i
                        locked = true
                        if (!isCorrect) wrongList.add(current)
                    }
                },
                enabled = !locked, // 선택 후 버튼 비활성화
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    // 비활성화 상태일 때의 색상 지정
                    disabledContainerColor = disabledBg,
                    disabledContentColor = disabledTxt
                )
            ) {
                Text(text)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { 
                wrongList.add(current)
                selectedIdx = -1; locked = false; index += 1 
            },
            modifier = Modifier.weight(1f)
        ) { Text("스킵") }
        Button(
            onClick = { selectedIdx = -1; locked = false; index += 1 },
            modifier = Modifier.weight(1f)
        ) { Text("다음") }
    }
}

/* 원형(도넛) 차트 */
@Composable
private fun DonutProgress(percentage: Int, size: Int = 140, stroke: Int = 18) {
    val sweep = (percentage.coerceIn(0, 100) / 100f) * 360f

    // Canvas에 들어가기 전에 컴포저블 컨텍스트에서 색상을 미리 읽어옵니다.
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = MaterialTheme.colorScheme.primary

    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(
                color = trackColor, // 미리 읽어온 변수를 사용합니다.
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = progressColor, // 미리 읽어온 변수를 사용합니다.
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(width = stroke.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Text("$percentage%")
    }
}

/* -------------------- ② 매칭 표 -------------------- */

@Composable
private fun MatchingQuiz(
    questions: List<WordPair>,
    onExit: () -> Unit,
    fileName: String
) {
    val shuffled = remember(questions) { questions.shuffled(Random(System.currentTimeMillis())) }
    val answers = remember(shuffled) { mutableStateListOf<String>().apply { repeat(shuffled.size) { add("") } } }
    var submitted by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.Top
        ) {
            items(shuffled.size) { idx ->
                val item = shuffled[idx]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            item.eng,
                            modifier = Modifier
                                .weight(1f)
                                .padding(8.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = answers[idx],
                            onValueChange = { answers[idx] = it },
                            label = { Text("뜻 입력") },
                            modifier = Modifier.weight(1f),
                            isError = submitted && answers[idx].trim() != item.kor
                        )
                    }
                    if (submitted) {
                        val ok = answers[idx].trim() == item.kor
                        val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        Text(if (ok) "정답" else "오답", color = color)
                        if (!ok) Text("정답: ${item.kor}", color = color)
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }
        }
        Column {
            if (!submitted) {
                Button(onClick = { submitted = true }, modifier = Modifier.fillMaxWidth()) { Text("제출하기") }
            }
            if (submitted) {
                val context = LocalContext.current
                val wrongList = remember(submitted) {
                    shuffled.filterIndexed {
                        index,
                        wordPair ->
                        answers[index].trim() != wordPair.kor
                    }
                }
                val createDocLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("text/plain")
                ) { uri ->
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            wrongList.forEach { pair ->
                                output.write("${pair.eng} = ${pair.kor}\n".toByteArray())
                            }
                        }
                    }
                }

                val correct = answers.indices.count { answers[it].trim() == shuffled[it].kor }
                Spacer(Modifier.height(6.dp))
                Text("정답: $correct / ${shuffled.size}")
                Spacer(Modifier.height(6.dp))
                if (wrongList.isNotEmpty()) {
                    Button(
                        onClick = {
                            val cal = java.util.Calendar.getInstance()
                            val year = cal.get(java.util.Calendar.YEAR).toString().substring(2)
                            val month = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
                            val day = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
                            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                            val minute = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
                            val dateTime = "${year}${month}${day}_${hour}${minute}"
                            val defaultFileName = "${fileName}_${dateTime}_오답.txt"
                            createDocLauncher.launch(defaultFileName)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("틀린 단어 파일로 내보내기")
                    }
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("끝내기") }
            }
        }
    }
}

/* -------------------- 공통 유틸 -------------------- */

private fun parsePairsFromInline(input: String): List<Pair<String, String>> {
    val tokens = input.split(Regex("\\s+")).filter { it.isNotBlank() }
    val pairs = mutableListOf<Pair<String, String>>()
    var i = 0
    while (i + 1 < tokens.size) { pairs.add(tokens[i] to tokens[i + 1]); i += 2 }
    return pairs
}

private fun parseLineToPair(line: String): Pair<String, String>? {
    val t = line.trim()
    if (t.isEmpty()) return null
    return if (t.contains("=")) {
        val p = t.split("=").map { it.trim() }
        if (p.size >= 2 && p[0].isNotEmpty() && p[1].isNotEmpty()) p[0] to p[1] else null
    } else {
        val p = t.split(Regex("\\s+"))
        if (p.size >= 2) p[0] to p[1] else null
    }
}

private fun saveWordList(folderName: String, fileName: String, list: List<Pair<String, String>>, baseDir: File) {
    val folder = File(baseDir, folderName)
    if (!folder.exists()) folder.mkdirs()
    val file = File(folder, "$fileName.txt")
    file.printWriter().use { out -> list.forEach { (e, k) -> out.println("$e = $k") } }
}

private fun loadWordList(folderName: String, fileName: String, baseDir: File): List<Pair<String, String>> {
    val file = File(File(baseDir, folderName), "$fileName.txt")
    if (!file.exists()) return emptyList()
    val result = mutableListOf<Pair<String, String>>()
    file.forEachLine { parseLineToPair(it)?.let(result::add) }
    return result
}

/* -------------------- SAF 메타 -------------------- */

private fun getDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return it.getString(idx)
        }
    }
    return null
}

private fun guessFolderNameFromUri(uri: android.net.Uri): String? {
    return try {
        val docId = DocumentsContract.getDocumentId(uri)
        val pathPart = docId.substringAfter(':', "")
        if (pathPart.contains("/")) {
            val parent = pathPart.substringBeforeLast("/")
            parent.substringAfterLast("/")
        } else null
    } catch (_: Exception) { null }
}