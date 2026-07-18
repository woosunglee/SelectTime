package com.selecttime.hogye;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;

/**
 * Handles app-card / ISP / intent:// deep links from PG pages.
 */
public final class PaymentAppCardAutomator {
    private static final String TAG = "AppCardPay";

    private PaymentAppCardAutomator() {
    }

    public static boolean shouldHandle(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase();
        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:")) {
            return false;
        }
        return true;
    }

    public static boolean handleUrl(Context context, WebView view, String url) {
        if (url == null) {
            return false;
        }
        Log.i(TAG, "handleUrl: " + url);
        try {
            if (url.startsWith("intent:")) {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                if (intent.resolveActivity(context.getPackageManager()) != null) {
                    NotifyHelper.notify(
                            context,
                            NotifyHelper.NOTIF_AUTH,
                            context.getString(R.string.app_name),
                            context.getString(R.string.notif_appcard)
                    );
                    context.startActivity(intent);
                    return true;
                }
                String fallback = intent.getStringExtra("browser_fallback_url");
                if (fallback != null) {
                    view.loadUrl(fallback);
                    return true;
                }
                String pkg = intent.getPackage();
                if (pkg != null) {
                    context.startActivity(new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=" + pkg)
                    ));
                    return true;
                }
            } else {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                NotifyHelper.notify(
                        context,
                        NotifyHelper.NOTIF_AUTH,
                        context.getString(R.string.app_name),
                        context.getString(R.string.notif_appcard)
                );
                context.startActivity(intent);
                return true;
            }
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No activity for " + url, e);
            NotifyHelper.notify(
                    context,
                    NotifyHelper.NOTIF_AUTH,
                    context.getString(R.string.app_name),
                    "앱카드 앱을 찾을 수 없습니다. 카드사 앱 설치 후 다시 시도하세요."
            );
            return true;
        } catch (Exception e) {
            Log.e(TAG, "handleUrl failed", e);
        }
        return false;
    }

    public static boolean handleRequest(Context context, WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            return false;
        }
        String url = request.getUrl().toString();
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return false;
        }
        return handleUrl(context, view, url);
    }
}
