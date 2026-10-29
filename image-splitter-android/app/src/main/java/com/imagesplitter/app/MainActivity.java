package com.imagesplitter.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Hosts the bundled image splitter/joiner page (assets/index.html) in a WebView
 * and provides the things a WebView can't do on its own: picking images from
 * the device and saving the results to Downloads.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_PICK_IMAGE = 1;
    private static final int REQUEST_STORAGE_PERMISSION = 2;
    private static final String SAVE_FOLDER = "ImageSplitter";

    private WebView webView;
    private ValueCallback<Uri[]> pendingFileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Draw edge-to-edge on every Android version and pad the content ourselves,
        // so the page never sits under the status bar, navigation buttons or keyboard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        // A WebView ignores its own padding, so the insets go on a container around it
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.parseColor("#111827"));
        container.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#030712"));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        container.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(container);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setSupportZoom(false);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(intent, "Select images"), REQUEST_PICK_IMAGE);
                } catch (Exception e) {
                    pendingFileCallback = null;
                    toast("No app available to pick images");
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_IMAGE || pendingFileCallback == null) return;

        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    result[i] = clip.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        pendingFileCallback.onReceiveValue(result);
        pendingFileCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_STORAGE_PERMISSION) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toast("Storage access allowed. Tap save again.");
        } else {
            toast("Storage permission is needed to save images");
        }
    }

    /** Exposed to the page as window.AndroidBridge. */
    private class AndroidBridge {
        /** Returns "ok", "permission" (the user was asked; save again) or an error message. */
        @JavascriptInterface
        public String saveBase64(String fileName, String mimeType, String base64) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION));
                return "permission";
            }
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                saveToDownloads(sanitizeFileName(fileName), mimeType, bytes);
                return "ok";
            } catch (Exception e) {
                return e.getMessage() != null ? e.getMessage() : e.toString();
            }
        }
    }

    private void saveToDownloads(String name, String mime, byte[] data) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + SAVE_FOLDER);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore insert failed");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot open output stream");
                out.write(data);
            }
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), SAVE_FOLDER);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create folder");
            try (OutputStream out = new FileOutputStream(new File(dir, name))) {
                out.write(data);
            }
        }
    }

    private static String sanitizeFileName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return cleaned.isEmpty() ? "image_" + System.currentTimeMillis() : cleaned;
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}
