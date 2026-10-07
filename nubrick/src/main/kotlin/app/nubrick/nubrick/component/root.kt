package app.nubrick.nubrick.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.nubrick.nubrick.Event
import app.nubrick.nubrick.NubrickSDK
import app.nubrick.nubrick.NubrickSize
import app.nubrick.nubrick.remoteNubrickSize
import app.nubrick.nubrick.component.bridge.UIBlockActionBridgeCollector
import app.nubrick.nubrick.component.bridge.UIBlockActionBridge
import app.nubrick.nubrick.component.provider.container.ContainerProvider
import app.nubrick.nubrick.component.provider.data.DataContext
import app.nubrick.nubrick.component.provider.data.PageDataProvider
import app.nubrick.nubrick.component.provider.event.EventListenerProvider
import app.nubrick.nubrick.component.provider.pageblock.PageBlockData
import app.nubrick.nubrick.component.provider.pageblock.PageBlockProvider
import app.nubrick.nubrick.component.renderer.ModalBottomSheetBackHandler
import app.nubrick.nubrick.component.renderer.NavigationHeader
import app.nubrick.nubrick.component.renderer.Page
import app.nubrick.nubrick.component.renderer.parseColor
import app.nubrick.nubrick.data.Container
import app.nubrick.nubrick.schema.ModalPresentationStyle
import app.nubrick.nubrick.schema.ModalScreenSize
import app.nubrick.nubrick.schema.PageKind
import app.nubrick.nubrick.schema.Property
import app.nubrick.nubrick.schema.PropertyType
import app.nubrick.nubrick.schema.UIBlockAction
import app.nubrick.nubrick.schema.UIBlock
import app.nubrick.nubrick.schema.UIPageBlock
import app.nubrick.nubrick.schema.UIRootBlock
import app.nubrick.nubrick.template.compile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

private fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext

        val baseContext = currentContext.baseContext
        if (baseContext === currentContext) return null
        currentContext = baseContext
    }
    return currentContext as? Activity
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
        submitSurveyResponse = action.submitSurveyResponse,
    )
}

private fun modalContainerColor(page: UIPageBlock) = when (val renderAs = page.data?.renderAs) {
    is UIBlock.UnionUIFlexContainerBlock -> {
        renderAs.data.data?.frame?.background?.let(::parseColor)
    }

    else -> null
}

internal data class WebviewData(
    val launchId: Long,
    val url: String,
    val trigger: UIBlockAction?,
    val pageBlock: UIPageBlock,
    val previousPageBlock: UIPageBlock?,
)

internal class WebLinkReturnTracker {
    private var pendingLaunchId: Long? = null
    private var hostPaused = false

    fun start(launchId: Long) {
        pendingLaunchId = launchId
        hostPaused = false
    }

    fun cancel(launchId: Long) {
        if (pendingLaunchId == launchId) {
            pendingLaunchId = null
            hostPaused = false
        }
    }

    fun onHostPaused() {
        if (pendingLaunchId != null) {
            hostPaused = true
        }
    }

    fun onHostResumed(): Long? {
        val launchId = pendingLaunchId
        if (launchId == null || !hostPaused) return null

        pendingLaunchId = null
        hostPaused = false
        return launchId
    }
}

internal class RootStateHolder(
    private val root: UIRootBlock,
    private val modalStateHolder: ModalStateHolder,
    private val onNextTooltip: ((pageId: String) -> Unit) = {},
    private val onDismiss: ((root: UIRootBlock) -> Unit) = {},
    private val onOpenDeepLink: ((link: String) -> Unit) = {},
    private val onTrigger: ((trigger: UIBlockAction, data: JsonElement) -> Unit) = { _, _ -> },
    private val onSizeChange: ((width: NubrickSize, height: NubrickSize) -> Unit)? = null,
    private val sessionId: String? = null,
) {
    private val pages: List<UIPageBlock> = root.data?.pages ?: emptyList()
    val displayedPageBlock = mutableStateOf<PageBlockData?>(null)
    val webviewData = mutableStateOf<WebviewData?>(null)
    private var nextWebviewLaunchId = 0L

    // We use them for sdk bridge between flutter <-> android.
    val currentPageBlock = mutableStateOf<UIPageBlock?>(null)
    var currentTooltipAnchorId = mutableStateOf("")

    fun initialize(data: JsonElement) {
        if (!NubrickSDK.ownsTriggerExperiment(sessionId)) return
        try {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("NubrickSDK", "Failed to initialize root", e)
            onDismiss(root)
        }
    }

    fun handleNavigate(action: UIBlockAction, rootData: JsonElement) {
        if (!NubrickSDK.ownsTriggerExperiment(sessionId)) return
        try {
            val deepLink = action.deepLink ?: ""
            if (deepLink.isNotEmpty()) {
                onOpenDeepLink(deepLink)
            }

            val destId = action.destinationPageId ?: ""
            if (destId.isNotEmpty()) {
                this.render(destId, rootData = rootData)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("NubrickSDK", "Failed to handle root navigation", e)
        }
    }

    private fun render(destId: String, properties: List<Property>? = null, rootData: JsonElement = JsonNull) {
        val destBlock = this.pages.firstOrNull {
            it.id == destId
        }
        if (destBlock == null) {
            if (currentPageBlock.value == null) onDismiss(root)
            return
        }

        val previousPageBlock = this.currentPageBlock.value
        this.currentPageBlock.value = destBlock

        if (destBlock.data?.kind == PageKind.DISMISSED) {
            this.dismiss()
            return
        }

        if (destBlock.data?.kind == PageKind.WEBVIEW_MODAL) {
            this.webviewData.value = WebviewData(
                launchId = ++nextWebviewLaunchId,
                url = destBlock.data.webviewUrl?.let { compile(it, rootData) } ?: "",
                trigger = destBlock.data.triggerSetting?.onTrigger,
                pageBlock = destBlock,
                previousPageBlock = previousPageBlock,
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
                it.page.block.id == destId
            }
            if (index >= 0) {
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
            val width = remoteNubrickSize(destBlock.data.frameWidth)
            val height = remoteNubrickSize(destBlock.data.frameHeight)
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

    fun handleWebviewDismiss(launchId: Long, onTrigger: (UIBlockAction) -> Unit): WebviewData? {
        if (!NubrickSDK.ownsTriggerExperiment(sessionId)) return null
        val dismissedData = this.webviewData.value?.takeIf { it.launchId == launchId }
            ?: return null

        this.webviewData.value = null
        if (this.currentPageBlock.value?.id == dismissedData.pageBlock.id) {
            this.currentPageBlock.value = dismissedData.previousPageBlock
        }
        dismissedData.trigger?.let(onTrigger)
        // Finish a standalone browser flow after its return action. A navigation
        // destination (including DISMISSED) owns its own presentation/cleanup.
        if (dismissedData.trigger?.destinationPageId.isNullOrEmpty()
            && this.currentPageBlock.value == null
            && this.webviewData.value == null
        ) {
            onDismiss(root)
        }
        return dismissedData
    }
}

@Composable
internal fun ModalPage(
    arguments: Any?,
    blockData: PageBlockData,
    eventBridge: UIBlockActionBridge?,
    isCurrentPage: Boolean,
    modifier: Modifier = Modifier,
    fixedTopInset: Dp? = null,
    onDataChange: (JsonElement) -> Unit = {},
) {
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
                isCurrentPage = isCurrentPage
            )
            Page(
                block = blockData.block,
                modifier = modifier,
                isModal = true,
                fixedTopInset = fixedTopInset,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Root(
    container: Container,
    modifier: Modifier = Modifier,
    arguments: Any? = null,
    root: UIRootBlock,
    experimentId: String? = null,
    variantId: String? = null,
    embeddingVisibility: Boolean = true,
    onEvent: (event: Event) -> Unit = {},
    onNextTooltip: (pageId: String) -> Unit = {},
    onShown: () -> Unit = {},
    onDismiss: ((root: UIRootBlock) -> Unit) = {},
    eventBridge: UIBlockActionBridge? = null,
    onSizeChange: ((width: NubrickSize, height: NubrickSize) -> Unit)? = null,
    sessionId: String? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    val largeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val rootContainer = remember(container, experimentId, variantId) {
        container.makeContainer(
            experimentId = experimentId ?: container.experimentId,
            variantId = variantId ?: container.variantId,
        )
    }
    val currentOnShown = rememberUpdatedState(onShown)
    val currentOnEvent = rememberUpdatedState(onEvent)
    val currentOnNextTooltip = rememberUpdatedState(onNextTooltip)
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    val currentOnSizeChange = rememberUpdatedState(onSizeChange)
    val modalStateHolder = remember(sheetState, largeSheetState, scope, root) {
        ModalStateHolder(sheetState, largeSheetState, scope, onDismiss = { currentOnDismiss.value(root) })
    }
    val context = LocalContext.current
    val rootStateHolder = remember(root, rootContainer, modalStateHolder, context, sessionId) {
        RootStateHolder(
            root,
            modalStateHolder,
            sessionId = sessionId,
            onNextTooltip = { pageId -> currentOnNextTooltip.value(pageId) },
            onDismiss = { dismissedRoot -> currentOnDismiss.value(dismissedRoot) },
            onOpenDeepLink = { link ->
                val activity = context.findActivity()
                val launchContext = activity ?: context
                val intent = Intent(Intent.ACTION_VIEW, link.toUri()).apply {
                    if (activity == null) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                try {
                    launchContext.startActivity(intent)
                } catch (_: Throwable) {
                }
            },
            onTrigger = { trigger, data ->
                val compiledTrigger = compileUIBlockAction(trigger, data)
                rootContainer.handleAction(compiledTrigger) { event ->
                    currentOnEvent.value(event)
                }
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
        val handler: (UIBlockAction, JsonElement) -> Unit = { action, data ->
            try {
                val compiledAction = compileUIBlockAction(action, data)
                rootStateHolder.handleNavigate(compiledAction, latestRootData.value)

                rootContainer.handleAction(compiledAction) { event ->
                    currentOnEvent.value(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("NubrickSDK", "Failed to handle root action", e)
            }
        }
        handler
    }
    LaunchedEffect(modalStateHolder, listener) {
        modalStateHolder.setOnTrigger { trigger, data ->
            listener(trigger, data)
        }
    }

    val currentPageBlock = rootStateHolder.currentPageBlock.value
    val displayedPageBlock = rootStateHolder.displayedPageBlock.value
    val modalState = modalStateHolder.modalState
    val webviewData = rootStateHolder.webviewData.value
    LaunchedEffect(
        rootStateHolder,
        embeddingVisibility,
        currentPageBlock,
        modalState.modalVisibility,
        webviewData,
    ) {
        // Trigger roots have no embedded surface. Release them whenever navigation
        // leaves both the modal and browser presentation paths, not only at startup.
        if (!embeddingVisibility &&
            currentPageBlock != null &&
            currentPageBlock.data?.kind != PageKind.MODAL &&
            !modalState.modalVisibility &&
            webviewData == null
        ) {
            currentOnDismiss.value(root)
        }
    }
    val modalDataByEntry = remember(root) { mutableStateMapOf<Long, JsonElement>() }
    fun currentModalData(): JsonElement {
        return modalStateHolder.modalState.currentEntry?.let { modalDataByEntry[it.id] } ?: JsonNull
    }
    LaunchedEffect(modalState.modalStack) {
        val entryIds = modalState.modalStack.map { it.id }.toSet()
        modalDataByEntry.keys.removeAll { it !in entryIds }
    }

    ContainerProvider(container = rootContainer) {
        EventListenerProvider(listener = listener) {
            Box(modifier) {
                if (embeddingVisibility && displayedPageBlock != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        key(displayedPageBlock) {
                            PageBlockProvider(displayedPageBlock) {
                                PageDataProvider(
                                    arguments = arguments,
                                    request = displayedPageBlock.block.data?.httpRequest
                                ) {
                                    UIBlockActionBridgeCollector(
                                        events = eventBridge?.events,
                                        isCurrentPage = displayedPageBlock.block.id == currentPageBlock?.id
                                    )
                                    Page(block = displayedPageBlock.block)
                                }
                            }
                        }
                    }
                }

                if (modalState.modalVisibility) {
                    val isFullScreen = modalState.modalPresentationStyle ==
                        ModalPresentationStyle.DEPENDS_ON_CONTEXT_OR_FULL_SCREEN
                    val isLarge = isFullScreen || modalState.modalScreenSize == ModalScreenSize.LARGE
                    val activeSheetState = if (isLarge) largeSheetState else sheetState
                    val sheetContainerColor = modalState.currentPageBlock
                        ?.block
                        ?.let(::modalContainerColor)
                        ?: MaterialTheme.colorScheme.surface
                    LaunchedEffect(activeSheetState) {
                        snapshotFlow {
                            activeSheetState.isVisible && !activeSheetState.isAnimationRunning &&
                                activeSheetState.currentValue == activeSheetState.targetValue
                        }.first { it }
                        currentOnShown.value()
                    }
                    ModalBottomSheet(
                        sheetState = activeSheetState,
                        sheetGesturesEnabled = !isFullScreen,
                        onDismissRequest = {
                            modalStateHolder.close()
                        },
                        properties = bottomSheetProps,
                        dragHandle = {},
                        // Each page opts into content insets without shrinking its background.
                        contentWindowInsets = { WindowInsets(0) },
                        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                        containerColor = sheetContainerColor,
                        tonalElevation = 0.dp, // to have the right background color as set in theme
                    ) {
                        ModalBottomSheetBackHandler {
                            modalStateHolder.back(currentModalData())
                        }
                        BoxWithConstraints {
                            val density = LocalDensity.current
                            val safeDrawingInsets = WindowInsets.safeDrawing
                            val statusBarTop = with(density) {
                                WindowInsets.statusBars.getTop(this).toDp()
                            }
                            val contentHeight = if (modalState.modalScreenSize == ModalScreenSize.MEDIUM) {
                                LocalConfiguration.current.screenHeightDp.dp * 0.5f
                            } else {
                                LocalConfiguration.current.screenHeightDp.dp - statusBarTop
                            }
                            val contentSizeModifier = if (isFullScreen) {
                                Modifier.fillMaxSize()
                            } else {
                                Modifier.height(contentHeight)
                            }
                            // This scope has the sheet's layout constraints before its content is
                            // composed. Use the expanded anchor, not the animated current offset.
                            val sheetHeightPx = if (isFullScreen) {
                                constraints.maxHeight
                            } else {
                                with(density) { contentHeight.roundToPx() }
                                    .coerceIn(0, constraints.maxHeight)
                            }
                            val expandedTopPx = constraints.maxHeight - sheetHeightPx
                            val fixedTopInset = with(density) {
                                (safeDrawingInsets.getTop(this) - expandedTopPx)
                                    .coerceAtLeast(0).toDp()
                            }
                            Column(modifier = contentSizeModifier) {
                                AnimatedContent(
                                    targetState = modalState.displayedModalIndex to modalState.currentEntry,
                                    contentKey = { it.second?.id },
                                    transitionSpec = {
                                        if (targetState.first > initialState.first) {
                                            slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                                        } else {
                                            slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                                        }
                                    },
                                    label = "Bottom Sheet"
                                ) { (index, entry) ->
                                    if (entry == null) return@AnimatedContent
                                    NavigationHeader(
                                        index,
                                        entry.page.block,
                                        fixedTopInset = fixedTopInset,
                                        onClose = {
                                            modalStateHolder.back(currentModalData(), collapseExpandedSheet = false)
                                        },
                                        onBack = { modalStateHolder.back(currentModalData()) },
                                    )
                                    ModalPage(
                                        arguments = arguments,
                                        blockData = entry.page,
                                        eventBridge = eventBridge,
                                        isCurrentPage = entry.id == modalState.currentEntry?.id
                                            && currentPageBlock?.data?.kind == PageKind.MODAL,
                                        fixedTopInset = fixedTopInset,
                                        onDataChange = { data ->
                                            if (modalStateHolder.modalState.modalStack.any { it.id == entry.id }) {
                                                modalDataByEntry[entry.id] = data
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                val webLinkReturnTracker = remember(rootStateHolder) {
                    WebLinkReturnTracker()
                }
                val currentWebLinkCompletion = rememberUpdatedState<(Long) -> Unit> { launchId ->
                    rootStateHolder.handleWebviewDismiss(launchId) { trigger ->
                        listener(trigger, latestRootData.value)
                    }
                }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, webLinkReturnTracker) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_PAUSE -> {
                                webLinkReturnTracker.onHostPaused()
                            }

                            Lifecycle.Event.ON_RESUME -> {
                                webLinkReturnTracker.onHostResumed()?.let { launchId ->
                                    currentWebLinkCompletion.value(launchId)
                                }
                            }

                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                LaunchedEffect(webviewData?.launchId) {
                    val data = webviewData ?: return@LaunchedEffect
                    webLinkReturnTracker.start(data.launchId)
                    try {
                        val customTabsIntent = CustomTabsIntent.Builder().build()
                        customTabsIntent.launchUrl(context, data.url.toUri())
                        currentOnShown.value()
                    } catch (_: Throwable) {
                        webLinkReturnTracker.cancel(data.launchId)
                        currentWebLinkCompletion.value(data.launchId)
                    }
                }
            }
        }
    }
}
