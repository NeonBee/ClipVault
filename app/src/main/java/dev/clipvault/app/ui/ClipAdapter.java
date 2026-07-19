package dev.clipvault.app.ui;

import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import dev.clipvault.app.R;
import dev.clipvault.app.data.ClipRecord;
import dev.clipvault.app.nativecore.NativeClassifier;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class ClipAdapter extends ListAdapter<ClipRecord, ClipAdapter.Holder> {
    public interface Actions {
        void onCopy(@NonNull ClipRecord record);
        void onFavorite(@NonNull ClipRecord record);
        void onDelete(@NonNull ClipRecord record);
    }

    private static final DiffUtil.ItemCallback<ClipRecord> DIFF = new DiffUtil.ItemCallback<>() {
        @Override
        public boolean areItemsTheSame(@NonNull ClipRecord oldItem, @NonNull ClipRecord newItem) {
            return oldItem.id == newItem.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull ClipRecord oldItem, @NonNull ClipRecord newItem) {
            return oldItem.createdAt == newItem.createdAt && oldItem.flags == newItem.flags &&
                    oldItem.favorite == newItem.favorite && oldItem.content.equals(newItem.content);
        }
    };

    private final Actions actions;
    private final Locale persian = new Locale("fa", "IR");

    public ClipAdapter(@NonNull Actions actions) {
        super(DIFF);
        this.actions = actions;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).id;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_clip, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ClipRecord record = getItem(position);
        holder.content.setText(record.content);
        boolean rtl = (record.flags & NativeClassifier.PERSIAN) != 0;
        holder.content.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        holder.content.setGravity(rtl ? Gravity.START : Gravity.START);
        holder.category.setText(categoryLabel(record.flags));
        holder.time.setText(new SimpleDateFormat("HH:mm", persian).format(new Date(record.createdAt)));
        holder.favorite.setImageResource(record.favorite ? R.drawable.ic_star_filled : R.drawable.ic_star);

        boolean showHeader = position == 0 || !sameDay(record.createdAt, getItem(position - 1).createdAt);
        holder.dateHeader.setVisibility(showHeader ? View.VISIBLE : View.GONE);
        if (showHeader) holder.dateHeader.setText(dateHeader(record.createdAt));

        holder.copy.setOnClickListener(view -> actions.onCopy(record));
        holder.favorite.setOnClickListener(view -> actions.onFavorite(record));
        holder.delete.setOnClickListener(view -> actions.onDelete(record));
    }

    @NonNull
    private String categoryLabel(int flags) {
        if ((flags & NativeClassifier.INSTAGRAM) != 0) return "اینستاگرام";
        if ((flags & NativeClassifier.YOUTUBE) != 0) return "یوتیوب";
        if ((flags & NativeClassifier.LINK) != 0) return "لینک";
        if ((flags & NativeClassifier.DATE) != 0) return "تاریخ";
        if ((flags & NativeClassifier.LONG_TEXT) != 0) return "متن طولانی";
        if ((flags & NativeClassifier.PERSIAN) != 0) return "فارسی";
        if ((flags & NativeClassifier.ENGLISH) != 0) return "English";
        return "متفرقه";
    }

    @NonNull
    private String dateHeader(long timestamp) {
        if (DateUtils.isToday(timestamp)) return "امروز";
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        Calendar value = Calendar.getInstance();
        value.setTimeInMillis(timestamp);
        if (yesterday.get(Calendar.ERA) == value.get(Calendar.ERA) &&
                yesterday.get(Calendar.YEAR) == value.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == value.get(Calendar.DAY_OF_YEAR)) {
            return "دیروز";
        }
        return new SimpleDateFormat("EEEE، d MMMM", persian).format(new Date(timestamp));
    }

    private static boolean sameDay(long first, long second) {
        Calendar one = Calendar.getInstance();
        one.setTimeInMillis(first);
        Calendar two = Calendar.getInstance();
        two.setTimeInMillis(second);
        return one.get(Calendar.ERA) == two.get(Calendar.ERA) &&
                one.get(Calendar.YEAR) == two.get(Calendar.YEAR) &&
                one.get(Calendar.DAY_OF_YEAR) == two.get(Calendar.DAY_OF_YEAR);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView dateHeader;
        final TextView category;
        final TextView time;
        final TextView content;
        final ImageButton copy;
        final ImageButton favorite;
        final ImageButton delete;

        Holder(@NonNull View itemView) {
            super(itemView);
            dateHeader = itemView.findViewById(R.id.dateHeader);
            category = itemView.findViewById(R.id.categoryLabel);
            time = itemView.findViewById(R.id.timeLabel);
            content = itemView.findViewById(R.id.contentText);
            copy = itemView.findViewById(R.id.copyButton);
            favorite = itemView.findViewById(R.id.favoriteButton);
            delete = itemView.findViewById(R.id.deleteButton);
        }
    }
}
