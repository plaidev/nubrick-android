package io.nubrick.e2e

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nubrick.e2e.ui.theme.NubrickAndroidTheme
import io.nubrick.nubrick.Config
import io.nubrick.nubrick.NubrickProvider
import io.nubrick.nubrick.NubrickSDK
import io.nubrick.nubrick.component.EmbeddingLoadingState
import io.nubrick.nubrick.remoteconfig.RemoteConfigLoadingState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NubrickSDK.initialize(
            context = this.applicationContext,
            config = Config(projectId = "ckto7v223akg00ag3jsg"),
        )

        setContent {
            NubrickAndroidTheme {
                // A surface container using the 'background' color from the theme
                NubrickProvider {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // embedding
                            NubrickSDK.Embedding(
                                "EMBEDDING_FOR_E2E",
                                modifier = Modifier.height(240f.dp),
                                content = {
                                    when (it) {
                                        is EmbeddingLoadingState.Completed -> {
                                            it.view()
                                        }
                                        is EmbeddingLoadingState.Loading -> {
                                            CircularProgressIndicator()
                                        }
                                        else -> {
                                            Text(text = "EMBED IS FAILED")
                                        }
                                    }
                                }
                            )

                            // remote config
                            NubrickSDK.RemoteConfig("REMOTE_CONFIG_FOR_E2E") {
                                when (it) {
                                    is RemoteConfigLoadingState.Completed -> {
                                        Text(text = it.variant.getAsString("message") ?: "")
                                    }
                                    is RemoteConfigLoadingState.Loading -> {
                                        CircularProgressIndicator()
                                    }
                                    else -> {
                                        Text(text = "CONFIG IS FAILED")
                                    }
                                }
                            }

                        }
                    }
                }
            }
        }
    }
}
