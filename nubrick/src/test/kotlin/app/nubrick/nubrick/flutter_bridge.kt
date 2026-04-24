package app.nubrick.nubrick

import app.nubrick.nubrick.schema.PageKind
import app.nubrick.nubrick.schema.TriggerSetting
import app.nubrick.nubrick.schema.UIBlockAction
import app.nubrick.nubrick.schema.UIPageBlock
import app.nubrick.nubrick.schema.UIPageBlockData
import app.nubrick.nubrick.schema.UIRootBlock
import app.nubrick.nubrick.schema.UIRootBlockData
import org.junit.Assert.assertEquals
import org.junit.Test

class FlutterBridgeUnitTest {
    @Test
    fun computeInitialSizeMapsZeroToFill() {
        val embedding = UIRootBlock(
            id = "root",
            data = UIRootBlockData(
                pages = listOf(
                    UIPageBlock(
                        id = "trigger",
                        data = UIPageBlockData(
                            kind = PageKind.TRIGGER,
                            triggerSetting = TriggerSetting(
                                onTrigger = UIBlockAction(destinationPageId = "component")
                            )
                        )
                    ),
                    UIPageBlock(
                        id = "component",
                        data = UIPageBlockData(
                            kind = PageKind.COMPONENT,
                            frameWidth = 0,
                            frameHeight = null
                        )
                    )
                )
            )
        )

        val size = FlutterBridge.computeInitialSize(embedding)

        assertEquals(Pair(NubrickSize.Fill, NubrickSize.Fill), size)
    }

    @Test
    fun computeInitialSizeMapsNonZeroToFixed() {
        val embedding = UIRootBlock(
            id = "root",
            data = UIRootBlockData(
                pages = listOf(
                    UIPageBlock(
                        id = "trigger",
                        data = UIPageBlockData(
                            kind = PageKind.TRIGGER,
                            triggerSetting = TriggerSetting(
                                onTrigger = UIBlockAction(destinationPageId = "component")
                            )
                        )
                    ),
                    UIPageBlock(
                        id = "component",
                        data = UIPageBlockData(
                            kind = PageKind.COMPONENT,
                            frameWidth = 180,
                            frameHeight = 96
                        )
                    )
                )
            )
        )

        val size = FlutterBridge.computeInitialSize(embedding)

        assertEquals(Pair(NubrickSize.Fixed(180), NubrickSize.Fixed(96)), size)
    }
}
