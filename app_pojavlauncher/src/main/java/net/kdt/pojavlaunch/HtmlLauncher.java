package net.kdt.pojavlaunch;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.screens.LauncherPreferenceFragment;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.progresskeeper.TaskCountListener;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;

/**
 * HTML-интерфейс лаунчера (assets/launcher.html) поверх главного экрана Amethyst.
 * Виден всегда, пока открыт главный экран. Обычные экраны (вход в аккаунт, настройки)
 * открываются только по кнопкам и закрываются кнопкой «Назад».
 */
public class HtmlLauncher {
    private static WeakReference<WebView> sWebView = new WeakReference<>(null);
    private static TaskCountListener sTaskListener;

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

        // HTML виден на главном экране; на других экранах (вход, настройки) он скрыт
        activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentResumed(FragmentManager fm, Fragment f) {
                        boolean main = f instanceof MainMenuFragment;
                        web.setVisibility(main ? View.VISIBLE : View.GONE);
                        if (main) {
                            web.onResume();
                            if (!ProgressLayout.hasProcesses()) {
                                web.evaluateJavascript("window.onIdle&&window.onIdle()", null);
                            }
                        } else {
                            web.onPause();
                        }
                    }
                }, false);

        // Занятость: пока идёт загрузка игры, кнопка заблокирована
        if (sTaskListener != null) ProgressKeeper.removeTaskCountListener(sTaskListener);
        sTaskListener = count -> {
            WebView w = sWebView.get();
            if (w == null) return;
            final String call = count > 0 ? "window.onBusy&&window.onBusy()" : "window.onIdle&&window.onIdle()";
            w.post(() -> w.evaluateJavascript(call, null));
        };
        ProgressKeeper.addTaskCountListener(sTaskListener);
    }

    /** Оставлено для совместимости с правкой в LauncherActivity.onBackPressed(). */
    public static boolean handleBack() {
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
                        error("Нет выбранного профиля.");
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

                    ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);

                    // Если загрузка не началась (например, нет аккаунта), разблокируем кнопку
                    mWeb.postDelayed(() -> {
                        if (!ProgressLayout.hasProcesses()) {
                            mWeb.evaluateJavascript("window.onIdle&&window.onIdle()", null);
                        }
                    }, 2500);
                } catch (Exception e) {
                    error("Ошибка запуска: " + e.getMessage());
                }
            });
        }

        /** Экран выбора способа входа / добавления аккаунта. */
        @JavascriptInterface
        public void openAccounts() {
            mActivity.runOnUiThread(() -> ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true));
        }

        /** Обычные настройки игры. */
        @JavascriptInterface
        public void openSettings() {
            mActivity.runOnUiThread(() -> Tools.swapFragment(mActivity,
                    LauncherPreferenceFragment.class, LauncherActivity.SETTING_FRAGMENT_TAG, null));
        }

        /** Выбор jar-файла установщика (Forge / Fabric) штатным способом. */
        @JavascriptInterface
        public void installJar() {
            mActivity.runOnUiThread(() -> mActivity.modInstallerLauncher.launch(null));
        }

        private void error(String msg) {
            mWeb.post(() -> mWeb.evaluateJavascript(
                    "window.onError(" + JSONObject.quote(msg) + ")", null));
        }
    }
}
