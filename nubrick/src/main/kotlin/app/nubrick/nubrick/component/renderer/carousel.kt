package app.nubrick.nubrick.component.renderer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.nubrick.nubrick.component.provider.data.DataContext
import app.nubrick.nubrick.component.provider.data.NestedDataProvider
import app.nubrick.nubrick.schema.FlexDirection
import app.nubrick.nubrick.schema.UICollectionBlock
import app.nubrick.nubrick.template.variableByPath
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

@Composable
internal fun Modifier.collectionItemSize(
    block: UICollectionBlock,
    direction: FlexDirection,
    fillsMainAxis: Boolean,
): Modifier {
    return if (fillsMainAxis && direction == FlexDirection.ROW) {
        this
            .height((block.data?.itemHeight ?: 0).coerceAtLeast(0).dp)
            .fillMaxWidth()
    } else if (fillsMainAxis) {
        this
            .width((block.data?.itemWidth ?: 0).coerceAtLeast(0).dp)
            .fillMaxHeight()
    } else {
        this.size(DpSize(
            (block.data?.itemWidth ?: 0).coerceAtLeast(0).dp,
            (block.data?.itemHeight ?: 0).coerceAtLeast(0).dp,
        ))
    }
}

@Composable
internal fun Carousel(block: UICollectionBlock, modifier: Modifier = Modifier) {
    val dataState = DataContext.state
    val reference = block.data?.reference
    var children = block.data?.children ?: emptyList()
    var arrayData: JsonArray? = null
    if (reference != null) {
        val data = variableByPath(reference, dataState.data)
        if (data is JsonArray && children.isNotEmpty()) {
            arrayData = data.jsonArray
            children = arrayData.map { children[0] }
        }
    }

    val direction: FlexDirection = block.data?.direction ?: FlexDirection.ROW
    val fillsMainAxis = if (direction == FlexDirection.ROW) {
        block.data?.fullItemWidth == true
    } else {
        block.data?.fullItemHeight == true
    }
    val state = rememberPagerState {
        children.size
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.currentPage, children.size) {
        if (children.isEmpty()) return@LaunchedEffect
        if (fillsMainAxis && block.data?.autoScroll == true) {
            delay((block.data?.autoScrollInterval?.toLong() ?: 3) * 1000)
            scope.launch {
                if (!state.isScrollInProgress) {
                    state.animateScrollToPage((state.currentPage + 1) % children.size)
                }
            }
        }
    }

    if (children.isEmpty()) {
        return
    }

    val padding = parseFramePadding(block.data?.frame)
    val gap = (block.data?.gap ?: 0).coerceAtLeast(0).dp
    val itemWidth = (block.data?.itemWidth ?: 0).coerceAtLeast(0)
    val itemHeight = (block.data?.itemHeight ?: 0).coerceAtLeast(0)
    val size = DpSize(itemWidth.dp, itemHeight.dp)
    // Collections do not support borders in the editor, so ignore any frame border values.
    val collectionModifier = modifier.frameSize(block.data?.frame, includeBorder = false)
    if (direction == FlexDirection.ROW) {
        val crossHeight = block.data?.frame?.height?.takeIf { it > 0 } ?: run {
            val value = (block.data?.frame?.paddingTop ?: 0).coerceAtLeast(0).toLong() +
                (block.data?.frame?.paddingBottom ?: 0).coerceAtLeast(0) + itemHeight
            value.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return
        }
        HorizontalPager(
            contentPadding = padding,
            pageSpacing = gap,
            state = state,
            pageSize = if (fillsMainAxis) PageSize.Fill else PageSize.Fixed(size.width.coerceAtLeast(1.dp)),
            modifier = collectionModifier
                .fillMaxWidth()
                .height(crossHeight.dp)
        ) {
            Box(
                modifier = Modifier.collectionItemSize(block, direction, fillsMainAxis),
                contentAlignment = Alignment.TopCenter,
            ) {
                NestedDataProvider(data = if (arrayData != null) arrayData[it] else dataState.data) {
                    Block(block = children[it])
                }
            }
        }
    } else {
        val crossWidth = block.data?.frame?.width?.takeIf { it > 0 } ?: run {
            val value = (block.data?.frame?.paddingLeft ?: 0).coerceAtLeast(0).toLong() +
                (block.data?.frame?.paddingRight ?: 0).coerceAtLeast(0) + itemWidth
            value.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return
        }
        VerticalPager(
            contentPadding = padding,
            pageSpacing = gap,
            state = state,
            pageSize = if (fillsMainAxis) PageSize.Fill else PageSize.Fixed(size.height.coerceAtLeast(1.dp)),
            modifier = collectionModifier
                .fillMaxHeight()
                .width(crossWidth.dp)
        ) {
            Box(
                modifier = Modifier.collectionItemSize(block, direction, fillsMainAxis),
                contentAlignment = Alignment.CenterStart,
            ) {
                NestedDataProvider(data = if (arrayData != null) arrayData[it] else dataState.data) {
                    Block(block = children[it])
                }
            }
        }
    }
}
