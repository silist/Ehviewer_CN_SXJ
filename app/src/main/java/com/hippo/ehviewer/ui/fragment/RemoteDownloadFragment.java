/*
 * Remote Download Settings Fragment
 * Configuration for NAS remote download functionality
 */

package com.hippo.ehviewer.ui.fragment;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.textfield.TextInputLayout;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCookieStore;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.RemoteDownloadClient;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

public class RemoteDownloadFragment extends PreferenceFragmentCompat implements
        SharedPreferences.OnSharedPreferenceChangeListener,
        Preference.OnPreferenceClickListener {

    private static final String TAG = "RemoteDownloadFragment";

    private static final String KEY_DOWNLOAD_MODE = "download_mode";
    private static final String KEY_REMOTE_SETTINGS = "remote_settings";
    private static final String KEY_COOKIE_SYNC = "cookie_sync";

    @Nullable
    private Preference mRemoteSettingsCategory;
    @Nullable
    private Preference mCookieSyncCategory;
    @Nullable
    private Preference mNasAddress;
    @Nullable
    private Preference mNasPort;
    @Nullable
    private Preference mApiToken;
    @Nullable
    private Preference mTestConnection;
    @Nullable
    private Preference mCookieSyncStatus;
    @Nullable
    private Preference mSyncCookie;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.remote_download_settings);

        mRemoteSettingsCategory = findPreference(KEY_REMOTE_SETTINGS);
        mCookieSyncCategory = findPreference(KEY_COOKIE_SYNC);
        mNasAddress = findPreference("remote_nas_address");
        mNasPort = findPreference("remote_nas_port");
        mApiToken = findPreference("remote_api_token");
        mTestConnection = findPreference("test_connection");
        mCookieSyncStatus = findPreference("cookie_sync_status");
        mSyncCookie = findPreference("sync_cookie");

        // Set click listeners for text input preferences
        if (mNasAddress != null) {
            mNasAddress.setOnPreferenceClickListener(this);
        }
        if (mNasPort != null) {
            mNasPort.setOnPreferenceClickListener(this);
        }
        if (mApiToken != null) {
            mApiToken.setOnPreferenceClickListener(this);
        }
        if (mTestConnection != null) {
            mTestConnection.setOnPreferenceClickListener(this);
        }
        if (mSyncCookie != null) {
            mSyncCookie.setOnPreferenceClickListener(this);
        }

        updatePreferenceSummaries();
        updateVisibility();
        updateCookieSyncStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (KEY_DOWNLOAD_MODE.equals(key)) {
            updateVisibility();
        }
    }

    private void updatePreferenceSummaries() {
        if (mNasAddress != null) {
            String address = Settings.getRemoteNasAddress();
            mNasAddress.setSummary(TextUtils.isEmpty(address) ? null : address);
        }
        if (mNasPort != null) {
            int port = Settings.getRemoteNasPort();
            mNasPort.setSummary(String.valueOf(port));
        }
        if (mApiToken != null) {
            String token = Settings.getRemoteApiToken();
            mApiToken.setSummary(TextUtils.isEmpty(token) ? null : "******");
        }
    }

    private void updateVisibility() {
        String mode = Settings.getDownloadMode();
        boolean isRemote = Settings.DOWNLOAD_MODE_REMOTE.equals(mode);

        if (mRemoteSettingsCategory != null) {
            mRemoteSettingsCategory.setVisible(isRemote);
        }
        if (mCookieSyncCategory != null) {
            mCookieSyncCategory.setVisible(isRemote);
        }
    }

    private void updateCookieSyncStatus() {
        boolean synced = Settings.isRemoteCookieSynced();
        long syncTime = Settings.getRemoteCookieSyncTime();

        String summary;
        if (synced && syncTime > 0) {
            String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(new Date(syncTime));
            summary = getString(R.string.settings_remote_synced, timeStr);
        } else {
            summary = getString(R.string.settings_remote_not_synced);
        }

        if (mCookieSyncStatus != null) {
            mCookieSyncStatus.setSummary(summary);
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        switch (key) {
            case "remote_nas_address":
                showAddressDialog();
                return true;
            case "remote_nas_port":
                showPortDialog();
                return true;
            case "remote_api_token":
                showTokenDialog();
                return true;
            case "test_connection":
                testConnection();
                return true;
            case "sync_cookie":
                syncCookie();
                return true;
        }
        return false;
    }

    private void showAddressDialog() {
        View view = View.inflate(requireContext(), R.layout.preference_dialog_simple_edittext, null);
        TextInputLayout inputLayout = view.findViewById(R.id.text_input_layout);
        EditText editText = inputLayout.getEditText();
        if (editText != null) {
            editText.setText(Settings.getRemoteNasAddress());
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            editText.setHint(getString(R.string.settings_remote_nas_address_hint));
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_remote_nas_address)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (editText != null) {
                        String value = editText.getText().toString().trim();
                        Settings.putRemoteNasAddress(value);
                        updatePreferenceSummaries();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPortDialog() {
        View view = View.inflate(requireContext(), R.layout.preference_dialog_simple_edittext, null);
        TextInputLayout inputLayout = view.findViewById(R.id.text_input_layout);
        EditText editText = inputLayout.getEditText();
        if (editText != null) {
            int port = Settings.getRemoteNasPort();
            editText.setText(String.valueOf(port));
            editText.setInputType(InputType.TYPE_CLASS_NUMBER);
            editText.setHint("23456");
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_remote_nas_port)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (editText != null) {
                        String value = editText.getText().toString().trim();
                        try {
                            int portValue = Integer.parseInt(value);
                            Settings.putRemoteNasPort(portValue);
                            updatePreferenceSummaries();
                        } catch (NumberFormatException e) {
                            // Ignore invalid input
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showTokenDialog() {
        View view = View.inflate(requireContext(), R.layout.preference_dialog_simple_edittext, null);
        TextInputLayout inputLayout = view.findViewById(R.id.text_input_layout);
        EditText editText = inputLayout.getEditText();
        if (editText != null) {
            editText.setText(Settings.getRemoteApiToken());
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            editText.setHint(getString(R.string.settings_remote_api_token_hint));
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_remote_api_token)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (editText != null) {
                        String value = editText.getText().toString().trim();
                        Settings.putRemoteApiToken(value);
                        updatePreferenceSummaries();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void testConnection() {
        if (mTestConnection == null) return;

        mTestConnection.setEnabled(false);
        mTestConnection.setSummary(getString(R.string.settings_remote_testing));

        new Thread(() -> {
            RemoteDownloadClient.TestResult result =
                    RemoteDownloadClient.INSTANCE.testConnectionBlocking();

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (mTestConnection != null) {
                        mTestConnection.setEnabled(true);

                        if (result instanceof RemoteDownloadClient.TestResult.Success) {
                            mTestConnection.setSummary(getString(R.string.settings_remote_connection_success));
                            Toast.makeText(requireContext(),
                                    getString(R.string.settings_remote_connection_success),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            RemoteDownloadClient.TestResult.Error error =
                                    (RemoteDownloadClient.TestResult.Error) result;
                            mTestConnection.setSummary(null);
                            Toast.makeText(requireContext(),
                                    error.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private void syncCookie() {
        if (mSyncCookie == null) return;

        String cookies = getCookies();

        if (TextUtils.isEmpty(cookies)) {
            Toast.makeText(requireContext(),
                    getString(R.string.no_cookie_in_clipboard),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        mSyncCookie.setEnabled(false);
        mSyncCookie.setSummary(getString(R.string.settings_remote_syncing));

        new Thread(() -> {
            RemoteDownloadClient.SyncResult result =
                    RemoteDownloadClient.INSTANCE.syncCookiesBlocking(cookies);

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (mSyncCookie != null) {
                        mSyncCookie.setEnabled(true);

                        if (result instanceof RemoteDownloadClient.SyncResult.Success) {
                            Settings.putRemoteCookieSynced(true);
                            Settings.putRemoteCookieSyncTime(System.currentTimeMillis());
                            mSyncCookie.setSummary(getString(R.string.settings_remote_sync_success));
                            updateCookieSyncStatus();
                            Toast.makeText(requireContext(),
                                    getString(R.string.settings_remote_sync_success),
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            RemoteDownloadClient.SyncResult.Error error =
                                    (RemoteDownloadClient.SyncResult.Error) result;
                            Settings.putRemoteCookieSynced(false);
                            mSyncCookie.setSummary(null);
                            Toast.makeText(requireContext(),
                                    error.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    private String getCookies() {
        EhCookieStore store = EhApplication.getEhCookieStore(requireContext());
        List<Cookie> eCookies = store.getCookies(HttpUrl.get(EhUrl.HOST_E));
        List<Cookie> exCookies = store.getCookies(HttpUrl.get(EhUrl.HOST_EX));
        List<Cookie> cookies = new LinkedList<>(eCookies);
        cookies.addAll(exCookies);

        String ipbMemberId = null;
        String ipbPassHash = null;
        String igneous = null;

        for (Cookie cookie : cookies) {
            switch (cookie.name()) {
                case EhCookieStore.KEY_IPD_MEMBER_ID:
                    ipbMemberId = cookie.value();
                    break;
                case EhCookieStore.KEY_IPD_PASS_HASH:
                    ipbPassHash = cookie.value();
                    break;
                case EhCookieStore.KEY_IGNEOUS:
                    igneous = cookie.value();
                    break;
            }
        }

        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(ipbMemberId)) {
            sb.append("ipb_member_id=").append(ipbMemberId);
        }
        if (!TextUtils.isEmpty(ipbPassHash)) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("ipb_pass_hash=").append(ipbPassHash);
        }
        if (!TextUtils.isEmpty(igneous)) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("igneous=").append(igneous);
        }
        return sb.toString();
    }
}
