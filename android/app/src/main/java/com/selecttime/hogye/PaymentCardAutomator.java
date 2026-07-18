package com.selecttime.hogye;

import android.util.Log;
import android.webkit.WebView;

/**
 * Injects JS to fill common PG card form fields inside the WebView.
 */
public final class PaymentCardAutomator {
    private static final String TAG = "CardPay";

    private PaymentCardAutomator() {
    }

    public static void fillCardForm(WebView webView, SecureStore store) {
        String number = store.get(SecureStore.KEY_CARD_NUMBER, "").replaceAll("\\D", "");
        String expiry = store.get(SecureStore.KEY_CARD_EXPIRY, "");
        String cvc = store.get(SecureStore.KEY_CARD_CVC, "");
        String pwd = store.get(SecureStore.KEY_CARD_PASSWORD, "");
        String birth = store.get(SecureStore.KEY_CARD_BIRTH, "");

        if (number.isEmpty()) {
            Log.w(TAG, "No card number stored");
            return;
        }
        Log.i(TAG, "Filling card form for " + SecureStore.maskCard(number));

        String js = ""
                + "(function(){"
                + "function set(sel,val){var n=document.querySelectorAll(sel);for(var i=0;i<n.length;i++){try{n[i].value=val;n[i].dispatchEvent(new Event('input',{bubbles:true}));}catch(e){}}}"
                + "set(\"input[name*='card' i],input[id*='cardNo' i],input[autocomplete='cc-number']\",\"" + esc(number) + "\");"
                + "set(\"input[name*='exp' i],input[autocomplete='cc-exp']\",\"" + esc(expiry) + "\");"
                + "set(\"input[name*='cvc' i],input[name*='cvv' i],input[autocomplete='cc-csc']\",\"" + esc(cvc) + "\");"
                + "set(\"input[type='password']\",\"" + esc(pwd) + "\");"
                + "set(\"input[name*='birth' i]\",\"" + esc(birth) + "\");"
                + "var btns=document.querySelectorAll(\"button,input[type='submit'],a\");"
                + "for(var j=0;j<btns.length;j++){var t=(btns[j].innerText||btns[j].value||'');"
                + "if(t.indexOf('결제')>=0||t.indexOf('다음')>=0||t.indexOf('확인')>=0){try{btns[j].click();break;}catch(e){}}}"
                + "return 'ok';"
                + "})();";

        webView.evaluateJavascript(js, value -> Log.d(TAG, "fill result=" + value));
    }

    public static void selectAppCardOption(WebView webView) {
        String js = ""
                + "(function(){"
                + "var nodes=document.querySelectorAll('button,a,label,input,span,div');"
                + "for(var i=0;i<nodes.length;i++){"
                + "var t=(nodes[i].innerText||nodes[i].value||nodes[i].getAttribute('aria-label')||'');"
                + "if(t.indexOf('앱카드')>=0){try{nodes[i].click();return 'clicked';}catch(e){}}"
                + "}"
                + "return 'missing';"
                + "})();";
        webView.evaluateJavascript(js, value -> Log.d(TAG, "appcard select=" + value));
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "");
    }
}
