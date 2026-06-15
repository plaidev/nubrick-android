package app.nubrick.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.nubrick.example.ui.theme.NubrickAndroidTheme
import app.nubrick.nubrick.NubrickProvider
import app.nubrick.nubrick.NubrickSDK

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NubrickAndroidTheme {
                NubrickProvider {
                    // A surface container using the 'background' color from the theme
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        var counter by remember { mutableIntStateOf(0) }
                        var plan by remember { mutableStateOf("pro") }

                        val arguments = mapOf(
                            "counter" to counter,
                        )

                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top,
                        ) {
                            Spacer(modifier = Modifier.height(48.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Button(onClick = { counter++ }) {
                                    Text("arg counter: $counter")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = {
                                    plan = if (plan == "pro") "free" else "pro"
                                    NubrickSDK.setUserProperty("plan", plan)
                                }) {
                                    Text("user plan: $plan")
                                }
                            }
                            NubrickSDK.Embedding(
                                "HEADER_INFORMATION",
                                arguments = arguments,
                                modifier = Modifier.height(100f.dp),
                            )
                            NubrickSDK.Embedding(
                                "TOP_COMPONENT",
                                arguments = arguments,
                                modifier = Modifier.height(270f.dp),
                            )
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
