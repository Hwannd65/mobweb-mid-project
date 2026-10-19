package com.example.photoviewer;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CommentsActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private PhotoBlogClient client;
    private CommentAdapter adapter;
    private int postId;
    private String postTitle;
    private boolean busy;
    private TextView status;
    private TextView account;
    private EditText input;
    private Button submit;
    private Button refresh;
    private Button login;
    private AlertDialog deleteDialog;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        postId = getIntent().getIntExtra("post_id", 0);
        postTitle = getIntent().getStringExtra("post_title");
        if (postId <= 0) { finish(); return; }
        setResult(RESULT_OK);
        PhotoViewerApplication app = (PhotoViewerApplication) getApplication();
        client = app.client();
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_comments);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.comments_main), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            view.setPadding(bars.left + padding, bars.top + padding, bars.right + padding, bottom + padding);
            return insets;
        });
        ((TextView) findViewById(R.id.comments_title)).setText(getString(R.string.comments_heading, postTitle));
        status = findViewById(R.id.comments_status);
        account = findViewById(R.id.comments_account);
        input = findViewById(R.id.comment_input);
        submit = findViewById(R.id.comment_submit);
        refresh = findViewById(R.id.comments_refresh);
        login = findViewById(R.id.comments_login);
        RecyclerView list = findViewById(R.id.comments_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CommentAdapter(this::confirmDelete);
        list.setAdapter(adapter);
        refresh.setOnClickListener(view -> loadComments(null));
        submit.setOnClickListener(view -> createComment());
        login.setOnClickListener(view -> {
            Intent result = new Intent();
            result.putExtra("request_login", true);
            result.putExtra("post_id", postId);
            result.putExtra("post_title", postTitle);
            setResult(RESULT_OK, result);
            finish();
        });
        findViewById(R.id.comments_back).setOnClickListener(view -> getOnBackPressedDispatcher().onBackPressed());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (busy) Toast.makeText(CommentsActivity.this, R.string.comment_busy, Toast.LENGTH_SHORT).show();
                else finish();
            }
        });
        app.identity().observe(this, ignored -> updateControls());
        loadComments(null);
    }

    private void updateControls() {
        boolean signedIn = client.isLoggedIn();
        account.setText(signedIn ? getString(R.string.upload_as_user, client.getCurrentUsername())
                : getString(R.string.comment_login_required));
        login.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        login.setEnabled(!busy);
        input.setEnabled(!busy && signedIn);
        submit.setEnabled(!busy && signedIn);
        refresh.setEnabled(!busy);
        adapter.setState(signedIn, busy);
    }
    private void setBusy(boolean value) { busy = value; updateControls(); }
    private boolean unavailable() { return isFinishing() || isDestroyed(); }

    private void loadComments(String notice) {
        if (busy || unavailable()) return;
        setBusy(true);
        status.setText(R.string.comments_loading);
        executor.execute(() -> {
            try {
                List<PhotoComment> comments = client.getComments(postId);
                runOnUiThread(() -> {
                    if (unavailable()) return;
                    adapter.submit(comments);
                    setBusy(false);
                    String count = comments.isEmpty() ? getString(R.string.comments_empty)
                            : getString(R.string.comments_count, comments.size());
                    status.setText(notice == null ? count : notice + " " + count);
                });
            } catch (IOException | JSONException error) {
                runOnUiThread(() -> {
                    if (unavailable()) return;
                    setBusy(false);
                    status.setText(notice == null ? errorMessage(error)
                            : getString(R.string.comment_reload_failed, notice));
                });
            }
        });
    }

    private void createComment() {
        if (busy || !client.isLoggedIn()) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) { input.setError(getString(R.string.comment_required)); return; }
        input.setError(null);
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(input.getWindowToken(), 0);
        setBusy(true);
        status.setText(R.string.comment_saving);
        executor.execute(() -> {
            try {
                JSONObject result = client.createComment(postId, text);
                PhotoComment created = new PhotoComment(result);
                if (created.postId != postId) throw new JSONException("Unexpected comment post");
                runOnUiThread(() -> {
                    if (unavailable()) return;
                    // Clear only after HTTP 201; a failed refresh must never trigger another POST.
                    input.setText("");
                    input.setError(null);
                    input.clearFocus();
                    setBusy(false);
                    loadComments(getString(R.string.comment_created));
                });
            } catch (IOException | JSONException error) {
                runOnUiThread(() -> {
                    if (unavailable()) return;
                    setBusy(false);
                    status.setText(error instanceof PhotoBlogClient.ApiException
                            ? errorMessage(error) : getString(R.string.comment_post_uncertain));
                });
            }
        });
    }

    private void confirmDelete(PhotoComment comment) {
        if (busy || !client.isLoggedIn() || !comment.canDelete) return;
        deleteDialog = new AlertDialog.Builder(this)
                .setMessage(R.string.comment_delete_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.comment_delete, (dialog, which) -> deleteComment(comment))
                .create();
        deleteDialog.setOnDismissListener(dialog -> deleteDialog = null);
        deleteDialog.show();
    }
    private void deleteComment(PhotoComment comment) {
        if (busy) return;
        setBusy(true);
        status.setText(R.string.comment_deleting);
        executor.execute(() -> {
            try {
                client.deleteComment(postId, comment.id);
                runOnUiThread(() -> {
                    if (unavailable()) return;
                    setBusy(false);
                    loadComments(getString(R.string.comment_deleted));
                });
            } catch (IOException error) {
                runOnUiThread(() -> {
                    if (unavailable()) return;
                    setBusy(false);
                    status.setText(error instanceof PhotoBlogClient.ApiException
                            ? errorMessage(error) : getString(R.string.comment_delete_uncertain));
                });
            }
        });
    }

    private String errorMessage(Exception error) {
        if (error instanceof PhotoBlogClient.ApiException) {
            PhotoBlogClient.ApiException api = (PhotoBlogClient.ApiException) error;
            if (api.getStatus() == 401) return getString(R.string.auth_expired);
            if (api.getStatus() == 403) return getString(R.string.comment_forbidden);
            if (api.getStatus() == 404) return getString(R.string.comment_not_found);
            if (api.getStatus() == 400) {
                try {
                    JSONObject errors = new JSONObject(api.getResponseBody());
                    if (errors.has("text")) return errors.get("text").toString();
                } catch (JSONException ignored) { }
            }
            return getString(R.string.comment_request_failed, api.getStatus());
        }
        if (error instanceof JSONException) return getString(R.string.comments_invalid_response);
        return getString(R.string.auth_connection_failed);
    }
    @Override protected void onDestroy() {
        if (deleteDialog != null) deleteDialog.dismiss();
        executor.shutdownNow();
        super.onDestroy();
    }
}
