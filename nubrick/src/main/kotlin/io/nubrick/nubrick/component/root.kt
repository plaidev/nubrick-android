package io.nubrick.nubrick.component

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.nubrick.nubrick.Event
import io.nubrick.nubrick.EventProperty
import io.nubrick.nubrick.EventPropertyType
import io.nubrick.nubrick.component.bridge.UIBlockActionBridgeCollector
import io.nubrick.nubrick.component.bridge.UIBlockActionBridge
import io.nubrick.nubrick.component.provider.container.ContainerProvider
import io.nubrick.nubrick.component.provider.data.PageDataProvider
import io.nubrick.nubrick.component.provider.event.EventListenerProvider
import io.nubrick.nubrick.component.provider.pageblock.PageBlockData
import io.nubrick.nubrick.component.provider.pageblock.PageBlockProvider
import io.nubrick.nubrick.component.renderer.ModalBottomSheetBackHandler
import io.nubrick.nubrick.component.renderer.NavigationHeader
import io.nubrick.nubrick.component.renderer.Page
import io.nubrick.nubrick.data.Container
import io.nubrick.nubrick.schema.ModalPresentationStyle
import io.nubrick.nubrick.schema.ModalScreenSize
import io.nubrick.nubrick.schema.PageKind
import io.nubrick.nubrick.schema.Property
import io.nubrick.nubrick.schema.PropertyType
import io.nubrick.nubrick.schema.UIBlockAction
import io.nubrick.nubrick.schema.UIPageBlock
import io.nubrick.nubrick.schema.UIRootBlock
import io.nubrick.nubrick.template.compile
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

private fun parseUIEventToEvent(event: UIBlockAction): Event {
    return Event(
        name = event.eventName,
        deepLink = event.deepLink,
        payload = event.payload?.map { p ->
            EventProperty(
                name = p.name ?: "",
                value = p.value ?: "",
                type = when (p.ptype) {
                    PropertyType.INTEGER -> EventPropertyType.INTEGER
                    PropertyType.STRING -> EventPropertyType.STRING
                    PropertyType.TIMESTAMPZ -> EventPropertyType.TIMESTAMPZ
                    else -> EventPropertyType.UNKNOWN
                }
            )
        }
    )
}

internal data class WebviewData(
    val url: String,
    val trigger: UIBlockAction?,
) {}

internal class RootStateHolder(
    private val root: UIRootBlock,
    private val modalStateHolder: ModalStateHolder,
    private val onNextTooltip: ((pageId: String) -> Unit) = {},
    private val onDismiss: ((root: UIRootBlock) -> Unit) = {},
    private val onOpenDeepLink: ((link: String) -> Unit) = {},
    private val onTrigger: ((trigger: UIBlockAction) -> Unit) = {},
    private val onSizeChange: ((width: Int?, height: Int?) -> Unit)? = null,
) {
    private val pages: List<UIPageBlock> = root.data?.pages ?: emptyList()
    val displayedPageBlock = mutableStateOf<PageBlockData?>(null)
    val webviewData = mutableStateOf<WebviewData?>(null)

    // We use them for sdk bridge between flutter <-> android.
    val currentPageBlock = mutableStateOf<UIPageBlock?>(null)
    var currentTooltipAnchorId = mutableStateOf("")

    fun initialize() {
        val trigger = pages.firstOrNull {
            it.data?.kind == PageKind.TRIGGER
        } ?: run {
            onDismiss(root)
            return
        }

        val onTrigger = trigger.data?.triggerSetting?.onTrigger
        if (onTrigger == null) {
          onDismiss(root)
          return
        }
        onTrigger(onTrigger)

        val destId = onTrigger.destinationPageId ?: ""
        if (destId.isNotEmpty()) {
            this.render(destId)
        } else {
          this.dismiss()
        }
    }

    fun handleNavigate(event: UIBlockAction, data: JsonElement) {
        val deepLink = event.deepLink?.let { compile(it, data) } ?: ""
        if (deepLink.isNotEmpty()) {
            onOpenDeepLink(deepLink)
        }

        val destId = event.destinationPageId ?: ""
        if (destId.isNotEmpty()) {
            this.render(destId)
        }
    }

    private fun render(destId: String, properties: List<Property>? = null) {
        val destBlock = this.pages.firstOrNull {
            it.id == destId
        }
        if (destBlock == null) {
            return
        }

        this.currentPageBlock.value = destBlock

        if (destBlock.data?.kind == PageKind.DISMISSED) {
            this.dismiss()
            return
        }

        if (destBlock.data?.kind == PageKind.WEBVIEW_MODAL) {
            this.webviewData.value = WebviewData(
                url = destBlock.data.webviewUrl ?: "",
                trigger = destBlock.data.triggerSetting?.onTrigger
            )
            return
        }

        if (destBlock.data?.kind == PageKind.TOOLTIP) {
            onNextTooltip(destId)
            val anchorId = destBlock.data.tooltipAnchor ?: ""
            this.currentTooltipAnchorId.value = anchorId
        }

        if (destBlock.data?.kind == PageKind.MODAL) {
            val index = modalStateHolder.modalState.modalStack.indexOfFirst {
                it.block.id == destId
            }
            if (index > 0) {
                // if it's already in modal stack, jump to the target stack
                modalStateHolder.backTo(index)
                return
            }

            modalStateHolder.show(
                block = PageBlockData(destBlock, properties),
                modalPresentationStyle = destBlock.data.modalPresentationStyle
                    ?: ModalPresentationStyle.UNKNOWN,
                modalScreenSize = destBlock.data.modalScreenSize ?: ModalScreenSize.UNKNOWN
            )
            return
        }

        if (destBlock.data?.kind == PageKind.COMPONENT) {
            onSizeChange?.invoke(destBlock.data.frameWidth, destBlock.data.frameHeight)
        }

        // Before displaying the page, we close displayed modals. but never emit dismiss event.
        modalStateHolder.close(forceReset = true, emitDispatch = false)
        this.displayedPageBlock.value = PageBlockData(destBlock, properties)
    }

    private fun dismiss(emitDispatch: Boolean = true) {
        this.currentPageBlock.value = null
        modalStateHolder.close(forceReset = true, emitDispatch = emitDispatch)
    }

    fun handleWebviewDismiss() {
        this.webviewData.value = null
    }
}

@Composable
internal fun ModalPage(
    container: Container,
    blockData: PageBlockData,
    eventBridge: UIBlockActionBridge?,
    currentPageBlock: UIPageBlock?,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean,
) {
    val insetTop = if (blockData.block.data?.modalRespectSafeArea == true) {
        if (isFullscreen) {
            with(LocalDensity.current) {
                WindowInsets.statusBars.getTop(this)
                    .toDp() + 38.dp // status bar + nav header height
            }
        } else {
            60.dp // nav header height
        }
    } else {
        0.dp
    }

    PageBlockProvider(
        blockData,
    ) {
        PageDataProvider(container = container, request = blockData.block.data?.httpRequest) {
            UIBlockActionBridgeCollector(
                events = eventBridge?.events,
                isCurrentPage = blockData.block.id == currentPageBlock?.id
            )
            Page(block = blockData.block, modifier, insetTop)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Root(
    modifier: Modifier = Modifier.fillMaxSize(),
    container: Container,
    root: UIRootBlock,
    embeddingVisibility: Boolean = true,
    onEvent: (event: Event) -> Unit = {},
    onNextTooltip: (pageId: String) -> Unit = {},
    onDismiss: ((root: UIRootBlock) -> Unit) = {},
    eventBridge: UIBlockActionBridge? = null,
    onSizeChange: ((width: Int?, height: Int?) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    val largeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val modalStateHolder = remember(sheetState, scope) {
        ModalStateHolder(sheetState, largeSheetState, scope, onDismiss = { onDismiss(root) })
    }
    val context = LocalContext.current
    val rootStateHolder = remember(root, modalStateHolder, onDismiss, context) {
        RootStateHolder(
            root,
            modalStateHolder,
            onNextTooltip,
            onDismiss,
            onOpenDeepLink = { link ->
                val intent = Intent(Intent.ACTION_VIEW, link.toUri()).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(intent)
                } catch (_: Throwable) {
                }
            },
            onTrigger = { trigger ->
                val e = parseUIEventToEvent(trigger)
                onEvent(e)
                container.handleEvent(e)
            },
            onSizeChange = onSizeChange,
        )
    }
    LaunchedEffect(Unit) {
        rootStateHolder.initialize()
    }
    val bottomSheetProps = remember {
        ModalBottomSheetDefaults.properties(shouldDismissOnBackPress = false)
    }
    val listener = remember(rootStateHolder, onEvent, container) {
        { event: UIBlockAction, data: JsonElement ->
            rootStateHolder.handleNavigate(event, data)

            val e = parseUIEventToEvent(event)
            onEvent(e)
            container.handleEvent(e)
        }
    }
    LaunchedEffect(Unit) {
        modalStateHolder.setOnTrigger { trigger, data ->
            listener(trigger, data)
        }
    }

    val currentPageBlock = rootStateHolder.currentPageBlock.value
    val displayedPageBlock = rootStateHolder.displayedPageBlock.value
    val modalState = modalStateHolder.modalState

    ContainerProvider(container = container) {
        EventListenerProvider(listener = listener) {
            Box(modifier) {
                if (embeddingVisibility && displayedPageBlock != null) {
                    AnimatedContent(
                        targetState = displayedPageBlock,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "Embedding",
                        modifier = Modifier.fillMaxSize()
                    ) {
                        PageBlockProvider(it) {
                            PageDataProvider(
                                container = container,
                                request = it.block.data?.httpRequest
                            ) {
                                UIBlockActionBridgeCollector(
                                    events = eventBridge?.events,
                                    isCurrentPage = it.block.id == currentPageBlock?.id
                                )
                                Page(block = it.block)
                            }
                        }
                    }
                }

                if (modalState.modalVisibility) {
                    BackHandler(true) {
                        modalStateHolder.back(JsonNull)
                    }
                    val isLarge =
                        modalState.modalPresentationStyle == ModalPresentationStyle.DEPENDS_ON_CONTEXT_OR_FULL_SCREEN
                                || modalState.modalScreenSize == ModalScreenSize.LARGE
                    val insetTop = with(LocalDensity.current) {
                        WindowInsets.statusBars.getTop(this).toDp()
                    }
                    ModalBottomSheet(
                        sheetState = if (isLarge) largeSheetState else sheetState,
                        onDismissRequest = {
                            modalStateHolder.close()
                        },
                        properties = bottomSheetProps,
                        dragHandle = {},
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                        tonalElevation = 0.dp, // to have the right background color as set in theme
                    ) {
                        ModalBottomSheetBackHandler {
                            modalStateHolder.back(JsonNull)
                        }
                        Column(
                            modifier = if (modalState.modalPresentationStyle == ModalPresentationStyle.DEPENDS_ON_CONTEXT_OR_FULL_SCREEN) {
                                Modifier.fillMaxSize()
                            } else {
                                if (modalState.modalScreenSize == ModalScreenSize.MEDIUM) {
                                    Modifier.height(LocalConfiguration.current.screenHeightDp.dp * 0.5f)
                                } else {
                                    Modifier.height(
                                        LocalConfiguration.current.screenHeightDp.dp - insetTop
                                    )
                                }
                            }
                        ) {
                            AnimatedContent(
                                targetState = modalState.displayedModalIndex,
                                transitionSpec = {
                                    if (targetState > initialState) {
                                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it } + fadeOut()
                                    } else {
                                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it } + fadeOut()
                                    }
                                },
                                label = "Bottom Sheet"
                            ) {
                                val stack = modalState.modalStack[it]
                                val isFullscreen =
                                    modalState.modalPresentationStyle == ModalPresentationStyle.DEPENDS_ON_CONTEXT_OR_FULL_SCREEN
                                NavigationHeader(
                                    it,
                                    stack.block,
                                    onClose = { modalStateHolder.close() },
                                    onBack = { modalStateHolder.back(JsonNull) },
                                    isFullscreen,
                                )
                                ModalPage(
                                    container = container,
                                    blockData = stack,
                                    eventBridge = eventBridge,
                                    currentPageBlock = currentPageBlock,
                                    isFullscreen = isFullscreen,
                                )
                            }
                        }
                    }
                }

                val webviewData = rootStateHolder.webviewData.value
                val pendingWebviewReturn = remember { mutableStateOf(false) }

                LaunchedEffect(webviewData) {
                    if (webviewData != null) {
                        try {
                            val customTabsIntent = CustomTabsIntent.Builder().build()
                            customTabsIntent.launchUrl(context, webviewData.url.toUri())
                            pendingWebviewReturn.value = true
                        } catch (_: Throwable) {
                            // No browser available — fire trigger immediately
                            webviewData.trigger?.let { trigger ->
                                listener(trigger, JsonNull)
                            }
                            rootStateHolder.handleWebviewDismiss()
                        }
                    }
                }

                if (pendingWebviewReturn.value) {
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                webviewData?.trigger?.let { trigger ->
                                    listener(trigger, JsonNull)
                                }
                                rootStateHolder.handleWebviewDismiss()
                                pendingWebviewReturn.value = false
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }
                }
            }
        }
    }
}
