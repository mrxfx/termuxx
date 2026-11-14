package com.termux.app.workspace;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.termux.R;

/**
 * Shared Samsung One UI Dark Black & White (#000000 / #111111 / #1A1A1A / #FFFFFF)
 * component builder with accessible 48dp+ touch targets.
 */
public final class WorkspaceUiFactory {

    private WorkspaceUiFactory() {}

    public static int dp(@NonNull Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    @NonNull
    public static LinearLayout createCard(@NonNull Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#111111"));
        bg.setCornerRadius(dp(ctx, 20));
        bg.setStroke(dp(ctx, 1), Color.parseColor("#292929"));
        card.setBackground(bg);
        int pad = dp(ctx, 16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 12);
        card.setLayoutParams(lp);
        return card;
    }

    @NonNull
    public static LinearLayout createSecondarySurfaceBox(@NonNull Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1A1A1A"));
        bg.setCornerRadius(dp(ctx, 14));
        bg.setStroke(dp(ctx, 1), Color.parseColor("#303030"));
        box.setBackground(bg);
        int pad = dp(ctx, 12);
        box.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        box.setLayoutParams(lp);
        return box;
    }

    @NonNull
    public static TextView createSectionHeader(@NonNull Context ctx, @NonNull String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text.toUpperCase());
        tv.setTextColor(Color.parseColor("#A3A3A3"));
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        lp.bottomMargin = dp(ctx, 8);
        lp.leftMargin = dp(ctx, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    @NonNull
    public static TextView createTitle(@NonNull Context ctx, @NonNull String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(16f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    @NonNull
    public static TextView createSubtitle(@NonNull Context ctx, @NonNull String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#A3A3A3"));
        tv.setTextSize(13f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    @NonNull
    public static TextView createMonospaceBlock(@NonNull Context ctx, @NonNull String code) {
        TextView tv = new TextView(ctx);
        tv.setText(code);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.MONOSPACE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1A1A1A"));
        bg.setCornerRadius(dp(ctx, 12));
        bg.setStroke(dp(ctx, 1), Color.parseColor("#303030"));
        tv.setBackground(bg);
        int pad = dp(ctx, 10);
        tv.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    @NonNull
    public static TextView createBadge(@NonNull Context ctx, @NonNull String text, boolean activeWhite) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 10));
        if (activeWhite) {
            bg.setColor(Color.WHITE);
            tv.setTextColor(Color.BLACK);
        } else {
            bg.setColor(Color.parseColor("#222222"));
            bg.setStroke(dp(ctx, 1), Color.parseColor("#303030"));
            tv.setTextColor(Color.parseColor("#A3A3A3"));
        }
        tv.setBackground(bg);
        tv.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4));
        return tv;
    }

    @NonNull
    public static Button createPrimaryButton(@NonNull Context ctx, @NonNull String label) {
        Button btn = new Button(ctx);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(Color.BLACK);
        btn.setTextSize(13f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setMinimumHeight(dp(ctx, 48));
        btn.setMinHeight(dp(ctx, 48));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(ctx, 14));
        btn.setBackground(bg);
        btn.setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0);
        return btn;
    }

    @NonNull
    public static Button createSecondaryButton(@NonNull Context ctx, @NonNull String label) {
        Button btn = new Button(ctx);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(13f);
        btn.setMinimumHeight(dp(ctx, 48));
        btn.setMinHeight(dp(ctx, 48));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1A1A1A"));
        bg.setCornerRadius(dp(ctx, 14));
        bg.setStroke(dp(ctx, 1), Color.parseColor("#303030"));
        btn.setBackground(bg);
        btn.setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0);
        return btn;
    }

    @NonNull
    public static Button createDangerButton(@NonNull Context ctx, @NonNull String label) {
        Button btn = createSecondaryButton(ctx, label);
        btn.setTextColor(Color.parseColor("#FF5252"));
        return btn;
    }

    @NonNull
    public static EditText createInput(
        @NonNull Context ctx,
        @NonNull String hint,
        @Nullable String initialValue,
        boolean monospace
    ) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(Color.parseColor("#707070"));
        et.setTextColor(Color.WHITE);
        et.setTextSize(14f);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        if (monospace) {
            et.setTypeface(Typeface.MONOSPACE);
        }
        if (initialValue != null) {
            et.setText(initialValue);
        }
        et.setMinimumHeight(dp(ctx, 48));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1A1A1A"));
        bg.setCornerRadius(dp(ctx, 14));
        bg.setStroke(dp(ctx, 1), Color.parseColor("#303030"));
        et.setBackground(bg);
        int padH = dp(ctx, 14);
        int padV = dp(ctx, 12);
        et.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 6);
        lp.bottomMargin = dp(ctx, 6);
        et.setLayoutParams(lp);
        return et;
    }

    @NonNull
    public static SwitchCompat createSwitchRow(
        @NonNull Context ctx,
        @NonNull LinearLayout parent,
        @NonNull String label,
        boolean checked
    ) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 48));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14f);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(tv, tvLp);

        SwitchCompat sw = new SwitchCompat(ctx);
        sw.setChecked(checked);
        sw.setMinimumHeight(dp(ctx, 48));
        sw.setMinimumWidth(dp(ctx, 48));
        row.addView(sw);

        parent.addView(row);
        return sw;
    }

    @NonNull
    public static View createDivider(@NonNull Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(Color.parseColor("#292929"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1));
        lp.topMargin = dp(ctx, 10);
        lp.bottomMargin = dp(ctx, 10);
        v.setLayoutParams(lp);
        return v;
    }
}
