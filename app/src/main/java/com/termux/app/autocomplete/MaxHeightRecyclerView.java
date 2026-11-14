package com.termux.app.autocomplete;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView that enforces a bounded vertical height in dp so the suggestion
 * dropdown never covers the terminal prompt or active viewport on compact screens.
 */
public class MaxHeightRecyclerView extends RecyclerView {

    private static final float DEFAULT_MAX_HEIGHT_DP = 168f;
    private final int mMaxHeightPx;

    public MaxHeightRecyclerView(@NonNull Context context) {
        this(context, null);
    }

    public MaxHeightRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MaxHeightRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mMaxHeightPx = Math.round(TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            DEFAULT_MAX_HEIGHT_DP,
            context.getResources().getDisplayMetrics()
        ));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int boundedHeightSpec = MeasureSpec.makeMeasureSpec(mMaxHeightPx, MeasureSpec.AT_MOST);
        super.onMeasure(widthSpec, boundedHeightSpec);
    }
}
