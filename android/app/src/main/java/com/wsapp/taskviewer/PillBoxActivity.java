package com.wsapp.taskviewer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.wsapp.taskviewer.util.ConnectivityLiveData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class PillBoxActivity extends AppCompatActivity {

    private ImageView imagePreview;
    private TextView imagePlaceholder, resultsText, resultsTitle;
    private MaterialButton btnCamera, btnGallery, btnAnalyze;
    private CardView resultsCard;
    private ProgressBar progressBar;

    private Uri photoUri;
    private Bitmap selectedBitmap;
    private boolean online;

    private final ActivityResultLauncher<Uri> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (success && photoUri != null) {
                    loadImage(photoUri);
                }
            });

    private final ActivityResultLauncher<String> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    loadImage(uri);
                }
            });

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    launchCamera();
                } else {
                    Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pill_box);

        setTitle("💊 Pill Box Checker");

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        imagePreview = findViewById(R.id.imagePreview);
        imagePlaceholder = findViewById(R.id.imagePlaceholder);
        resultsText = findViewById(R.id.resultsText);
        resultsTitle = findViewById(R.id.resultsTitle);
        resultsCard = findViewById(R.id.resultsCard);
        progressBar = findViewById(R.id.progressBar);
        btnCamera = findViewById(R.id.btnCamera);
        btnGallery = findViewById(R.id.btnGallery);
        btnAnalyze = findViewById(R.id.btnAnalyze);
        ConnectivityLiveData.get(this).observe(this, isOnline -> {
            online = Boolean.TRUE.equals(isOnline);
            btnAnalyze.setEnabled(selectedBitmap != null && online);
            if (!online && selectedBitmap != null) {
                resultsCard.setVisibility(View.VISIBLE);
                resultsTitle.setText("Offline");
                resultsText.setText("Reconnect to the internet before analyzing this image.");
            }
        });

        btnCamera.setOnClickListener(v -> checkCameraPermission());
        btnGallery.setOnClickListener(v -> galleryLauncher.launch("image/*"));
        btnAnalyze.setOnClickListener(v -> analyzeImage());
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchCamera() {
        File photoFile = new File(getCacheDir(), "pill_box_" + System.currentTimeMillis() + ".jpg");
        photoUri = FileProvider.getUriForFile(this,
                getApplicationContext().getPackageName() + ".fileprovider", photoFile);
        cameraLauncher.launch(photoUri);
    }

    private void loadImage(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            selectedBitmap = BitmapFactory.decodeStream(is);
            if (is != null) is.close();
            if (selectedBitmap == null) {
                throw new IOException("The selected file is not a readable image");
            }

            imagePreview.setImageBitmap(selectedBitmap);
            imagePlaceholder.setVisibility(View.GONE);
            btnAnalyze.setEnabled(online);
            resultsCard.setVisibility(View.GONE);
        } catch (IOException e) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
        }
    }

    private String bitmapToBase64(Bitmap bitmap) {
        // Resize if too large
        int maxSize = 2048;
        float scale = Math.min((float) maxSize / bitmap.getWidth(), (float) maxSize / bitmap.getHeight());
        if (scale < 1) {
            bitmap = Bitmap.createScaledBitmap(bitmap,
                    (int) (bitmap.getWidth() * scale),
                    (int) (bitmap.getHeight() * scale), true);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos);
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }

    private void analyzeImage() {
        if (selectedBitmap == null) return;
        if (!online) {
            resultsCard.setVisibility(View.VISIBLE);
            resultsTitle.setText("Offline");
            resultsText.setText("Reconnect to the internet before analyzing this image.");
            return;
        }

        btnAnalyze.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        resultsCard.setVisibility(View.GONE);

        String base64Image = bitmapToBase64(selectedBitmap);

        new Thread(() -> {
            try {
                String result = callGeminiApi(base64Image);
                runOnUiThread(() -> showResults(result));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    resultsCard.setVisibility(View.VISIBLE);
                    resultsTitle.setText("Analysis failed");
                    resultsText.setText(e.getMessage() == null
                            ? "The AI service returned an unknown error."
                            : e.getMessage());
                    btnAnalyze.setEnabled(selectedBitmap != null && online);
                    progressBar.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private static final String[] MODELS = {
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash",
        "gemini-2.0-flash-lite"
    };

    private String callGeminiApi(String base64Image) throws Exception {
        String apiKey = BuildConfig.GEMINI_API_KEY;
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Gemini is not configured on this build. Add the API key or use the server API.");
        }

        String prompt = "This is a round pill organizer with 7 compartments in a circle. "
                + "Look carefully at each individual compartment. "
                + "Which ones have pills and which are empty? "
                + "List each one with ✅ for full or ❌ for empty. "
                + "End with a count: X full, Y empty.";

        JSONObject requestBody = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject content = new JSONObject();
        JSONArray parts = new JSONArray();

        JSONObject textPart = new JSONObject();
        textPart.put("text", prompt);
        parts.put(textPart);

        JSONObject imagePart = new JSONObject();
        JSONObject inlineData = new JSONObject();
        inlineData.put("mime_type", "image/jpeg");
        inlineData.put("data", base64Image);
        imagePart.put("inline_data", inlineData);
        parts.put(imagePart);

        content.put("parts", parts);
        contents.put(content);
        requestBody.put("contents", contents);

        byte[] bodyBytes = requestBody.toString().getBytes(StandardCharsets.UTF_8);
        Exception lastError = null;

        for (String model : MODELS) {
            String urlStr = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;

            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(60000);

                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(bodyBytes);
                    }

                    int responseCode = conn.getResponseCode();
                    InputStream is = responseCode >= 400
                            ? conn.getErrorStream()
                            : conn.getInputStream();
                    if (is == null) {
                        throw new IOException("The AI service returned an empty HTTP response.");
                    }
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    if (responseCode == 503 || responseCode == 429) {
                        lastError = new Exception(model + " returned " + responseCode);
                        Thread.sleep(2000);
                        continue;
                    }

                    if (responseCode >= 400) {
                        lastError = new Exception(model + " error " + responseCode + ": " + response);
                        break; // try next model
                    }

                    JSONObject json = new JSONObject(response.toString());
                    if (!json.has("candidates") || json.getJSONArray("candidates").length() == 0) {
                        throw new IOException("The AI service returned no analysis result.");
                    }
                    String text = json.getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text");
                    if (text == null || text.trim().isEmpty()) {
                        throw new IOException("The AI service returned an empty analysis.");
                    }
                    return text;
                } catch (IOException e) {
                    lastError = e;
                    Thread.sleep(2000);
                } catch (org.json.JSONException e) {
                    throw new IOException("The AI service returned an unexpected response format.", e);
                }
            }
        }

        throw lastError != null ? lastError : new Exception("All models failed");
    }

    private void showResults(String result) {
        progressBar.setVisibility(View.GONE);
        btnAnalyze.setEnabled(true);
        resultsCard.setVisibility(View.VISIBLE);
        resultsTitle.setText("💊 Analysis Results");
        resultsText.setText(result);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
