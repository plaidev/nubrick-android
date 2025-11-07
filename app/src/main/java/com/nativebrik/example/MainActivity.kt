package com.nativebrik.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nativebrik.example.ui.theme.NubrickAndroidTheme
import com.nativebrik.sdk.Config
import com.nativebrik.sdk.Nubrick
import com.nativebrik.sdk.NubrickClient
import com.nativebrik.sdk.NubrickProvider

class MainActivity : ComponentActivity() {
    private lateinit var nubrick: NubrickClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.nubrick = NubrickClient(
            config = Config(projectId = "cgv3p3223akg00fod19g"),
            context = this.applicationContext,
        )

        setContent {
            NubrickAndroidTheme {
                NubrickProvider(client = nubrick) {
                    // A surface container using the 'background' color from the theme
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                            Nubrick.client.experiment.Embedding(
                                "HEADER_INFORMATION",
                                arguments = emptyMap<String, String>(),
                                modifier = Modifier.height(100f.dp),
                            )
                            Nubrick.client.experiment.Embedding(
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

    override fun onDestroy() {
        this.nubrick.close()
        super.onDestroy()
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
