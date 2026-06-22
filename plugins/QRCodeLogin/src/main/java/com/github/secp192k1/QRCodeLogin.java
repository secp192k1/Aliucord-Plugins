package com.github.secp192k1;

import android.content.Context;
import android.view.View;

import com.aliucord.annotations.AliucordPlugin;
import com.aliucord.entities.Plugin;
import com.aliucord.fragments.InputDialog;
import com.aliucord.patcher.Hook;
import com.discord.app.AppFragment;
import com.discord.widgets.auth.WidgetRemoteAuth;
import com.discord.widgets.auth.WidgetRemoteAuthViewModel;

@AliucordPlugin
public class QRCodeLogin extends Plugin {
    // MFA code entry for QR login page
    public static class MfaDialog extends InputDialog {
        @Override
        public void onViewBound(View view) {
            super.onViewBound(view);
            QrLogin.bindMfaDialog(this);
        }
    }

    @Override
    public void start(Context context) throws Throwable {
        // The native QR scanner already launches WidgetRemoteAuth for ra codes. Route its "Login"
        // button through our finish
        // Also hook the login button so we can catch error 60003 and warn the user when the
        // handshake has expired
        patcher.patch(
            WidgetRemoteAuth.class.getDeclaredMethod("configureUI", WidgetRemoteAuthViewModel.ViewState.class),
            new Hook(param -> QrLogin.onRemoteAuthState((AppFragment) param.thisObject, param.args[0]))
        );

        // TODO: When switching apps, the screen gets re-created on resume,
        //  which shows you an 404 error "Cant find this computer!"
        /*
        patcher.patch(
            WidgetRemoteAuthViewModel.class.getDeclaredConstructor(String.class, RestAPI.class),
            new PreHook(param -> QrLogin.onRemoteAuthInit(param))
        ); */
    }

    @Override
    public void stop(Context context) {
        patcher.unpatchAll();
    }
}
