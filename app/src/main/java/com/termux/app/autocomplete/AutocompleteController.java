package com.termux.app.autocomplete;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.ai.TerminalCommandBridge;
import com.termux.shared.shell.ShellUtils;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Coordinates TerminalView input/screen updates with the offline AutocompleteEngine,
 * renders inline ghost text on TerminalView, and manages the non-focus-stealing suggestion dropdown.
 */
public class AutocompleteController implements TerminalView.TerminalObserver {

    private final TermuxActivity mActivity;
    private final TerminalView mTerminalView;
    private final AutocompleteSettingsManager mSettings;
    private final AutocompleteEngine mEngine;

    private final View mContainerView;
    private final RecyclerView mRecyclerView;
    private final SuggestionAdapter mAdapter;

    private List<SuggestionItem> mCurrentSuggestions = Collections.emptyList();
    private int mSelectedIndex = -1; // -1 means top item (ghost) unless user explicitly navigates dropdown
    @Nullable
    private String mActiveGhostSuffix;
    private boolean mDismissedForCurrentLine = false;
    private String mLastEvaluatedCommandLine = "";

    public AutocompleteController(
        @NonNull TermuxActivity activity,
        @NonNull TerminalView terminalView,
        @NonNull View autocompleteRootView
    ) {
        mActivity = activity;
        mTerminalView = terminalView;
        mSettings = new AutocompleteSettingsManager(activity);
        mEngine = new AutocompleteEngine(activity);
        mEngine.warmUpCachesAsync();

        mContainerView = autocompleteRootView;
        mRecyclerView = autocompleteRootView.findViewById(R.id.autocomplete_recycler_view);
        TextView acceptButton = autocompleteRootView.findViewById(R.id.autocomplete_accept_button);
        ImageButton dismissButton = autocompleteRootView.findViewById(R.id.autocomplete_dismiss_button);

        mAdapter = new SuggestionAdapter(position -> {
            if (position >= 0 && position < mCurrentSuggestions.size()) {
                acceptSuggestion(mCurrentSuggestions.get(position));
            }
        });

        mRecyclerView.setLayoutManager(new LinearLayoutManager(activity));
        mRecyclerView.setAdapter(mAdapter);

        acceptButton.setOnClickListener(v -> acceptActiveSuggestion());
        dismissButton.setOnClickListener(v -> dismissSuggestions(true));
    }

    @NonNull
    public AutocompleteEngine getEngine() {
        return mEngine;
    }

    @NonNull
    public AutocompleteSettingsManager getSettings() {
        return mSettings;
    }

    @Override
    public void onSessionAttached(TerminalSession session) {
        TerminalCommandBridge.attachSession(session);
        TerminalCommandBridge.updateAvailableTools(mEngine.getAvailableKnownToolsSnapshot());
        dismissSuggestions(false);
    }

    @Override
    public void onTerminalScreenUpdated() {
        TerminalSession session = mActivity.getCurrentSession();
        TerminalCommandBridge.attachSession(session);
        if (session != null && session.isRunning()) {
            String transcript = ShellUtils.getTerminalSessionTranscriptText(session, false, true);
            TerminalCommandBridge.updateRecentTranscript(transcript);
        }
        evaluateSuggestions();
    }

    @Override
    public void onTerminalInputSent(int codePoint, boolean ctrlDown, boolean altDown) {
        if (ctrlDown) {
            if (codePoint == 3 || codePoint == 'c' || codePoint == 'C'
                || codePoint == 'u' || codePoint == 'U' || codePoint == 'd' || codePoint == 'D') {
                dismissSuggestions(false);
                mLastEvaluatedCommandLine = "";
                return;
            }
        }
        if (codePoint == '\r' || codePoint == '\n') {
            captureSubmittedCommandFromScreen();
            dismissSuggestions(false);
            mLastEvaluatedCommandLine = "";
            return;
        }
        mDismissedForCurrentLine = false;
    }

    @Override
    public void onKeyCodeSent(int keyCode, int keyMod) {
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            captureSubmittedCommandFromScreen();
            dismissSuggestions(false);
            mLastEvaluatedCommandLine = "";
        } else if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            dismissSuggestions(true);
        } else if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL
            || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            mDismissedForCurrentLine = false;
        }
    }

    /**
     * Handles suggestion acceptance, navigation, and dismissal keys cleanly without
     * breaking standard terminal editing or interactive apps.
     *
     * @return true if the key event was consumed by autocomplete UI.
     */
    public boolean handleKeyDown(int keyCode, @NonNull KeyEvent event, @Nullable TerminalSession session) {
        if (!hasActiveSuggestion()) {
            return false;
        }
        TerminalEmulator emulator = mTerminalView.mEmulator;
        if (TerminalInputAnalyzer.isInteractiveMode(emulator, session != null ? session.getPid() : -1)) {
            dismissSuggestions(false);
            return false;
        }

        boolean noModifiers = !event.isCtrlPressed() && !event.isAltPressed();

        if (keyCode == KeyEvent.KEYCODE_ESCAPE && noModifiers) {
            dismissSuggestions(true);
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_TAB && noModifiers && mSettings.acceptsTab()) {
            return acceptActiveSuggestion();
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && noModifiers && mSettings.acceptsRightArrow()
            && mActiveGhostSuffix != null && !mActiveGhostSuffix.isEmpty()) {
            return acceptActiveSuggestion();
        }

        if ((keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.isAltPressed())
            || (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && noModifiers && mSelectedIndex >= 0)) {
            if (!mCurrentSuggestions.isEmpty()) {
                mSelectedIndex = Math.min(mCurrentSuggestions.size() - 1, mSelectedIndex + 1);
                updateSelectionUi();
                return true;
            }
        }

        if ((keyCode == KeyEvent.KEYCODE_DPAD_UP && event.isAltPressed())
            || (keyCode == KeyEvent.KEYCODE_DPAD_UP && noModifiers && mSelectedIndex >= 0)) {
            if (!mCurrentSuggestions.isEmpty()) {
                mSelectedIndex = mSelectedIndex - 1; // -1 disengages dropdown selection cleanly
                updateSelectionUi();
                return true;
            }
        }

        if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            && noModifiers && mSelectedIndex >= 0 && mSelectedIndex < mCurrentSuggestions.size()) {
            return acceptSuggestion(mCurrentSuggestions.get(mSelectedIndex));
        }

        return false;
    }

    public boolean hasActiveSuggestion() {
        return (mActiveGhostSuffix != null && !mActiveGhostSuffix.isEmpty()) || !mCurrentSuggestions.isEmpty();
    }

    public boolean acceptActiveSuggestion() {
        if (mSelectedIndex >= 0 && mSelectedIndex < mCurrentSuggestions.size()) {
            return acceptSuggestion(mCurrentSuggestions.get(mSelectedIndex));
        }
        if (!mCurrentSuggestions.isEmpty()) {
            return acceptSuggestion(mCurrentSuggestions.get(0));
        }
        if (mActiveGhostSuffix != null && !mActiveGhostSuffix.isEmpty()) {
            TerminalSession session = mActivity.getCurrentSession();
            if (session != null && session.isRunning()) {
                session.write(mActiveGhostSuffix);
                dismissSuggestions(false);
                return true;
            }
        }
        return false;
    }

    public boolean acceptSuggestion(@NonNull SuggestionItem item) {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null || !session.isRunning()) return false;
        String suffix = item.getInsertSuffix();
        if (suffix.isEmpty()) return false;
        session.write(suffix);
        mDismissedForCurrentLine = false;
        dismissSuggestions(false);
        return true;
    }

    public void dismissSuggestions(boolean lockUntilTyped) {
        if (lockUntilTyped) {
            mDismissedForCurrentLine = true;
        }
        mEngine.cancelPending();
        mCurrentSuggestions = Collections.emptyList();
        mSelectedIndex = -1;
        mActiveGhostSuffix = null;
        mTerminalView.setGhostText(null);
        mAdapter.submit(Collections.emptyList(), -1);
        mContainerView.setVisibility(View.GONE);
    }

    public void evaluateSuggestions() {
        TerminalSession session = mActivity.getCurrentSession();
        TerminalEmulator emulator = mTerminalView.mEmulator;
        if (!mSettings.isEnabled() || session == null || !session.isRunning() || emulator == null) {
            dismissSuggestions(false);
            return;
        }
        if (TerminalInputAnalyzer.isInteractiveMode(emulator, session.getPid())) {
            dismissSuggestions(false);
            return;
        }
        if (mTerminalView.isSelectingText() || mTerminalView.getTopRow() != 0) {
            mTerminalView.setGhostText(null);
            mContainerView.setVisibility(View.GONE);
            return;
        }

        TerminalInputAnalyzer.ParsedInput parsed = TerminalInputAnalyzer.analyzeEmulatorCursorLine(emulator);
        if (parsed == null || parsed.commandLine.trim().isEmpty() || !parsed.atEndOfLine) {
            dismissSuggestions(false);
            mLastEvaluatedCommandLine = "";
            return;
        }

        if (!parsed.commandLine.equals(mLastEvaluatedCommandLine)) {
            mLastEvaluatedCommandLine = parsed.commandLine;
        }

        if (mDismissedForCurrentLine) {
            return;
        }

        String cwd = null;
        try {
            cwd = session.getCwd();
        } catch (Exception ignored) {}

        mEngine.requestSuggestionsAsync(parsed, cwd, (suggestions, inlineGhostSuffix) -> {
            if (mDismissedForCurrentLine || !mSettings.isEnabled()) return;
            mCurrentSuggestions = suggestions;
            mSelectedIndex = -1;
            mActiveGhostSuffix = inlineGhostSuffix;
            mTerminalView.setGhostText(mSettings.isGhostTextEnabled() ? inlineGhostSuffix : null);

            if (mSettings.isDropdownEnabled() && !suggestions.isEmpty()) {
                mAdapter.submit(suggestions, mSelectedIndex);
                mContainerView.setVisibility(View.VISIBLE);
            } else {
                mAdapter.submit(Collections.emptyList(), -1);
                mContainerView.setVisibility(View.GONE);
            }
        });
    }

    private void updateSelectionUi() {
        mAdapter.submit(mCurrentSuggestions, mSelectedIndex);
        if (mSelectedIndex >= 0 && mSelectedIndex < mCurrentSuggestions.size()) {
            mRecyclerView.scrollToPosition(mSelectedIndex);
            String ghost = mCurrentSuggestions.get(mSelectedIndex).getInsertSuffix();
            mActiveGhostSuffix = ghost;
            mTerminalView.setGhostText(mSettings.isGhostTextEnabled() ? ghost : null);
        } else if (!mCurrentSuggestions.isEmpty()) {
            String ghost = mCurrentSuggestions.get(0).getInsertSuffix();
            mActiveGhostSuffix = ghost;
            mTerminalView.setGhostText(mSettings.isGhostTextEnabled() ? ghost : null);
        }
    }

    private void captureSubmittedCommandFromScreen() {
        TerminalSession session = mActivity.getCurrentSession();
        TerminalEmulator emulator = mTerminalView.mEmulator;
        if (session == null || emulator == null) return;
        if (TerminalInputAnalyzer.isInteractiveMode(emulator, session.getPid())) return;
        TerminalInputAnalyzer.ParsedInput parsed = TerminalInputAnalyzer.analyzeEmulatorCursorLine(emulator);
        if (parsed != null && !parsed.commandLine.trim().isEmpty()) {
            String cmd = parsed.commandLine.trim();
            mEngine.recordSubmittedCommand(cmd);
            TerminalCommandBridge.updateLastCommand(cmd);
        }
    }

    private static final class SuggestionAdapter extends RecyclerView.Adapter<SuggestionAdapter.VH> {

        interface OnItemClick {
            void onClick(int position);
        }

        private final OnItemClick mOnClick;
        private List<SuggestionItem> mItems = new ArrayList<>();
        private int mSelected = -1;

        SuggestionAdapter(OnItemClick onClick) {
            mOnClick = onClick;
        }

        void submit(List<SuggestionItem> items, int selectedIndex) {
            mItems = new ArrayList<>(items);
            mSelected = selectedIndex;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_autocomplete_suggestion, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            SuggestionItem item = mItems.get(position);
            holder.badge.setText(item.getType().getBadge());
            holder.text.setText(item.getDisplayText());
            holder.desc.setText(item.getDescription());
            holder.itemView.setBackgroundColor(position == mSelected ? 0x3358A6FF : 0x00000000);
            holder.itemView.setOnClickListener(v -> mOnClick.onClick(holder.getAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView badge;
            final TextView text;
            final TextView desc;

            VH(@NonNull View itemView) {
                super(itemView);
                badge = itemView.findViewById(R.id.autocomplete_item_badge);
                text = itemView.findViewById(R.id.autocomplete_item_text);
                desc = itemView.findViewById(R.id.autocomplete_item_desc);
            }
        }
    }
}
