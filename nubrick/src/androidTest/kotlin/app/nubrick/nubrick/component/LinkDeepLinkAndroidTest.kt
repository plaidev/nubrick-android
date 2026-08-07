package app.nubrick.nubrick.component

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nubrick.nubrick.schema.PageKind
import app.nubrick.nubrick.schema.UIBlockAction
import app.nubrick.nubrick.schema.UIPageBlock
import app.nubrick.nubrick.schema.UIPageBlockData
import app.nubrick.nubrick.schema.UIRootBlock
import app.nubrick.nubrick.schema.UIRootBlockData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3Api::class)
class LinkDeepLinkAndroidTest {
    private fun modalHolder(): ModalStateHolder {
        val sheet = SheetState(
            skipPartiallyExpanded = false,
            positionalThreshold = { 56f },
            velocityThreshold = { 125f },
            initialValue = SheetValue.Hidden,
        )
        val largeSheet = SheetState(
            skipPartiallyExpanded = true,
            positionalThreshold = { 56f },
            velocityThreshold = { 125f },
            initialValue = SheetValue.Hidden,
        )
        return ModalStateHolder(
            sheetState = sheet,
            largeSheetState = largeSheet,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onDismiss = {},
        )
    }

    @Test
    fun handleNavigateParsesMalformedDeepLinkWithoutThrowing() {
        var opened: String? = null
        val holder = RootStateHolder(
            root = UIRootBlock(id = "root", data = UIRootBlockData(pages = emptyList())),
            modalStateHolder = modalHolder(),
            onOpenDeepLink = { link ->
                // Mirrors production: toUri() runs before startActivity try/catch.
                link.toUri()
                opened = link
            },
        )
        holder.handleNavigate(
            action = UIBlockAction(deepLink = ":::not-a-valid-uri:::"),
            rootData = JsonNull,
        )
        assertEquals(":::not-a-valid-uri:::", opened)
    }

    @Test
    fun handleNavigateContainsOpenDeepLinkFailure() {
        val holder = RootStateHolder(
            root = UIRootBlock(
                id = "root",
                data = UIRootBlockData(
                    pages = listOf(
                        UIPageBlock(
                            id = "page",
                            data = UIPageBlockData(kind = PageKind.COMPONENT),
                        ),
                    ),
                ),
            ),
            modalStateHolder = modalHolder(),
            onOpenDeepLink = { throw RuntimeException("boom") },
        )
        holder.handleNavigate(
            action = UIBlockAction(
                deepLink = "https://example.com",
                destinationPageId = "page",
            ),
            rootData = JsonNull,
        )
        // contain: exception must not escape; destination navigate is skipped after failure.
        assertTrue(holder.currentPageBlock.value == null)
    }
}
