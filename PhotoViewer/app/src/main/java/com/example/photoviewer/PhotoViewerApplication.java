package com.example.photoviewer;

import android.app.Application;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/** One in-memory session per process. Passwords and tokens are never saved to disk. */
public class PhotoViewerApplication extends Application {
    private final PhotoBlogClient client = new PhotoBlogClient(BuildConfig.PHOTO_BLOG_URL);
    private final MutableLiveData<String> identity = new MutableLiveData<>("");

    @Override public void onCreate() {
        super.onCreate();
        client.setSessionListener(identity::postValue);
    }
    public PhotoBlogClient client() { return client; }
    public LiveData<String> identity() { return identity; }
}
