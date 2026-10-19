package com.example.photoviewer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

final class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.Holder> {
    interface DeleteClick { void clicked(PhotoComment comment); }
    private final List<PhotoComment> comments = new ArrayList<>();
    private final DeleteClick listener;
    private boolean signedIn;
    private boolean busy;
    CommentAdapter(DeleteClick listener) { this.listener = listener; }
    void submit(List<PhotoComment> values) { comments.clear(); comments.addAll(values); notifyDataSetChanged(); }
    void setState(boolean signedIn, boolean busy) {
        this.signedIn = signedIn;
        this.busy = busy;
        notifyDataSetChanged();
    }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_comment, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        PhotoComment comment = comments.get(position);
        String date = comment.created.replace('T', ' ');
        if (date.length() > 16) date = date.substring(0, 16);
        holder.author.setText(holder.itemView.getContext().getString(R.string.comment_author_date, comment.author, date));
        holder.text.setText(comment.text);
        holder.delete.setVisibility(signedIn && comment.canDelete ? View.VISIBLE : View.GONE);
        holder.delete.setEnabled(!busy);
        holder.delete.setOnClickListener(view -> listener.clicked(comment));
    }
    @Override public int getItemCount() { return comments.size(); }
    static final class Holder extends RecyclerView.ViewHolder {
        final TextView author;
        final TextView text;
        final Button delete;
        Holder(View item) {
            super(item);
            author = item.findViewById(R.id.comment_author);
            text = item.findViewById(R.id.comment_text);
            delete = item.findViewById(R.id.comment_delete);
        }
    }
}
