package com.selecttime.hogye;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Injects JS + native touch to drive EasyPAY gate
 * (주문자 약관 전체 동의 + 카드사선택 + 다음).
 */
public final class PaymentCardAutomator {
    private static final String TAG = "CardPay";
    private static final int MAX_EASY_ATTEMPTS = 5;

    private PaymentCardAutomator() {
    }

    public static void runEasyPayGate(WebView webView, SecureStore store, Handler handler,
                                      Runnable onDone) {
        runEasyPayGate(webView, store, handler, onDone, 0);
    }

    /** After terms-disagree modal — dismiss, touch-check agree, 다음. */
    public static void agreeTermsThenNext(WebView webView, Handler handler, Runnable onDone) {
        Log.i(TAG, "EasyPAY re-agree terms then next (touch)");
        dismissEasyPayModal(webView, handler, () ->
                ensureTermsAgreed(webView, handler, 0, () ->
                        handler.postDelayed(() -> clickNext(webView, next -> {
                            Log.d(TAG, "next after terms=" + next);
                            if (onDone != null) {
                                handler.postDelayed(onDone, 1000);
                            }
                        }), 700)));
    }

    private static void runEasyPayGate(WebView webView, SecureStore store, Handler handler,
                                       Runnable onDone, int attempt) {
        String issuer = store.get(SecureStore.KEY_CARD_ISSUER, "").trim();
        if ("(선택 안 함)".equals(issuer)) {
            issuer = "";
        }
        final String issuerFinal = issuer;
        Log.i(TAG, "EasyPAY gate attempt=" + attempt + " issuer="
                + (issuerFinal.isEmpty() ? "(none)" : issuerFinal));

        if (issuerFinal.isEmpty()) {
            Log.w(TAG, "No card issuer in settings");
            if (onDone != null) {
                handler.post(onDone);
            }
            return;
        }
        if (attempt >= MAX_EASY_ATTEMPTS) {
            Log.e(TAG, "EasyPAY failed after retries");
            if (onDone != null) {
                handler.post(onDone);
            }
            return;
        }

        dismissEasyPayModal(webView, handler, () ->
                ensureTermsAgreed(webView, handler, 0, () ->
                        handler.postDelayed(() -> ensureIssuerSelected(webView, issuerFinal, handler, 0, () ->
                                ensureTermsAgreed(webView, handler, 0, () ->
                                        handler.postDelayed(() -> clickNext(webView, next ->
                                                        finishNext(next, handler, onDone)),
                                                700))), 300)));
    }

    /** Force the configured issuer; never treat a different default (e.g. KBPAY) as OK. */
    private static void ensureIssuerSelected(WebView webView, String issuer, Handler handler,
                                             int attempt, Done done) {
        if (attempt >= 4) {
            Log.e(TAG, "issuer select gave up want=" + issuer);
            if (done != null) {
                done.run();
            }
            return;
        }
        readSelectedIssuer(webView, current -> {
            Log.d(TAG, "issuer current=" + current + " want=" + issuer);
            if (issuerMatches(current, issuer)) {
                Log.d(TAG, "issuer already correct");
                if (done != null) {
                    done.run();
                }
                return;
            }
            openCardPicker(webView, opened -> {
                Log.d(TAG, "open picker=" + opened);
                handler.postDelayed(() -> pickIssuerInPopup(webView, issuer, picked -> {
                    Log.d(TAG, "pick=" + picked);
                    handler.postDelayed(() -> readSelectedIssuer(webView, after -> {
                        Log.d(TAG, "issuer after pick=" + after);
                        if (issuerMatches(after, issuer)) {
                            if (done != null) {
                                done.run();
                            }
                            return;
                        }
                        handler.postDelayed(() ->
                                        ensureIssuerSelected(webView, issuer, handler, attempt + 1, done),
                                800);
                    }), 700);
                }), opened != null && opened.contains("already") ? 250 : 1200);
            });
        });
    }

    private static boolean issuerMatches(String currentRaw, String want) {
        if (currentRaw == null || want == null) {
            return false;
        }
        String current = currentRaw.replace("\"", "").trim();
        if (current.isEmpty() || "선택".equals(current) || current.startsWith("fail")
                || current.startsWith("none")) {
            return false;
        }
        String cn = current.replaceAll("\\s+", "");
        String wn = want.replaceAll("\\s+", "");
        if (cn.equalsIgnoreCase(wn) || cn.contains(wn) || wn.contains(cn)) {
            return true;
        }
        String aliases = issuerAliases(want);
        for (String a : aliases.split("\\|")) {
            String an = a.replaceAll("\\s+", "");
            if (!an.isEmpty() && (cn.equalsIgnoreCase(an) || cn.contains(an) || an.contains(cn))) {
                // Avoid weak single-letter / overly short matches
                if (an.length() >= 2 && cn.length() >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void finishNext(String next, Handler handler, Runnable onDone) {
        Log.d(TAG, "next=" + next);
        if (onDone != null) {
            handler.postDelayed(onDone, 1000);
        }
    }

    private interface JsCb {
        void onResult(String raw);
    }

    private interface Done {
        void run();
    }

    /**
     * Agree terms only if not already checked. Re-tapping a checked box unchecks it
     * (caused flaky 「이용 약관에 동의하지 않았습니다」).
     */
    private static void ensureTermsAgreed(WebView webView, Handler handler, int attempt, Done done) {
        if (attempt >= 5) {
            Log.w(TAG, "terms agree gave up after retries");
            if (done != null) {
                done.run();
            }
            return;
        }
        verifyTermsAgreed(webView, verified -> {
            Log.d(TAG, "terms pre-check attempt=" + attempt + " -> " + verified);
            if (verified != null && verified.contains("ok")) {
                if (done != null) {
                    done.run();
                }
                return;
            }
            touchAgreeTermsOnce(webView, handler, () ->
                    handler.postDelayed(() -> verifyTermsAgreed(webView, after -> {
                        Log.d(TAG, "terms post-check attempt=" + attempt + " -> " + after);
                        if (after != null && after.contains("ok")) {
                            if (done != null) {
                                done.run();
                            }
                            return;
                        }
                        expandAndCheckAll(webView, handler, () ->
                                handler.postDelayed(() ->
                                                ensureTermsAgreed(webView, handler, attempt + 1, done),
                                        450));
                    }), 600));
        });
    }

    /** One deliberate tap on the left square of 「주문자 약관 전체 동의」 (not multi-tap). */
    private static void touchAgreeTermsOnce(WebView webView, Handler handler, Done done) {
        webView.requestFocus();
        String js = ""
                + "(function(){"
                + "function vis(el){if(!el)return false;var r=el.getBoundingClientRect();"
                + " return r.width>0&&r.height>0;}"
                + "function norm(s){return String(s||'').replace(/\\s+/g,'').trim();}"
                + "function docs(){"
                + " var out=[document];"
                + " try{for(var i=0;i<window.frames.length;i++){"
                + "  try{out.push(window.frames[i].document);}catch(e){}"
                + " }}catch(e){}"
                + " return out;"
                + "}"
                + "var best=null,bestLen=999,bestDoc=document;"
                + "var ds=docs();"
                + "for(var d=0;d<ds.length;d++){"
                + " var doc=ds[d]; if(!doc) continue;"
                + " var nodes=doc.querySelectorAll('label,span,div,p,a,li,td,strong,font,em,button');"
                + " for(var i=0;i<nodes.length;i++){"
                + "  if(!vis(nodes[i])) continue;"
                + "  var t=norm(nodes[i].innerText||nodes[i].textContent);"
                + "  if(!t||t.length>40) continue;"
                + "  if(t.indexOf('주문자약관전체동의')<0 && t.indexOf('약관전체동의')<0) continue;"
                + "  if(t.length<bestLen){bestLen=t.length;best=nodes[i];bestDoc=doc;}"
                + " }"
                + "}"
                + "if(!best){"
                + " for(var d2=0;d2<ds.length;d2++){"
                + "  var doc2=ds[d2]; if(!doc2) continue;"
                + "  var ns=doc2.querySelectorAll('label,span,div,p,a,li,td,strong');"
                + "  for(var j=0;j<ns.length;j++){"
                + "   if(!vis(ns[j])) continue;"
                + "   var t2=norm(ns[j].innerText||'');"
                + "   if(t2==='전체동의'||(t2.indexOf('주문자')>=0&&t2.indexOf('동의')>=0&&t2.length<=20)){"
                + "    best=ns[j];bestDoc=doc2;break;"
                + "   }"
                + "  }"
                + "  if(best) break;"
                + " }"
                + "}"
                + "if(!best) return JSON.stringify({ok:false,reason:'no-label',"
                + " iw:window.innerWidth,ih:window.innerHeight});"
                + "var row=best;"
                + "for(var u=0;u<8&&row;u++){"
                + " var txt=norm(row.innerText||'');"
                + " var rr=row.getBoundingClientRect();"
                + " if(txt.indexOf('주문자')>=0 && rr.width>60 && rr.width<bestDoc.documentElement.clientWidth*0.95"
                + "  && rr.height>18 && rr.height<120) break;"
                + " row=row.parentElement;"
                + "}"
                + "if(!row) row=best;"
                + "try{row.scrollIntoView({block:'center'});}catch(e){}"
                + "var box=null;"
                + "var inp=row.querySelector&&row.querySelector('input[type=checkbox],input[type=radio]');"
                + "if(inp&&vis(inp)) box=inp;"
                + "if(!box){"
                + " var kids=row.querySelectorAll('i,em,span,div,img,label,a,button');"
                + " for(var k=0;k<kids.length;k++){"
                + "  if(!vis(kids[k])) continue;"
                + "  var kr=kids[k].getBoundingClientRect();"
                + "  var kt=norm(kids[k].innerText||kids[k].textContent||'');"
                + "  if(kt.length>8) continue;"
                + "  if(kr.width>=10&&kr.width<=44&&kr.height>=10&&kr.height<=44){box=kids[k];break;}"
                + " }"
                + "}"
                + "var target=box||row;"
                + "var r=target.getBoundingClientRect();"
                + "var cx,cy;"
                + "if(box){cx=r.left+r.width/2;cy=r.top+r.height/2;}"
                + "else{cx=r.left+18;cy=r.top+r.height/2;}"
                + "try{"
                + " var all=bestDoc.querySelectorAll('input[type=checkbox],input[type=radio]');"
                + " for(var a=0;a<all.length;a++){"
                + "  if(all[a].checked) continue;"
                + "  all[a].checked=true;"
                + "  all[a].setAttribute('checked','checked');"
                + "  try{all[a].dispatchEvent(new Event('input',{bubbles:true}));}catch(e){}"
                + "  try{all[a].dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}"
                + " }"
                + "}catch(e){}"
                + "return JSON.stringify({"
                + " ok:true,"
                + " x:cx,y:cy,"
                + " iw:window.innerWidth,ih:window.innerHeight,"
                + " rw:row.getBoundingClientRect().width,"
                + " rh:row.getBoundingClientRect().height,"
                + " html:(row.outerHTML||'').slice(0,280)"
                + "});"
                + "})();";

        webView.evaluateJavascript(js, raw -> {
            Log.d(TAG, "locate agree=" + raw);
            String s = unwrapJs(raw);
            try {
                JSONObject o = new JSONObject(s);
                float iw = (float) o.optDouble("iw", webView.getWidth());
                float ih = (float) o.optDouble("ih", webView.getHeight());
                float scaleX = iw > 0 ? webView.getWidth() / iw : 1f;
                float scaleY = ih > 0 ? webView.getHeight() / ih : scaleX;

                if (!o.optBoolean("ok", false)) {
                    // Guess: left side of typical EasyPAY terms row
                    float gx = webView.getWidth() * 0.11f;
                    float gy = webView.getHeight() * 0.40f;
                    Log.w(TAG, "no agree label — guess tap @" + gx + "," + gy);
                    handler.post(() -> dispatchTap(webView, gx, gy));
                    handler.postDelayed(() -> {
                        if (done != null) {
                            done.run();
                        }
                    }, 400);
                    return;
                }

                float x = (float) o.getDouble("x") * scaleX;
                float y = (float) o.getDouble("y") * scaleY;
                // Skip tap if UI already looks checked (avoid toggle-off).
                verifyTermsAgreed(webView, pre -> {
                    if (pre != null && pre.contains("ok")) {
                        Log.i(TAG, "agree already on — skip tap");
                        if (done != null) {
                            done.run();
                        }
                        return;
                    }
                    Log.i(TAG, "agree tap once @" + Math.round(x) + "," + Math.round(y)
                            + " row=" + o.optDouble("rw") + "x" + o.optDouble("rh"));
                    handler.post(() -> dispatchTap(webView, x, y));
                    handler.postDelayed(() -> {
                        if (done != null) {
                            done.run();
                        }
                    }, 400);
                });
            } catch (Exception e) {
                Log.e(TAG, "locate agree parse", e);
                if (done != null) {
                    done.run();
                }
            }
        });
    }

    private static void expandAndCheckAll(WebView webView, Handler handler, Done done) {
        String js = ""
                + "(function(){"
                + "function vis(el){if(!el)return false;var r=el.getBoundingClientRect();"
                + " return r.width>0&&r.height>0;}"
                + "function norm(s){return String(s||'').replace(/\\s+/g,'').trim();}"
                + "var taps=[];"
                + "var nodes=document.querySelectorAll('button,a,span,div,i,em,label');"
                + "for(var i=0;i<nodes.length;i++){"
                + " if(!vis(nodes[i])) continue;"
                + " var t=norm(nodes[i].innerText||nodes[i].textContent||'');"
                + " if(t==='+'||t==='＋'){"
                + "  var r=nodes[i].getBoundingClientRect();"
                + "  taps.push({x:r.left+r.width/2,y:r.top+r.height/2,kind:'plus'});"
                + "  try{nodes[i].click();}catch(e){}"
                + " }"
                + "}"
                + "var boxes=document.querySelectorAll('input[type=checkbox],input[type=radio]');"
                + "for(var b=0;b<boxes.length;b++){"
                + " try{"
                + "  if(boxes[b].checked) continue;"
                + "  boxes[b].checked=true;"
                + "  boxes[b].setAttribute('checked','checked');"
                + "  boxes[b].dispatchEvent(new Event('change',{bubbles:true}));"
                + " }catch(e){}"
                + "}"
                + "return JSON.stringify({ok:true,taps:taps,nbox:boxes.length,"
                + " iw:window.innerWidth,ih:window.innerHeight});"
                + "})();";
        webView.evaluateJavascript(js, raw -> {
            Log.d(TAG, "expand/checkAll=" + raw);
            String s = unwrapJs(raw);
            try {
                JSONObject o = new JSONObject(s);
                JSONArray taps = o.optJSONArray("taps");
                float iw = (float) o.optDouble("iw", webView.getWidth());
                float ih = (float) o.optDouble("ih", webView.getHeight());
                float scaleX = iw > 0 ? webView.getWidth() / iw : 1f;
                float scaleY = ih > 0 ? webView.getHeight() / ih : scaleX;
                if (taps != null && taps.length() > 0) {
                    JSONObject t = taps.getJSONObject(0);
                    float x = (float) t.optDouble("x") * scaleX;
                    float y = (float) t.optDouble("y") * scaleY;
                    handler.post(() -> dispatchTap(webView, x, y));
                    // After expand, wait then touch 전체동의 once more
                    handler.postDelayed(() -> touchAgreeTermsOnce(webView, handler, done), 500);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "expand parse", e);
            }
            if (done != null) {
                done.run();
            }
        });
    }

    private static void verifyTermsAgreed(WebView webView, JsCb cb) {
        String js = ""
                + "(function(){"
                + "function norm(s){return String(s||'').replace(/\\s+/g,'').trim();}"
                + "function vis(el){if(!el)return false;var r=el.getBoundingClientRect();"
                + " return r.width>0&&r.height>0;}"
                + "function looksChecked(el){"
                + " if(!el) return false;"
                + " if(el.tagName==='INPUT'&&(el.type==='checkbox'||el.type==='radio')&&el.checked) return true;"
                + " var aria=el.getAttribute&&el.getAttribute('aria-checked');"
                + " if(aria==='true') return true;"
                + " var cls=String(el.className||'');"
                + " if(/\\b(checked|on|active|selected|agree|chk_on|is-check|is_on)\\b/i.test(cls)) return true;"
                + " var html=(el.outerHTML||'');"
                + " if(/✓|✔|☑|check(ed)?/i.test(html)&&/chk|check|agree/i.test(cls+html)) return true;"
                + " // painted checkmark often uses background-image / ::before content"
                + " try{"
                + "  var before=getComputedStyle(el,'::before').content||'';"
                + "  if(before&&before!=='none'&&before!=='\"\"') return true;"
                + " }catch(e){}"
                + " return false;"
                + "}"
                + "var boxes=document.querySelectorAll('input[type=checkbox],input[type=radio]');"
                + "var checked=0,total=0;"
                + "for(var i=0;i<boxes.length;i++){"
                + " if(!vis(boxes[i])) continue;"
                + " total++;"
                + " if(boxes[i].checked) checked++;"
                + "}"
                + "if(total>0&&checked===total) return 'ok:all-input';"
                + "if(total>0&&checked>0) return 'ok:some-input:'+checked+'/'+total;"
                + "var nodes=document.querySelectorAll('label,span,div,i,em,a,li,td,p,strong');"
                + "for(var n=0;n<nodes.length;n++){"
                + " if(!vis(nodes[n])) continue;"
                + " var t=norm(nodes[n].innerText||'');"
                + " if(t.indexOf('주문자약관전체동의')<0&&t.indexOf('약관전체동의')<0&&t!=='전체동의') continue;"
                + " var el=nodes[n];"
                + " for(var d=0;d<6&&el;d++){"
                + "  if(looksChecked(el)) return 'ok:ui';"
                + "  var kids=el.children||[];"
                + "  for(var k=0;k<kids.length;k++){ if(looksChecked(kids[k])) return 'ok:child'; }"
                + "  var inp=el.querySelector&&el.querySelector('input[type=checkbox],input[type=radio]');"
                + "  if(inp&&inp.checked) return 'ok:nested';"
                + "  el=el.parentElement;"
                + " }"
                + "}"
                + "try{"
                + " if(window.agreeAll===true||window.isAgree===true||window.allCheck===true) return 'ok:global';"
                + " if(typeof window.agreeYn!=='undefined'&&(window.agreeYn==='Y'||window.agreeYn===true)) return 'ok:agreeYn';"
                + "}catch(e){}"
                + "return 'fail:checked='+checked+'/'+total;"
                + "})();";
        webView.evaluateJavascript(js, v -> cb.onResult(v == null ? "" : v));
    }

    private static void dispatchTap(WebView webView, float x, float y) {
        if (webView.getWidth() <= 0) {
            return;
        }
        webView.requestFocus();
        x = Math.max(2f, Math.min(webView.getWidth() - 2f, x));
        y = Math.max(2f, Math.min(webView.getHeight() - 2f, y));
        long down = SystemClock.uptimeMillis();
        MotionEvent eDown = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent eUp = MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP, x, y, 0);
        boolean okDown = webView.dispatchTouchEvent(eDown);
        boolean okUp = webView.dispatchTouchEvent(eUp);
        eDown.recycle();
        eUp.recycle();
        Log.d(TAG, "tap @" + Math.round(x) + "," + Math.round(y) + " ok=" + okDown + "/" + okUp);
    }

    private static void dismissEasyPayModal(WebView webView, Handler handler, Done done) {
        String js = ""
                + "(function(){"
                + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                + "function norm(s){ return String(s||'').replace(/\\s+/g,'').trim(); }"
                + "var body=(document.body&&document.body.innerText)||'';"
                + "if(body.indexOf('이용 약관에 동의하지')<0 && body.indexOf('약관에 동의하지')<0"
                + " && body.indexOf('카드를 선택')<0) return JSON.stringify({ok:false});"
                + "var nodes=document.querySelectorAll('button,a,input,span,div');"
                + "for(var i=0;i<nodes.length;i++){"
                + " if(!visible(nodes[i])) continue;"
                + " var t=norm(nodes[i].innerText||nodes[i].value);"
                + " if(t==='OK'||t==='Ok'||t==='확인'){"
                + "  var r=nodes[i].getBoundingClientRect();"
                + "  return JSON.stringify({ok:true,x:r.left+r.width/2,y:r.top+r.height/2,"
                + "   iw:window.innerWidth,ih:window.innerHeight});"
                + " }"
                + "}"
                + "return JSON.stringify({ok:false,reason:'no-ok'});"
                + "})();";
        webView.evaluateJavascript(js, raw -> {
            String s = unwrapJs(raw);
            try {
                JSONObject o = new JSONObject(s);
                if (o.optBoolean("ok", false)) {
                    float iw = (float) o.optDouble("iw", webView.getWidth());
                    float scale = iw > 0 ? webView.getWidth() / iw : 1f;
                    float x = (float) o.getDouble("x") * scale;
                    float y = (float) o.getDouble("y") * scale;
                    handler.post(() -> dispatchTap(webView, x, y));
                    webView.evaluateJavascript(
                            "(function(){var ns=document.querySelectorAll('button,a,span,div');"
                                    + "for(var i=0;i<ns.length;i++){"
                                    + "var t=(ns[i].innerText||'').replace(/\\s+/g,'').trim();"
                                    + "if(t==='OK'||t==='확인'){ns[i].click();return 'c';}}"
                                    + "return 'n';})();",
                            null);
                    handler.postDelayed(done::run, 500);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "dismiss parse", e);
            }
            done.run();
        });
    }

    private static String unwrapJs(String raw) {
        if (raw == null || "null".equals(raw)) {
            return "{}";
        }
        String s = raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return s;
    }

    private static void openCardPicker(WebView webView, JsCb cb) {
        String js = ""
                + "(function(){"
                + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                + "function norm(s){ return String(s||'').replace(/\\s+/g,'').trim(); }"
                + "function tap(el){"
                + " if(!el) return false;"
                + " try{ el.scrollIntoView({block:'center'}); el.click(); return true; }catch(e){ return false; }"
                + "}"
                + "var body=(document.body&&document.body.innerText)||'';"
                + "if(body.indexOf('결제 카드 선택')>=0||body.indexOf('결제카드선택')>=0) return 'already-open';"
                // 1) classic 「선택」 button
                + "var best=null,bestScore=-1;"
                + "var nodes=document.querySelectorAll('button,a,input,span,div');"
                + "for(var i=0;i<nodes.length;i++){"
                + " var el=nodes[i]; if(!visible(el)) continue;"
                + " if(norm(el.innerText||el.value||'')!=='선택') continue;"
                + " var r=el.getBoundingClientRect();"
                + " if(r.width<24||r.width>280) continue;"
                + " var score=(el.tagName==='BUTTON'||el.tagName==='A'?10:0)+ (r.width<160?4:0);"
                + " if(score>bestScore){bestScore=score;best=el;}"
                + "}"
                + "if(best&&tap(best)) return 'opened-select';"
                // 2) already filled value (KBPAY / 신한카드 …) next to 카드사선택 — tap to re-open
                + "var labels=document.querySelectorAll('label,span,div,th,td,p,strong');"
                + "for(var L=0;L<labels.length;L++){"
                + " if(!visible(labels[L])) continue;"
                + " if(norm(labels[L].innerText||'')!=='카드사선택') continue;"
                + " var row=labels[L];"
                + " for(var u=0;u<5&&row;u++){"
                + "  var kids=row.querySelectorAll('button,a,span,div,input');"
                + "  for(var k=0;k<kids.length;k++){"
                + "   if(!visible(kids[k])) continue;"
                + "   var t=norm(kids[k].innerText||kids[k].value||'');"
                + "   if(!t||t==='카드사선택'||t==='할부개월'||t==='일시불'||t==='다음'||t==='취소') continue;"
                + "   if(t.length>24) continue;"
                + "   var kr=kids[k].getBoundingClientRect();"
                + "   if(kr.width<40||kr.height<18) continue;"
                + "   if(tap(kids[k])) return 'opened-value:'+t;"
                + "  }"
                + "  row=row.parentElement;"
                + " }"
                + "}"
                // 3) any visible chip that looks like a selected card brand
                + "var chips=document.querySelectorAll('button,a,span,div');"
                + "for(var c=0;c<chips.length;c++){"
                + " if(!visible(chips[c])) continue;"
                + " var ct=norm(chips[c].innerText||'');"
                + " if(!ct||ct.length>18) continue;"
                + " if(/^(KBPAY|KBPay|KB페이|KB국민|신한|삼성|하나|롯데|우리|현대|농협|비씨|카카오|네이버)/i.test(ct)"
                + "  || /카드$/.test(ct)|| /페이$/.test(ct)|| /SOL/.test(ct)){"
                + "  var cr=chips[c].getBoundingClientRect();"
                + "  if(cr.width>=48&&cr.width<420&&cr.height>=24&&cr.height<80){"
                + "   if(tap(chips[c])) return 'opened-chip:'+ct;"
                + "  }"
                + " }"
                + "}"
                + "return 'no-select-btn';"
                + "})();";
        webView.evaluateJavascript(js, v -> cb.onResult(v == null ? "" : v));
    }

    private static void pickIssuerInPopup(WebView webView, String issuer, JsCb cb) {
        String want = JSONObject.quote(issuer);
        String aliases = JSONObject.quote(issuerAliases(issuer));
        String js = ""
                + "(function(){"
                + "var want=" + want + ";"
                + "var aliases=(" + aliases + ").split('|');"
                + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                + "function norm(s){ return String(s||'').replace(/\\s+/g,'').trim(); }"
                + "function matchText(t){"
                + " var tn=norm(t); if(!tn||tn.length>48) return false;"
                + " if(tn==='선택'||tn==='취소'||tn==='다음'||tn==='카드사선택'||tn==='결제카드선택') return false;"
                + " // Prefer exact / prefix over loose contains for short tokens"
                + " var wn=norm(want);"
                + " if(tn===wn) return true;"
                + " if(wn.length>=2&&(tn===wn||tn.indexOf(wn)===0||wn.indexOf(tn)===0&&tn.length>=2)) return true;"
                + " for(var a=0;a<aliases.length;a++){"
                + "  var an=norm(aliases[a]); if(!an||an.length<2) continue;"
                + "  if(tn===an) return true;"
                + "  if(tn.indexOf(an)>=0||an.indexOf(tn)>=0&&tn.length>=an.length) return true;"
                + " }"
                + " return false;"
                + "}"
                + "var nodes=document.querySelectorAll('button,a,li,span,div,td,label,p');"
                + "var hits=[];"
                + "for(var i=0;i<nodes.length;i++){"
                + " if(!visible(nodes[i])) continue;"
                + " var t=(nodes[i].innerText||'').trim();"
                + " if(!matchText(t)) continue;"
                // Prefer leaf-ish short labels inside the popup
                + " hits.push({el:nodes[i],len:norm(t).length,tag:nodes[i].tagName});"
                + "}"
                + "hits.sort(function(a,b){"
                + " var ta=(a.tag==='BUTTON'||a.tag==='A'?0:1);"
                + " var tb=(b.tag==='BUTTON'||b.tag==='A'?0:1);"
                + " if(ta!==tb) return ta-tb;"
                + " return a.len-b.len;"
                + "});"
                + "for(var h=0;h<hits.length;h++){"
                + " try{ hits[h].el.scrollIntoView({block:'center'}); hits[h].el.click(); return 'picked:'+norm(hits[h].el.innerText||''); }catch(e){}"
                + "}"
                + "return 'missing';"
                + "})();";
        webView.evaluateJavascript(js, v -> cb.onResult(v == null ? "" : v));
    }

    /** Read the visible selected issuer next to 「카드사선택」 (e.g. KBPAY, 신한카드). */
    private static void readSelectedIssuer(WebView webView, JsCb cb) {
        String js = ""
                + "(function(){"
                + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                + "function norm(s){ return String(s||'').replace(/\\s+/g,'').trim(); }"
                + "var labels=document.querySelectorAll('label,span,div,th,td,p,strong');"
                + "for(var L=0;L<labels.length;L++){"
                + " if(!visible(labels[L])) continue;"
                + " if(norm(labels[L].innerText||'')!=='카드사선택') continue;"
                + " var row=labels[L];"
                + " for(var u=0;u<6&&row;u++){"
                + "  var kids=row.querySelectorAll('button,a,span,div,input');"
                + "  var best='',bestW=0;"
                + "  for(var k=0;k<kids.length;k++){"
                + "   if(!visible(kids[k])) continue;"
                + "   var t=(kids[k].innerText||kids[k].value||'').trim();"
                + "   var tn=norm(t);"
                + "   if(!tn||tn==='카드사선택'||tn==='선택'||tn==='할부개월'||tn==='일시불') continue;"
                + "   if(tn.length>24) continue;"
                + "   var r=kids[k].getBoundingClientRect();"
                + "   if(r.width<36||r.height<16) continue;"
                + "   if(r.width>bestW){bestW=r.width;best=t;}"
                + "  }"
                + "  if(best) return best;"
                + "  row=row.parentElement;"
                + " }"
                + "}"
                + "return 'none';"
                + "})();";
        webView.evaluateJavascript(js, v -> cb.onResult(v == null ? "" : unwrapJs(v)));
    }

    private static String issuerAliases(String issuer) {
        if (issuer == null) {
            return "";
        }
        String n = issuer.replace(" ", "");
        // Order matters: KB Pay before 국민, so "KB Pay" settings don't map to 국민카드 aliases only.
        if (n.equalsIgnoreCase("KBPay") || n.contains("KBPay") || n.contains("KB페이")
                || n.equals("KB Pay") || n.contains("KB Pay")) {
            return "KB Pay|KBPay|KBPAY|KB페이";
        }
        if (n.contains("KB국민") || (n.contains("국민") && !n.contains("Pay") && !n.contains("페이"))) {
            return "KB국민카드|국민카드|KB국민|국민카드|국민";
        }
        if (n.contains("삼성")) {
            return "삼성카드|삼성";
        }
        if (n.contains("하나")) {
            return "하나카드|하나";
        }
        if (n.contains("신한") || n.contains("SOL")) {
            return "신한(SOL페이)|신한카드|신한|SOL페이|SOL|신한SOL페이";
        }
        if (n.contains("롯데")) {
            return "롯데카드|롯데";
        }
        if (n.contains("농협") || n.contains("NH")) {
            return "농협(NH페이)|농협카드|농협|NH페이|NH";
        }
        if (n.contains("우리")) {
            return "우리카드|우리";
        }
        if (n.contains("비씨") || n.contains("페이북") || n.contains("BC")) {
            return "비씨(페이북)|비씨카드|비씨|페이북|BC";
        }
        if (n.contains("현대")) {
            return "현대카드|현대";
        }
        if (n.contains("카카오")) {
            return "카카오페이|카카오";
        }
        if (n.contains("네이버")) {
            return "네이버페이|네이버";
        }
        return issuer;
    }

    private static void clickNext(WebView webView, JsCb cb) {
        String js = ""
                + "(function(){"
                + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                + "function norm(s){ return String(s||'').replace(/\\s+/g,'').trim(); }"
                + "var nodes=document.querySelectorAll('button,a,input,span,div');"
                + "for(var i=0;i<nodes.length;i++){"
                + " if(!visible(nodes[i])) continue;"
                + " if(norm(nodes[i].innerText||nodes[i].value)==='다음'){"
                + "  try{ nodes[i].scrollIntoView({block:'center'}); nodes[i].click(); return 'clicked'; }catch(e){}"
                + " }"
                + "}"
                + "return 'missing';"
                + "})();";
        webView.evaluateJavascript(js, v -> cb.onResult(v == null ? "" : v));
    }

    public static void fillCardForm(WebView webView, SecureStore store) {
        String number = store.get(SecureStore.KEY_CARD_NUMBER, "").replaceAll("\\D", "");
        if (number.isEmpty()) {
            Log.w(TAG, "No card number stored");
            return;
        }
        Log.i(TAG, "Card form skipped (issuer/EasyPAY path)");
    }

    public static void selectAppCardOption(WebView webView) {
        String js = ""
                + "(function(){"
                + "var nodes=document.querySelectorAll('button,a,label,input,span,div');"
                + "for(var i=0;i<nodes.length;i++){"
                + "var t=(nodes[i].innerText||nodes[i].value||'');"
                + "if(t.indexOf('앱카드')>=0){try{nodes[i].click();return 'clicked';}catch(e){}}"
                + "}"
                + "return 'missing';"
                + "})();";
        webView.evaluateJavascript(js, value -> Log.d(TAG, "appcard select=" + value));
    }
}
