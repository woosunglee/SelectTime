package com.selecttime.hogye;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Calendar;
import java.util.Locale;

/**
 * Drives AUC reservation pages inside a WebView: login, queue wait, date/slot, payment.
 */
public class AucWebAutomator {
    private static final String TAG = "AucWeb";

    public interface Listener {
        void onStatus(String message);

        void onFinished(boolean success, String message);
    }

    private final Context context;
    private final SecureStore store;
    private final WebView webView;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean paymentTriggered;
    private int pollCount;

    public AucWebAutomator(Context context, WebView webView, Listener listener) {
        this.context = context.getApplicationContext();
        this.store = new SecureStore(context);
        this.webView = webView;
        this.listener = listener;
        configureWebView();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setUserAgentString(s.getUserAgentString() + " SelectTime/0.1");

        webView.addJavascriptInterface(new Bridge(), "SelectTimeBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return PaymentAppCardAutomator.handleRequest(context, view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (PaymentAppCardAutomator.shouldHandle(url)) {
                    return PaymentAppCardAutomator.handleUrl(context, view, url);
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                status("페이지 로드: " + url);
                handler.postDelayed(() -> onPageReady(view), 800);
            }
        });
    }

    public void start() {
        paymentTriggered = false;
        pollCount = 0;
        String url = store.get(SecureStore.KEY_RESERVATION_URL, SecureStore.DEFAULT_URL);
        status("예약 시작");
        NotifyHelper.notify(context, NotifyHelper.NOTIF_STATUS,
                context.getString(R.string.app_name), "예약 자동화를 시작합니다");
        webView.loadUrl(url);
        handler.postDelayed(this::pollLoop, 2000);
    }

    private void pollLoop() {
        pollCount++;
        if (pollCount > 400) {
            finish(false, "시간 초과");
            return;
        }
        inspectPage();
        handler.postDelayed(this::pollLoop, 2500);
    }

    private void onPageReady(WebView view) {
        injectHelpers(view);
        tryLogin(view);
        trySelectDateAndSlot(view);
        maybePay(view);
    }

    private void inspectPage() {
        webView.evaluateJavascript(
                "(function(){return document.body?document.body.innerText.slice(0,4000):'';})();",
                value -> {
                    if (value == null) {
                        return;
                    }
                    String text = value.replace("\\n", "\n").replace("\\\"", "\"");
                    if (text.contains("서비스 접속 대기") || text.contains("TRACER")) {
                        status("TRACER 대기열…");
                        NotifyHelper.notify(context, NotifyHelper.NOTIF_STATUS,
                                context.getString(R.string.app_name),
                                context.getString(R.string.notif_queue));
                    }
                    if (text.contains("예약완료") || text.contains("결제완료")) {
                        finish(true, "예약/결제 완료로 보입니다");
                    }
                    if (text.contains("인증번호") || text.contains("OTP") || text.contains("본인인증")) {
                        NotifyHelper.notify(context, NotifyHelper.NOTIF_AUTH,
                                context.getString(R.string.app_name),
                                context.getString(R.string.notif_otp));
                    }
                }
        );
    }

    private void injectHelpers(WebView view) {
        // no-op placeholder for future shared JS
    }

    private void tryLogin(WebView view) {
        String id = store.get(SecureStore.KEY_AUC_ID, "");
        String pw = store.get(SecureStore.KEY_AUC_PASSWORD, "");
        if (id.isEmpty() || pw.isEmpty()) {
            status("AUC 계정 미설정");
            return;
        }
        String js = ""
                + "(function(id,pw){"
                + "var links=document.querySelectorAll('a,button');"
                + "for(var i=0;i<links.length;i++){if((links[i].innerText||'').indexOf('로그인')>=0){try{links[i].click();}catch(e){}}}"
                + "var u=document.querySelector(\"input[type='text'],input[name*='id' i],input[id*='id' i]\");"
                + "var p=document.querySelector(\"input[type='password']\");"
                + "if(u&&p){u.value=id;p.value=pw;"
                + "var b=document.querySelector(\"button,input[type='submit']\");"
                + "if(b){try{b.click();}catch(e){}} return 'filled';}"
                + "return 'no-form';"
                + "})(\"" + esc(id) + "\",\"" + esc(pw) + "\");";
        view.evaluateJavascript(js, v -> Log.d(TAG, "login=" + v));
    }

    private void trySelectDateAndSlot(WebView view) {
        int daysAhead = store.getInt(SecureStore.KEY_DAYS_AHEAD, 7);
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, daysAhead);
        int day = cal.get(Calendar.DAY_OF_MONTH);
        String times = store.get(SecureStore.KEY_PREFERRED_TIMES, "19:00,20:00,18:00");
        String[] preferred = times.split(",");

        StringBuilder timeArray = new StringBuilder("[");
        for (int i = 0; i < preferred.length; i++) {
            if (i > 0) {
                timeArray.append(',');
            }
            timeArray.append('"').append(esc(preferred[i].trim())).append('"');
        }
        timeArray.append(']');

        String js = String.format(Locale.US, ""
                        + "(function(day, times){"
                        + "var cells=document.querySelectorAll('td,a,button,span');"
                        + "for(var i=0;i<cells.length;i++){"
                        + "var t=(cells[i].innerText||'').trim();"
                        + "if(t===String(day)||t.indexOf(String(day))===0){try{cells[i].click();break;}catch(e){}}"
                        + "}"
                        + "setTimeout(function(){"
                        + "for(var ti=0;ti<times.length;ti++){"
                        + "var want=times[ti];"
                        + "var nodes=document.querySelectorAll('td,a,button,span,div,li');"
                        + "for(var j=0;j<nodes.length;j++){"
                        + "var tx=(nodes[j].innerText||'');"
                        + "if(tx.indexOf(want)>=0 && tx.indexOf('불가')<0 && tx.indexOf('마감')<0){"
                        + "try{nodes[j].click();SelectTimeBridge.onSlotClicked(want);return;}catch(e){}}"
                        + "}}"
                        + "},800);"
                        + "return 'date='+day;"
                        + "})(%d, %s);",
                day, timeArray);

        view.evaluateJavascript(js, v -> Log.d(TAG, "slot=" + v));

        // Try confirm buttons shortly after
        handler.postDelayed(() -> view.evaluateJavascript(""
                + "(function(){var b=document.querySelectorAll('button,a,input');"
                + "for(var i=0;i<b.length;i++){var t=b[i].innerText||b[i].value||'';"
                + "if(t.indexOf('예약')>=0||t.indexOf('신청')>=0||t.indexOf('다음')>=0){"
                + "try{b[i].click();return 'confirm';}catch(e){}}}return 'none';})();",
                v -> Log.d(TAG, "confirm=" + v)), 2000);
    }

    private void maybePay(WebView view) {
        if (paymentTriggered) {
            return;
        }
        String method = store.get(SecureStore.KEY_PAYMENT_METHOD, "card");
        handler.postDelayed(() -> {
            paymentTriggered = true;
            if ("app_card".equals(method)) {
                status("앱카드 결제 선택");
                PaymentCardAutomator.selectAppCardOption(view);
            } else {
                status("카드 결제 입력");
                PaymentCardAutomator.fillCardForm(view, store);
            }
        }, 3500);
    }

    private void status(String msg) {
        Log.i(TAG, msg);
        if (listener != null) {
            handler.post(() -> listener.onStatus(msg));
        }
    }

    private void finish(boolean ok, String msg) {
        handler.removeCallbacksAndMessages(null);
        NotifyHelper.notify(context, NotifyHelper.NOTIF_STATUS,
                context.getString(R.string.app_name), msg);
        if (listener != null) {
            handler.post(() -> listener.onFinished(ok, msg));
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private class Bridge {
        @JavascriptInterface
        public void onSlotClicked(String time) {
            status("슬롯 클릭: " + time);
        }
    }
}
