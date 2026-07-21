package com.selecttime.hogye;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Objects;

/**
 * Highly robust JS + native touch engine for EasyPAY.
 * Handles deep iframe nesting, accurate coordinate mapping, and robust terms detection.
 * Optimized for cross-domain frame scenarios and non-standard checkbox implementations.
 */
public final class PaymentCardAutomator {
    private static final String TAG = "CardPay";
    private static final int MAX_EASY_ATTEMPTS = 4;

    /**
     * JS helper to find all documents across frames and calculate their absolute screen offsets.
     * Uses a broad search to bypass potential CORS limitations where possible.
     */
    private static final String JS_GET_DOCS_WITH_OFFSET =
            "function getDocs(){" +
            "  var ds=[];" +
            "  function scan(w, ox, oy){" +
            "    try {" +
            "      ds.push({doc: w.document, ox: ox, oy: oy, win: w});" +
            "      for(var i=0; i<w.frames.length; i++){" +
            "        try {" +
            "          var f=w.frames[i]; var el=f.frameElement;" +
            "          var r=el?el.getBoundingClientRect():{left:0,top:0};" +
            "          scan(f, ox + r.left, oy + r.top);" +
            "        } catch(e){}" +
            "      }" +
            "    } catch(e){}" +
            "  }" +
            "  scan(window, 0, 0);" +
            "  return ds;" +
            "}";

    private PaymentCardAutomator() {
    }

    public static void runEasyPayGate(WebView webView, SecureStore store, Handler handler,
                                      Runnable onDone) {
        runEasyPayGate(webView, store, handler, onDone, 0);
    }

    public static void agreeTermsThenNext(WebView webView, Handler handler, Runnable onDone) {
        Log.i(TAG, "Re-checking EasyPAY terms");
        dismissEasyPayModal(webView, handler, () ->
                ensureTermsAgreed(webView, handler, 0, agreed -> {
                    if (!agreed) {
                        showManualMessage(webView, "주문자 약관 체크를 확인해 주세요.");
                        return;
                    }
                    handler.postDelayed(() -> clickNext(webView, next -> {
                        if (next != null && next.contains("clicked") && onDone != null) {
                            handler.postDelayed(onDone, 800);
                        }
                    }), 350);
                }));
    }

    private static void runEasyPayGate(WebView webView, SecureStore store, Handler handler,
                                       Runnable onDone, int attempt) {
        String rawIssuer = store.get(SecureStore.KEY_CARD_ISSUER, "").trim();
        final String targetIssuer = "(선택 안 함)".equals(rawIssuer) ? "" : rawIssuer;

        Log.i(TAG, "EasyPAY start: attempt=" + attempt + " target=" + targetIssuer);

        if (targetIssuer.isEmpty()) {
            showManualMessage(webView, "설정에서 결제 카드사를 선택해 주세요.");
            return;
        }
        if (attempt >= MAX_EASY_ATTEMPTS) {
            Log.e(TAG, "EasyPAY failed after max retries");
            showManualMessage(webView, "카드사 자동 선택에 실패했습니다. 화면에서 직접 선택해 주세요.");
            return;
        }

        dismissEasyPayModal(webView, handler, () -> {
            // Priority Sequence: 1. Card Selection -> 2. Terms Agreement -> 3. Next
            ensureIssuerSelected(webView, targetIssuer, handler, 0, issuerOk -> {
                if (!issuerOk) {
                    Log.w(TAG, "Issuer selection verify failed, retrying gate");
                    handler.postDelayed(() ->
                            runEasyPayGate(webView, store, handler, onDone, attempt + 1), 700);
                    return;
                }
                ensureTermsAgreed(webView, handler, 0, termsOk -> {
                    if (!termsOk) {
                        showManualMessage(webView, "주문자 약관 체크를 확인해 주세요.");
                        return;
                    }
                    handler.postDelayed(() -> clickNext(webView, next ->
                            finishNext(next, handler, onDone)), 350);
                });
            });
        });
    }

    private static void ensureIssuerSelected(WebView webView, String issuer, Handler handler,
                                             int attempt, ResultDone done) {
        if (attempt >= 4) {
            Log.e(TAG, "Issuer select gave up: " + issuer);
            if (done != null) done.run(false);
            return;
        }
        readSelectedIssuer(webView, current -> {
            Log.d(TAG, "Issuer check: current=" + current + ", want=" + issuer);
            if (issuerMatches(current, issuer)) {
                if (done != null) done.run(true);
                return;
            }
            openCardPicker(webView, issuer, opened -> {
                Log.d(TAG, "Picker open: " + opened);
                int wait = (opened != null && opened.contains("already")) ? 250 : 700;
                handler.postDelayed(() -> pickIssuerInPopup(webView, issuer, picked -> {
                    Log.d(TAG, "Pick result: " + picked);
                    handler.postDelayed(() -> readSelectedIssuer(webView, after -> {
                        if (issuerMatches(after, issuer)) {
                            if (done != null) done.run(true);
                        } else {
                            handler.postDelayed(() ->
                                    ensureIssuerSelected(webView, issuer, handler, attempt + 1, done), 500);
                        }
                    }), 500);
                }), wait);
            });
        });
    }

    private static boolean issuerMatches(String currentRaw, String want) {
        if (currentRaw == null || want == null) return false;
        String current = currentRaw.replace("\"", "").trim();
        if (current.isEmpty() || "선택".equals(current) || current.startsWith("fail") || current.startsWith("none")) return false;
        String currentBrand = canonicalIssuer(current);
        String wantedBrand = canonicalIssuer(want);
        return !currentBrand.isEmpty() && currentBrand.equals(wantedBrand);
    }

    private static void finishNext(String result, Handler handler, Runnable onDone) {
        Log.i(TAG, "EasyPAY sequence finished: " + result);
        if (result != null && result.contains("clicked") && onDone != null) {
            handler.postDelayed(onDone, 800);
        }
    }

    private interface JsCb { void onResult(String raw); }
    private interface Done { void run(); }
    private interface ResultDone { void run(boolean ok); }
    private interface TapCb { void onTap(float x, float y); }

    private static void ensureTermsAgreed(WebView webView, Handler handler, int attempt, ResultDone done) {
        if (attempt >= 5) {
            Log.w(TAG, "Terms loop exceeded limits; attempting Next anyway");
            if (done != null) done.run(true); // Heuristic: try to proceed
            return;
        }
        verifyTermsAgreed(webView, status -> {
            Log.d(TAG, "Terms status [" + attempt + "]: " + status);
            if (status != null && status.contains("ok")) {
                if (done != null) done.run(true);
                return;
            }
            touchAgreeTermsOnce(webView, (float x, float y) -> {
                handler.post(() -> {
                    // Triple-tap across the row to ensure hitting the checkbox
                    dispatchTap(webView, x, y);
                    handler.postDelayed(() -> dispatchTap(webView, x + 20, y), 100);
                    handler.postDelayed(() -> dispatchTap(webView, x + 40, y), 200);
                });
                handler.postDelayed(() -> verifyTermsAgreed(webView, afterTap -> {
                    if (afterTap != null && afterTap.contains("ok")) {
                        if (done != null) done.run(true);
                    } else {
                        forceTargetTermsJs(webView, forced -> 
                                handler.postDelayed(() -> ensureTermsAgreed(webView, handler, attempt + 1, done), 600));
                    }
                }), 1000);
            }, () -> {
                forceTargetTermsJs(webView, forced -> 
                        handler.postDelayed(() -> ensureTermsAgreed(webView, handler, attempt + 1, done), 600));
            });
        });
    }

    private static void touchAgreeTermsOnce(WebView webView, TapCb tapCb, Runnable fallback) {
        webView.requestFocus();
        String js = JS_GET_DOCS_WITH_OFFSET + "(function(){"
                + "function vis(el){if(!el)return false;var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}"
                + "function norm(s){return String(s||'').replace(/\\s+/g,'').trim();}"
                + "function findTerms(doc){var ns=doc.querySelectorAll('label,span,p,strong,div');var best=null,score=9999;"
                + " for(var i=0;i<ns.length;i++){if(!vis(ns[i]))continue;var t=norm(ns[i].innerText||ns[i].textContent);"
                + "  if((t.indexOf('주문자')>=0&&t.indexOf('동의')>=0) || t.indexOf('약관전체동의')>=0 || t.indexOf('모두동의')>=0){"
                + "   if(t.length<score){best=ns[i];score=t.length;}}}"
                + " return best;}"
                + "var ds=getDocs();"
                + "for(var d=0;d<ds.length;d++){var text=findTerms(ds[d].doc);if(!text)continue;"
                + " var row=text;for(var u=0;u<4&&row.parentElement;up++){"
                + "  var p=row.parentElement,rt=norm(p.innerText||'');if(rt.indexOf('동의')>=0&&rt.length<150)row=p;else break;}"
                + " var tr=row.getBoundingClientRect();"
                // Tap the far left of the identified row (where checkboxes live)
                + " return JSON.stringify({ok:true,x:ds[d].ox+tr.left+12,y:ds[d].oy+tr.top+tr.height/2,iw:window.innerWidth,ih:window.innerHeight});}"
                + "return JSON.stringify({ok:false});})();";

        webView.evaluateJavascript(js, raw -> {
            String s = unwrapJs(raw);
            try {
                JSONObject o = new JSONObject(s);
                if (o.optBoolean("ok")) {
                    float scaleX = webView.getWidth() / (float) o.getDouble("iw");
                    float scaleY = webView.getHeight() / (float) o.getDouble("ih");
                    tapCb.onTap((float) o.getDouble("x") * scaleX,
                            (float) o.getDouble("y") * scaleY);
                } else {
                    fallback.run();
                }
            } catch (Exception e) {
                fallback.run();
            }
        });
    }

    private static void verifyTermsAgreed(WebView webView, JsCb cb) {
        String js = JS_GET_DOCS_WITH_OFFSET + "(function(){"
                + "function vis(el){if(!el)return false;var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}"
                + "function norm(s){return String(s||'').replace(/\\s+/g,'').trim();}"
                + "function target(doc){var ns=doc.querySelectorAll('label,span,p,strong,div');var best=null,score=9999;"
                + " for(var i=0;i<ns.length;i++){if(!vis(ns[i]))continue;var t=norm(ns[i].innerText||ns[i].textContent);"
                + "  if(t.indexOf('주문자')>=0&&t.indexOf('동의')>=0&&t.length<score){best=ns[i];score=t.length;}}return best;}"
                + "var ds=getDocs();"
                + "for(var d=0;d<ds.length;d++){var text=target(ds[d].doc);if(!text)continue;var row=text;"
                + " for(var up=0;up<4&&row.parentElement;up++){var p=row.parentElement,rt=norm(p.innerText||'');"
                + "  if(rt.indexOf('주문자')>=0&&rt.indexOf('동의')>=0&&rt.length<120)row=p;else break;}"
                + " var boxes=row.querySelectorAll('input[type=checkbox]');"
                + " for(var b=0;b<boxes.length;b++){if(boxes[b].checked)return 'ok:input';}"
                + " var cs=row.querySelectorAll('[role=checkbox],label,i,span');"
                + " for(var c=0;c<cs.length;c++){var el=cs[c],cl=String(el.className||'').toLowerCase();"
                + "  if(el.getAttribute('aria-checked')==='true'||/(^|[ _-])(checked|is-checked|on|selected|active)($|[ _-])/.test(cl))return 'ok:visual';"
                + "  try{var p=getComputedStyle(el,'::before').content;if(/[✓✔☑]/.test(p))return 'ok:pseudo';}catch(e){}}"
                + " return 'fail:target-found';}"
                + "return 'fail:no-target';})();";
        webView.evaluateJavascript(js, v -> cb.onResult(unwrapJs(v)));
    }

    private static void forceTargetTermsJs(WebView webView, JsCb cb) {
        String js = JS_GET_DOCS_WITH_OFFSET + "(function(){"
                + "function norm(s){return String(s||'').replace(/\\s+/g,'').trim();}"
                + "var ds=getDocs();"
                + "for(var d=0;d<ds.length;d++){var ns=ds[d].doc.querySelectorAll('label,span,p,strong,div');"
                + " for(var i=0;i<ns.length;i++){var t=norm(ns[i].innerText||ns[i].textContent);"
                + "  if(t.indexOf('주문자')<0||t.indexOf('동의')<0||t.length>=120)continue;var row=ns[i];"
                + "  for(var up=0;up<4&&row.parentElement;up++){var p=row.parentElement,rt=norm(p.innerText||'');"
                + "   if(rt.indexOf('주문자')>=0&&rt.indexOf('동의')>=0&&rt.length<120)row=p;else break;}"
                + "  var boxes=row.querySelectorAll('input[type=checkbox]');"
                + "  for(var b=0;b<boxes.length;b++){if(!boxes[b].checked){boxes[b].checked=true;"
                + "   boxes[b].dispatchEvent(new Event('input',{bubbles:true}));boxes[b].dispatchEvent(new Event('change',{bubbles:true}));}"
                + "   return 'forced';}}}"
                + "return 'none';})();";
        webView.evaluateJavascript(js, v -> cb.onResult(unwrapJs(v)));
    }

    private static void openCardPicker(WebView webView, String target, JsCb cb) {
        String js = JS_GET_DOCS_WITH_OFFSET + "(function(){"
                + "function vis(el){if(!el)return false;var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}"
                + "function norm(s){return String(s||'').replace(/\\s+/g,'').trim();}"
                + "var ds=getDocs();"
                + "for(var d=0; d<ds.length; d++){"
                + " var titles=ds[d].doc.querySelectorAll('h1,h2,h3,[role=dialog]');"
                + " for(var t=0;t<titles.length;t++){var tt=norm(titles[t].innerText||'');"
                + "  if(tt==='결제카드선택'||tt==='카드사선택')return 'already-open';}"
                + "}"
                + "for(var d2=0; d2<ds.length; d2++){"
                + " var doc=ds[d2].doc;"
                + " var nodes=doc.querySelectorAll('label,th,td,span,div,strong');"
                + " for(var i=0; i<nodes.length; i++){"
                + "  var t=norm(nodes[i].innerText||'');"
                + "  if(!vis(nodes[i])||t.indexOf('카드사')<0||t.length>30)continue;"
                + "  var row=nodes[i];for(var up=0;up<4&&row;up++){"
                + "   var buttons=row.querySelectorAll('button,a,[role=button],select');"
                + "   for(var b=0;b<buttons.length;b++){if(vis(buttons[b])){buttons[b].click();return 'opened-row-control';}}"
                + "   row=row.parentElement;}"
                + " }"
                + "}"
                + "return 'no-btn';"
                + "})();";
        webView.evaluateJavascript(js, v -> cb.onResult(Objects.requireNonNullElse(v, "")));
    }

    private static void pickIssuerInPopup(WebView webView, String issuer, JsCb cb) {
        String want = JSONObject.quote(issuer);
        String aliases = JSONObject.quote(issuerAliases(issuer));
        String js = JS_GET_DOCS_WITH_OFFSET + "(function(){"
                + "var want=" + want + "; var aliases=(" + aliases + ").split('|');"
                + "function vis(el){if(!el)return false;var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}"
                + "function norm(s){return String(s||'').replace(/\\s+/g,'').replace(/카드$/,'').toLowerCase();}"
                + "function match(t){"
                + " var tn=norm(t); if(!tn) return false;"
                + " if(tn===norm(want)) return true;"
                + " for(var i=0; i<aliases.length; i++){if(tn===norm(aliases[i]))return true;}"
                + " return false;"
                + "}"
                + "var ds=getDocs();"
                + "for(var d=0; d<ds.length; d++){"
                + " var nodes=ds[d].doc.querySelectorAll('button,a,li,span,div,td,label,strong');"
                + " var hits=[];"
                + " for(var j=0; j<nodes.length; j++){"
                + "  var txt=String(nodes[j].innerText||'').trim();"
                + "  if(vis(nodes[j])&&match(txt)){"
                + "   var score=txt.length+(nodes[j].children.length*20);"
                + "   if(/^(BUTTON|A|LI|LABEL)$/.test(nodes[j].tagName))score-=10;"
                + "   hits.push({el:nodes[j],score:score});"
                + "  }"
                + " }"
                + " hits.sort(function(a,b){return a.score-b.score;});"
                + " if(hits.length){var el=hits[0].el,clicker=el;"
                + "  for(var up=0;up<3&&clicker;up++){if(/^(BUTTON|A|LI|LABEL)$/.test(clicker.tagName)||clicker.getAttribute('role')==='button')break;clicker=clicker.parentElement;}"
                + "  if(!clicker)clicker=el;clicker.scrollIntoView({block:'center'});clicker.click();return 'picked:'+el.innerText;}"
                + "}"
                + "return 'not-found';"
                + "})();";
        webView.evaluateJavascript(js, v -> cb.onResult(Objects.requireNonNullElse(v, "")));
    }

    private static void readSelectedIssuer(WebView webView, JsCb cb) {
        String js = JS_GET_DOCS_WITH_OFFSET + "(function(){"
                + "function vis(el){if(!el)return false;var r=el.getBoundingClientRect();return r.width>0&&r.height>0;}"
                + "function norm(s){return String(s||'').replace(/\\s+/g,'').trim();}"
                + "var ds=getDocs();"
                + "for(var d=0; d<ds.length; d++){"
                + " var labels=ds[d].doc.querySelectorAll('label,th,td,span,strong,div');"
                + " for(var i=0; i<labels.length; i++){"
                + "  var lt=norm(labels[i].innerText||'');"
                + "  if(lt.indexOf('카드사')<0||lt.length>30||!vis(labels[i]))continue;"
                + "  var row=labels[i];for(var up=0;up<4&&row;up++){"
                + "   var controls=row.querySelectorAll('button,a,[role=button],select,option:checked');"
                + "   for(var c=0;c<controls.length;c++){var val=String(controls[c].innerText||controls[c].value||'').trim();"
                + "    if(vis(controls[c])&&val&&norm(val)!=='선택'&&norm(val).indexOf('카드사')<0&&val.length<30)return val;}"
                + "   row=row.parentElement;}"
                + " }"
                + "}"
                + "return 'none';"
                + "})();";
        webView.evaluateJavascript(js, v -> cb.onResult(unwrapJs(v)));
    }

    private static String issuerAliases(String issuer) {
        if (issuer == null) return "";
        String n = issuer.replace(" ", "");
        if (n.contains("KBPay") || n.contains("KB페이")) return "KB Pay|KBPay|KB페이";
        if (n.contains("국민")) return "KB국민카드|국민카드|KB국민|국민";
        if (n.contains("삼성")) return "삼성카드|삼성";
        if (n.contains("신한") || n.contains("SOL")) return "신한(SOL페이)|신한카드|신한|SOL페이|SOL";
        if (n.contains("하나")) return "하나카드|하나";
        if (n.contains("롯데")) return "롯데카드|롯데";
        if (n.contains("우리")) return "우리카드|우리";
        if (n.contains("비씨") || n.contains("페이북")) return "비씨(페이북)|비씨카드|비씨|페이북";
        if (n.contains("현대")) return "현대카드|현대";
        if (n.contains("농협") || n.contains("NH")) return "농협(NH페이)|농협카드|농협|NH페이";
        return issuer;
    }

    private static String canonicalIssuer(String issuer) {
        if (issuer == null) return "";
        String n = issuer.replaceAll("[\\s()_-]", "").toUpperCase();
        if (n.contains("KBPAY") || n.contains("KB페이")) return "KBPAY";
        if (n.contains("신한") || n.contains("SOL")) return "SHINHAN";
        if (n.contains("국민") || n.contains("KB국민")) return "KB";
        if (n.contains("삼성")) return "SAMSUNG";
        if (n.contains("하나")) return "HANA";
        if (n.contains("롯데")) return "LOTTE";
        if (n.contains("우리")) return "WOORI";
        if (n.contains("비씨") || n.contains("BC") || n.contains("페이북")) return "BC";
        if (n.contains("현대")) return "HYUNDAI";
        if (n.contains("농협") || n.contains("NH")) return "NH";
        return "";
    }

    private static void clickNext(WebView webView, JsCb cb) {
        String js = JS_GET_DOCS_WITH_OFFSET + "(function(){"
                + "var ds=getDocs();"
                + "for(var d=0; d<ds.length; d++){"
                + " var nodes=ds[d].doc.querySelectorAll('button,a,input');"
                + " for(var i=0; i<nodes.length; i++){"
                + "  var t=String(nodes[i].innerText||nodes[i].value||'').trim();"
                + "  if(t==='다음' || t==='결제하기'){"
                + "   nodes[i].scrollIntoView({block:'center'}); nodes[i].click(); return 'clicked';"
                + "  }"
                + " }"
                + "}"
                + "return 'missing';"
                + "})();";
        webView.evaluateJavascript(js, v -> cb.onResult(Objects.requireNonNullElse(v, "")));
    }

    private static void dispatchTap(WebView webView, float x, float y) {
        if (webView.getWidth() <= 0) return;
        webView.requestFocus();
        float tx = Math.max(2f, Math.min(webView.getWidth() - 2f, x));
        float ty = Math.max(2f, Math.min(webView.getHeight() - 2f, y));
        long down = SystemClock.uptimeMillis();
        MotionEvent eDown = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, tx, ty, 0);
        MotionEvent eUp = MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP, tx, ty, 0);
        webView.dispatchTouchEvent(eDown);
        webView.dispatchTouchEvent(eUp);
        eDown.recycle(); eUp.recycle();
        Log.d(TAG, "Native tap @" + Math.round(tx) + "," + Math.round(ty));
    }

    private static void dismissEasyPayModal(WebView webView, Handler handler, Done done) {
        String js = JS_GET_DOCS_WITH_OFFSET + "(function(){"
                + "var ds=getDocs(); for(var d=0; d<ds.length; d++){"
                + " var ns=ds[d].doc.querySelectorAll('button,a,span,div');"
                + " for(var i=0; i<ns.length; i++){"
                + "  var t=(ns[i].innerText||'').trim();"
                + "  if(t==='OK'||t==='확인'){ ns[i].click(); return 'c'; }"
                + " }"
                + "} return 'n';})();";
        webView.evaluateJavascript(js, null);
        handler.postDelayed(done::run, 500);
    }

    private static void showManualMessage(WebView webView, String message) {
        webView.post(() -> Toast.makeText(
                webView.getContext().getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }

    private static String unwrapJs(String raw) {
        if (raw == null || "null".equals(raw)) return "{}";
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    public static void fillCardForm(WebView webView, SecureStore store) {
        // Stub for card form path if needed later
    }

    public static void selectAppCardOption(WebView webView) {
        String js = JS_GET_DOCS_WITH_OFFSET + "(function(){"
                + "var ds=getDocs(); for(var d=0; d<ds.length; d++){"
                + " var nodes=ds[d].doc.querySelectorAll('button,a,label,input,span');"
                + " for(var i=0; i<nodes.length; i++){"
                + "  if((nodes[i].innerText||'').indexOf('앱카드')>=0){ nodes[i].click(); return 'c'; }"
                + " }"
                + "} return 'n';})();";
        webView.evaluateJavascript(js, null);
    }
}
