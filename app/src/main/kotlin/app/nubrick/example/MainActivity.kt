package app.nubrick.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.nubrick.example.ui.theme.NubrickAndroidTheme
import app.nubrick.nubrick.NubrickEvent
import app.nubrick.nubrick.ExperimentalEventPropertiesApi
import app.nubrick.nubrick.NubrickProvider
import app.nubrick.nubrick.NubrickSDK
import java.util.Date

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalEventPropertiesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NubrickAndroidTheme {
                NubrickProvider {
                    // A surface container using the 'background' color from the theme
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier
                                .systemBarsPadding()
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top,
                        ) {
                            NubrickSDK.Embedding(
                                "HEADER_INFORMATION",
                                arguments = emptyMap<String, String>(),
                            )
                            NubrickSDK.Embedding(
                                "TOP_COMPONENT",
                                arguments = emptyMap<String, String>(),
                            )
                            Button(
                                onClick = {
                                    NubrickSDK.dispatch(
                                        NubrickEvent(
                                            "example_purchase",
                                            mapOf(
                                                "item_id" to "example-item",
                                                "price" to 19.99,
                                                "quantity" to 2,
                                                "is_test" to true,
                                                "sent_at" to Date(),
                                            ),
                                        )
                                    )
                                },
                                modifier = Modifier.padding(16.dp),
                            ) {
                                Text("Send event with properties")
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    NubrickAndroidTheme {
        Greeting("Android")
    }
}
