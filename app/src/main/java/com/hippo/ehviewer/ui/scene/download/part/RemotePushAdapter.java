/*
 * Remote Push Adapter
 * Displays remote push records in DownloadsScene
 */

package com.hippo.ehviewer.ui.scene.download.part;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.dao.RemotePushInfo;
import com.hippo.widget.LoadImageView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RemotePushAdapter extends RecyclerView.Adapter<RemotePushAdapter.RemotePushHolder> {

    private final LayoutInflater mInflater;
    private List<RemotePushInfo> mList;

    public RemotePushAdapter(Context context) {
        mInflater = LayoutInflater.from(context);
    }

    public void setData(List<RemotePushInfo> list) {
        mList = list;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mList == null ? 0 : mList.size();
    }

    @NonNull
    @Override
    public RemotePushHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mInflater.inflate(R.layout.item_download, parent, false);
        return new RemotePushHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RemotePushHolder holder, int position) {
        if (mList == null || position >= mList.size()) {
            return;
        }

        RemotePushInfo info = mList.get(position);

        // Load thumbnail
        holder.thumb.load(EhCacheKeyFactory.getThumbKey(info.getGid()), info.getThumb());

        // Set title
        holder.title.setText(info.getTitle());

        // Set uploader
        String uploader = info.getUploader();
        if (uploader != null && !uploader.isEmpty()) {
            holder.uploader.setText(uploader);
            holder.uploader.setVisibility(View.VISIBLE);
        } else {
            holder.uploader.setVisibility(View.GONE);
        }

        // Set rating (if available)
        Float rating = info.getRating();
        if (rating != null && rating > 0) {
            holder.rating.setRating(rating);
            holder.rating.setVisibility(View.VISIBLE);
        } else {
            holder.rating.setVisibility(View.GONE);
        }

        // Set category color
        int categoryColor = EhUtils.getCategoryColor(info.getCategory());
        holder.category.setBackgroundColor(categoryColor);

        // Hide actions and progress for remote push (not applicable)
        holder.actions.setVisibility(View.GONE);
        holder.readProgress.setVisibility(View.VISIBLE);

        // Show push time in read_progress field
        long pushTime = info.getPushTime();
        if (pushTime > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            holder.readProgress.setText(sdf.format(new Date(pushTime)));
        } else {
            holder.readProgress.setText("");
        }
    }

    public static class RemotePushHolder extends RecyclerView.ViewHolder {
        public final LoadImageView thumb;
        public final TextView title;
        public final TextView uploader;
        public final View category;
        public final com.hippo.ehviewer.widget.SimpleRatingView rating;
        public final TextView readProgress;
        public final View actions;

        public RemotePushHolder(View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumb);
            title = itemView.findViewById(R.id.title);
            uploader = itemView.findViewById(R.id.uploader);
            category = itemView.findViewById(R.id.category);
            rating = itemView.findViewById(R.id.rating);
            readProgress = itemView.findViewById(R.id.read_progress);
            actions = itemView.findViewById(R.id.actions);
        }
    }
}