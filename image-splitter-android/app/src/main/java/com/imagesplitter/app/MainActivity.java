package com.imagesplitter.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Hosts the bundled image splitter page (assets/index.html) in a WebView and
 * provides the two things a WebView can't do on its own: picking an image
 * from the device and saving the generated pieces / ZIP to Downloads.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_PICK_IMAGE = 1;
    private static final int REQUEST_STORAGE_PERMISSION = 2;
    private static final String SAVE_FOLDER = "ImageSplitter";

    private WebView webView;
    private ValueCallback<Uri[]> pendingFileCallback;

    // A save that is waiting for the storage permission (Android 9 and older)
    private String pendingSaveName;
    private String pendingSaveMime;
    private byte[] pendingSaveData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#030712"));
        setContentView(webView);

        // Keep the page clear of the status / navigation bars (edge-to-edge on Android 15+)
        webView.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });

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
                try {
                    startActivityForResult(Intent.createChooser(intent, "Select image"), REQUEST_PICK_IMAGE);
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
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            result = new Uri[]{data.getData()};
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
        if (requestCode != REQUEST_STORAGE_PERMISSION || pendingSaveData == null) return;

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            saveToDownloads(pendingSaveName, pendingSaveMime, pendingSaveData);
        } else {
            toast("Storage permission is needed to save files");
        }
        pendingSaveName = null;
        pendingSaveMime = null;
        pendingSaveData = null;
    }

    /** Exposed to the page as window.AndroidBridge. */
    private class AndroidBridge {
        @JavascriptInterface
        public void saveBase64(String fileName, String mimeType, String base64) {
            final byte[] bytes;
            try {
                bytes = Base64.decode(base64, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                toast("Could not save file: invalid data");
                return;
            }
            final String name = sanitizeFileName(fileName);
            runOnUiThread(() -> requestSave(name, mimeType, bytes));
        }
    }

    private void requestSave(String name, String mime, byte[] data) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingSaveName = name;
            pendingSaveMime = mime;
            pendingSaveData = data;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION);
            return;
        }
        saveToDownloads(name, mime, data);
    }

    private void saveToDownloads(String name, String mime, byte[] data) {
        try {
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
            toast("Saved to Downloads/" + SAVE_FOLDER + "/" + name);
        } catch (Exception e) {
            toast("Could not save file: " + e.getMessage());
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
