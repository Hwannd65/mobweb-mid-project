package com.example.photoviewer;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Spinner;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    static final String SITE_URL = BuildConfig.PHOTO_BLOG_URL;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private PhotoBlogClient photoBlogClient;
    private RecyclerView recyclerView;
    private ImageAdapter imageAdapter;
    private String displayedUsername = "";
    private TextView textView;
    private Button syncButton;
    private Button uploadButton;
    private Button searchButton;
    private Button clearSearchButton;
    private EditText searchKeyword;
    private Spinner searchField;
    private Spinner sortOrder;
    private Button sortButton;
    private static final String[] SORT_ORDERS = {"newest", "oldest", "recommendations", "comments"};
    private static final String[] SEARCH_FIELDS = {"title", "author"};
    private Button accountButton;
    private TextView accountStatus;
    private AlertDialog loginDialog;
    private AlertDialog registerDialog;
    private boolean networkBusy;
    private Runnable pendingNetworkAction;
    private AlertDialog uploadDialog;
    private Uri selectedImageUri;
    private TextView selectedImageName;
    private int pendingCommentsPostId;
    private String pendingCommentsTitle;

    private void clearPendingComments() { pendingCommentsPostId = 0; pendingCommentsTitle = null; }

    private final ActivityResultLauncher<Intent> commentsScreen = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK) return;
                Intent data = result.getData();
                if (data != null && data.getBooleanExtra("request_login", false)) {
                    pendingCommentsPostId = data.getIntExtra("post_id", 0);
                    pendingCommentsTitle = data.getStringExtra("post_title");
                    showLogin();
                } else if (!networkBusy) {
                    startDownload();
                }
            });

    private void launchComments(int id, String title) {
        Intent intent = new Intent(this, CommentsActivity.class);
        intent.putExtra("post_id", id);
        intent.putExtra("post_title", title);
        commentsScreen.launch(intent);
    }

    private void onCommentsClick(PhotoPost post) {
        if (!networkBusy && post.commentsSupported) {
            runWithLocalNetworkPermission(() -> launchComments(post.id, post.title));
        }
    }

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
        PhotoViewerApplication app = (PhotoViewerApplication) getApplication();
        photoBlogClient = app.client();
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
        accountButton = findViewById(R.id.btn_account);
        accountStatus = findViewById(R.id.account_status);
        accountButton.setOnClickListener(view -> {
            if (networkBusy) return;
            if (photoBlogClient.isLoggedIn()) {
                runWithLocalNetworkPermission(this::startLogout);
            } else {
                showLogin();
            }
        });
        updateAccount();
        app.identity().observe(this, ignored -> updateAccount());
        sortOrder = findViewById(R.id.sort_order);
        sortButton = findViewById(R.id.btn_sort);
        sortButton.setOnClickListener(this::onClickDownload);
        searchKeyword = findViewById(R.id.search_keyword);
        searchField = findViewById(R.id.search_field);
        searchButton = findViewById(R.id.btn_search);
        clearSearchButton = findViewById(R.id.btn_search_clear);
        searchButton.setOnClickListener(this::onClickDownload);
        clearSearchButton.setOnClickListener(view -> {
            searchKeyword.setText("");
            searchField.setSelection(0);
            onClickDownload(view);
        });
        searchKeyword.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            onClickDownload(view);
            return true;
        });
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        imageAdapter = new ImageAdapter(this::onRecommendationClick, this::onCommentsClick);
        recyclerView.setAdapter(imageAdapter);
        syncButton.setOnClickListener(this::onClickDownload);
        uploadButton.setOnClickListener(this::onClickUpload);
    }

    private void updateAccount() {
        boolean signedIn = photoBlogClient.isLoggedIn();
        String username = photoBlogClient.getCurrentUsername();
        if (!displayedUsername.equals(username)) {
            displayedUsername = username;
            if (imageAdapter != null) imageAdapter.clearPersonalization();
        }
        accountStatus.setText(signedIn
                ? getString(R.string.account_signed_in, photoBlogClient.getCurrentUsername())
                : getString(R.string.account_guest));
        accountButton.setText(signedIn ? R.string.logout : R.string.login);
    }

    private void showLogin() { showLogin(""); }

    private void showLogin(String initialUsername) {
        if (loginDialog != null) return;
        View form = getLayoutInflater().inflate(R.layout.dialog_login, null);
        EditText username = form.findViewById(R.id.login_username);
        EditText password = form.findViewById(R.id.login_password);
        TextView feedback = form.findViewById(R.id.login_feedback);
        username.setText(initialUsername);
        form.findViewById(R.id.btn_register).setOnClickListener(view -> {
            loginDialog.dismiss();
            showRegister();
        });
        loginDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.login)
                .setView(form)
                .setPositiveButton(R.string.login, null)
                .setNegativeButton(R.string.cancel, (dialog, which) -> clearPendingComments())
                .create();
        loginDialog.setOnCancelListener(dialog -> clearPendingComments());
        loginDialog.setOnDismissListener(dialog -> {
            password.setText("");
            loginDialog = null;
        });
        loginDialog.setOnShowListener(dialog -> loginDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    String name = username.getText().toString().trim();
                    String secret = password.getText().toString();
                    if (name.isEmpty()) {
                        username.setError(getString(R.string.required_field));
                        return;
                    }
                    if (secret.isEmpty()) {
                        password.setError(getString(R.string.required_field));
                        return;
                    }
                    runWithLocalNetworkPermission(() -> {
                        if (loginDialog == null || !loginDialog.isShowing()) return;
                        password.setText("");
                        setNetworkBusy(true);
                        feedback.setText(R.string.login_loading);
                        networkExecutor.execute(() -> {
                            try {
                                photoBlogClient.login(name, secret);
                                runOnUiThread(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    setNetworkBusy(false);
                                    if (loginDialog != null) loginDialog.dismiss();
                                    updateAccount();
                                    textView.setText(R.string.login_success);
                                    if (pendingCommentsPostId > 0) {
                                        int postId = pendingCommentsPostId;
                                        String title = pendingCommentsTitle;
                                        clearPendingComments();
                                        launchComments(postId, title);
                                    } else if (imageAdapter.getItemCount() > 0) startDownload();
                                });
                            } catch (IOException | JSONException error) {
                                runOnUiThread(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    setNetworkBusy(false);
                                    feedback.setText(accountErrorMessage(error));
                                });
                            }
                        });
                    });
                }));
        loginDialog.show();
    }

    private void showRegister() {
        if (registerDialog != null) return;
        View form = getLayoutInflater().inflate(R.layout.dialog_register, null);
        EditText username = form.findViewById(R.id.register_username);
        EditText password = form.findViewById(R.id.register_password);
        EditText confirmation = form.findViewById(R.id.register_confirmation);
        TextView feedback = form.findViewById(R.id.register_feedback);
        registerDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.register)
                .setView(form)
                .setPositiveButton(R.string.register, null)
                .setNegativeButton(R.string.cancel, (dialog, which) -> clearPendingComments())
                .create();
        registerDialog.setOnCancelListener(dialog -> clearPendingComments());
        registerDialog.setOnDismissListener(dialog -> {
            password.setText("");
            confirmation.setText("");
            registerDialog = null;
        });
        registerDialog.setOnShowListener(dialog -> registerDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(button -> {
                    String name = username.getText().toString().trim();
                    String secret = password.getText().toString();
                    String confirm = confirmation.getText().toString();
                    if (name.isEmpty()) {
                        username.setError(getString(R.string.required_field));
                        return;
                    }
                    if (secret.isEmpty()) {
                        password.setError(getString(R.string.required_field));
                        return;
                    }
                    if (!secret.equals(confirm)) {
                        confirmation.setError(getString(R.string.password_mismatch));
                        return;
                    }
                    runWithLocalNetworkPermission(() -> {
                        if (registerDialog == null || !registerDialog.isShowing()) return;
                        password.setText("");
                        confirmation.setText("");
                        setNetworkBusy(true);
                        feedback.setText(R.string.register_loading);
                        networkExecutor.execute(() -> {
                            try {
                                JSONObject created = photoBlogClient.register(name, secret, confirm);
                                String savedName = created.getString("username");
                                runOnUiThread(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    setNetworkBusy(false);
                                    if (registerDialog != null) registerDialog.dismiss();
                                    textView.setText(R.string.register_success);
                                    showLogin(savedName);
                                });
                            } catch (IOException | JSONException error) {
                                runOnUiThread(() -> {
                                    if (isFinishing() || isDestroyed()) return;
                                    setNetworkBusy(false);
                                    feedback.setText(registrationErrorMessage(error));
                                });
                            }
                        });
                    });
                }));
        registerDialog.show();
    }

    private String registrationErrorMessage(Exception error) {
        if (error instanceof PhotoBlogClient.ApiException
                && ((PhotoBlogClient.ApiException) error).getStatus() == 400) {
            try {
                JSONObject errors = new JSONObject(((PhotoBlogClient.ApiException) error)
                        .getResponseBody());
                StringBuilder message = new StringBuilder();
                for (String field : new String[]{"username", "password", "password_confirm", "non_field_errors"}) {
                    if (errors.has(field)) {
                        if (message.length() > 0) message.append("\n");
                        message.append(errors.get(field).toString());
                    }
                }
                if (message.length() > 0) return message.toString();
            } catch (JSONException ignored) { }
        }
        if (error instanceof SocketTimeoutException) return getString(R.string.register_uncertain);
        return accountErrorMessage(error);
    }

    private void startLogout() {
        setNetworkBusy(true);
        textView.setText(R.string.logout_loading);
        networkExecutor.execute(() -> {
            try {
                photoBlogClient.logout();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setNetworkBusy(false);
                    updateAccount();
                    textView.setText(R.string.logout_success);
                    if (imageAdapter.getItemCount() > 0) startDownload();
                });
            } catch (IOException | JSONException error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setNetworkBusy(false);
                    textView.setText(accountErrorMessage(error));
                });
            }
        });
    }

    private String accountErrorMessage(Exception error) {
        if (error instanceof PhotoBlogClient.ApiException) {
            int status = ((PhotoBlogClient.ApiException) error).getStatus();
            if (status == 400) return getString(R.string.login_invalid_credentials);
            if (status == 401) return getString(R.string.auth_expired);
            if (status == 404) return getString(R.string.auth_server_not_ready);
            if (status == 429) return getString(R.string.auth_rate_limited);
            return getString(R.string.auth_failed, status);
        }
        if (error instanceof JSONException) return getString(R.string.auth_invalid_response);
        return getString(R.string.auth_connection_failed);
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
        String keyword = searchKeyword.getText().toString().trim();
        String field = SEARCH_FIELDS[searchField.getSelectedItemPosition()];
        String sort = SORT_ORDERS[sortOrder.getSelectedItemPosition()];
        boolean searching = !keyword.isEmpty();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(searchKeyword.getWindowToken(), 0);
        setNetworkBusy(true);
        textView.setText(searching ? R.string.search_loading : R.string.sync_loading);
        networkExecutor.execute(() -> {
            try {
                List<PhotoPost> posts = photoBlogClient.downloadPosts(keyword, field, sort);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    imageAdapter.submitPosts(posts);
                    recyclerView.scrollToPosition(0);
                    if (searching) {
                        textView.setText(posts.isEmpty() ? getString(R.string.search_empty)
                                : getString(R.string.search_success, posts.size()));
                    } else {
                        textView.setText(posts.isEmpty() ? getString(R.string.sync_empty)
                                : getString(R.string.sync_success, posts.size()));
                    }
                    setNetworkBusy(false);
                });
            } catch (IOException | JSONException error) {
                Log.e("PhotoViewer", "Image synchronization failed", error);
                String message;
                if (error instanceof PhotoBlogClient.ApiException
                        && ((PhotoBlogClient.ApiException) error).getStatus() == 401) {
                    message = getString(R.string.auth_expired);
                } else if (error instanceof SocketTimeoutException) {
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

    private void onRecommendationClick(PhotoPost post) {
        if (networkBusy) return;
        if (!photoBlogClient.isLoggedIn()) {
            textView.setText(R.string.recommendation_login_required);
            showLogin();
            return;
        }
        runWithLocalNetworkPermission(() -> {
            setNetworkBusy(true);
            textView.setText(R.string.recommendation_loading);
            networkExecutor.execute(() -> {
                try {
                    JSONObject result = photoBlogClient.setRecommendation(post.id, !post.recommended);
                    int id = result.getInt("id");
                    int count = result.getInt("recommendation_count");
                    boolean mine = result.getBoolean("is_recommended");
                    if (id != post.id || count < 0) throw new JSONException("Invalid recommendation result");
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        imageAdapter.updateRecommendation(id, count, mine);
                        setNetworkBusy(false);
                        textView.setText(mine ? R.string.recommendation_saved : R.string.recommendation_removed);
                        if (sortOrder.getSelectedItemPosition() == 2) startDownload();
                    });
                } catch (IOException | JSONException error) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        setNetworkBusy(false);
                        textView.setText(error instanceof PhotoBlogClient.ApiException
                                ? accountErrorMessage(error) : getString(R.string.recommendation_uncertain));
                    });
                }
            });
        });
    }

    public void onClickUpload(View view) {
        if (networkBusy || (uploadDialog != null && uploadDialog.isShowing())) return;
        if (!photoBlogClient.isLoggedIn()) {
            textView.setText(R.string.login_required);
            showLogin();
            return;
        }
        View form = getLayoutInflater().inflate(R.layout.dialog_upload, null);
        ((TextView) form.findViewById(R.id.upload_author_name)).setText(
                getString(R.string.upload_as_user, photoBlogClient.getCurrentUsername()));
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
                    Uri imageUri = selectedImageUri;
                    if (imageUri == null) {
                        Toast.makeText(this, R.string.choose_image_first,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    runWithLocalNetworkPermission(() -> {
                        if (uploadDialog != null && uploadDialog.isShowing()) {
                            startUpload(imageUri, title, text);
                        }
                    });
                }));
        uploadDialog.show();
    }

    private void startUpload(Uri imageUri, String title, String text) {
        if (networkBusy || isFinishing()) return;
        setNetworkBusy(true);
        textView.setText(R.string.upload_loading);
        networkExecutor.execute(() -> {
            try {
                JSONObject created = photoBlogClient.uploadPost(getApplicationContext(),
                        imageUri, title, text);
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
            if (apiError.getStatus() == 401) {
                return getString(R.string.auth_expired);
            }
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
        imageAdapter.setBusy(busy);
        syncButton.setEnabled(!busy);
        uploadButton.setEnabled(!busy);
        accountButton.setEnabled(!busy);
        searchButton.setEnabled(!busy);
        clearSearchButton.setEnabled(!busy);
        searchKeyword.setEnabled(!busy);
        searchField.setEnabled(!busy);
        sortOrder.setEnabled(!busy);
        sortButton.setEnabled(!busy);
        if (loginDialog != null && loginDialog.isShowing()) {
            loginDialog.setCancelable(!busy);
            loginDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
            loginDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
            loginDialog.findViewById(R.id.login_username).setEnabled(!busy);
            loginDialog.findViewById(R.id.login_password).setEnabled(!busy);
            loginDialog.findViewById(R.id.btn_register).setEnabled(!busy);
        }
        if (registerDialog != null && registerDialog.isShowing()) {
            registerDialog.setCancelable(!busy);
            registerDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
            registerDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
            for (int id : new int[]{R.id.register_username, R.id.register_password, R.id.register_confirmation}) {
                registerDialog.findViewById(id).setEnabled(!busy);
            }
        }
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
        if (loginDialog != null) loginDialog.dismiss();
        if (registerDialog != null) registerDialog.dismiss();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
