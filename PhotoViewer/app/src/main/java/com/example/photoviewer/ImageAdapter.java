package com.example.photoviewer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class ImageAdapter extends RecyclerView.Adapter<ImageAdapter.ImageViewHolder> {
    interface RecommendationClick { void clicked(PhotoPost post); }
    interface CommentsClick { void clicked(PhotoPost post); }
    private final List<PhotoPost> posts = new ArrayList<>();
    private final RecommendationClick listener;
    private final CommentsClick commentsListener;
    private boolean busy;

    public ImageAdapter(RecommendationClick listener, CommentsClick commentsListener) {
        this.listener = listener;
        this.commentsListener = commentsListener;
    }
    public void submitPosts(List<PhotoPost> values) {
        posts.clear();
        posts.addAll(values);
        notifyDataSetChanged();
    }
    public void setBusy(boolean value) {
        if (busy == value) return;
        busy = value;
        notifyDataSetChanged();
    }
    public void clearPersonalization() {
        for (int i = 0; i < posts.size(); i++) {
            PhotoPost post = posts.get(i);
            posts.set(i, post.withRecommendation(post.recommendationCount, false));
        }
        notifyDataSetChanged();
    }
    public void updateRecommendation(int id, int count, boolean mine) {
        for (int i = 0; i < posts.size(); i++) {
            if (posts.get(i).id != id) continue;
            posts.set(i, posts.get(i).withRecommendation(count, mine));
            notifyItemChanged(i);
            return;
        }
    }
    @NonNull @Override
    public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ImageViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_image, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
        PhotoPost post = posts.get(position);
        holder.title.setText(post.title);
        holder.author.setText(holder.itemView.getContext().getString(R.string.post_by_author, post.author));
        holder.text.setText(post.text);
        holder.imageView.setImageBitmap(post.image);
        holder.imageView.setVisibility(post.image == null ? View.GONE : View.VISIBLE);
        holder.recommendationRow.setVisibility(post.recommendationSupported || post.commentsSupported ? View.VISIBLE : View.GONE);
        holder.recommendationCount.setVisibility(post.recommendationSupported ? View.VISIBLE : View.GONE);
        holder.recommendButton.setVisibility(post.recommendationSupported ? View.VISIBLE : View.GONE);
        holder.commentsButton.setVisibility(post.commentsSupported ? View.VISIBLE : View.GONE);
        holder.commentsButton.setText(holder.itemView.getContext().getString(R.string.comments_count, post.commentCount));
        holder.commentsButton.setEnabled(!busy);
        holder.commentsButton.setOnClickListener(view -> commentsListener.clicked(post));
        holder.recommendationCount.setText(holder.itemView.getContext()
                .getString(R.string.recommendation_count, post.recommendationCount));
        holder.recommendButton.setText(post.recommended ? R.string.cancel_recommendation : R.string.recommend);
        holder.recommendButton.setEnabled(!busy);
        holder.recommendButton.setOnClickListener(view -> listener.clicked(post));
    }
    @Override public int getItemCount() { return posts.size(); }

    public static class ImageViewHolder extends RecyclerView.ViewHolder {
        final ImageView imageView;
        final TextView title;
        final TextView author;
        final TextView text;
        final View recommendationRow;
        final TextView recommendationCount;
        final Button recommendButton;
        final Button commentsButton;
        public ImageViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.imageViewItem);
            title = itemView.findViewById(R.id.post_title);
            author = itemView.findViewById(R.id.post_author);
            text = itemView.findViewById(R.id.post_body);
            recommendationRow = itemView.findViewById(R.id.recommendation_row);
            recommendationCount = itemView.findViewById(R.id.recommendation_count);
            recommendButton = itemView.findViewById(R.id.btn_recommend);
            commentsButton = itemView.findViewById(R.id.btn_comments);
        }
    }
}
