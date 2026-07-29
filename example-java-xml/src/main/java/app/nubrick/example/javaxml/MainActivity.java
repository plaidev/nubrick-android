package app.nubrick.example.javaxml;

import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import app.nubrick.nubrick.view.NubrickEmbeddingView;

public final class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        View appRoot = findViewById(R.id.app_root);
        applySystemBarInsets(appRoot);

        NubrickEmbeddingView embeddingView = findViewById(R.id.sdk_embedding);
        embeddingView.setOnSizeChangeListener((width, height) ->
                Log.d("NubrickExample", "width=" + width + ", height=" + height)
        );
    }

    private static void applySystemBarInsets(View root) {
        int initialLeft = root.getPaddingLeft();
        int initialTop = root.getPaddingTop();
        int initialRight = root.getPaddingRight();
        int initialBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets safeArea = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout()
            );
            view.setPadding(
                    initialLeft + safeArea.left,
                    initialTop + safeArea.top,
                    initialRight + safeArea.right,
                    initialBottom + safeArea.bottom
            );
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
