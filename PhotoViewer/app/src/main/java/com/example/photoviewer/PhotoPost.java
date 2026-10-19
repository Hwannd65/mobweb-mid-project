package com.example.photoviewer;

import android.graphics.Bitmap;

/** API metadata and the image fetched from its URL stay together in one list item. */
public final class PhotoPost {
    public final int id;
    public final int authorId;
    public final String author;
    public final String title;
    public final String text;
    public final Bitmap image;
    public final int recommendationCount;
    public final boolean recommended;
    public final boolean recommendationSupported;
    public final int commentCount;
    public final boolean commentsSupported;

    PhotoPost(int id, int authorId, String author, String title, String text, Bitmap image,
              int recommendationCount, boolean recommended, boolean recommendationSupported,
              int commentCount, boolean commentsSupported) {
        this.id = id;
        this.authorId = authorId;
        this.author = author;
        this.title = title;
        this.text = text;
        this.image = image;
        this.recommendationCount = recommendationCount;
        this.recommended = recommended;
        this.recommendationSupported = recommendationSupported;
        this.commentCount = commentCount;
        this.commentsSupported = commentsSupported;
    }
    PhotoPost withRecommendation(int count, boolean mine) {
        return new PhotoPost(id, authorId, author, title, text, image, count, mine, recommendationSupported, commentCount, commentsSupported);
    }
}
