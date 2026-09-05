package dev.quietinbox

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.quietinbox.core.model.SourceScope
import dev.quietinbox.platform.storage.SpikeDatabase
import dev.quietinbox.platform.storage.SpikeEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
data object InboxRoute : NavKey

@Serializable
data class ConversationRoute(val id: String) : NavKey

@HiltViewModel
class SpikeViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {
    private val _scope = MutableStateFlow(SourceScope("jp.naver.line.android", "profile:0"))
    val scope: StateFlow<SourceScope> = _scope
    private val _dbStatus = MutableStateFlow("db: opening")
    val dbStatus: StateFlow<String> = _dbStatus

    init {
        viewModelScope.launch {
            _dbStatus.value = runCatching {
                val db = SpikeDatabase.open(context, "spike-key-not-for-production".toByteArray())
                db.spikeDao().insert(SpikeEntity(text = "hello"))
                "db: sqlcipher ok, rows=${db.spikeDao().count()}"
            }.getOrElse { "db: FAILED ${it::class.simpleName}: ${it.message}" }
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialExpressiveTheme {
                val backStack = rememberNavBackStack(InboxRoute)
                Scaffold { padding ->
                    NavDisplay(
                        backStack = backStack,
                        modifier = Modifier.padding(padding),
                        onBack = { backStack.removeLastOrNull() },
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        entryProvider = entryProvider {
                            entry<InboxRoute> {
                                SpikeScreen(onOpen = { backStack.add(ConversationRoute("c1")) })
                            }
                            entry<ConversationRoute> { key ->
                                Text("Conversation ${key.id}")
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SpikeScreen(onOpen: () -> Unit, viewModel: SpikeViewModel = hiltViewModel()) {
    val scope by viewModel.scope.collectAsStateWithLifecycle()
    val dbStatus by viewModel.dbStatus.collectAsStateWithLifecycle()
    Column {
        LoadingIndicator()
        Text("Spike: ${scope.packageName}")
        Text(dbStatus)
        Button(onClick = onOpen) { Text("Open") }
    }
}
