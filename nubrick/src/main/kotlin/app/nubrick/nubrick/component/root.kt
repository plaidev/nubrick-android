package app.nubrick.nubrick.component

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
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.nubrick.nubrick.Event
import app.nubrick.nubrick.EventProperty
import app.nubrick.nubrick.EventPropertyType
import app.nubrick.nubrick.NubrickSize
import app.nubrick.nubrick.component.bridge.UIBlockActionBridgeCollector
import app.nubrick.nubrick.component.bridge.UIBlockActionBridge
import app.nubrick.nubrick.NubrickSDK
import app.nubrick.nubrick.component.provider.container.ContainerProvider
import app.nubrick.nubrick.component.provider.data.DataContext
import app.nubrick.nubrick.component.provider.data.PageDataProvider
import app.nubrick.nubrick.component.provider.event.EventListenerProvider
import app.nubrick.nubrick.component.provider.pageblock.PageBlockData
import app.nubrick.nubrick.component.provider.pageblock.PageBlockProvider
import app.nubrick.nubrick.component.renderer.ModalBottomSheetBackHandler
import app.nubrick.nubrick.component.renderer.NavigationHeader
import app.nubrick.nubrick.component.renderer.Page
import app.nubrick.nubrick.schema.ModalPresentationStyle
import app.nubrick.nubrick.schema.ModalScreenSize
import app.nubrick.nubrick.schema.PageKind
import app.nubrick.nubrick.schema.Property
import app.nubrick.nubrick.schema.PropertyType
import app.nubrick.nubrick.schema.UIBlockAction
import app.nubrick.nubrick.schema.UIPageBlock
import app.nubrick.nubrick.schema.UIRootBlock
import app.nubrick.nubrick.template.compile
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

private fun parseActionToEvent(action: UIBlockAction): Event {
    return Event(
        name = action.eventName,
        deepLink = action.deepLink,
        payload = action.payload?.map { p ->
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

private fun compileUIBlockAction(action: UIBlockAction, data: JsonElement): UIBlockAction {
    return UIBlockAction(
        eventName = action.eventName?.let { compile(it, data) },
        name = action.name?.let { compile(it, data) },
        destinationPageId = action.destinationPageId,
        deepLink = action.deepLink?.let { compile(it, data) },
        payload = action.payload?.map { prop ->
            Property(
                name = prop.name ?: "",
                value = prop.value?.let { compile(it, data) } ?: "",
                ptype = prop.ptype ?: PropertyType.STRING
            )
        },
        requiredFields = action.requiredFields,
        httpRequest = action.httpRequest,
        httpResponseAssertion = action.httpResponseAssertion,
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
    private val onTrigger: ((trigger: UIBlockAction, data: JsonElement) -> Unit) = { _, _ -> },
    private val onSizeChange: ((width: NubrickSize, height: NubrickSize) -> Unit)? = null,
) {
    private val pages: List<UIPageBlock> = root.data?.pages ?: emptyList()
    val displayedPageBlock = mutableStateOf<PageBlockData?>(null)
    val webviewData = mutableStateOf<WebviewData?>(null)

    // We use them for sdk bridge between flutter <-> android.
    val currentPageBlock = mutableStateOf<UIPageBlock?>(null)
    var currentTooltipAnchorId = mutableStateOf("")

    fun initialize(data: JsonElement) {
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
        onTrigger(onTrigger, data)

        val destId = onTrigger.destinationPageId ?: ""
        if (destId.isNotEmpty()) {
            this.render(destId, rootData = data)
        } else {
          this.dismiss()
        }
    }

    fun handleNavigate(action: UIBlockAction, rootData: JsonElement) {
        val deepLink = action.deepLink ?: ""
        if (deepLink.isNotEmpty()) {
            onOpenDeepLink(deepLink)
        }

        val destId = action.destinationPageId ?: ""
        if (destId.isNotEmpty()) {
            this.render(destId, rootData = rootData)
        }
    }

    private fun render(destId: String, properties: List<Property>? = null, rootData: JsonElement = JsonNull) {
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
                url = destBlock.data.webviewUrl?.let { compile(it, rootData) } ?: "",
                trigger = destBlock.data.triggerSetting?.onTrigger,
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
            if (index >= 0) {
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
            val frameWidth = destBlock.data.frameWidth ?: 0
            val frameHeight = destBlock.data.frameHeight ?: 0
            val width = if (frameWidth == 0) NubrickSize.Fill else NubrickSize.Fixed(frameWidth)
            val height = if (frameHeight == 0) NubrickSize.Fill else NubrickSize.Fixed(frameHeight)
            onSizeChange?.invoke(width, height)
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
    arguments: Any?,
    blockData: PageBlockData,
    eventBridge: UIBlockActionBridge?,
    currentPageBlock: UIPageBlock?,
    isFullscreen: Boolean,
    modifier: Modifier = Modifier,
    onDataChange: (JsonElement) -> Unit = {},
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
        PageDataProvider(arguments = arguments, request = blockData.block.data?.httpRequest) {
            val dataState = DataContext.state
            LaunchedEffect(dataState.data) {
                onDataChange(dataState.data)
            }
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
    modifier: Modifier = Modifier,
    arguments: Any? = null,
    root: UIRootBlock,
    embeddingVisibility: Boolean = true,
    onEvent: (event: Event) -> Unit = {},
    onNextTooltip: (pageId: String) -> Unit = {},
    onDismiss: ((root: UIRootBlock) -> Unit) = {},
    eventBridge: UIBlockActionBridge? = null,
    onSizeChange: ((width: NubrickSize, height: NubrickSize) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    val largeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val sdkContainer = NubrickSDK.containerOrNull() ?: return
    val rootContainer = remember { sdkContainer.makeContainer() }
    val currentOnEvent = rememberUpdatedState(onEvent)
    val currentOnNextTooltip = rememberUpdatedState(onNextTooltip)
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    val currentOnSizeChange = rememberUpdatedState(onSizeChange)
    val modalStateHolder = remember(sheetState, largeSheetState, scope, root) {
        ModalStateHolder(sheetState, largeSheetState, scope, onDismiss = { currentOnDismiss.value(root) })
    }
    val context = LocalContext.current
    val rootStateHolder = remember(root, modalStateHolder, context) {
        RootStateHolder(
            root,
            modalStateHolder,
            onNextTooltip = { pageId -> currentOnNextTooltip.value(pageId) },
            onDismiss = { dismissedRoot -> currentOnDismiss.value(dismissedRoot) },
            onOpenDeepLink = { link ->
                val intent = Intent(Intent.ACTION_VIEW, link.toUri()).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(intent)
                } catch (_: Throwable) {
                }
            },
            onTrigger = { trigger, data ->
                val compiledTrigger = compileUIBlockAction(trigger, data)
                val e = parseActionToEvent(compiledTrigger)
                currentOnEvent.value(e)
                rootContainer.handleEvent(e)
            },
            onSizeChange = { width, height ->
                currentOnSizeChange.value?.invoke(width, height)
            },
        )
    }
    val rootData = rootContainer.rememberVariableForTemplate(
        data = null,
        pageProperties = null,
        arguments = arguments,
    )
    val latestRootData = rememberUpdatedState(rootData)
    LaunchedEffect(rootStateHolder) {
        rootStateHolder.initialize(rootData)
    }
    val bottomSheetProps = remember {
        ModalBottomSheetProperties(shouldDismissOnBackPress = false)
    }
    val listener = remember(rootStateHolder) {
        { action: UIBlockAction, data: JsonElement ->
            val compiledAction = compileUIBlockAction(action, data)
            rootStateHolder.handleNavigate(compiledAction, latestRootData.value)

            val e = parseActionToEvent(compiledAction)
            currentOnEvent.value(e)
            rootContainer.handleEvent(e)
        }
    }
    LaunchedEffect(modalStateHolder, listener) {
        modalStateHolder.setOnTrigger { trigger, data ->
            listener(trigger, data)
        }
    }

    val currentPageBlock = rootStateHolder.currentPageBlock.value
    val displayedPageBlock = rootStateHolder.displayedPageBlock.value
    val modalState = modalStateHolder.modalState
    val modalDataByIndex = remember(root) { mutableStateMapOf<Int, JsonElement>() }
    fun currentModalData(): JsonElement {
        return modalDataByIndex[modalStateHolder.modalState.displayedModalIndex] ?: JsonNull
    }
    LaunchedEffect(modalState.modalStack.size) {
        if (modalState.modalStack.isEmpty()) {
            modalDataByIndex.clear()
        } else {
            modalDataByIndex.keys.removeAll { it >= modalState.modalStack.size }
        }
    }

    ContainerProvider(container = rootContainer) {
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
                                arguments = arguments,
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
                        modalStateHolder.back(currentModalData())
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
                        contentWindowInsets = { WindowInsets(0)},
                        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                        tonalElevation = 0.dp, // to have the right background color as set in theme
                    ) {
                        ModalBottomSheetBackHandler {
                            modalStateHolder.back(currentModalData())
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
                                val stack = modalState.modalStack.getOrNull(it) ?: return@AnimatedContent
                                val isFullscreen =
                                    modalState.modalPresentationStyle == ModalPresentationStyle.DEPENDS_ON_CONTEXT_OR_FULL_SCREEN
                                NavigationHeader(
                                    it,
                                    stack.block,
                                    onClose = { modalStateHolder.close() },
                                    onBack = { modalStateHolder.back(currentModalData()) },
                                    isFullscreen,
                                )
                                ModalPage(
                                    arguments = arguments,
                                    blockData = stack,
                                    eventBridge = eventBridge,
                                    currentPageBlock = currentPageBlock,
                                    isFullscreen = isFullscreen,
                                    onDataChange = { data -> modalDataByIndex[it] = data },
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
                                listener(trigger, latestRootData.value)
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
                                    listener(trigger, latestRootData.value)
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
