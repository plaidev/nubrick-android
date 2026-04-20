package app.nubrick.nubrick.component.provider.pageblock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import app.nubrick.nubrick.schema.Property
import app.nubrick.nubrick.schema.UIPageBlock

internal data class PageBlockData(val block: UIPageBlock, val properties: List<Property>? = null) {
    fun toProperties(): List<Property>? {
        return this.block.data?.props?.map {
            val found = properties?.firstOrNull {prop ->
                prop.name == it.name
            }
            Property(it.name, found?.value ?: it.value)
        }
    }
}

internal val LocalPageBlock = compositionLocalOf<PageBlockData> {
    error("LocalPageBlock is not found")
}

internal object PageBlockContext {
    /**
     * Retrieves the current [LocalPageBlock] at the call site's position in the hierarchy.
     */
    val value: PageBlockData
        @Composable
        @ReadOnlyComposable
        get() = LocalPageBlock.current
}

@Composable
internal fun PageBlockProvider(
    pageBlock: PageBlockData,
    content: @Composable() () -> Unit,
) {
    CompositionLocalProvider(
        LocalPageBlock provides pageBlock
    ) {
        content()
    }
}
