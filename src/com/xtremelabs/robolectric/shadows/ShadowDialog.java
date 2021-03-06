package com.xtremelabs.robolectric.shadows;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import com.xtremelabs.robolectric.util.Implementation;
import com.xtremelabs.robolectric.util.Implements;
import com.xtremelabs.robolectric.util.RealObject;
import com.xtremelabs.robolectric.view.TestWindow;

import java.lang.reflect.Method;

@SuppressWarnings({"UnusedDeclaration"})
@Implements(Dialog.class)
public class ShadowDialog {
    private static ShadowDialog latestDialog;

    @RealObject private Dialog realDialog;

    private boolean isShowing;
    Context context;
    private int layoutId;
    private int themeId;
    private View inflatedView;
    private boolean hasBeenDismissed;
    private DialogInterface.OnDismissListener onDismissListener;
    private CharSequence title;
    private DialogInterface.OnCancelListener onCancelListener;
    private Window window;
    private Activity ownerActivity;

    public static void reset() {
        latestDialog = null;
    }

    public static ShadowDialog getLatestDialog() {
        return latestDialog;
    }

    public void __constructor__(Context context) {
        __constructor__(context, -1);
    }

    public void __constructor__(Context context, int themeId) {
        this.context = context;
        this.themeId = themeId;

        latestDialog = this;
    }

    @Implementation
    public void setContentView(int layoutResID) {
        layoutId = layoutResID;
    }

    @Implementation
    public void setTitle(int stringResourceId) {
        this.title = context.getResources().getText(stringResourceId);
    }

    @Implementation
    public void setTitle(CharSequence title) {
        this.title = title;
    }

    @Implementation
    public void setOwnerActivity(Activity activity) {
        this.ownerActivity = activity;
    }

    @Implementation
    public Activity getOwnerActivity() {
        return this.ownerActivity;
    }

    @Implementation
    public Context getContext() {
        return context;
    }

    @Implementation
    public void show() {
        isShowing = true;
        try {
            Method onCreateMethod = Dialog.class.getDeclaredMethod("onCreate", Bundle.class);
            onCreateMethod.setAccessible(true);
            onCreateMethod.invoke(realDialog, (Bundle) null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Implementation
    public void hide() {
        isShowing = false;
    }

    @Implementation
    public boolean isShowing() {
        return isShowing;
    }

    @Implementation
    public void dismiss() {
        isShowing = false;
        hasBeenDismissed = true;

        if (onDismissListener != null) {
            onDismissListener.onDismiss(realDialog);
        }
    }

    @Implementation
    public View findViewById(int viewId) {
        if (layoutId > 0 && context != null) {
            if (inflatedView == null) {
                inflatedView = ShadowLayoutInflater.from(context).inflate(layoutId, null);
            }
            return inflatedView.findViewById(viewId);
        }
        return null;
    }

    public void clickOn(int viewId) {
        findViewById(viewId).performClick();
    }

    @Implementation
    public void setOnDismissListener(DialogInterface.OnDismissListener onDismissListener) {
        this.onDismissListener = onDismissListener;
    }

    @Implementation
    public void cancel() {
        if (onCancelListener != null) {
            onCancelListener.onCancel(realDialog);
        }
        dismiss();
    }

    @Implementation
    public void setOnCancelListener(final DialogInterface.OnCancelListener listener) {
        this.onCancelListener = listener;
    }

    @Implementation
    public Window getWindow() {
        if (window == null) {
            window = new TestWindow(realDialog.getContext());
        }
        return window;
    }

    public int getLayoutId() {
        return layoutId;
    }

    public int getThemeId() {
        return themeId;
    }

    public boolean hasBeenDismissed() {
        return hasBeenDismissed;
    }

    public CharSequence getTitle() {
        return title;
    }
}
