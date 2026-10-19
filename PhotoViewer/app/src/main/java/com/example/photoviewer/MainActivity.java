package com.example.photoviewer;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String SITE_URL = "http://10.0.2.2:8000";
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final PhotoBlogClient photoBlogClient = new PhotoBlogClient(SITE_URL);
    private RecyclerView recyclerView;
    private TextView textView;
    private Button syncButton;
    private Button uploadButton;
    private boolean networkBusy;
    private Runnable pendingNetworkAction;
    private AlertDialog uploadDialog;
    private Uri selectedImageUri;
    private TextView selectedImageName;

    private final ActivityResultLauncher<String> localNetworkPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                Runnable action = pendingNetworkAction;
                pendingNetworkAction = null;
                if (granted && action != null) {
                    action.run();
                } else if (!granted) {
                    textView.setText(R.string.local_network_permission_denied);
                    Toast.makeText(this, R.string.local_network_permission_denied,
                            Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String[]> imagePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null || uploadDialog == null || !uploadDialog.isShowing()) return;
                try {
                    String filename = MultipartBody.displayName(getContentResolver(), uri);
                    selectedImageUri = uri;
                    selectedImageName.setText(filename);
                } catch (SecurityException error) {
                    Toast.makeText(this, R.string.upload_image_permission_denied,
                            Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        textView = findViewById(R.id.textView);
        syncButton = findViewById(R.id.btn_load);
        uploadButton = findViewById(R.id.btn_save);
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(new ImageAdapter(new ArrayList<>()));
        syncButton.setOnClickListener(this::onClickDownload);
        uploadButton.setOnClickListener(this::onClickUpload);
    }

    private void runWithLocalNetworkPermission(Runnable action) {
        if (Build.VERSION.SDK_INT >= 37
                && SITE_URL.startsWith("http://10.0.2.2:")
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK)
                        != PackageManager.PERMISSION_GRANTED) {
            pendingNetworkAction = action;
            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK);
            return;
        }
        action.run();
    }

    public void onClickDownload(View view) {
        if (!networkBusy) runWithLocalNetworkPermission(this::startDownload);
    }

    private void startDownload() {
        if (networkBusy || isFinishing()) return;
        setNetworkBusy(true);
        textView.setText(R.string.sync_loading);
        networkExecutor.execute(() -> {
            try {
                List<Bitmap> images = photoBlogClient.downloadImages();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    recyclerView.setAdapter(new ImageAdapter(images));
                    textView.setText(images.isEmpty()
                            ? getString(R.string.sync_empty)
                            : getString(R.string.sync_success, images.size()));
                    setNetworkBusy(false);
                });
            } catch (IOException | JSONException error) {
                Log.e("PhotoViewer", "Image synchronization failed", error);
                String message;
                if (error instanceof SocketTimeoutException) {
                    message = getString(R.string.sync_timeout);
                } else if (error instanceof ConnectException) {
                    message = getString(R.string.sync_connection_failed);
                } else if (error instanceof JSONException) {
                    message = getString(R.string.sync_invalid_json);
                } else {
                    message = getString(R.string.sync_failed, error.getMessage());
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    textView.setText(message);
                    setNetworkBusy(false);
                });
            }
        });
    }

    public void onClickUpload(View view) {
        if (networkBusy || (uploadDialog != null && uploadDialog.isShowing())) return;
        View form = getLayoutInflater().inflate(R.layout.dialog_upload, null);
        EditText authorInput = form.findViewById(R.id.upload_author);
        EditText titleInput = form.findViewById(R.id.upload_title);
        EditText textInput = form.findViewById(R.id.upload_text);
        selectedImageName = form.findViewById(R.id.selected_image_name);
        selectedImageUri = null;
        form.findViewById(R.id.btn_choose_image).setOnClickListener(button ->
                imagePicker.launch(new String[]{"image/*"}));
        uploadDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.upload_image)
                .setView(form)
                .setPositiveButton(R.string.submit_post, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        uploadDialog.setOnDismissListener(dialog -> {
            uploadDialog = null;
            selectedImageUri = null;
            selectedImageName = null;
        });
        uploadDialog.setOnShowListener(dialog ->
                uploadDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                    String title = titleInput.getText().toString().trim();
                    String text = textInput.getText().toString().trim();
                    if (title.isEmpty()) {
                        titleInput.setError(getString(R.string.required_field));
                        return;
                    }
                    if (text.isEmpty()) {
                        textInput.setError(getString(R.string.required_field));
                        return;
                    }
                    int authorId;
                    try {
                        authorId = Integer.parseInt(authorInput.getText().toString());
                        if (authorId <= 0) throw new NumberFormatException();
                    } catch (NumberFormatException error) {
                        authorInput.setError(getString(R.string.invalid_author_id));
                        return;
                    }
                    Uri imageUri = selectedImageUri;
                    if (imageUri == null) {
                        Toast.makeText(this, R.string.choose_image_first,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runWithLocalNetworkPermission(() -> {
                        if (uploadDialog != null && uploadDialog.isShowing()) {
                            startUpload(imageUri, authorId, title, text);
                        }
                    });
                }));
        uploadDialog.show();
    }

    private void startUpload(Uri imageUri, int authorId, String title, String text) {
        if (networkBusy || isFinishing()) return;
        setNetworkBusy(true);
        textView.setText(R.string.upload_loading);
        networkExecutor.execute(() -> {
            try {
                JSONObject created = photoBlogClient.uploadPost(getApplicationContext(),
                        imageUri, authorId, title, text);
                String savedTitle = created.getString("title");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setNetworkBusy(false);
                    if (uploadDialog != null) uploadDialog.dismiss();
                    textView.setText(getString(R.string.upload_success, savedTitle));
                });
            } catch (IOException | JSONException | SecurityException error) {
                Log.e("PhotoViewer", "Post upload failed", error);
                String message = uploadErrorMessage(error);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    textView.setText(message);
                    setNetworkBusy(false);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String uploadErrorMessage(Exception error) {
        if (error instanceof PhotoBlogClient.ApiException) {
            PhotoBlogClient.ApiException apiError = (PhotoBlogClient.ApiException) error;
            try {
                String details = new JSONObject(apiError.getResponseBody()).toString();
                return getString(R.string.upload_rejected, apiError.getStatus(), details);
            } catch (JSONException ignored) {
                return getString(R.string.upload_failed, apiError.getMessage());
            }
        }
        if (error instanceof SecurityException) {
            return getString(R.string.upload_image_permission_denied);
        }
        if (error instanceof JSONException || error instanceof SocketTimeoutException) {
            return getString(R.string.upload_result_uncertain);
        }
        return getString(R.string.upload_failed, error.getMessage());
    }

    private void setNetworkBusy(boolean busy) {
        networkBusy = busy;
        syncButton.setEnabled(!busy);
        uploadButton.setEnabled(!busy);
        if (uploadDialog != null && uploadDialog.isShowing()) {
            uploadDialog.setCancelable(!busy);
            uploadDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
            uploadDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
            uploadDialog.findViewById(R.id.btn_choose_image).setEnabled(!busy);
        }
    }

    @Override
    protected void onDestroy() {
        pendingNetworkAction = null;
        if (uploadDialog != null) uploadDialog.dismiss();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
