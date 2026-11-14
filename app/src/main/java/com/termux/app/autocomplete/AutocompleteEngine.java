package com.termux.app.autocomplete;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.ai.TerminalCommandBridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Offline-first autocomplete orchestrator.
 * - Zero network calls and zero AI calls during typing.
 * - Runs indexing & filesystem scans on a dedicated background executor with debouncing.
 * - Ranks and deduplicates results across History, Context-Aware, Command, Directory, File, and Package sources.
 */
public class AutocompleteEngine {

    public interface Callback {
        void onSuggestionsReady(@NonNull List<SuggestionItem> suggestions, @Nullable String inlineGhostSuffix);
    }

    private static final long DEBOUNCE_DELAY_MS = 35L;

    private final AutocompleteSettingsManager mSettings;
    private final CommandIndexCache mCommandIndex;
    private final CommandHistoryStore mHistoryStore;
    private final FileAndDirectorySuggester mFileSuggester;
    private final InstalledPackageSuggester mPackageSuggester;
    private final ContextAwareSuggester mContextSuggester;

    private final ExecutorService mBgExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong mGeneration = new AtomicLong(0);
    private Runnable mPendingTask;

    public AutocompleteEngine(@NonNull Context context) {
        this(
            new AutocompleteSettingsManager(context),
            new CommandIndexCache(),
            new CommandHistoryStore(context),
            new FileAndDirectorySuggester(),
            new InstalledPackageSuggester(),
            new ContextAwareSuggester()
        );
    }

    public AutocompleteEngine(
        @NonNull AutocompleteSettingsManager settings,
        @NonNull CommandIndexCache commandIndex,
        @NonNull CommandHistoryStore historyStore,
        @NonNull FileAndDirectorySuggester fileSuggester,
        @NonNull InstalledPackageSuggester packageSuggester,
        @NonNull ContextAwareSuggester contextSuggester
    ) {
        mSettings = settings;
        mCommandIndex = commandIndex;
        mHistoryStore = historyStore;
        mFileSuggester = fileSuggester;
        mPackageSuggester = packageSuggester;
        mContextSuggester = contextSuggester;
    }

    public void warmUpCachesAsync() {
        if (mBgExecutor.isShutdown()) return;
        mBgExecutor.execute(() -> {
            mCommandIndex.getIndex();
            TerminalCommandBridge.updateAvailableTools(mCommandIndex.getAvailableKnownTools());
            mPackageSuggester.getInstalledPackages();
            mHistoryStore.refreshShellHistoryFiles();
        });
    }

    public void recordSubmittedCommand(@Nullable String command) {
        if (command == null || command.trim().isEmpty() || mBgExecutor.isShutdown()) return;
        mBgExecutor.execute(() -> mHistoryStore.recordCommand(command));
    }

    public void clearHistory() {
        if (mBgExecutor.isShutdown()) return;
        mBgExecutor.execute(mHistoryStore::clearHistory);
    }

    public void invalidateCommandCache() {
        if (mBgExecutor.isShutdown()) return;
        mBgExecutor.execute(mCommandIndex::invalidate);
    }

    @NonNull
    public List<String> getAvailableKnownToolsSnapshot() {
        return mCommandIndex.getAvailableKnownTools();
    }

    public void requestSuggestionsAsync(
        @Nullable TerminalInputAnalyzer.ParsedInput parsedInput,
        @Nullable String cwd,
        @NonNull Callback callback
    ) {
        long gen = mGeneration.incrementAndGet();
        if (mPendingTask != null) {
            mMainHandler.removeCallbacks(mPendingTask);
        }

        if (!mSettings.isEnabled() || mBgExecutor.isShutdown() || parsedInput == null || !parsedInput.atEndOfLine || parsedInput.commandLine.trim().isEmpty()) {
            callback.onSuggestionsReady(Collections.emptyList(), null);
            return;
        }

        mPendingTask = () -> {
            if (mBgExecutor.isShutdown()) return;
            mBgExecutor.execute(() -> {
                if (mGeneration.get() != gen) return;
                List<SuggestionItem> suggestions = computeSuggestionsSync(parsedInput, cwd);
                if (mGeneration.get() != gen) return;

                String ghost = null;
                if (mSettings.isGhostTextEnabled() && !suggestions.isEmpty()) {
                    SuggestionItem top = suggestions.get(0);
                    if (!top.getInsertSuffix().isEmpty()) {
                        ghost = top.getInsertSuffix();
                    }
                }
                List<SuggestionItem> dropdown = mSettings.isDropdownEnabled() ? suggestions : Collections.emptyList();
                String finalGhost = ghost;
                mMainHandler.post(() -> {
                    if (mGeneration.get() == gen) {
                        callback.onSuggestionsReady(dropdown, finalGhost);
                    }
                });
            });
        };

        mMainHandler.postDelayed(mPendingTask, DEBOUNCE_DELAY_MS);
    }

    public void cancelPending() {
        mGeneration.incrementAndGet();
        if (mPendingTask != null) {
            mMainHandler.removeCallbacks(mPendingTask);
            mPendingTask = null;
        }
    }

    public void shutdown() {
        cancelPending();
        mBgExecutor.shutdown();
    }

    @NonNull
    public List<SuggestionItem> computeSuggestionsSync(
        @NonNull TerminalInputAnalyzer.ParsedInput input,
        @Nullable String cwd
    ) {
        if (!mSettings.isEnabled() || input.commandLine.trim().isEmpty()) {
            return Collections.emptyList();
        }

        int maxResults = mSettings.getMaxSuggestions();
        List<SuggestionItem> pool = new ArrayList<>();

        // 1. Command history (whole-line prefix match)
        if (mSettings.isHistoryEnabled()) {
            pool.addAll(mHistoryStore.matchPrefix(input.commandLine, maxResults));
        }

        // 2. Context-aware suggestions (git/python/node/pkg)
        pool.addAll(mContextSuggester.suggestForParsedInput(input, cwd, mCommandIndex, maxResults));

        // 3. Command name completion when typing first token (and not a path like ./script)
        if (input.isCommandPosition() && !looksLikePathToken(input.currentToken)) {
            pool.addAll(mCommandIndex.matchCommands(input.currentToken, maxResults));
        }

        // 4. Installed packages for pkg/apt/dpkg subcommands
        if (mSettings.isPackageSuggestionsEnabled() && input.completedTokens.size() >= 2) {
            String cmd = input.completedTokens.get(0);
            String sub = input.completedTokens.get(1);
            if (InstalledPackageSuggester.acceptsInstalledPackageArg(cmd, sub)) {
                pool.addAll(mPackageSuggester.suggestPackages(input.currentToken, maxResults));
            }
        }

        // 5. File and directory completion
        if (mSettings.isFileSuggestionsEnabled()) {
            boolean dirOnly = !input.isCommandPosition() && "cd".equals(input.getCommandName());
            if (!input.isCommandPosition() || looksLikePathToken(input.currentToken)) {
                pool.addAll(mFileSuggester.suggest(input.currentToken, cwd, dirOnly, maxResults));
            }
        }

        // Deduplicate by insertSuffix/displayText keeping highest ranked score
        pool.sort((a, b) -> {
            int cmp = Integer.compare(b.getScore(), a.getScore());
            if (cmp != 0) return cmp;
            return a.getDisplayText().compareTo(b.getDisplayText());
        });

        Map<String, SuggestionItem> deduped = new LinkedHashMap<>();
        for (SuggestionItem item : pool) {
            if (item.getInsertSuffix().isEmpty()) continue;
            String key = item.getDisplayText().toLowerCase() + "|" + item.getInsertSuffix();
            if (!deduped.containsKey(key)) {
                deduped.put(key, item);
            }
            if (deduped.size() >= maxResults) break;
        }

        return new ArrayList<>(deduped.values());
    }

    private static boolean looksLikePathToken(@NonNull String token) {
        return token.startsWith("./")
            || token.startsWith("../")
            || token.startsWith("~/")
            || token.startsWith("/")
            || token.contains("/");
    }
}
