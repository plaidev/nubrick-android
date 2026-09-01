package app.nubrick.nubrick.component.renderer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.disk.DiskCache
import java.io.File

private const val IMAGE_DISK_CACHE_DIR = "nubrick/images"
private const val IMAGE_DISK_CACHE_MAX_BYTES = 50L * 1024 * 1024

/**
 * SDK-scoped Coil loader with a disk cache for experiment images.
 * Freshness follows HTTP cache headers
 */
internal object NubrickImageLoader {
    private val lock = Any()
    @Volatile private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        instance?.let { return it }
        return synchronized(lock) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .diskCache {
                    val cacheDir = context.applicationContext.cacheDir ?: return@diskCache null
                    val directory = File(cacheDir, IMAGE_DISK_CACHE_DIR)
                    if (!directory.isDirectory && !directory.mkdirs()) {
                        null
                    } else {
                        DiskCache.Builder()
                            .directory(directory)
                            .maxSizeBytes(IMAGE_DISK_CACHE_MAX_BYTES)
                            .build()
                    }
                }
                .build()
                .also { instance = it }
        }
    }

    fun shutdown() {
        synchronized(lock) {
            instance?.shutdown()
            instance = null
        }
    }
}

@Composable
internal fun rememberNubrickImageLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) { NubrickImageLoader.get(context) }
}
