package com.termux.app.workspace;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.termux.R;
import com.termux.app.ai.TerminalCommandBridge;
import com.termux.app.ai.ui.AIAssistantActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Screen 4: Command Snippet Manager (Samsung One UI Dark Black & White).
 * Supports saving commands, custom titles, 11 categories, tags, favorites, search,
 * duplicate, import/export JSON (SAF), edit, delete, copy, edit-before-insert,
 * AI command explanation, and non-executing insertion into the active terminal.
 */
public class CommandSnippetActivity extends AppCompatActivity {

    private SnippetRepository mSnippetRepo;
    private LinearLayout mContentContainer;
    private LinearLayout mSnippetListContainer;

    private String mSearchQuery = "";
    private String mSelectedCategory = "All";
    private boolean mFavoritesOnly = false;

    private final ActivityResultLauncher<String[]> mImportLauncher =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onImportUriSelected);

    private final ActivityResultLauncher<String> mExportLauncher =
        registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::onExportUriSelected);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_workspace_screen_shell);

        mSnippetRepo = new SnippetRepository(this);

        ImageButton backBtn = findViewById(R.id.btn_workspace_back);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        TextView titleView = findViewById(R.id.text_workspace_screen_title);
        if (titleView != null) titleView.setText(R.string.title_snippet_manager);

        TextView subtitleView = findViewById(R.id.text_workspace_screen_subtitle);
        if (subtitleView != null) subtitleView.setText(R.string.subtitle_snippet_manager);

        Button topBtn = findViewById(R.id.btn_workspace_top_action);
        if (topBtn != null) {
            topBtn.setVisibility(View.VISIBLE);
            topBtn.setText(R.string.action_new_snippet);
            topBtn.setOnClickListener(v -> showSnippetEditorDialog(null));
        }

        mContentContainer = findViewById(R.id.workspace_content_container);
        buildFilterAndListLayout();
        refreshSnippetsList();
    }

    private void buildFilterAndListLayout() {
        if (mContentContainer == null) return;
        mContentContainer.removeAllViews();

        // Search, Import/Export & Filter Card
        LinearLayout filterCard = WorkspaceUiFactory.createCard(this);
        filterCard.addView(WorkspaceUiFactory.createTitle(this, "Search, Filter & Share Snippets"));
        filterCard.addView(WorkspaceUiFactory.createSubtitle(this,
            "All saved commands remain 100% user-controlled and never execute automatically."));

        LinearLayout ioRow = new LinearLayout(this);
        ioRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ioLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ioLp.topMargin = WorkspaceUiFactory.dp(this, 8);
        ioLp.bottomMargin = WorkspaceUiFactory.dp(this, 4);
        ioRow.setLayoutParams(ioLp);

        Button importBtn = WorkspaceUiFactory.createSecondaryButton(this, "Import JSON");
        LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        w1.rightMargin = WorkspaceUiFactory.dp(this, 8);
        importBtn.setLayoutParams(w1);
        importBtn.setOnClickListener(v -> mImportLauncher.launch(new String[]{"application/json", "text/*"}));
        ioRow.addView(importBtn);

        Button exportBtn = WorkspaceUiFactory.createSecondaryButton(this, "Export JSON");
        exportBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        exportBtn.setOnClickListener(v -> mExportLauncher.launch("termux_ai_snippets.json"));
        ioRow.addView(exportBtn);
        filterCard.addView(ioRow);

        EditText searchInput = WorkspaceUiFactory.createInput(
            this, "Search title, command, category, tags, or description...", mSearchQuery, false);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mSearchQuery = s != null ? s.toString() : "";
                refreshSnippetsList();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        filterCard.addView(searchInput);

        // Category Filter Chips
        HorizontalScrollView catScroll = new HorizontalScrollView(this);
        catScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout catRow = new LinearLayout(this);
        catRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams catRowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        catRowLp.topMargin = WorkspaceUiFactory.dp(this, 8);
        catScroll.setLayoutParams(catRowLp);

        addFilterChip(catRow, "All", !mFavoritesOnly && "All".equalsIgnoreCase(mSelectedCategory), () -> {
            mFavoritesOnly = false;
            mSelectedCategory = "All";
            buildFilterAndListLayout();
            refreshSnippetsList();
        });

        addFilterChip(catRow, "★ Favorites", mFavoritesOnly, () -> {
            mFavoritesOnly = !mFavoritesOnly;
            buildFilterAndListLayout();
            refreshSnippetsList();
        });

        for (String cat : CommandSnippet.CATEGORIES) {
            boolean selected = !mFavoritesOnly && cat.equalsIgnoreCase(mSelectedCategory);
            addFilterChip(catRow, cat, selected, () -> {
                mFavoritesOnly = false;
                mSelectedCategory = cat;
                buildFilterAndListLayout();
                refreshSnippetsList();
            });
        }

        catScroll.addView(catRow);
        filterCard.addView(catScroll);
        mContentContainer.addView(filterCard);

        mSnippetListContainer = new LinearLayout(this);
        mSnippetListContainer.setOrientation(LinearLayout.VERTICAL);
        mContentContainer.addView(mSnippetListContainer);
    }

    private void addFilterChip(
        @NonNull LinearLayout row,
        @NonNull String label,
        boolean selected,
        @NonNull Runnable onClick
    ) {
        Button chip = selected
            ? WorkspaceUiFactory.createPrimaryButton(this, label)
            : WorkspaceUiFactory.createSecondaryButton(this, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = WorkspaceUiFactory.dp(this, 8);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> onClick.run());
        row.addView(chip);
    }

    private void refreshSnippetsList() {
        if (mSnippetListContainer == null) return;
        mSnippetListContainer.removeAllViews();

        List<CommandSnippet> list = mSnippetRepo.querySnippets(
            mSearchQuery, mSelectedCategory, mFavoritesOnly);

        mSnippetListContainer.addView(WorkspaceUiFactory.createSectionHeader(
            this, "Snippets (" + list.size() + ")"));

        if (list.isEmpty()) {
            LinearLayout emptyCard = WorkspaceUiFactory.createCard(this);
            emptyCard.addView(WorkspaceUiFactory.createTitle(this, "No matching command snippets"));
            emptyCard.addView(WorkspaceUiFactory.createSubtitle(
                this, "Try clearing filters or tap '+ Snippet' to create a reusable command."));
            mSnippetListContainer.addView(emptyCard);
            return;
        }

        for (CommandSnippet s : list) {
            mSnippetListContainer.addView(buildSnippetCard(s));
        }
    }

    @NonNull
    private View buildSnippetCard(@NonNull CommandSnippet s) {
        LinearLayout card = WorkspaceUiFactory.createCard(this);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = WorkspaceUiFactory.createTitle(
            this, (s.favorite ? "★ " : "") + s.title);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(WorkspaceUiFactory.createBadge(this, s.category, s.favorite));
        card.addView(header);

        if (!s.description.isEmpty()) {
            card.addView(WorkspaceUiFactory.createSubtitle(this, s.description));
        }
        if (!s.tags.isEmpty()) {
            card.addView(WorkspaceUiFactory.createSubtitle(this, "Tags: " + s.tags));
        }

        card.addView(WorkspaceUiFactory.createMonospaceBlock(this, s.command));

        // Row 1: Insert into Terminal, Edit & Insert, Copy
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1Lp.topMargin = WorkspaceUiFactory.dp(this, 12);
        row1.setLayoutParams(r1Lp);

        Button insertBtn = WorkspaceUiFactory.createPrimaryButton(this, "Insert");
        LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        w1.rightMargin = WorkspaceUiFactory.dp(this, 6);
        insertBtn.setLayoutParams(w1);
        insertBtn.setOnClickListener(v -> insertSnippetCommand(s.command));
        row1.addView(insertBtn);

        Button editInsertBtn = WorkspaceUiFactory.createSecondaryButton(this, "Review/Insert");
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f);
        w2.rightMargin = WorkspaceUiFactory.dp(this, 6);
        editInsertBtn.setLayoutParams(w2);
        editInsertBtn.setOnClickListener(v -> showEditBeforeInsertDialog(s));
        row1.addView(editInsertBtn);

        Button copyBtn = WorkspaceUiFactory.createSecondaryButton(this, "Copy");
        copyBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f));
        copyBtn.setOnClickListener(v -> {
            copyToClipboard(s.command);
            Toast.makeText(this, "Command copied", Toast.LENGTH_SHORT).show();
        });
        row1.addView(copyBtn);
        card.addView(row1);

        // Row 2: Explain with AI, Favorite, Duplicate, Edit, Delete
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2Lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2Lp.topMargin = WorkspaceUiFactory.dp(this, 8);
        row2.setLayoutParams(r2Lp);

        Button explainBtn = WorkspaceUiFactory.createSecondaryButton(this, "Explain");
        LinearLayout.LayoutParams f0 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        f0.rightMargin = WorkspaceUiFactory.dp(this, 4);
        explainBtn.setLayoutParams(f0);
        explainBtn.setOnClickListener(v -> {
            Intent aiIntent = new Intent(this, AIAssistantActivity.class);
            aiIntent.putExtra(AIAssistantActivity.EXTRA_INITIAL_PROMPT,
                "Explain this terminal command, its flags, and any potential side effects:\n```bash\n" + s.command + "\n```");
            startActivity(aiIntent);
        });
        row2.addView(explainBtn);

        Button favBtn = WorkspaceUiFactory.createSecondaryButton(this, s.favorite ? "★" : "☆");
        LinearLayout.LayoutParams f1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f);
        f1.rightMargin = WorkspaceUiFactory.dp(this, 4);
        favBtn.setLayoutParams(f1);
        favBtn.setOnClickListener(v -> {
            mSnippetRepo.toggleFavorite(s.id);
            refreshSnippetsList();
        });
        row2.addView(favBtn);

        Button dupBtn = WorkspaceUiFactory.createSecondaryButton(this, "Dup");
        LinearLayout.LayoutParams fDup = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f);
        fDup.rightMargin = WorkspaceUiFactory.dp(this, 4);
        dupBtn.setLayoutParams(fDup);
        dupBtn.setOnClickListener(v -> {
            mSnippetRepo.duplicateSnippet(s.id, s.title + " (Copy)");
            refreshSnippetsList();
        });
        row2.addView(dupBtn);

        Button editBtn = WorkspaceUiFactory.createSecondaryButton(this, "Edit");
        LinearLayout.LayoutParams f2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f);
        f2.rightMargin = WorkspaceUiFactory.dp(this, 4);
        editBtn.setLayoutParams(f2);
        editBtn.setOnClickListener(v -> showSnippetEditorDialog(s));
        row2.addView(editBtn);

        Button delBtn = WorkspaceUiFactory.createDangerButton(this, "Del");
        delBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f));
        delBtn.setOnClickListener(v -> confirmDeleteSnippet(s));
        row2.addView(delBtn);

        card.addView(row2);
        return card;
    }

    private void insertSnippetCommand(@NonNull String command) {
        boolean ok = TerminalCommandBridge.insertIntoActiveTerminal(command);
        if (ok) {
            Toast.makeText(this, "Inserted into terminal (not executed)", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            copyToClipboard(command);
            Toast.makeText(this, "Queued for terminal & copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void showEditBeforeInsertDialog(@NonNull CommandSnippet s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = WorkspaceUiFactory.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText cmdInput = WorkspaceUiFactory.createInput(this, "Command to insert", s.command, true);
        box.addView(cmdInput);

        new AlertDialog.Builder(this)
            .setTitle("Review & Edit Before Insertion")
            .setMessage("Customize arguments before inserting into the terminal input line (will not execute automatically):")
            .setView(box)
            .setPositiveButton("Insert into Terminal", (d, w) -> {
                String edited = cmdInput.getText().toString().trim();
                if (!edited.isEmpty()) {
                    insertSnippetCommand(edited);
                }
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void showSnippetEditorDialog(@Nullable CommandSnippet existing) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = WorkspaceUiFactory.dp(this, 20);
        form.setPadding(pad, pad / 2, pad, 0);

        EditText titleEt = WorkspaceUiFactory.createInput(
            this, "Snippet Title", existing != null ? existing.title : "", false);
        form.addView(WorkspaceUiFactory.createSubtitle(this, "Title"));
        form.addView(titleEt);

        EditText cmdEt = WorkspaceUiFactory.createInput(
            this, "Shell Command", existing != null ? existing.command : "", true);
        form.addView(WorkspaceUiFactory.createSubtitle(this, "Command (Never auto-executed)"));
        form.addView(cmdEt);

        Spinner catSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, CommandSnippet.CATEGORIES);
        catSpinner.setAdapter(adapter);
        if (existing != null) {
            int idx = CommandSnippet.CATEGORIES.indexOf(existing.category);
            if (idx >= 0) catSpinner.setSelection(idx);
        }
        form.addView(WorkspaceUiFactory.createSubtitle(this, "Category"));
        form.addView(catSpinner);

        EditText descEt = WorkspaceUiFactory.createInput(
            this, "Optional description", existing != null ? existing.description : "", false);
        form.addView(WorkspaceUiFactory.createSubtitle(this, "Description"));
        form.addView(descEt);

        EditText tagsEt = WorkspaceUiFactory.createInput(
            this, "Comma-separated tags (e.g. git,adb,debug)", existing != null ? existing.tags : "", false);
        form.addView(WorkspaceUiFactory.createSubtitle(this, "Tags"));
        form.addView(tagsEt);

        SwitchCompat favSwitch = WorkspaceUiFactory.createSwitchRow(
            this, form, "Mark as Favorite", existing != null && existing.favorite);

        scroll.addView(form);

        new AlertDialog.Builder(this)
            .setTitle(existing != null ? "Edit Command Snippet" : "Save Command Snippet")
            .setView(scroll)
            .setPositiveButton("Save", (d, w) -> {
                String title = titleEt.getText().toString().trim();
                String cmd = cmdEt.getText().toString().trim();
                if (title.isEmpty() || cmd.isEmpty()) {
                    Toast.makeText(this, "Title and command are required.", Toast.LENGTH_SHORT).show();
                    return;
                }
                String id = existing != null
                    ? existing.id
                    : "snip_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                CommandSnippet saved = new CommandSnippet(
                    id,
                    title,
                    cmd,
                    catSpinner.getSelectedItem().toString(),
                    descEt.getText().toString().trim(),
                    tagsEt.getText().toString().trim(),
                    favSwitch.isChecked(),
                    System.currentTimeMillis()
                );
                mSnippetRepo.saveSnippet(saved);
                refreshSnippetsList();
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void confirmDeleteSnippet(@NonNull CommandSnippet s) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Snippet")
            .setMessage("Delete command snippet '" + s.title + "'?")
            .setPositiveButton("Delete", (d, w) -> {
                mSnippetRepo.deleteSnippet(s.id);
                refreshSnippetsList();
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }

    private void onImportUriSelected(@Nullable Uri uri) {
        if (uri == null) return;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            String json = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            int count = mSnippetRepo.importSnippetsFromJson(json);
            Toast.makeText(this, "Imported " + count + " snippet(s).", Toast.LENGTH_SHORT).show();
            refreshSnippetsList();
        } catch (Exception e) {
            Toast.makeText(this, "Invalid snippet JSON: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onExportUriSelected(@Nullable Uri uri) {
        if (uri == null) return;
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) return;
            String json = mSnippetRepo.exportSnippetsToJson();
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
            Toast.makeText(this, "Exported snippets to JSON.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void copyToClipboard(@NonNull String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Termux Snippet", text));
        }
    }
}
