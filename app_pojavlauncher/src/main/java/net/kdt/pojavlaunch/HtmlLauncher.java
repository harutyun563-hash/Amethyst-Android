package net.kdt.pojavlaunch;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;

/**
 * HTML-интерфейс лаунчера (assets/launcher.html) поверх обычного экрана Amethyst.
 * Обычный интерфейс остаётся под ним и продолжает работать (аккаунты, загрузка, прогресс).
 */
public class HtmlLauncher {
    private static WeakReference<WebView> sWebView = new WeakReference<>(null);

    /** Вызывается из LauncherActivity.onCreate сразу после bindViews(). */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public static void attach(final LauncherActivity activity) {
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        final WebView web = new WebView(activity);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(activity, web), "Android");
        web.loadUrl("file:///android_asset/launcher.html");

        activity.addContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sWebView = new WeakReference<>(web);
    }

    /** Вызывается в начале LauncherActivity.onBackPressed(). true = нажатие обработано. */
    public static boolean handleBack() {
        WebView web = sWebView.get();
        if (web == null || web.getVisibility() == View.VISIBLE) return false;
        LauncherActivity activity = (LauncherActivity) web.getContext();
        // Мы на главном экране обычного интерфейса: возвращаем HTML-лаунчер
        if (activity.getSupportFragmentManager().getBackStackEntryCount() <= 1) {
            web.setVisibility(View.VISIBLE);
            return true;
        }
        return false;
    }

    private static class Bridge {
        private final LauncherActivity mActivity;
        private final WebView mWeb;

        Bridge(LauncherActivity activity, WebView web) {
            mActivity = activity;
            mWeb = web;
        }

        /** JSON-массив версий из профилей приложения. */
        @JavascriptInterface
        public String getInstalledVersions() {
            JSONArray arr = new JSONArray();
            try {
                if (LauncherProfiles.mainProfileJson != null) {
                    LinkedHashSet<String> ids = new LinkedHashSet<>();
                    for (MinecraftProfile p : LauncherProfiles.mainProfileJson.profiles.values()) {
                        if (p != null && p.lastVersionId != null && !"Unknown".equals(p.lastVersionId)) {
                            ids.add(p.lastVersionId);
                        }
                    }
                    for (String id : ids) arr.put(id);
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }

        /** Ставит версию в выбранный профиль и запускает игру штатным способом. */
        @JavascriptInterface
        public void startGame(final String versionId) {
            mActivity.runOnUiThread(() -> {
                try {
                    String selected = LauncherPreferences.DEFAULT_PREF
                            .getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
                    if (LauncherProfiles.mainProfileJson == null
                            || !LauncherProfiles.mainProfileJson.profiles.containsKey(selected)) {
                        error("Нет выбранного профиля. Нажмите «Аккаунт и настройки» и выберите профиль.");
                        return;
                    }
                    MinecraftProfile prof = LauncherProfiles.mainProfileJson.profiles.get(selected);
                    if (prof == null) {
                        error("Профиль не найден.");
                        return;
                    }
                    prof.lastVersionId = versionId;
                    LauncherProfiles.mainProfileJson.profiles.put(selected, prof);
                    LauncherProfiles.write();

                    // Прячем HTML, чтобы был виден обычный прогресс загрузки
                    mWeb.setVisibility(View.GONE);
                    ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                } catch (Exception e) {
                    error("Ошибка запуска: " + e.getMessage());
                }
            });
        }

        /** Скрывает HTML и показывает обычный экран (аккаунты, профили, настройки). */
        @JavascriptInterface
        public void openNative() {
            mActivity.runOnUiThread(() -> mWeb.setVisibility(View.GONE));
        }

        private void error(String msg) {
            mWeb.post(() -> mWeb.evaluateJavascript(
                    "window.onError(" + JSONObject.quote(msg) + ")", null));
        }
    }
}
