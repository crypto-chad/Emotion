package org.telegram.emotion;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

/**
 * Asks the user which sticker pack an uploaded sticker should go into:
 * an existing pack of theirs, or a brand new one (created on the backend
 * before the callback fires). Reports the chosen pack id; cancelling at
 * any point reports nothing.
 */
public class EmotionPackPicker {

    public static final int MAX_PACK_TITLE_LENGTH = 64;

    public interface Listener {
        void onPackChosen(String packId);
    }

    public static void show(BaseFragment fragment, Listener listener) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final long userId = fragment.getUserConfig().getClientUserId();

        final AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        progress.showDelayed(300);

        EmotionPacksApi.listPacks(userId, new EmotionPacksApi.Callback<List<EmotionPacksApi.Pack>>() {
            @Override
            public void onSuccess(List<EmotionPacksApi.Pack> packs) {
                dismissQuietly(progress);
                if (fragment.getParentActivity() == null) {
                    return;
                }
                if (packs.isEmpty()) {
                    askNewPackTitle(fragment, userId, listener);
                } else {
                    askExistingOrNew(fragment, userId, packs, listener);
                }
            }

            @Override
            public void onError(Exception error) {
                dismissQuietly(progress);
                if (fragment.getParentActivity() == null) {
                    return;
                }
                BulletinFactory.of(fragment).createErrorBulletin("Couldn't load sticker packs: " + error.getMessage()).show();
            }
        });
    }

    private static void askExistingOrNew(BaseFragment fragment, long userId, List<EmotionPacksApi.Pack> packs, Listener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle("Add sticker to a pack");
        builder.setItems(new CharSequence[]{"Add to an existing pack", "Create a new pack"}, (dialog, which) -> {
            if (which == 0) {
                chooseExistingPack(fragment, packs, listener);
            } else {
                askNewPackTitle(fragment, userId, listener);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private static void chooseExistingPack(BaseFragment fragment, List<EmotionPacksApi.Pack> packs, Listener listener) {
        CharSequence[] items = new CharSequence[packs.size()];
        for (int i = 0; i < items.length; i++) {
            EmotionPacksApi.Pack pack = packs.get(i);
            items[i] = pack.title + " (" + pack.stickerCount + ")";
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle("Choose a pack");
        builder.setItems(items, (dialog, which) -> listener.onPackChosen(packs.get(which).id));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private static void askNewPackTitle(BaseFragment fragment, long userId, Listener listener) {
        final Context context = fragment.getParentActivity();

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHint("Pack name");
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setMaxLines(1);
        editText.setLines(1);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_PACK_TITLE_LENGTH)});
        editText.setGravity(Gravity.LEFT | Gravity.TOP);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(dp(20));
        editText.setCursorWidth(1.5f);
        editText.setPadding(0, dp(4), 0, 0);

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        final TextView message = new TextView(context);
        message.setText("Name your new sticker pack");
        message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        message.setPadding(dp(23), dp(12), dp(23), dp(6));
        message.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        layout.addView(message, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        layout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP | Gravity.LEFT, 24, 6, 24, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("New sticker pack");
        builder.setView(layout);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        // Real click handler is attached below so an empty name or a failed request keeps the dialog open.
        builder.setPositiveButton(LocaleController.getString(R.string.Create), (dialog, which) -> {
        });

        final AlertDialog alertDialog = builder.create();
        alertDialog.setOnShowListener(dialog -> AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }));
        fragment.showDialog(alertDialog);

        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String title = editText.getText().toString().trim();
            if (title.isEmpty()) {
                AndroidUtilities.shakeView(editText);
                return;
            }
            final Activity activity = fragment.getParentActivity();
            if (activity == null) {
                return;
            }

            final AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
            progress.setCanCancel(false);
            progress.showDelayed(300);

            EmotionPacksApi.createPack(userId, title, new EmotionPacksApi.Callback<EmotionPacksApi.Pack>() {
                @Override
                public void onSuccess(EmotionPacksApi.Pack pack) {
                    dismissQuietly(progress);
                    AndroidUtilities.hideKeyboard(editText);
                    dismissQuietly(alertDialog);
                    listener.onPackChosen(pack.id);
                }

                @Override
                public void onError(Exception error) {
                    dismissQuietly(progress);
                    // Keep the dialog open so the typed name is not lost.
                    if (fragment.getParentActivity() != null) {
                        Toast.makeText(fragment.getParentActivity(), "Couldn't create pack: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        });
    }

    private static void dismissQuietly(AlertDialog dialog) {
        try {
            dialog.dismiss();
        } catch (Exception ignore) {
        }
    }
}
