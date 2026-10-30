package com.click.ui.common;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.InsetDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.click.designpreview.AccountSelectionMark;
import com.click.designpreview.R;

/** An inline single-choice field using the same marks and surfaces as Click signup. */
public final class ClickSelectionField extends LinearLayout {
    private final TextView field;
    private final RadioGroup options;
    private final String label;
    private final String[] values;
    private int selectedIndex;

    public ClickSelectionField(Context context, String label, String[] values, String selected) {
        super(context);
        this.label = label;
        setOrientation(VERTICAL);
        for (int i = 1; i < values.length; i++) if (values[i].equals(selected)) selectedIndex = i;
        // Keep an older saved category intact until the user chooses a replacement.
        if (selectedIndex == 0 && selected != null && !selected.isEmpty() && !values[0].equals(selected)) {
            this.values = java.util.Arrays.copyOf(values, values.length + 1);
            selectedIndex = values.length;
            this.values[selectedIndex] = selected;
        } else this.values = values.clone();

        field = ClickUi.body(context, "");
        field.setTextSize(17);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setMinHeight(dp(58));
        field.setPadding(dp(16), dp(16), dp(16), dp(16));
        field.setBackgroundResource(R.drawable.signup_input_background);
        field.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.signup_occupation_arrow, 0);
        field.setCompoundDrawablePadding(dp(12));
        field.setFocusable(true);
        addView(field, new LayoutParams(-1, -2));

        options = new RadioGroup(context);
        options.setOrientation(VERTICAL);
        options.setVisibility(GONE);
        field.setOnClickListener(view -> setExpanded(options.getVisibility() != VISIBLE));
        LayoutParams listParams = new LayoutParams(-1, -2);
        listParams.topMargin = dp(8);
        addView(options, listParams);
        for (int i = 0; i < this.values.length; i++) {
            final int index = i;
            RadioButton option = new RadioButton(context);
            option.setId(View.generateViewId());
            option.setSaveEnabled(false);
            option.setText(this.values[i]);
            option.setTextSize(17);
            option.setTextColor(ClickUi.TEXT);
            option.setTypeface(getResources().getFont(R.font.pretendard_medium));
            option.setIncludeFontPadding(false);
            option.setButtonDrawable(new InsetDrawable(
                    new AccountSelectionMark(getResources().getDisplayMetrics().density), dp(12), 0, dp(12), 0));
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setPadding(0, dp(14), dp(12), dp(14));
            option.setMinHeight(dp(56));
            option.setBackgroundResource(R.drawable.signup_occupation_option);
            option.setOnCheckedChangeListener((button, checked) -> button.setTypeface(
                    getResources().getFont(checked ? R.font.pretendard_semibold : R.font.pretendard_medium)));
            option.setOnClickListener(view -> {
                selectedIndex = index;
                setExpanded(false);
                field.announceForAccessibility(this.values[index]);
            });
            options.addView(option, new RadioGroup.LayoutParams(-1, -2));
        }
        refresh();
    }

    public String selectedValue() { return selectedIndex == 0 ? "" : values[selectedIndex]; }

    private void setExpanded(boolean expanded) {
        View focused = getRootView().findFocus();
        if (focused != null) focused.clearFocus();
        InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        options.setVisibility(expanded ? VISIBLE : GONE);
        refresh();
        field.requestFocus();
        field.post(() -> {
            Rect bounds = new Rect(0, 0, field.getWidth(), field.getHeight());
            if (expanded) {
                for (ViewParent parent = getParent(); parent != null; parent = parent.getParent()) {
                    if (parent instanceof ScrollView) {
                        ScrollView scroll = (ScrollView) parent;
                        scroll.offsetDescendantRectToMyCoords(field, bounds);
                        scroll.smoothScrollTo(0, Math.max(0, bounds.top - dp(36)));
                        return;
                    }
                }
            }
            field.requestRectangleOnScreen(bounds, false);
        });
    }

    private void refresh() {
        boolean expanded = options.getVisibility() == VISIBLE;
        field.setText(values[selectedIndex]);
        field.setTextColor(selectedIndex == 0 ? ClickUi.SUBTEXT : ClickUi.TEXT);
        field.setActivated(expanded);
        field.setContentDescription(label + ": " + values[selectedIndex]
                + (expanded ? ", 눌러서 목록 접기" : ", 눌러서 목록 펼치기"));
        options.check(options.getChildAt(selectedIndex).getId());
    }

    private int dp(int value) { return ClickUi.dp(getContext(), value); }
}
