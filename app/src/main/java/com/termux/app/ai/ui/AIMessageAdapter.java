package com.termux.app.ai.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.ai.model.AIMessage;
import com.termux.app.ai.model.AIUsage;

import java.util.ArrayList;
import java.util.List;

import io.noties.markwon.Markwon;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;

public class AIMessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface ActionListener {
        void onCopyCode(@NonNull String code);
        void onInsertCode(@NonNull String code);
        void onExplainCode(@NonNull String code);
        default void onReviewAndInsertCode(@NonNull String code) {
            onInsertCode(code);
        }
        default void onSaveCodeAsSnippet(@NonNull String code) {}
        void onRegenerate();
        void onRetry();
    }

    private static final int VIEW_TYPE_USER = 1;
    private static final int VIEW_TYPE_ASSISTANT = 2;

    private final Markwon mMarkwon;
    private final ActionListener mListener;
    private final List<AIMessage> mMessages = new ArrayList<>();

    public AIMessageAdapter(@NonNull Context context, @NonNull ActionListener listener) {
        mMarkwon = Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .build();
        mListener = listener;
    }

    public void setMessages(@NonNull List<AIMessage> messages) {
        mMessages.clear();
        mMessages.addAll(messages);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return mMessages.get(position).getRole() == AIMessage.Role.USER ? VIEW_TYPE_USER : VIEW_TYPE_ASSISTANT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_USER) {
            return new UserVH(inflater.inflate(R.layout.item_ai_user_message, parent, false));
        }
        return new AssistantVH(inflater.inflate(R.layout.item_ai_assistant_message, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        AIMessage msg = mMessages.get(position);
        if (holder instanceof UserVH) {
            ((UserVH) holder).textView.setText(msg.getContent());
        } else if (holder instanceof AssistantVH) {
            bindAssistant((AssistantVH) holder, msg, position == mMessages.size() - 1);
        }
    }

    @Override
    public int getItemCount() {
        return mMessages.size();
    }

    private void bindAssistant(@NonNull AssistantVH holder, @NonNull AIMessage msg, boolean isLast) {
        String providerLabel = msg.getProvider() != null ? msg.getProvider().getDisplayName() : "Assistant";
        String modelLabel = msg.getModelId() != null ? msg.getModelId() : "";
        String meta = modelLabel.isEmpty() ? providerLabel : (providerLabel + " · " + modelLabel);
        if (msg.isStreaming()) {
            meta += " (streaming...)";
        }
        holder.metaView.setText(meta);

        AIUsage usage = msg.getUsage();
        if (usage != null && usage.getTotalTokens() > 0) {
            holder.usageView.setText(usage.getTotalTokens() + " tok");
            holder.usageView.setVisibility(View.VISIBLE);
        } else {
            holder.usageView.setVisibility(View.GONE);
        }

        holder.segmentsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(holder.itemView.getContext());
        List<MarkdownCodeBlockParser.Segment> segments = MarkdownCodeBlockParser.parse(msg.getContent());

        if (segments.isEmpty() && msg.isStreaming()) {
            TextView thinking = new TextView(holder.itemView.getContext());
            thinking.setText(R.string.ai_thinking);
            thinking.setTextColor(0xFFA3A3A3);
            thinking.setTextSize(13f);
            holder.segmentsContainer.addView(thinking);
        } else {
            for (MarkdownCodeBlockParser.Segment segment : segments) {
                if (segment.kind == MarkdownCodeBlockParser.Segment.Kind.CODE_BLOCK) {
                    View card = inflater.inflate(R.layout.item_ai_code_block, holder.segmentsContainer, false);
                    TextView langView = card.findViewById(R.id.ai_code_language);
                    TextView bodyView = card.findViewById(R.id.ai_code_body);
                    TextView copyBtn = card.findViewById(R.id.ai_code_copy_button);
                    TextView reviewBtn = card.findViewById(R.id.ai_code_review_insert_button);
                    TextView insertBtn = card.findViewById(R.id.ai_code_insert_button);
                    TextView explainBtn = card.findViewById(R.id.ai_code_explain_button);
                    TextView saveSnippetBtn = card.findViewById(R.id.ai_code_save_snippet_button);

                    langView.setText(segment.language);
                    bodyView.setText(BashSyntaxHighlighter.highlight(segment.content));
                    copyBtn.setOnClickListener(v -> mListener.onCopyCode(segment.content));
                    if (reviewBtn != null) {
                        reviewBtn.setOnClickListener(v -> mListener.onReviewAndInsertCode(segment.content));
                    }
                    insertBtn.setOnClickListener(v -> mListener.onInsertCode(segment.content));
                    explainBtn.setOnClickListener(v -> mListener.onExplainCode(segment.content));
                    if (saveSnippetBtn != null) {
                        saveSnippetBtn.setOnClickListener(v -> mListener.onSaveCodeAsSnippet(segment.content));
                    }

                    holder.segmentsContainer.addView(card);
                } else {
                    TextView proseView = new TextView(holder.itemView.getContext());
                    proseView.setTextColor(0xFFFFFFFF);
                    proseView.setTextSize(14f);
                    proseView.setLineSpacing(4f, 1.15f);
                    proseView.setTextIsSelectable(true);
                    mMarkwon.setMarkdown(proseView, segment.content);
                    holder.segmentsContainer.addView(proseView);
                }
            }
        }

        if (msg.getErrorMessage() != null && !msg.getErrorMessage().isEmpty()) {
            holder.errorView.setText(msg.getErrorMessage());
            holder.errorView.setVisibility(View.VISIBLE);
        } else {
            holder.errorView.setVisibility(View.GONE);
        }

        if (!msg.isStreaming() && (!msg.getContent().trim().isEmpty() || isLast)) {
            holder.actionsRow.setVisibility(View.VISIBLE);
            boolean hasErr = msg.getErrorMessage() != null && !msg.getErrorMessage().isEmpty();
            boolean hasText = !msg.getContent().trim().isEmpty();
            if (holder.copyAllButton != null) {
                holder.copyAllButton.setVisibility(hasText ? View.VISIBLE : View.GONE);
                holder.copyAllButton.setOnClickListener(v -> mListener.onCopyCode(msg.getContent()));
            }
            holder.retryButton.setVisibility(isLast && hasErr ? View.VISIBLE : View.GONE);
            holder.regenerateButton.setVisibility(isLast && !hasErr ? View.VISIBLE : View.GONE);
            holder.retryButton.setOnClickListener(v -> mListener.onRetry());
            holder.regenerateButton.setOnClickListener(v -> mListener.onRegenerate());
        } else {
            holder.actionsRow.setVisibility(View.GONE);
        }
    }

    static final class UserVH extends RecyclerView.ViewHolder {
        final TextView textView;

        UserVH(@NonNull View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.ai_user_message_text);
        }
    }

    static final class AssistantVH extends RecyclerView.ViewHolder {
        final TextView metaView;
        final TextView usageView;
        final LinearLayout segmentsContainer;
        final TextView errorView;
        final View actionsRow;
        final TextView copyAllButton;
        final TextView retryButton;
        final TextView regenerateButton;

        AssistantVH(@NonNull View itemView) {
            super(itemView);
            metaView = itemView.findViewById(R.id.ai_assistant_meta);
            usageView = itemView.findViewById(R.id.ai_assistant_usage);
            segmentsContainer = itemView.findViewById(R.id.ai_assistant_segments_container);
            errorView = itemView.findViewById(R.id.ai_assistant_error_text);
            actionsRow = itemView.findViewById(R.id.ai_assistant_actions_row);
            copyAllButton = itemView.findViewById(R.id.ai_action_copy_all_button);
            retryButton = itemView.findViewById(R.id.ai_action_retry_button);
            regenerateButton = itemView.findViewById(R.id.ai_action_regenerate_button);
        }
    }
}
