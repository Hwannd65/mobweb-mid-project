package com.example.photoviewer;

import org.json.JSONException;
import org.json.JSONObject;

public final class PhotoComment {
    public final int id;
    public final int postId;
    public final int authorId;
    public final String author;
    public final String text;
    public final String created;
    public final boolean canDelete;

    PhotoComment(JSONObject data) throws JSONException {
        id = data.getInt("id");
        postId = data.getInt("post");
        authorId = data.getInt("author");
        author = data.getString("author_username");
        text = data.getString("text");
        created = data.getString("created_date");
        canDelete = data.getBoolean("can_delete");
        if (id <= 0 || postId <= 0 || authorId <= 0) throw new JSONException("Invalid comment identity");
    }
}
