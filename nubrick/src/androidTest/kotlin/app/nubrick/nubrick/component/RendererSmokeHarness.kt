package app.nubrick.nubrick.component

import androidx.compose.runtime.Composable
import app.nubrick.nubrick.component.provider.container.ContainerProvider
import app.nubrick.nubrick.component.provider.data.PageDataProvider
import app.nubrick.nubrick.component.provider.event.EventListenerProvider
import app.nubrick.nubrick.component.provider.pageblock.PageBlockData
import app.nubrick.nubrick.component.provider.pageblock.PageBlockProvider
import app.nubrick.nubrick.data.NotFoundException
import app.nubrick.nubrick.schema.UIPageBlock

@Composable
internal fun RendererSmokeHarness(content: @Composable () -> Unit) {
    ContainerProvider(FakeContainer(Result.failure(NotFoundException()))) {
        PageBlockProvider(PageBlockData(block = UIPageBlock(id = "page"))) {
            PageDataProvider(request = null) {
                EventListenerProvider(listener = { _, _ -> }) {
                    content()
                }
            }
        }
    }
}
