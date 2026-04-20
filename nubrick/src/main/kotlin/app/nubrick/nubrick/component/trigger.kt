package app.nubrick.nubrick.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.nubrick.nubrick.NubrickEvent
import app.nubrick.nubrick.data.Container
import app.nubrick.nubrick.data.user.NubrickUser
import app.nubrick.nubrick.data.user.getNubrickUserSharedPreferences
import app.nubrick.nubrick.schema.ExperimentKind
import app.nubrick.nubrick.schema.TriggerEventNameDefs
import app.nubrick.nubrick.schema.UIBlock
import app.nubrick.nubrick.schema.UIRootBlock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class TriggerStateHolder(
    internal val container: Container,
    internal val user: NubrickUser,
    private val scope: CoroutineScope,
    onTooltip: ((data: String, experimentId: String) -> Unit)? = null,
) {
    @Volatile
    private var onTooltip: ((data: String, experimentId: String) -> Unit)? = onTooltip

    private val isFirstStart = AtomicBoolean(true)
    internal val modalStacks = mutableStateListOf<UIRootBlock>()

    fun updateOnTooltip(onTooltip: ((data: String, experimentId: String) -> Unit)?) {
        this.onTooltip = onTooltip
    }

    internal fun ignoreFirstCall(): Boolean {
        return isFirstStart.compareAndSet(true, false)
    }

    internal fun callWhenUserComesBack() {
        this.user.comeBack()

        // dispatch the event when every time the user is activated
        this.dispatch(NubrickEvent(TriggerEventNameDefs.USER_ENTER_TO_APP.name))

        val retention = this.user.retention
        if (retention == 1) {
            this.dispatch(NubrickEvent(TriggerEventNameDefs.RETENTION_1.name))
        } else if (retention in 2..3) {
            this.dispatch(NubrickEvent(TriggerEventNameDefs.RETENTION_2_3.name))
        } else if (retention in 4..7) {
            this.dispatch(NubrickEvent(TriggerEventNameDefs.RETENTION_4_7.name))
        } else if (retention in 8..14) {
            this.dispatch(NubrickEvent(TriggerEventNameDefs.RETENTION_8_14.name))
        } else if (retention > 14) {
            this.dispatch(NubrickEvent(TriggerEventNameDefs.RETENTION_15.name))
        }
    }

    fun dispatch(event: NubrickEvent) {
        val self = this
        // onTooltip is only set in the Flutter SDK. Tooltips are a Flutter-only feature,
        // so we fetch both popups and tooltips when running in Flutter, and popups only otherwise.
        val kinds: List<ExperimentKind> = if (self.onTooltip != null) {
            listOf(ExperimentKind.POPUP, ExperimentKind.TOOLTIP)
        } else {
            listOf(ExperimentKind.POPUP)
        }
        // scope runs on Dispatchers.IO, provided by NubrickRuntime
        scope.launch {
            self.container.handleNubrickEvent(event)
            val triggerContent = self.container.fetchTriggerContent(event.name, kinds).getOrNull()
                ?: return@launch
            val kind = triggerContent.kind
            val block = triggerContent.block
            if (kind == ExperimentKind.TOOLTIP) {
                self.onTooltip?.let { callback ->
                    val jsonString = Json.encodeToString(UIBlock.encode(block))
                    // Flutter MethodChannel requires calls on the main thread
                    withContext(Dispatchers.Main) {
                        callback(jsonString, triggerContent.experimentId)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    if (block is UIBlock.UnionUIRootBlock) {
                        if (self.modalStacks.indexOfFirst { stack ->
                                stack.id == block.data.id
                            } < 0) {
                            self.modalStacks.add(block.data)
                        }
                    }
                }
            }
        }
    }

    fun handleDismiss(root: UIRootBlock) {
        modalStacks.removeIf {
            it.id == root.id
        }
    }

}

@Composable
internal fun Trigger(trigger: TriggerStateHolder) {
    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current
    val observer = remember {
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (trigger.ignoreFirstCall()) {
                        trigger.dispatch(NubrickEvent(TriggerEventNameDefs.USER_BOOT_APP.name))

                        val preferences = getNubrickUserSharedPreferences(context)
                        val countKey = "NATIVEBRIK_SDK_INITIALIZED_COUNT"
                        val count: Int = preferences?.getInt(countKey, 0) ?: 0
                        preferences?.edit()?.putInt(countKey, count + 1)?.apply()
                        if (count == 0) {
                            trigger.dispatch(NubrickEvent(TriggerEventNameDefs.USER_ENTER_TO_APP_FIRSTLY.name))
                        }
                    } else {
                        trigger.dispatch(NubrickEvent(TriggerEventNameDefs.USER_ENTER_TO_FOREGROUND.name))
                    }
                    trigger.callWhenUserComesBack()
                }
                else -> {}
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    if (trigger.modalStacks.isNotEmpty()) {
        for (stack in trigger.modalStacks) {
            Root(
                container = trigger.container,
                root = stack,
                embeddingVisibility = false,
                onDismiss = {
                    trigger.handleDismiss(it)
                }
            )
        }
    }
}
