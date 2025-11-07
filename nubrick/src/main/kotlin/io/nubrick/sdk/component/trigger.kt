package io.nubrick.sdk.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import io.nubrick.sdk.NubrickEvent
import io.nubrick.sdk.data.Container
import io.nubrick.sdk.data.user.NubrickUser
import io.nubrick.sdk.data.user.getNativebrikUserSharedPreferences
import io.nubrick.sdk.schema.TriggerEventNameDefs
import io.nubrick.sdk.schema.UIBlock
import io.nubrick.sdk.schema.UIRootBlock
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

internal class TriggerViewModel(internal val container: Container, internal val user: NubrickUser) : ViewModel() {
    private val ignoreFirstUserEventToForegroundEvent = mutableStateOf(true)
    internal val modalStacks = mutableStateListOf<UIRootBlock>()

    internal fun ignoreFirstCall(): Boolean {
        return if (this.ignoreFirstUserEventToForegroundEvent.value) {
            this.ignoreFirstUserEventToForegroundEvent.value = false
            true
        } else {
            false
        }
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

    @OptIn(DelicateCoroutinesApi::class)
    fun dispatch(event: NubrickEvent) {
        val self = this
        GlobalScope.launch(Dispatchers.IO) {
            self.container.handleNubrickEvent(event)
            self.container.fetchInAppMessage(event.name).onSuccess {
                GlobalScope.launch(Dispatchers.Main) {
                    if (it is UIBlock.UnionUIRootBlock) {
                        if (self.modalStacks.indexOfFirst { stack ->
                            stack.id == it.data.id
                        } < 0) {
                            self.modalStacks.add(it.data)
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
internal fun Trigger(trigger: TriggerViewModel) {
    val context = LocalContext.current
    LaunchedEffect("") {
        // dispatch user boot
        trigger.dispatch(NubrickEvent(TriggerEventNameDefs.USER_BOOT_APP.name))

        // dispatch the first time visit
        val preferences = getNativebrikUserSharedPreferences(context)
        val countKey = "NATIVEBRIK_SDK_INITIALIZED_COUNT"
        val count: Int = preferences?.getInt(countKey, 0) ?: 0
        preferences?.edit()?.putInt(countKey, count + 1)?.apply()
        if (count == 0) {
            trigger.dispatch(NubrickEvent(TriggerEventNameDefs.USER_ENTER_TO_APP_FIRSTLY.name))
        }

        trigger.callWhenUserComesBack()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val observer = remember {
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (trigger.ignoreFirstCall()) {
                        return@LifecycleEventObserver
                    }
                    trigger.dispatch(NubrickEvent(TriggerEventNameDefs.USER_ENTER_TO_FOREGROUND.name))
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
