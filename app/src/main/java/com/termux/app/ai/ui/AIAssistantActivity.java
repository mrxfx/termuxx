package com.termux.app.ai.ui;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.ai.AIManager;
import com.termux.app.ai.AISettingsManager;
import com.termux.app.ai.TerminalCommandBridge;
import com.termux.app.ai.context.AIContextManager;
import com.termux.app.ai.context.AIContextSnapshot;
import com.termux.app.ai.model.AIError;
import com.termux.app.ai.model.AIMessage;
import com.termux.app.ai.model.AIProviderType;
import com.termux.app.ai.model.AIResponse;
import com.termux.app.ai.model.AIStreamCallback;
import com.termux.app.ai.model.CancellationSignalHandle;
import com.termux.app.ai.security.SensitiveDataRedactor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * On-demand Termux AI Assistant Activity.
 * - Never auto-executes generated commands; "Insert" puts text into terminal input only.
 * - Supports Gemini + OpenRouter streaming, Stop/Cancel, Retry, Regenerate, Clear Chat,
 *   explicit file attachment, and live redacted Context Preview.
 */
public class AIAssistantActivity extends AppCompatActivity implements AIMessageAdapter.ActionListener {

    public static final String EXTRA_INITIAL_PROMPT = "ai_initial_prompt";
    public static final String EXTRA_SELECTED_TEXT = "ai_selected_text";

    private AIManager mAiManager;
    private AIMessageAdapter mAdapter;
    private final List<AIMessage> mMessages = new ArrayList<>();
    private final List<AIContextSnapshot.AttachedFile> mAttachedFiles = new ArrayList<>();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private CancellationSignalHandle mActiveRequestHandle;
    private boolean mIsGenerating = false;

    private TextView mSubtitleView;
    private View mMissingKeyBanner;
    private TextView mMissingKeyText;
    private TextView mAttachedBanner;
    private View mEmptyStateView;
    private RecyclerView mRecyclerView;
    private EditText mPromptInput;
    private ImageButton mSendStopButton;

    private final ActivityResultLauncher<String[]> mFilePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                attachDocumentUri(uri);
            }
        });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_assistant);

        mAiManager = AIManager.getInstance(this);

        mSubtitleView = findViewById(R.id.ai_provider_model_subtitle);
        mMissingKeyBanner = findViewById(R.id.ai_missing_key_banner);
        mMissingKeyText = findViewById(R.id.ai_missing_key_text);
        mAttachedBanner = findViewById(R.id.ai_attached_files_banner);
        mEmptyStateView = findViewById(R.id.ai_empty_state);
        mRecyclerView = findViewById(R.id.ai_messages_recycler);
        mPromptInput = findViewById(R.id.ai_prompt_input);
        mSendStopButton = findViewById(R.id.ai_send_stop_button);

        mAdapter = new AIMessageAdapter(this, this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        mRecyclerView.setLayoutManager(lm);
        mRecyclerView.setAdapter(mAdapter);

        findViewById(R.id.ai_back_button).setOnClickListener(v -> finish());
        findViewById(R.id.ai_settings_button).setOnClickListener(v -> openAISettings());
        findViewById(R.id.ai_configure_key_button).setOnClickListener(v -> openAISettings());
        findViewById(R.id.ai_context_preview_button).setOnClickListener(v -> showContextPreviewDialog());
        findViewById(R.id.ai_clear_chat_button).setOnClickListener(v -> confirmClearChat());

        findViewById(R.id.ai_chip_fix_error).setOnClickListener(v ->
            submitPrompt("Analyze the recent terminal output/error and suggest a safe command to fix it.", true));
        findViewById(R.id.ai_chip_explain_last).setOnClickListener(v ->
            submitPrompt("Explain my last terminal command, its flags, and any side effects.", false));
        findViewById(R.id.ai_chip_bash_script).setOnClickListener(v ->
            mPromptInput.setText(R.string.ai_template_bash_script));
        findViewById(R.id.ai_chip_attach_file).setOnClickListener(v ->
            showAttachFileChooser());

        mSendStopButton.setOnClickListener(v -> {
            if (mIsGenerating) {
                stopActiveGeneration();
            } else {
                submitPrompt(mPromptInput.getText().toString(), false);
            }
        });

        mPromptInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND && !mIsGenerating) {
                submitPrompt(mPromptInput.getText().toString(), false);
                return true;
            }
            return false;
        });

        mMessages.addAll(mAiManager.getConversationStore().loadMessages());
        refreshChatList();

        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHeaderStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mActiveRequestHandle != null) {
            mActiveRequestHandle.cancel();
            mActiveRequestHandle = null;
        }
    }

    private void handleIncomingIntent(@Nullable Intent intent) {
        if (intent == null) return;
        String selectedText = intent.getStringExtra(EXTRA_SELECTED_TEXT);
        String initialPrompt = intent.getStringExtra(EXTRA_INITIAL_PROMPT);
        if (selectedText != null && !selectedText.trim().isEmpty()) {
            String redacted = SensitiveDataRedactor.redact(selectedText.trim());
            mPromptInput.setText("Explain this terminal text or error:\n```\n" + redacted + "\n```");
            mPromptInput.setSelection(mPromptInput.getText().length());
        } else if (initialPrompt != null && !initialPrompt.trim().isEmpty()) {
            mPromptInput.setText(initialPrompt);
            mPromptInput.setSelection(mPromptInput.getText().length());
        }
    }

    private void updateHeaderStatus() {
        AISettingsManager settings = mAiManager.getSettings();
        AIProviderType provider = settings.getActiveProvider();
        String model = settings.getModelForProvider(provider);
        mSubtitleView.setText(provider.getDisplayName() + " · " + model);

        boolean hasKey = mAiManager.getCredentials().hasApiKey(provider);
        if (hasKey) {
            mMissingKeyBanner.setVisibility(View.GONE);
        } else {
            mMissingKeyBanner.setVisibility(View.VISIBLE);
            mMissingKeyText.setText(getString(R.string.ai_missing_key_for_provider, provider.getDisplayName()));
        }
    }

    private void submitPrompt(@NonNull String rawPrompt, boolean forceIncludeOutput) {
        if (mIsGenerating) return;
        String prompt = rawPrompt.trim();
        if (prompt.isEmpty()) return;

        mPromptInput.setText("");
        AIMessage userMsg = new AIMessage(AIMessage.Role.USER, SensitiveDataRedactor.redact(prompt));
        mMessages.add(userMsg);
        startAssistantResponse(forceIncludeOutput);
    }

    private void startAssistantResponse(boolean forceIncludeOutput) {
        AISettingsManager settings = mAiManager.getSettings();
        AIProviderType activeProvider = settings.getActiveProvider();
        String activeModel = settings.getModelForProvider(activeProvider);

        List<AIMessage> requestHistory = new ArrayList<>(mMessages);
        AIMessage assistantMsg = new AIMessage(AIMessage.Role.ASSISTANT, "");
        assistantMsg.setProvider(activeProvider);
        assistantMsg.setModelId(activeModel);
        assistantMsg.setStreaming(true);
        mMessages.add(assistantMsg);

        setGeneratingState(true);
        refreshChatList();

        AIContextSnapshot snapshot = TerminalCommandBridge.captureSnapshot(mAttachedFiles);
        String redactedContext = AIContextManager.buildContextPayload(settings, snapshot, forceIncludeOutput);

        mActiveRequestHandle = mAiManager.sendConversation(requestHistory, redactedContext, new AIStreamCallback() {
            @Override
            public void onChunk(@NonNull String delta, @NonNull String accumulated) {
                mMainHandler.post(() -> {
                    assistantMsg.setContent(accumulated);
                    mAdapter.notifyItemChanged(mMessages.size() - 1);
                    mRecyclerView.scrollToPosition(mMessages.size() - 1);
                });
            }

            @Override
            public void onComplete(@NonNull AIResponse response) {
                mMainHandler.post(() -> {
                    assistantMsg.setStreaming(false);
                    assistantMsg.setContent(response.getText());
                    assistantMsg.setProvider(response.getProvider());
                    assistantMsg.setModelId(response.getModelId());
                    assistantMsg.setUsage(response.getUsage());
                    assistantMsg.setErrorMessage(null);
                    setGeneratingState(false);
                    mAiManager.getConversationStore().saveMessages(mMessages);
                    refreshChatList();
                });
            }

            @Override
            public void onError(@NonNull AIError error) {
                mMainHandler.post(() -> {
                    assistantMsg.setStreaming(false);
                    if (error.getType() == AIError.Type.CANCELLED) {
                        if (assistantMsg.getContent().trim().isEmpty()) {
                            assistantMsg.setContent("(Generation stopped)");
                        }
                    } else {
                        assistantMsg.setErrorMessage(error.getMessage());
                    }
                    setGeneratingState(false);
                    mAiManager.getConversationStore().saveMessages(mMessages);
                    refreshChatList();
                });
            }
        });
    }

    private void stopActiveGeneration() {
        if (mActiveRequestHandle != null) {
            mActiveRequestHandle.cancel();
        }
    }

    private void setGeneratingState(boolean generating) {
        mIsGenerating = generating;
        mSendStopButton.setImageResource(generating ? R.drawable.ic_stop : R.drawable.ic_send);
        mSendStopButton.setContentDescription(getString(generating ? R.string.ai_stop_generation : R.string.ai_send_prompt));
    }

    private void refreshChatList() {
        mAdapter.setMessages(mMessages);
        mEmptyStateView.setVisibility(mMessages.isEmpty() ? View.VISIBLE : View.GONE);
        if (!mMessages.isEmpty()) {
            mRecyclerView.scrollToPosition(mMessages.size() - 1);
        }
    }

    private void openAISettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.putExtra(SettingsActivity.EXTRA_INITIAL_FRAGMENT, "com.termux.app.ai.ui.AISettingsFragment");
        startActivity(intent);
    }

    private void confirmClearChat() {
        if (mMessages.isEmpty()) return;
        new AlertDialog.Builder(this)
            .setTitle(R.string.ai_clear_chat)
            .setMessage(R.string.ai_clear_chat_confirm)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                stopActiveGeneration();
                mMessages.clear();
                mAiManager.getConversationStore().clearHistory();
                refreshChatList();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showContextPreviewDialog() {
        AIContextSnapshot snapshot = TerminalCommandBridge.captureSnapshot(mAttachedFiles);
        String preview = AIContextManager.buildContextPayload(mAiManager.getSettings(), snapshot, false);
        if (preview.isEmpty()) {
            preview = getString(R.string.ai_context_preview_empty);
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.ai_context_preview_title)
            .setMessage(preview)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.ai_context_configure_button, (d, w) -> openAISettings())
            .show();
    }

    private void showAttachFileChooser() {
        AIContextSnapshot snapshot = TerminalCommandBridge.captureSnapshot(mAttachedFiles);
        List<File> cwdFiles = new ArrayList<>();
        if (snapshot.cwd != null) {
            File dir = new File(snapshot.cwd);
            File[] listed = dir.listFiles();
            if (listed != null) {
                for (File f : listed) {
                    if (f.isFile() && f.canRead() && !f.getName().startsWith(".")) {
                        cwdFiles.add(f);
                        if (cwdFiles.size() >= 12) break;
                    }
                }
            }
        }

        if (cwdFiles.isEmpty()) {
            mFilePickerLauncher.launch(new String[]{"*/*"});
            return;
        }

        String[] labels = new String[cwdFiles.size() + 1];
        for (int i = 0; i < cwdFiles.size(); i++) {
            labels[i] = cwdFiles.get(i).getName();
        }
        labels[cwdFiles.size()] = getString(R.string.ai_browse_system_files);

        new AlertDialog.Builder(this)
            .setTitle(R.string.ai_quick_attach_file)
            .setItems(labels, (d, which) -> {
                if (which == cwdFiles.size()) {
                    mFilePickerLauncher.launch(new String[]{"*/*"});
                } else {
                    try {
                        AIContextSnapshot.AttachedFile attached = AIContextManager.readProjectFileSafely(cwdFiles.get(which));
                        if (attached != null) {
                            addAttachedFile(attached);
                        }
                    } catch (Exception e) {
                        Toast.makeText(this, "Could not read file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .show();
    }

    private void attachDocumentUri(@NonNull Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int total = 0;
            int max = AIContextManager.MAX_ATTACHED_FILE_CHARS * 2;
            int n;
            while (total < max && (n = is.read(buf, 0, Math.min(buf.length, max - total))) != -1) {
                baos.write(buf, 0, n);
                total += n;
            }
            String text = SensitiveDataRedactor.redact(new String(baos.toByteArray(), StandardCharsets.UTF_8));
            String name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "attached_file";
            addAttachedFile(new AIContextSnapshot.AttachedFile(name, uri.toString(), text));
        } catch (Exception e) {
            Toast.makeText(this, "Failed to attach file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void addAttachedFile(@NonNull AIContextSnapshot.AttachedFile file) {
        mAttachedFiles.add(file);
        StringBuilder sb = new StringBuilder("Attached: ");
        for (int i = 0; i < mAttachedFiles.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(mAttachedFiles.get(i).name);
        }
        mAttachedBanner.setText(sb.toString());
        mAttachedBanner.setVisibility(View.VISIBLE);
    }

    @Override
    public void onCopyCode(@NonNull String code) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Termux AI Command", code));
            Toast.makeText(this, R.string.ai_code_copied, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onInsertCode(@NonNull String code) {
        boolean inserted = TerminalCommandBridge.insertIntoActiveTerminal(code);
        Toast.makeText(
            this,
            inserted ? R.string.ai_code_inserted : R.string.ai_code_queued_for_insert,
            Toast.LENGTH_SHORT
        ).show();
        finish();
    }

    @Override
    public void onExplainCode(@NonNull String code) {
        if (mIsGenerating) return;
        submitPrompt("Explain this command and each flag clearly:\n```bash\n" + code + "\n```", false);
    }

    @Override
    public void onRegenerate() {
        if (mIsGenerating || mMessages.isEmpty()) return;
        if (mMessages.get(mMessages.size() - 1).getRole() == AIMessage.Role.ASSISTANT) {
            mMessages.remove(mMessages.size() - 1);
            startAssistantResponse(false);
        }
    }

    @Override
    public void onRetry() {
        onRegenerate();
    }
}
