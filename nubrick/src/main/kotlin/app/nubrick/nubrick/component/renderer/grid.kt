package app.nubrick.nubrick.component.renderer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.nubrick.nubrick.component.provider.data.DataContext
import app.nubrick.nubrick.component.provider.data.NestedDataProvider
import app.nubrick.nubrick.schema.FlexDirection
import app.nubrick.nubrick.schema.UICollectionBlock
import app.nubrick.nubrick.template.variableByPath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

@Composable
internal fun Grid(block: UICollectionBlock, modifier: Modifier = Modifier) {
    val state = rememberLazyGridState(0, 0)
    val padding = parseFramePadding(block.data?.frame)
    val gridSize = block.data?.gridSize ?: 1
    val gap = (block.data?.gap ?: 0).dp
    val direction: FlexDirection = block.data?.direction ?: FlexDirection.ROW
    val size = DpSize((block.data?.itemWidth ?: 0).dp, (block.data?.itemHeight ?: 0).dp)
    val calculatedHeight = (block.data?.frame?.paddingTop ?: 0) + (block.data?.frame?.paddingBottom ?: 0) + (gridSize - 1) * (block.data?.gap ?: 0) + (gridSize * (block.data?.itemHeight ?: 0))
    val calculatedWidth = (block.data?.frame?.paddingLeft ?: 0) + (block.data?.frame?.paddingRight ?: 0) + (gridSize - 1) * (block.data?.gap ?: 0) + (gridSize * (block.data?.itemWidth ?: 0))
    val gridHeight = block.data?.frame?.height?.takeIf { it > 0 } ?: calculatedHeight
    val gridWidth = block.data?.frame?.width?.takeIf { it > 0 } ?: calculatedWidth
    // Collections do not support borders in the editor, so ignore any frame border values.
    val collectionModifier = modifier.frameSize(block.data?.frame, includeBorder = false)

    val dataState = DataContext.state
    val reference = block.data?.reference
    var children = block.data?.children ?: emptyList()
    var arrayData: JsonArray? = null
    if (reference != null) {
        val data = variableByPath(reference, dataState.data)
        if (data is JsonArray && children.isNotEmpty()) {
            arrayData = data.jsonArray
            children = arrayData.map { children[0] }
        } else {
            children = emptyList()
        }
    }

    if (direction == FlexDirection.ROW) {
        LazyHorizontalGrid(
            contentPadding = padding,
            rows = GridCells.FixedSize(size.height.coerceAtLeast(1.dp)),
            state = state,
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = collectionModifier
                .fillMaxWidth()
                .height(gridHeight.dp)
        ) {
            items(children.size) {
                Box(Modifier.size(size)) {
                    NestedDataProvider(data = if (arrayData != null) arrayData[it] else dataState.data) {
                        Block(block = children[it])
                    }
                }
            }
        }
    } else {
        LazyVerticalGrid(
            contentPadding = padding,
            columns = GridCells.FixedSize(size.width.coerceAtLeast(1.dp)),
            state = state,
            verticalArrangement = Arrangement.spacedBy(gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
            modifier = collectionModifier
                .fillMaxHeight()
                .width(gridWidth.dp)
        ) {
            items(children.size) {
                Box(Modifier.size(size)) {
                    NestedDataProvider(data = if (arrayData != null) arrayData[it] else dataState.data) {
                        Block(block = children[it])
                    }
                }
            }
        }
    }
}
