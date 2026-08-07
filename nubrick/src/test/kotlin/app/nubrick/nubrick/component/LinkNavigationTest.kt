package app.nubrick.nubrick.component

import app.nubrick.nubrick.schema.PageKind
import app.nubrick.nubrick.schema.TriggerSetting
import app.nubrick.nubrick.schema.UIBlockAction
import app.nubrick.nubrick.schema.UIPageBlock
import app.nubrick.nubrick.schema.UIPageBlockData
import app.nubrick.nubrick.schema.UIRootBlock
import app.nubrick.nubrick.schema.UIRootBlockData
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

class LinkNavigationTest {
    @Test
    fun webLinkReturnRequiresHostToPauseAfterLaunch() {
        val tracker = WebLinkReturnTracker()

        tracker.start(7L)

        assertNull(tracker.onHostResumed())

        tracker.onHostPaused()

        assertEquals(7L, tracker.onHostResumed())
        assertNull(tracker.onHostResumed())
    }

    @Test
    fun cancelledWebLinkLaunchDoesNotCompleteOnResume() {
        val tracker = WebLinkReturnTracker()

        tracker.start(7L)
        tracker.onHostPaused()
        tracker.cancel(7L)

        assertNull(tracker.onHostResumed())
    }

    @Test
    fun completingWebLinkRestoresPreviousPageBeforeChainedNavigation() {
        val visiblePage = UIPageBlock(
            id = "visible",
            data = UIPageBlockData(kind = PageKind.COMPONENT),
        )
        val secondWebPage = UIPageBlock(
            id = "web-2",
            data = UIPageBlockData(
                kind = PageKind.WEBVIEW_MODAL,
                webviewUrl = "https://example.com/second",
            ),
        )
        val firstWebPage = UIPageBlock(
            id = "web-1",
            data = UIPageBlockData(
                kind = PageKind.WEBVIEW_MODAL,
                webviewUrl = "https://example.com/first",
                triggerSetting = TriggerSetting(
                    onTrigger = UIBlockAction(destinationPageId = secondWebPage.id),
                ),
            ),
        )
        val stateHolder = RootStateHolder(
            root = UIRootBlock(
                id = "root",
                data = UIRootBlockData(
                    pages = listOf(visiblePage, firstWebPage, secondWebPage),
                ),
            ),
            modalStateHolder = mock(ModalStateHolder::class.java),
        )
        stateHolder.handleNavigate(
            action = UIBlockAction(destinationPageId = visiblePage.id),
            rootData = JsonNull,
        )
        stateHolder.handleNavigate(
            action = UIBlockAction(destinationPageId = firstWebPage.id),
            rootData = JsonNull,
        )
        val firstLaunch = stateHolder.webviewData.value
            ?: error("Expected the first browser launch")

        val completed = stateHolder.handleWebviewDismiss(firstLaunch.launchId)

        assertEquals(firstWebPage.id, completed?.pageBlock?.id)
        assertEquals(visiblePage.id, stateHolder.currentPageBlock.value?.id)
        assertNull(stateHolder.webviewData.value)

        stateHolder.handleNavigate(
            action = completed?.trigger ?: error("Expected a chained trigger"),
            rootData = JsonNull,
        )
        val secondLaunch = stateHolder.webviewData.value
            ?: error("Expected the chained browser launch")

        assertNotEquals(firstLaunch.launchId, secondLaunch.launchId)
        assertEquals(secondWebPage.id, stateHolder.currentPageBlock.value?.id)
        assertNull(stateHolder.handleWebviewDismiss(firstLaunch.launchId))
        assertEquals(secondLaunch.launchId, stateHolder.webviewData.value?.launchId)

        stateHolder.handleWebviewDismiss(secondLaunch.launchId)

        assertEquals(visiblePage.id, stateHolder.currentPageBlock.value?.id)
        assertNull(stateHolder.webviewData.value)
    }

    @Test
    fun emptyDeepLinkStillNavigatesToDestination() {
        val page = UIPageBlock(
            id = "page",
            data = UIPageBlockData(kind = PageKind.COMPONENT),
        )
        val stateHolder = RootStateHolder(
            root = UIRootBlock(
                id = "root",
                data = UIRootBlockData(pages = listOf(page)),
            ),
            modalStateHolder = mock(ModalStateHolder::class.java),
        )
        stateHolder.handleNavigate(
            action = UIBlockAction(deepLink = "", destinationPageId = page.id),
            rootData = JsonNull,
        )
        assertEquals(page.id, stateHolder.currentPageBlock.value?.id)
    }

    @Test
    fun malformedDeepLinkIsPassedThroughToOpenHandler() {
        var opened: String? = null
        val stateHolder = RootStateHolder(
            root = UIRootBlock(id = "root", data = UIRootBlockData(pages = emptyList())),
            modalStateHolder = mock(ModalStateHolder::class.java),
            onOpenDeepLink = { link -> opened = link },
        )
        stateHolder.handleNavigate(
            action = UIBlockAction(deepLink = ":::not-a-valid-uri:::"),
            rootData = JsonNull,
        )
        assertEquals(":::not-a-valid-uri:::", opened)
    }

    @Test
    fun deepLinkAndDestinationAreBothApplied() {
        var opened: String? = null
        val page = UIPageBlock(
            id = "page",
            data = UIPageBlockData(kind = PageKind.COMPONENT),
        )
        val stateHolder = RootStateHolder(
            root = UIRootBlock(
                id = "root",
                data = UIRootBlockData(pages = listOf(page)),
            ),
            modalStateHolder = mock(ModalStateHolder::class.java),
            onOpenDeepLink = { link -> opened = link },
        )
        stateHolder.handleNavigate(
            action = UIBlockAction(
                deepLink = "https://example.com/path",
                destinationPageId = page.id,
            ),
            rootData = JsonNull,
        )
        assertEquals("https://example.com/path", opened)
        assertEquals(page.id, stateHolder.currentPageBlock.value?.id)
    }

    @Test
    fun webviewModalStoresEmptyAndMalformedUrlsOnNavigate() {
        val emptyUrlPage = UIPageBlock(
            id = "web-empty",
            data = UIPageBlockData(kind = PageKind.WEBVIEW_MODAL, webviewUrl = ""),
        )
        val weirdUrlPage = UIPageBlock(
            id = "web-weird",
            data = UIPageBlockData(kind = PageKind.WEBVIEW_MODAL, webviewUrl = ":::not-a-valid-uri:::"),
        )
        val stateHolder = RootStateHolder(
            root = UIRootBlock(
                id = "root",
                data = UIRootBlockData(pages = listOf(emptyUrlPage, weirdUrlPage)),
            ),
            modalStateHolder = mock(ModalStateHolder::class.java),
        )

        stateHolder.handleNavigate(
            action = UIBlockAction(destinationPageId = emptyUrlPage.id),
            rootData = JsonNull,
        )
        assertEquals("", stateHolder.webviewData.value?.url)
        assertEquals(emptyUrlPage.id, stateHolder.currentPageBlock.value?.id)

        stateHolder.handleNavigate(
            action = UIBlockAction(destinationPageId = weirdUrlPage.id),
            rootData = JsonNull,
        )
        assertEquals(":::not-a-valid-uri:::", stateHolder.webviewData.value?.url)
        assertEquals(weirdUrlPage.id, stateHolder.currentPageBlock.value?.id)
    }
}
