package app.nubrick.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.nubrick.example.ui.theme.NubrickAndroidTheme
import app.nubrick.nubrick.Config
import app.nubrick.nubrick.NubrickProvider
import app.nubrick.nubrick.NubrickSDK

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NubrickSDK.initialize(
            context = this.applicationContext,
            config = Config(projectId = "cgv3p3223akg00fod19g"),
        )

        setContent {
            NubrickAndroidTheme {
                NubrickProvider {
                    // A surface container using the 'background' color from the theme
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top,
                        ) {
                            NubrickSDK.Embedding(
                                "HEADER_INFORMATION",
                                arguments = emptyMap<String, String>(),
                                modifier = Modifier.height(100f.dp),
                            )
                            NubrickSDK.Embedding(
                                "TOP_COMPONENT",
                                arguments = emptyMap<String, String>(),
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
