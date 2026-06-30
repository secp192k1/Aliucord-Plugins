package com.github.secp192k1;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentManager;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.fragments.InputDialog;
import com.aliucord.patcher.Hook;
import com.discord.app.AppFragment;
import com.discord.utilities.color.ColorCompat;
import com.discord.widgets.auth.WidgetRemoteAuth;
import com.discord.widgets.auth.WidgetRemoteAuthViewModel;

import com.lytefast.flexinput.R;

@AliucordPlugin
public class QRCodeLogin extends Plugin {
    private static final String MFA_TAG = "qr_mfa";

    // MFA code entry for QR login page
    public static class MfaDialog extends InputDialog {
        @Override
        public void onViewBound(View view) {
            super.onViewBound(view);
            QrLogin.bindMfaDialog(this);
        }
    }

    // Standalone host for the MFA prompt so it survives the user switching apps,
    // WidgetRemoteAuth gives us 404 "Cant find this computer" on resume. Blank view so the
    // dialog sits over the activity's dark backdrop, not a borrowed settings layout
    public static class MfaHost extends AppFragment {
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            Context ctx = requireContext();
            View view = new View(ctx);
            view.setBackgroundColor(ColorCompat.getThemedColor(ctx, R.b.colorBackgroundPrimary));
            return view;
        }

        @Override
        public void onViewBound(View view) {
            super.onViewBound(view);
            FragmentManager fm = requireActivity().getSupportFragmentManager();
            if (fm.findFragmentByTag(MFA_TAG) == null) new MfaDialog().show(fm, MFA_TAG);
        }
    }

    @Override
    public void start(@NonNull Context context) throws Throwable {
        // The native QR scanner already launches WidgetRemoteAuth for ra codes. Route its "Login"
        // button through our finish
        // Also hook the login button so we can catch error 60003 and warn the user when the
        // handshake has expired
        patcher.patch(
            WidgetRemoteAuth.class.getDeclaredMethod("configureUI", WidgetRemoteAuthViewModel.ViewState.class),
            new Hook(param -> QrLogin.onRemoteAuthState((AppFragment) param.thisObject, param.args[0]))
        );
    }

    @Override
    public void stop(@NonNull Context context) {
        patcher.unpatchAll();
    }
}
