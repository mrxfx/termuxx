package com.termux.app.activities;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.termux.R;
import com.termux.app.ai.TerminalCommandBridge;
import com.termux.app.ai.firebase.AppCheckInit;
import com.termux.app.autocomplete.CommandHistoryStore;
import com.termux.app.ui.OneUINavHelper;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Samsung One UI Command History Browser.
 * - Searches CommandHistoryStore offline.
 * - Groups entries cleanly by Today / Yesterday / Earlier.
 * - Reuse inserts into active terminal WITHOUT auto-executing.
 * - Delete removes from Termux AI history store without mutating ~/.bash_history or ~/.zsh_history.
 */
public class CommandHistoryActivity extends AppCompatActivity {

    private CommandHistoryStore mHistoryStore;
    private HistoryAdapter mAdapter;
    private EditText mSearchInput;
    private View mClearSearchButton;
    private TextView mCountSubtitle;
    private View mEmptyState;
    private TextView mEmptyTitle;
    private TextView mEmptySubtitle;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCheckInit.configureAppCheck(this, getIntent());
        setContentView(R.layout.activity_command_history);

        mHistoryStore = new CommandHistoryStore(this);

        mSearchInput = findViewById(R.id.history_search_input);
        mClearSearchButton = findViewById(R.id.history_search_clear);
        mCountSubtitle = findViewById(R.id.history_count_subtitle);
        mEmptyState = findViewById(R.id.history_empty_state);
        mEmptyTitle = findViewById(R.id.history_empty_title);
        mEmptySubtitle = findViewById(R.id.history_empty_subtitle);

        RecyclerView recyclerView = findViewById(R.id.history_recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new HistoryAdapter();
        recyclerView.setAdapter(mAdapter);

        findViewById(R.id.history_back_button).setOnClickListener(v -> finish());
        findViewById(R.id.history_clear_button).setOnClickListener(v -> confirmClearAll());

        mClearSearchButton.setOnClickListener(v -> mSearchInput.setText(""));
        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean hasText = s != null && s.length() > 0;
                mClearSearchButton.setVisibility(hasText ? View.VISIBLE : View.GONE);
                loadHistory(s != null ? s.toString() : "");
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        BottomNavigationView bottomNav = findViewById(R.id.oneui_bottom_nav);
        OneUINavHelper.setupBottomNav(this, bottomNav, R.id.nav_history);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistory(mSearchInput.getText().toString());
    }

    private void loadHistory(@NonNull String query) {
        List<CommandHistoryStore.HistoryEntry> entries = mHistoryStore.searchEntries(query);
        mCountSubtitle.setText(entries.size() + (entries.size() == 1 ? " command" : " commands"));

        if (entries.isEmpty()) {
            mAdapter.setRows(new ArrayList<>());
            mEmptyState.setVisibility(View.VISIBLE);
            boolean searching = !query.trim().isEmpty();
            mEmptyTitle.setText(searching ? R.string.history_search_empty_title : R.string.history_empty_title);
            mEmptySubtitle.setText(searching ? R.string.history_search_empty_subtitle : R.string.history_empty_subtitle);
            return;
        }

        mEmptyState.setVisibility(View.GONE);
        List<HistoryRow> rows = buildGroupedRows(entries);
        mAdapter.setRows(rows);
    }

    @NonNull
    private List<HistoryRow> buildGroupedRows(@NonNull List<CommandHistoryStore.HistoryEntry> entries) {
        Calendar todayStart = Calendar.getInstance();
        todayStart.set(Calendar.HOUR_OF_DAY, 0);
        todayStart.set(Calendar.MINUTE, 0);
        todayStart.set(Calendar.SECOND, 0);
        todayStart.set(Calendar.MILLISECOND, 0);
        long todayMs = todayStart.getTimeInMillis();
        long yesterdayMs = todayMs - 86_400_000L;

        String todayLabel = getString(R.string.history_today);
        String yesterdayLabel = getString(R.string.history_yesterday);
        String earlierLabel = getString(R.string.history_earlier);

        List<HistoryRow> rows = new ArrayList<>();
        String lastGroup = null;
        for (CommandHistoryStore.HistoryEntry entry : entries) {
            String group;
            if (entry.lastUsedMs >= todayMs) {
                group = todayLabel;
            } else if (entry.lastUsedMs >= yesterdayMs) {
                group = yesterdayLabel;
            } else {
                group = earlierLabel;
            }
            if (!group.equals(lastGroup)) {
                rows.add(HistoryRow.header(group));
                lastGroup = group;
            }
            rows.add(HistoryRow.entry(entry));
        }
        return rows;
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.history_clear_title)
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                mHistoryStore.clearHistory();
                loadHistory(mSearchInput.getText().toString());
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void copyCommand(@NonNull String command) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Termux Command", command));
            Toast.makeText(this, R.string.history_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void reuseCommand(@NonNull String command) {
        TerminalCommandBridge.insertIntoActiveTerminal(command);
        Toast.makeText(this, R.string.history_reused, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void deleteCommand(@NonNull String command) {
        mHistoryStore.deleteEntry(command);
        Toast.makeText(this, R.string.history_deleted, Toast.LENGTH_SHORT).show();
        loadHistory(mSearchInput.getText().toString());
    }

    private static final class HistoryRow {
        final boolean isHeader;
        final String headerTitle;
        final CommandHistoryStore.HistoryEntry entry;

        private HistoryRow(boolean isHeader, String headerTitle, CommandHistoryStore.HistoryEntry entry) {
            this.isHeader = isHeader;
            this.headerTitle = headerTitle;
            this.entry = entry;
        }

        static HistoryRow header(String title) {
            return new HistoryRow(true, title, null);
        }

        static HistoryRow entry(CommandHistoryStore.HistoryEntry entry) {
            return new HistoryRow(false, null, entry);
        }
    }

    private final class HistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_HEADER = 0;
        private static final int TYPE_ENTRY = 1;
        private List<HistoryRow> mRows = new ArrayList<>();
        private final DateFormat mTimeFormat = DateFormat.getTimeInstance(DateFormat.SHORT);

        void setRows(@NonNull List<HistoryRow> rows) {
            mRows = rows;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).isHeader ? TYPE_HEADER : TYPE_ENTRY;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(inflater.inflate(R.layout.item_history_date_header, parent, false));
            }
            return new EntryVH(inflater.inflate(R.layout.item_history_entry, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            HistoryRow row = mRows.get(position);
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).title.setText(row.headerTitle);
            } else if (holder instanceof EntryVH && row.entry != null) {
                EntryVH vh = (EntryVH) holder;
                CommandHistoryStore.HistoryEntry entry = row.entry;
                vh.command.setText(entry.command);
                vh.countBadge.setText(entry.count + "x");
                vh.timestamp.setText(entry.lastUsedMs > 0 ? mTimeFormat.format(new Date(entry.lastUsedMs)) : "Shell history");
                vh.copyBtn.setOnClickListener(v -> copyCommand(entry.command));
                vh.reuseBtn.setOnClickListener(v -> reuseCommand(entry.command));
                vh.deleteBtn.setOnClickListener(v -> deleteCommand(entry.command));
            }
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }
    }

    private static final class HeaderVH extends RecyclerView.ViewHolder {
        final TextView title;
        HeaderVH(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.history_date_header_text);
        }
    }

    private static final class EntryVH extends RecyclerView.ViewHolder {
        final TextView command;
        final TextView countBadge;
        final TextView timestamp;
        final View copyBtn;
        final View reuseBtn;
        final ImageButton deleteBtn;

        EntryVH(@NonNull View itemView) {
            super(itemView);
            command = itemView.findViewById(R.id.history_entry_command);
            countBadge = itemView.findViewById(R.id.history_entry_count_badge);
            timestamp = itemView.findViewById(R.id.history_entry_timestamp);
            copyBtn = itemView.findViewById(R.id.history_btn_copy);
            reuseBtn = itemView.findViewById(R.id.history_btn_reuse);
            deleteBtn = itemView.findViewById(R.id.history_btn_delete);
        }
    }
}
