package com.selecttime.hogye;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

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

    private boolean loginAttempted;
    private boolean loginSucceeded;
    private boolean loginFailed;
    private boolean navigatedToReservation;
    private boolean paymentTriggered;
    private boolean easyPayGateDone;
    private boolean easyPayTermsRetrying;
    private boolean integratedLoginClicked;
    private long lastShortcutClickMs;
    private boolean slotSelectionStarted;
    private boolean slotClicked;
    private boolean bookingFlowDone;
    private boolean confirmAccepted;
    private boolean courtsDone;
    private boolean partyDone;
    private boolean reserveGateDone;
    private int partyPhase;
    private int partyTries;
    private int pollCount;
    private int loginTries;
    private int pageReadyToken;
    private int confirmPollTries;
    private int payConfirmTries;
    private int slotSelectAttempts;
    private long lastBookingStepMs;

    /** Open-time: login/calendar before open, then strike. */
    private boolean warmMode;
    private boolean fastOpenPath;
    private boolean strikeStarted;
    private boolean waitingForOpen;
    private long openAtMillis;
    private final Runnable strikeRunnable = this::beginStrikeNow;

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
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        webView.addJavascriptInterface(new Bridge(), "SelectTimeBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                Log.w(TAG, "JS alert: " + message);
                if (message != null && (
                        message.contains("로그인정보가 잘못")
                                || message.contains("아이디") && message.contains("비밀번호")
                                || message.contains("로그인 실패")
                )) {
                    onLoginRejected(message);
                }
                result.confirm();
                // EasyPAY: 「카드를 선택하여 주십시오」 → re-run issuer selection.
                if (message != null && message.contains("카드를 선택")) {
                    status("카드사 미선택 — 다시 선택");
                    easyPayGateDone = false;
                    paymentTriggered = false;
                    handler.postDelayed(() -> {
                        if (!bookingFlowDone) {
                            easyPayGateDone = true;
                            PaymentCardAutomator.runEasyPayGate(webView, store, handler, () -> {
                                paymentTriggered = false;
                                handler.postDelayed(() -> maybePay(webView), 1200);
                            });
                        }
                    }, 600);
                }
                // EasyPAY: 「이용 약관에 동의하지 않았습니다」 → check terms then 다음.
                if (message != null && (
                        message.contains("이용 약관에 동의")
                                || message.contains("약관에 동의하지")
                                || message.contains("약관동의")
                )) {
                    status("이용약관 동의 후 재진행");
                    paymentTriggered = false;
                    handler.postDelayed(() -> {
                        if (!bookingFlowDone) {
                            PaymentCardAutomator.agreeTermsThenNext(webView, handler, () -> {
                                paymentTriggered = false;
                                handler.postDelayed(() -> maybePay(webView), 1200);
                            });
                        }
                    }, 500);
                }
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                Log.w(TAG, "JS confirm: " + message);
                // Auto-accept payment / register confirms from window.confirm().
                result.confirm();
                if (message != null && message.contains("결제")) {
                    status("결제 확인(예) — 카드 입력");
                    paymentTriggered = false;
                    handler.postDelayed(() -> maybePay(webView), 1500);
                }
                return true;
            }
        });
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
                Log.d(TAG, "onPageFinished: " + url);
                // Debounce: ignore rapid/repeated finished events from the same navigation.
                final int token = ++pageReadyToken;
                final String finishedUrl = url == null ? "" : url;
                handler.postDelayed(() -> {
                    if (token != pageReadyToken || bookingFlowDone) {
                        return;
                    }
                    onPageReady(view, finishedUrl);
                }, pageDebounceMs());
            }
        });
    }

    private int pageDebounceMs() {
        return fastOpenPath ? 350 : 700;
    }

    private int pollIntervalMs() {
        if (waitingForOpen) {
            return 1000;
        }
        return fastOpenPath ? 1200 : 2000;
    }

    /** Manual / quick book: full flow from login. */
    public void start() {
        warmMode = false;
        fastOpenPath = false;
        openAtMillis = 0;
        strikeStarted = false;
        waitingForOpen = false;
        startInternal();
    }

    /** Open-time warm: login + calendar, wait until openAt, then strike. */
    public void startWarm(long openAtMs) {
        warmMode = true;
        fastOpenPath = false;
        openAtMillis = openAtMs;
        strikeStarted = false;
        waitingForOpen = false;
        startInternal();
    }

    /**
     * Open-time strike: if warm already waiting, release slot click;
     * otherwise cold-start with fast delays.
     */
    public void startStrike(long openAtMs) {
        if (waitingForOpen || (warmMode && loginSucceeded && navigatedToReservation && !strikeStarted)) {
            openAtMillis = openAtMs > 0 ? openAtMs : openAtMillis;
            beginStrikeNow();
            return;
        }
        warmMode = false;
        fastOpenPath = true;
        openAtMillis = openAtMs;
        strikeStarted = true;
        waitingForOpen = false;
        startInternal();
    }

    /** Called at open instant (warm wait end or OPEN alarm). */
    public void beginStrikeNow() {
        handler.removeCallbacks(strikeRunnable);
        if (bookingFlowDone) {
            return;
        }
        if (strikeStarted && slotClicked) {
            return;
        }
        strikeStarted = true;
        waitingForOpen = false;
        fastOpenPath = true;
        warmMode = false;
        slotClicked = false;
        slotSelectionStarted = false;
        slotSelectAttempts = 0;
        confirmPollTries = 0;
        confirmAccepted = false;
        status("오픈! 「가」 슬롯 클릭 시작");
        NotifyHelper.notify(context, NotifyHelper.NOTIF_STATUS,
                context.getString(R.string.app_name), "오픈 — 슬롯 선점 시도");
        if (!loginSucceeded || !navigatedToReservation) {
            // Cold path still logging in — mark fast and continue; slot when calendar ready.
            return;
        }
        trySelectDateAndSlot(webView);
    }

    private void startInternal() {
        handler.removeCallbacks(strikeRunnable);
        loginAttempted = false;
        loginSucceeded = false;
        loginFailed = false;
        navigatedToReservation = false;
        paymentTriggered = false;
        easyPayGateDone = false;
        easyPayTermsRetrying = false;
        integratedLoginClicked = false;
        lastShortcutClickMs = 0;
        slotSelectionStarted = false;
        slotClicked = false;
        bookingFlowDone = false;
        confirmAccepted = false;
        courtsDone = false;
        partyDone = false;
        reserveGateDone = false;
        partyPhase = 0;
        partyTries = 0;
        confirmPollTries = 0;
        payConfirmTries = 0;
        slotSelectAttempts = 0;
        lastBookingStepMs = 0;
        pollCount = 0;
        loginTries = 0;
        pageReadyToken = 0;
        waitingForOpen = false;

        // Restore values changed by the temporary fast-discount migration.
        if (store.getBool("party_disc_fast_v1", false)
                && !store.getBool("party_disc_rollback_v1", false)) {
            store.putInt(SecureStore.KEY_PARTY_SIZE, 6);
            store.putInt(SecureStore.KEY_DISCOUNT_SENIOR, 2);
            store.putInt(SecureStore.KEY_DISCOUNT_MULTI_CHILD, 2);
            store.putBool("party_disc_rollback_v1", true);
        }
        if (warmMode) {
            status("오픈 사전준비 — 통합 로그인·달력");
            NotifyHelper.notify(context, NotifyHelper.NOTIF_STATUS,
                    context.getString(R.string.app_name), "오픈 사전준비(로그인·달력)");
        } else {
            status("안양시 통합 로그인으로 이동");
            NotifyHelper.notify(context, NotifyHelper.NOTIF_STATUS,
                    context.getString(R.string.app_name), "예약 자동화를 시작합니다");
        }
        webView.loadUrl(SecureStore.LOGIN_URL);
        handler.postDelayed(this::pollLoop, fastOpenPath ? 800 : 1200);
    }

    private void pollLoop() {
        pollCount++;
        if (pollCount > 600) {
            finish(false, "시간 초과");
            return;
        }
        // Warm wait: keep session alive and strike exactly at open.
        if (waitingForOpen && !strikeStarted && !bookingFlowDone) {
            long left = openAtMillis - System.currentTimeMillis();
            if (left <= 0) {
                beginStrikeNow();
            } else if (left < 5000 || pollCount % 5 == 0) {
                status(String.format(Locale.KOREAN,
                        "오픈 대기 중… %d초 (달력 준비됨)", Math.max(0, (left + 999) / 1000)));
            }
        }
        inspectPage();
        handler.postDelayed(this::pollLoop, pollIntervalMs());
    }

    private void onPageReady(WebView view, String url) {
        if (bookingFlowDone) {
            return;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        boolean onLogin = lower.contains("/sign/") || lower.contains("login") || lower.contains("signin");
        boolean onReservation = lower.contains("calendar")
                || lower.contains("/reservation/program/")
                || lower.contains("facility");

        if (loginFailed) {
            status(context.getString(R.string.notif_login_fail));
            return;
        }

        // Already on reservation calendar — do not bounce back to login (stops reload loop).
        if (onReservation) {
            loginSucceeded = true;
            if (!navigatedToReservation) {
                navigatedToReservation = true;
                status("날짜·시간 선택 화면");
            }
            if (shouldHoldForOpen()) {
                armOpenWait();
                return;
            }
            trySelectDateAndSlot(view);
            // After time confirm: courts → party/discount → payment
            if (confirmAccepted) {
                advanceBookingSteps(view);
            } else if (slotClicked) {
                clickRegisterConfirmIfPresent(view);
            }
            return;
        }

        if (!loginSucceeded && (onLogin || lower.contains("auc.or.kr"))) {
            advanceLogin(view);
            return;
        }

        if (loginSucceeded && !navigatedToReservation) {
            goReservation();
            return;
        }

        if (loginSucceeded && navigatedToReservation) {
            if (shouldHoldForOpen()) {
                armOpenWait();
                return;
            }
            trySelectDateAndSlot(view);
            if (confirmAccepted) {
                advanceBookingSteps(view);
            } else if (slotClicked) {
                clickRegisterConfirmIfPresent(view);
            }
        }
    }

    private boolean shouldHoldForOpen() {
        return warmMode && !strikeStarted && openAtMillis > 0
                && System.currentTimeMillis() < openAtMillis - 200L;
    }

    private void armOpenWait() {
        if (waitingForOpen) {
            return;
        }
        waitingForOpen = true;
        long wait = Math.max(0L, openAtMillis - System.currentTimeMillis());
        status(String.format(Locale.KOREAN,
                "사전준비 완료 — 오픈까지 %d초 대기", (wait + 999) / 1000));
        NotifyHelper.notify(context, NotifyHelper.NOTIF_STATUS,
                context.getString(R.string.app_name),
                "달력 준비됨 — 오픈 시각까지 대기");
        handler.removeCallbacks(strikeRunnable);
        handler.postDelayed(strikeRunnable, wait);
    }

    private void onLoginRejected(String message) {
        loginSucceeded = false;
        loginFailed = true;
        loginAttempted = false;
        navigatedToReservation = false;
        status(context.getString(R.string.notif_login_fail));
        NotifyHelper.notify(context, NotifyHelper.NOTIF_AUTH,
                context.getString(R.string.app_name),
                context.getString(R.string.notif_login_fail));
        Log.e(TAG, "Login rejected by site: " + message);
        // Reload login page so user can fix credentials in Settings and tap run again
        handler.postDelayed(() -> {
            if (!loginSucceeded) {
                webView.loadUrl(SecureStore.LOGIN_URL);
            }
        }, 1500);
        finish(false, context.getString(R.string.notif_login_fail));
    }

    private void inspectPage() {
        webView.evaluateJavascript(
                "(function(){return document.body?document.body.innerText.slice(0,5000):'';})();",
                value -> {
                    if (value == null) {
                        return;
                    }
                    String text = value.replace("\\n", "\n").replace("\\\"", "\"");
                    if (text.contains("서비스 접속 대기") || text.contains("TRACER") || text.contains("MBUSTER")) {
                        status("접속 대기/보안 페이지…");
                    }
                    // EasyPAY HTML modal (not window.alert): 이용 약관에 동의하지 않았습니다
                    if (!bookingFlowDone && !easyPayTermsRetrying && (
                            text.contains("이용 약관에 동의하지")
                                    || text.contains("약관에 동의하지 않았습니다")
                    )) {
                        easyPayTermsRetrying = true;
                        status("이용약관 미동의 팝업 — 체크 후 재진행");
                        PaymentCardAutomator.agreeTermsThenNext(webView, handler, () -> {
                            easyPayTermsRetrying = false;
                            paymentTriggered = false;
                            handler.postDelayed(() -> maybePay(webView), 1200);
                        });
                    }
                    if (!loginSucceeded && (
                            text.contains("로그아웃")
                                    || text.contains("님 안녕하세요")
                                    || text.contains("마이페이지")
                                    || text.contains("회원정보")
                    )) {
                        loginSucceeded = true;
                        loginFailed = false;
                        status("로그인 확인됨");
                        if (!navigatedToReservation) {
                            handler.postDelayed(this::goReservation, fastOpenPath ? 300 : 600);
                        }
                    }
                    if (text.contains("예약하기") || text.contains("코트선택")
                            || text.contains("사용인원") || text.contains("감면대상")
                            || text.contains("약관동의") || text.contains("결제하기")
                            || text.contains("환불 불가") || text.contains("전체동의")) {
                        // Do NOT set confirmAccepted from body text — that skipped
                        // 「등록하시겠습니까?」 and caused false mid-flow jumps.
                        if (loginSucceeded && confirmAccepted) {
                            advanceBookingSteps(webView);
                        }
                    }
                    // Legend on court page also contains "예약완료" — do not treat that as success.
                    boolean midFlow = text.contains("코트선택")
                            || text.contains("사용인원")
                            || text.contains("감면대상")
                            || text.contains("등록하시겠습니까")
                            || text.contains("날짜와 시간을 선택");
                    if (!midFlow && (
                            text.contains("예약이 완료")
                                    || text.contains("결제가 완료")
                                    || text.contains("정상적으로 예약")
                                    || text.contains("예약완료되었습니다")
                    )) {
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

    /**
     * Full Anyang integrated login:
     * 1) ALWAYS click pink "통합 로그인 바로가기" first when on AUC gateway
     *    (do not fill the local AUC member form on the same page)
     * 2) Fill ID/password on the real Anyang SSO form
     * 3) Click "로그인"
     */
    private void advanceLogin(WebView view) {
        if (loginFailed || loginSucceeded) {
            return;
        }
        String probe = ""
                + "(function(){"
                + "function visible(el){"
                + " if(!el) return false;"
                + " var r=el.getBoundingClientRect();"
                + " return r.width>0 && r.height>0;"
                + "}"
                + "function isShortcutText(t){"
                + " return t.indexOf('통합로그인바로가기')>=0"
                + "  || (t.indexOf('통합로그인')>=0 && t.indexOf('바로가기')>=0);"
                + "}"
                + "var shortcut=null, href='';"
                + "var nodes=document.querySelectorAll('a,button,input,span,div,p,strong');"
                + "for(var j=0;j<nodes.length;j++){"
                + " var el=nodes[j];"
                + " var t=(el.innerText||el.value||'').replace(/\\s+/g,'').trim();"
                + " if(!isShortcutText(t)) continue;"
                + " var target=el;"
                + " if(el.tagName!=='A' && el.tagName!=='BUTTON'){"
                + "  var a=el.closest?el.closest('a,button'):null; if(a) target=a;"
                + " }"
                + " shortcut=target;"
                + " href=(target.href||target.getAttribute('href')||'').trim();"
                + " break;"
                + "}"
                + "var body=(document.body&&document.body.innerText||'');"
                // Gateway = pink shortcut only (SSO page title "안양시 통합 로그인" is NOT gateway).
                + "var gatewayHint=body.indexOf('통합 로그인 바로가기')>=0"
                + " || body.indexOf('통합로그인바로가기')>=0;"
                + "var pass=null;"
                + "var plist=document.querySelectorAll(\"input[type='password']\");"
                + "for(var i=0;i<plist.length;i++){ if(visible(plist[i])){ pass=plist[i]; break; } }"
                + "return JSON.stringify({"
                + " hasShortcut: !!shortcut,"
                + " href: href,"
                + " gatewayHint: gatewayHint,"
                + " hasPass: !!pass"
                + "});"
                + "})();";

        view.evaluateJavascript(probe, result -> {
            Log.d(TAG, "login probe=" + result);
            String raw = result == null ? "" : result;
            boolean hasShortcut = raw.contains("\"hasShortcut\":true")
                    || raw.contains("\\\"hasShortcut\\\":true");
            boolean gatewayHint = raw.contains("\"gatewayHint\":true")
                    || raw.contains("\\\"gatewayHint\\\":true");
            boolean hasPass = raw.contains("\"hasPass\":true")
                    || raw.contains("\\\"hasPass\\\":true");

            // SSO form ready: fill immediately (do not treat page title as gateway).
            if (hasPass && !hasShortcut) {
                tryLogin(view);
                return;
            }
            // AUC gateway: click pink 「통합 로그인 바로가기」 first.
            if (hasShortcut || gatewayHint) {
                clickIntegratedLoginShortcut(view);
                return;
            }
            status("통합 로그인 화면 대기 중…");
        });
    }

    private void clickIntegratedLoginShortcut(WebView view) {
        long now = System.currentTimeMillis();
        if (now - lastShortcutClickMs < 1500) {
            return;
        }
        lastShortcutClickMs = now;
        status("「통합 로그인 바로가기」 클릭");
        String js = ""
                + "(function(){"
                + "function isShortcutText(t){"
                + " return t.indexOf('통합로그인바로가기')>=0"
                + "  || (t.indexOf('통합로그인')>=0 && t.indexOf('바로가기')>=0);"
                + "}"
                + "var nodes=document.querySelectorAll('a,button,input,span,div,p,strong,em');"
                + "for(var j=0;j<nodes.length;j++){"
                + " var el=nodes[j];"
                + " var t=(el.innerText||el.value||'').replace(/\\s+/g,'').trim();"
                + " if(!isShortcutText(t)) continue;"
                + " var target=el;"
                + " if(el.tagName!=='A' && el.tagName!=='BUTTON'){"
                + "  var a=el.closest?el.closest('a,button'):null; if(a) target=a;"
                + " }"
                + " var href=(target.href||target.getAttribute('href')||'').trim();"
                + " if(href && href.indexOf('javascript:')!==0 && href!=='#'){"
                + "  try{ SelectTimeBridge.openUrl(href); return 'nav:'+href; }catch(e){}"
                + "  try{ location.href=href; return 'loc:'+href; }catch(e){}"
                + " }"
                + " try{ target.scrollIntoView({block:'center'}); target.click(); return 'clicked'; }catch(e){ return 'fail'; }"
                + "}"
                + "var links=document.querySelectorAll('a[href]');"
                + "for(var k=0;k<links.length;k++){"
                + " var h=links[k].href||'';"
                + " var lt=(links[k].innerText||'').replace(/\\s+/g,'').trim();"
                + " if(lt.indexOf('통합')>=0 && (h.indexOf('anyang')>=0||h.indexOf('sso')>=0||h.indexOf('Login')>=0||h.indexOf('login')>=0)){"
                + "  try{ SelectTimeBridge.openUrl(h); return 'nav-fallback:'+h; }catch(e){}"
                + "  try{ location.href=h; return 'loc-fallback:'+h; }catch(e){}"
                + " }"
                + "}"
                + "return 'missing';"
                + "})();";
        view.evaluateJavascript(js, v -> {
            Log.d(TAG, "integrated shortcut=" + v);
            String raw = v == null ? "" : v;
            if (raw.contains("nav") || raw.contains("loc") || raw.contains("clicked")) {
                integratedLoginClicked = true;
                status("통합 로그인 폼으로 이동 중…");
                handler.postDelayed(() -> {
                    if (!loginSucceeded && !loginFailed) {
                        advanceLogin(webView);
                    }
                }, 2800);
            } else {
                integratedLoginClicked = false;
                status("통합 로그인 바로가기 버튼을 찾지 못함 — 재시도");
                handler.postDelayed(() -> {
                    if (!loginSucceeded && !loginFailed) {
                        advanceLogin(webView);
                    }
                }, 2000);
            }
        });
    }

    private void tryLogin(WebView view) {
        String id = store.get(SecureStore.KEY_AUC_ID, "").trim();
        String pw = store.get(SecureStore.KEY_AUC_PASSWORD, "");
        if (id.isEmpty() || pw.isEmpty()) {
            status("설정에서 통합 아이디/비밀번호를 저장하세요");
            return;
        }
        if (loginFailed) {
            return;
        }
        if (loginAttempted) {
            return;
        }
        if (loginTries >= 3) {
            status("로그인 재시도 한도 초과 — 아이디/비밀번호를 확인하세요");
            return;
        }
        loginAttempted = true;
        loginTries++;
        status("아이디/비밀번호 입력 후 로그인… (" + loginTries + "/3)");

        String idJson = JSONObject.quote(id);
        String pwJson = JSONObject.quote(pw);

        String fillJs = ""
                + "(function(){"
                + "var id=" + idJson + ";"
                + "var pw=" + pwJson + ";"
                + "function visible(el){"
                + " if(!el) return false;"
                + " var r=el.getBoundingClientRect();"
                + " return r.width>0 && r.height>0;"
                + "}"
                // Refuse AUC gateway local form — must use integrated SSO page.
                + "var body=(document.body&&document.body.innerText||'');"
                + "if(body.indexOf('통합 로그인 바로가기')>=0 || body.indexOf('통합로그인바로가기')>=0){"
                + " return JSON.stringify({ok:false,reason:'still-gateway'});"
                + "}"
                + "function setVal(el,val){"
                + " if(!el) return false;"
                + " el.focus();"
                + " el.click();"
                + " try{ el.select && el.select(); }catch(e){}"
                + " var desc=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');"
                + " if(desc&&desc.set){ desc.set.call(el,val); } else { el.value=val; }"
                + " try{"
                + "  el.dispatchEvent(new InputEvent('input',{bubbles:true,data:val,inputType:'insertText'}));"
                + " }catch(e){ el.dispatchEvent(new Event('input',{bubbles:true})); }"
                + " el.dispatchEvent(new Event('change',{bubbles:true}));"
                + " el.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true}));"
                + " return true;"
                + "}"
                + "function findUser(){"
                + " var sels=["
                + "  \"input[name='userId']\",\"input[name='user_id']\",\"input[name='loginId']\","
                + "  \"input[name='mberId']\",\"input[name='id']\",\"input#userId\",\"input#loginId\","
                + "  \"input[placeholder*='아이디']\",\"input[placeholder*='ID']\",\"input[placeholder*='id']\""
                + " ];"
                + " for(var i=0;i<sels.length;i++){"
                + "  var el=document.querySelector(sels[i]);"
                + "  if(el&&visible(el)&&el.type!=='password'&&el.type!=='hidden') return el;"
                + " }"
                + " var all=document.querySelectorAll('input');"
                + " for(var k=0;k<all.length;k++){"
                + "  var el3=all[k];"
                + "  if(!visible(el3)||el3.type==='password'||el3.type==='hidden'||el3.type==='submit') continue;"
                + "  var meta=((el3.name||'')+' '+(el3.id||'')+' '+(el3.placeholder||'')).toLowerCase();"
                + "  if(meta.indexOf('search')>=0||meta.indexOf('검색')>=0||meta.indexOf('pass')>=0) continue;"
                + "  return el3;"
                + " }"
                + " return null;"
                + "}"
                + "function findPass(){"
                + " var sels=["
                + "  \"input[type='password']\",\"input[name='password']\",\"input[name='passwd']\","
                + "  \"input[name='userPw']\",\"input[name='pwd']\",\"input[placeholder='Password']\","
                + "  \"input[placeholder*='비밀번호']\",\"input[placeholder*='Password']\""
                + " ];"
                + " for(var i=0;i<sels.length;i++){"
                + "  var list=document.querySelectorAll(sels[i]);"
                + "  for(var j=0;j<list.length;j++){ if(visible(list[j])) return list[j]; }"
                + " }"
                + " return null;"
                + "}"
                + "var user=findUser();"
                + "var pass=findPass();"
                + "if(!user||!pass){return JSON.stringify({ok:false,reason:'no-form'});}"
                + "setVal(user,id);"
                + "setVal(pass,pw);"
                + "pass.scrollIntoView({block:'center'});"
                + "return JSON.stringify({ok:true,uv:(user.value||'').length,pv:(pass.value||'').length});"
                + "})();";

        view.evaluateJavascript(fillJs, v -> {
            Log.d(TAG, "login fill=" + v);
            String raw = v == null ? "" : v;
            if (raw.contains("still-gateway")) {
                loginAttempted = false;
                loginTries = Math.max(0, loginTries - 1);
                clickIntegratedLoginShortcut(webView);
                return;
            }
            if (raw.contains("no-form") || raw.contains("\"ok\":false")) {
                loginAttempted = false;
                status("로그인 폼 없음 — 통합 로그인 바로가기 확인");
                handler.postDelayed(() -> {
                    if (!loginSucceeded && !loginFailed) {
                        advanceLogin(webView);
                    }
                }, 1500);
                return;
            }
            status("아이디/비밀번호 입력됨 → 로그인 클릭");
            handler.postDelayed(() -> clickLoginButton(view), fastOpenPath ? 350 : 600);
        });
    }

    private void clickLoginButton(WebView view) {
        String js = ""
                + "(function(){"
                + "var body=(document.body&&document.body.innerText||'');"
                + "if(body.indexOf('통합 로그인 바로가기')>=0 || body.indexOf('통합로그인바로가기')>=0){"
                + " return 'still-gateway';"
                + "}"
                + "var nodes=document.querySelectorAll('button,input[type=submit],a,input[type=button]');"
                + "for(var n=0;n<nodes.length;n++){"
                + " var t=(nodes[n].innerText||nodes[n].value||'').replace(/\\s+/g,'').trim();"
                + " if(!t) continue;"
                + " if(t.indexOf('통합로그인')>=0 || t.indexOf('바로가기')>=0) continue;"
                + " if(t.indexOf('회원가입')>=0||t.indexOf('아이디찾기')>=0||t.indexOf('비밀번호')>=0) continue;"
                + " if(t==='로그인'||t==='Login'||t==='LOGIN'){"
                + "  try{nodes[n].click(); return 'clicked';}catch(e){return 'click-fail';}"
                + " }"
                + "}"
                + "var pass=document.querySelector(\"input[type='password']\");"
                + "if(pass&&pass.form){"
                + " var sub=pass.form.querySelector(\"button[type=submit],input[type=submit],button\");"
                + " if(sub){ try{sub.click(); return 'form-btn';}catch(e){} }"
                + " try{pass.form.submit(); return 'form-submit';}catch(e){}"
                + "}"
                + "return 'no-button';"
                + "})();";
        view.evaluateJavascript(js, v -> {
            Log.d(TAG, "login click=" + v);
            String raw = v == null ? "" : v;
            if (raw.contains("still-gateway")) {
                loginAttempted = false;
                status("아직 게이트웨이 — 통합 로그인 바로가기 재시도");
                clickIntegratedLoginShortcut(webView);
                return;
            }
            status("로그인 버튼: " + String.valueOf(v));
            // Re-arm quickly if still on login (SSO often needs a second pass).
            handler.postDelayed(() -> {
                if (!loginSucceeded && !loginFailed && loginTries < 3) {
                    loginAttempted = false;
                    advanceLogin(webView);
                }
            }, 2500);
        });
    }

    private void goReservation() {
        if (navigatedToReservation || !loginSucceeded) {
            return;
        }
        navigatedToReservation = true;
        String url = store.get(SecureStore.KEY_RESERVATION_URL, SecureStore.DEFAULT_URL);
        status("예약 페이지로 이동");
        webView.loadUrl(url);
    }

    private void trySelectDateAndSlot(WebView view) {
        if (!navigatedToReservation || !loginSucceeded || slotClicked || confirmAccepted) {
            return;
        }
        // Avoid re-entrancy while a click attempt is in flight; allow retries after failure.
        if (slotSelectionStarted) {
            return;
        }
        if (slotSelectAttempts >= 8) {
            status("날짜·시간 슬롯을 클릭하지 못했습니다 — 화면에서 직접 「가」 시간을 눌러 주세요");
            return;
        }
        slotSelectionStarted = true;
        slotSelectAttempts++;
        status("날짜·시간 그리드에서 「가」 슬롯 클릭… (" + slotSelectAttempts + ")");

        String useDate = store.get(SecureStore.KEY_USE_DATE, "").trim();
        int day;
        if (useDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            day = Integer.parseInt(useDate.substring(8, 10));
        } else {
            int daysAhead = store.getInt(SecureStore.KEY_DAYS_AHEAD, 7);
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, daysAhead);
            day = cal.get(Calendar.DAY_OF_MONTH);
        }

        String times = store.get(SecureStore.KEY_PREFERRED_TIMES, "09:00,09:00~10:30");
        String courts = store.get(SecureStore.KEY_PREFERRED_COURTS, "");
        boolean autoCourt = store.getBool(SecureStore.KEY_AUTO_COURT, true);
        String[] preferred = times.split(",");

        StringBuilder timeArray = new StringBuilder("[");
        for (int i = 0; i < preferred.length; i++) {
            String p = preferred[i].trim();
            if (p.isEmpty()) {
                continue;
            }
            if (timeArray.length() > 1) {
                timeArray.append(',');
            }
            timeArray.append(JSONObject.quote(p));
            // Also match start HH:MM if a range was given
            if (p.matches("\\d{1,2}:\\d{2}~\\d{1,2}:\\d{2}")) {
                timeArray.append(',').append(JSONObject.quote(p.substring(0, p.indexOf('~'))));
            }
        }
        timeArray.append(']');
        String courtsJson = JSONObject.quote(courts);

        // Weekly grid: must find day column first — never click document.body (false positives).
        String js = String.format(Locale.US, ""
                        + "(function(day, times, courtsCsv, autoAny){"
                        + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                        + "function txt(el){ return ((el&&(el.innerText||el.textContent))||'').replace(/\\s+/g,' ').trim(); }"
                        + "function clickEl(el){"
                        + " if(!el) return false;"
                        + " try{ el.scrollIntoView({block:'center',inline:'center'}); }catch(e){}"
                        + " try{"
                        + "  var r=el.getBoundingClientRect();"
                        + "  var x=r.left+r.width/2, y=r.top+r.height/2;"
                        + "  var opts={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y};"
                        + "  el.dispatchEvent(new MouseEvent('mousedown',opts));"
                        + "  el.dispatchEvent(new MouseEvent('mouseup',opts));"
                        + "  el.dispatchEvent(new MouseEvent('click',opts));"
                        + " }catch(e){}"
                        + " var t=el;"
                        + " for(var up=0;up<5&&t;up++){"
                        + "  try{ t.click(); return true; }catch(e){}"
                        + "  t=t.parentElement;"
                        + " }"
                        + " return false;"
                        + "}"
                        + "function clickWeekNav(dir){"
                        + " var nodes=document.querySelectorAll('a,button,span,i,em,div,img');"
                        + " for(var i=0;i<nodes.length;i++){"
                        + "  if(!visible(nodes[i])) continue;"
                        + "  var t=txt(nodes[i]);"
                        + "  var cls=((nodes[i].className||'')+'').toLowerCase();"
                        + "  var aria=(nodes[i].getAttribute&& (nodes[i].getAttribute('aria-label')||''))||'';"
                        + "  var hit=false;"
                        + "  if(dir==='next'){"
                        + "   hit=t==='다음'||t==='>'||t==='›'||t==='»'||t.indexOf('다음주')>=0"
                        + "    || cls.indexOf('next')>=0 || aria.indexOf('다음')>=0;"
                        + "  } else {"
                        + "   hit=t==='이전'||t==='<'||t==='‹'||t==='«'||t.indexOf('이전주')>=0"
                        + "    || cls.indexOf('prev')>=0 || aria.indexOf('이전')>=0;"
                        + "  }"
                        + "  if(!hit) continue;"
                        + "  if(t.length>12 && cls.indexOf('next')<0 && cls.indexOf('prev')<0) continue;"
                        + "  if(clickEl(nodes[i])) return true;"
                        + " }"
                        + " return false;"
                        + "}"
                        + "var d=String(day);"
                        + "var roots=[];"
                        + "var cols=document.querySelectorAll('td,th,li,div');"
                        + "for(var i=0;i<cols.length;i++){"
                        + " var el=cols[i]; if(!visible(el)) continue;"
                        + " var t=txt(el); if(!t || t.length<4 || t.length>420) continue;"
                        + " var head=el.children&&el.children.length?txt(el.children[0]):'';"
                        + " var dayHead=(head===d || head.indexOf(d+'(')===0 || t===d || t.indexOf(d+' ')===0 || t.indexOf(d+'(')===0);"
                        + " if(!dayHead) continue;"
                        + " if(!/\\d{1,2}:\\d{2}/.test(t)) continue;"
                        + " roots.push(el);"
                        + "}"
                        + "roots.sort(function(a,b){ return txt(a).length-txt(b).length; });"
                        + "if(!roots.length){"
                        + " var dayEls=document.querySelectorAll('td,th,div,span,strong,a,p');"
                        + " for(var di=0;di<dayEls.length;di++){"
                        + "  var de=dayEls[di]; if(!visible(de)) continue;"
                        + "  if(txt(de)!==d) continue;"
                        + "  var p=de.parentElement;"
                        + "  for(var up=0;up<5&&p;up++){"
                        + "   var pt=txt(p);"
                        + "   if(pt.length>4 && pt.length<420 && /\\d{1,2}:\\d{2}/.test(pt)){ roots.push(p); break; }"
                        + "   p=p.parentElement;"
                        + "  }"
                        + " }"
                        + " roots.sort(function(a,b){ return txt(a).length-txt(b).length; });"
                        + "}"
                        // Target day not on this week → navigate, do NOT click random body slots.
                        + "if(!roots.length){"
                        + " if(clickWeekNav('next')) return 'week-nav:next';"
                        + " if(clickWeekNav('prev')) return 'week-nav:prev';"
                        + " SelectTimeBridge.onSlotClicked('none');"
                        + " return 'none;no-day-col';"
                        + "}"
                        + "var scopes=[roots[0]];"
                        + "var best=null, bestScore=-1, bestWant='';"
                        + "for(var si=0;si<scopes.length;si++){"
                        + " var nodes=scopes[si].querySelectorAll('a,button,li,td,span,div,label,p');"
                        + " for(var j=0;j<nodes.length;j++){"
                        + "  var el=nodes[j]; if(!visible(el)) continue;"
                        + "  var tx=txt(el); if(!tx || tx.length>56) continue;"
                        + "  var cls=((el.className||'')+' '+((el.parentElement&&el.parentElement.className)||'')).toString();"
                        + "  var busy=(tx.indexOf('불')>=0 && tx.indexOf('가')<0) || /unable|disable|full|close|ico_?bul|state_?n/i.test(cls);"
                        + "  var avail=tx.indexOf('가')>=0 || /able|avail|possible|open|ico_?ga|state_?y/i.test(cls);"
                        + "  if(busy && !avail) continue;"
                        + "  if(tx.indexOf('불가')>=0||tx.indexOf('마감')>=0) continue;"
                        + "  var wantHit='';"
                        + "  for(var ti=0;ti<times.length;ti++){"
                        + "   if(times[ti] && tx.indexOf(times[ti])>=0){ wantHit=times[ti]; break; }"
                        + "  }"
                        + "  var timeLike=/\\d{1,2}:\\d{2}/.test(tx);"
                        + "  if(!wantHit && !(autoAny && timeLike && avail)) continue;"
                        + "  if(!timeLike) continue;"
                        + "  if(!avail && wantHit) continue;" // prefer real 「가」 cells only
                        + "  var score=0;"
                        + "  if(wantHit) score+=30;"
                        + "  if(avail) score+=20;"
                        + "  if(el.tagName==='A'||el.tagName==='BUTTON') score+=4;"
                        + "  if(tx.length<=24) score+=3;"
                        + "  if(score>bestScore){ bestScore=score; best=el; bestWant=wantHit||tx; }"
                        + " }"
                        + " if(best) break;"
                        + "}"
                        + "if(best && clickEl(best)){"
                        + " SelectTimeBridge.onSlotClicked(bestWant);"
                        + " return 'clicked:'+bestWant+';score='+bestScore;"
                        + "}"
                        + "SelectTimeBridge.onSlotClicked('none');"
                        + "return 'none;roots='+roots.length;"
                        + "})(%d, %s, %s, %s);",
                day, timeArray, courtsJson, autoCourt ? "true" : "false");

        // Delay so the weekly calendar DOM is fully painted.
        handler.postDelayed(() -> {
            if (bookingFlowDone || slotClicked) {
                slotSelectionStarted = false;
                return;
            }
            view.evaluateJavascript(js, v -> {
                Log.d(TAG, "slot=" + v);
                String raw = v == null ? "" : v;
                if (raw.contains("week-nav")) {
                    status("이용일이 다른 주 — 달력 이동 후 재시도");
                    slotSelectionStarted = false;
                    // Don't burn attempt count for week navigation.
                    if (slotSelectAttempts > 0) {
                        slotSelectAttempts--;
                    }
                    handler.postDelayed(() -> {
                        if (!bookingFlowDone && !slotClicked) {
                            trySelectDateAndSlot(webView);
                        }
                    }, fastOpenPath ? 700 : 1400);
                }
            });
        }, slotSelectAttempts == 1
                ? (fastOpenPath ? 250 : 1000)
                : (fastOpenPath ? 120 : 700));
    }

    /**
     * Handles the AUC modal: "등록하시겠습니까?" → click blue "예".
     * Must run before payment automation.
     */
    private void clickRegisterConfirmIfPresent(WebView view) {
        if (confirmAccepted || bookingFlowDone || !slotClicked) {
            return;
        }
        if (confirmPollTries > 6) {
            status("확인 팝업 없음 — 슬롯 다시 선택");
            confirmPollTries = 0;
            slotClicked = false;
            slotSelectionStarted = false;
            handler.postDelayed(() -> trySelectDateAndSlot(webView), 800);
            return;
        }
        confirmPollTries++;

        String js = ""
                + "(function(){"
                + "var body=(document.body&&document.body.innerText)||'';"
                + "var hasDialog=body.indexOf('등록하시겠습니까')>=0 || body.indexOf('선택하신 날짜')>=0;"
                + "if(!hasDialog){ return JSON.stringify({ok:false,reason:'no-dialog'}); }"
                + "var nodes=document.querySelectorAll('button,a,input,span,div');"
                + "var labels=['예','확인','등록'];"
                + "for(var li=0;li<labels.length;li++){"
                + " var want=labels[li];"
                + " for(var i=0;i<nodes.length;i++){"
                + "  var t=(nodes[i].innerText||nodes[i].value||'').replace(/\\s+/g,'').trim();"
                + "  if(t===want){"
                + "   try{ nodes[i].click(); return JSON.stringify({ok:true,action:t}); }catch(e){}"
                + "  }"
                + " }"
                + "}"
                + "return JSON.stringify({ok:false,reason:'no-yes'});"
                + "})();";

        view.evaluateJavascript(js, v -> {
            Log.d(TAG, "confirm dialog=" + v);
            String raw = v == null ? "" : v;
            if (raw.contains("\"ok\":true") || raw.contains("\\\"ok\\\":true")) {
                confirmAccepted = true;
                status("등록 확인(예) 클릭 — 코트/인원 단계");
                handler.postDelayed(() -> advanceBookingSteps(webView), 1500);
            } else {
                if (confirmPollTries <= 2 || confirmPollTries % 2 == 0) {
                    status("등록 확인 팝업 대기 중… (" + confirmPollTries + ")");
                }
                // False click: still on calendar with no modal → retry slot sooner.
                if (confirmPollTries >= 3 && raw.contains("no-dialog")) {
                    status("슬롯 클릭 미반영 — 재선택");
                    confirmPollTries = 0;
                    slotClicked = false;
                    slotSelectionStarted = false;
                    handler.postDelayed(() -> trySelectDateAndSlot(webView), 700);
                    return;
                }
                handler.postDelayed(() -> {
                    if (!confirmAccepted && !bookingFlowDone && slotClicked) {
                        clickRegisterConfirmIfPresent(webView);
                    }
                }, 800);
            }
        });
    }

    /**
     * After time confirm: (공지)예약하기 → 코트선택 → 사용인원/감면 → 결제.
     */
    private void advanceBookingSteps(WebView view) {
        if (bookingFlowDone) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBookingStepMs < (fastOpenPath ? 400 : 700)) {
            return;
        }
        lastBookingStepMs = now;

        String probe = ""
                + "(function(){"
                + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                + "function norm(s){ return String(s||'').replace(/\\s+/g,'').trim(); }"
                + "var body=(document.body&&document.body.innerText)||'';"
                + "var hasReserveBtn=false, hasPayBtn=false, hasYes=false;"
                + "var nodes=document.querySelectorAll('button,a,input[type=button],input[type=submit],span,div');"
                + "for(var i=0;i<nodes.length;i++){"
                + " if(!visible(nodes[i])) continue;"
                + " var t=norm(nodes[i].innerText||nodes[i].value);"
                + " if(t==='예약하기') hasReserveBtn=true;"
                + " if(t==='결제하기') hasPayBtn=true;"
                + " if(t==='예') hasYes=true;"
                + "}"
                + "return JSON.stringify({"
                + " reserveBtn: hasReserveBtn,"
                + " payBtn: hasPayBtn,"
                + " yesBtn: hasYes,"
                + " payConfirm: body.indexOf('결제하시겠습니까')>=0 && hasYes,"
                + " refundModal: (body.indexOf('환불 불가에 동의')>=0 || (body.indexOf('동의하십니까')>=0 && body.indexOf('당일 취소')>=0)) && hasYes,"
                + " easyPay: body.indexOf('카드사선택')>=0 || body.indexOf('주문자 약관')>=0 || body.indexOf('Easy PAY')>=0,"
                + " terms: (body.indexOf('약관동의')>=0 || body.indexOf('전체동의')>=0) && body.indexOf('주문자')<0,"
                + " cancelFee: body.indexOf('취소수수료')>=0,"
                + " court: body.indexOf('코트선택')>=0,"
                + " party: body.indexOf('사용인원')>=0,"
                + " discount: body.indexOf('감면대상')>=0,"
                + " pay: body.indexOf('카드번호')>=0||body.indexOf('결제수단')>=0||body.indexOf('신용카드')>=0,"
                + " modal: body.indexOf('경로우대')>=0||body.indexOf('다자녀')>=0"
                + "});"
                + "})();";
        view.evaluateJavascript(probe, v -> {
            String raw = v == null ? "" : v;
            Log.d(TAG, "booking step probe=" + raw);
            boolean reserveBtn = raw.contains("\"reserveBtn\":true")
                    || raw.contains("\\\"reserveBtn\\\":true");
            boolean payBtn = raw.contains("\"payBtn\":true")
                    || raw.contains("\\\"payBtn\\\":true");
            boolean payConfirm = raw.contains("\"payConfirm\":true")
                    || raw.contains("\\\"payConfirm\\\":true");
            boolean refundModal = raw.contains("\"refundModal\":true")
                    || raw.contains("\\\"refundModal\\\":true");
            boolean easyPay = raw.contains("\"easyPay\":true")
                    || raw.contains("\\\"easyPay\\\":true");
            boolean terms = raw.contains("\"terms\":true")
                    || raw.contains("\\\"terms\\\":true");
            boolean cancelFee = raw.contains("\"cancelFee\":true")
                    || raw.contains("\\\"cancelFee\\\":true");
            boolean court = raw.contains("\"court\":true") || raw.contains("\\\"court\\\":true");
            boolean party = raw.contains("\"party\":true") || raw.contains("\\\"party\\\":true");
            boolean discount = raw.contains("\"discount\":true") || raw.contains("\\\"discount\\\":true");
            boolean pay = raw.contains("\"pay\":true") || raw.contains("\\\"pay\\\":true");
            boolean modal = raw.contains("\"modal\":true") || raw.contains("\\\"modal\\\":true");

            // "결제하시겠습니까?" → 「예」 (before terms retry).
            if (payConfirm) {
                partyDone = true;
                courtsDone = true;
                clickPayConfirmYes(view);
                return;
            }
            // Same-day cancel / refund agree modal → always click 「예」 first.
            if (refundModal) {
                clickRefundAgreeYes(view);
                return;
            }
            // EasyPAY: 주문자 약관 + 카드사선택
            if (easyPay) {
                partyDone = true;
                courtsDone = true;
                if (!easyPayGateDone) {
                    easyPayGateDone = true;
                    status("EasyPAY — 약관동의·카드사 선택");
                    PaymentCardAutomator.runEasyPayGate(view, store, handler, () -> {
                        paymentTriggered = false;
                        handler.postDelayed(() -> maybePay(webView), 1200);
                    });
                } else {
                    maybePay(view);
                }
                return;
            }
            // Terms + 결제하기 (after party or when already discounted).
            if (terms || cancelFee || payBtn) {
                partyDone = true;
                courtsDone = true;
                agreeTermsAndPay(view);
                return;
            }
            if (reserveBtn && !court && !party) {
                clickReserveButton(view);
                return;
            }
            if (court && !courtsDone) {
                selectAvailableCourts(view);
                return;
            }
            if ((party || discount || modal) && !partyDone) {
                applyPartyAndDiscounts(view);
                return;
            }
            if (reserveBtn && partyDone) {
                clickReserveButton(view);
                return;
            }
            if (party || discount) {
                return;
            }
            if (pay || (courtsDone && partyDone)) {
                maybePay(view);
            } else if (reserveBtn) {
                clickReserveButton(view);
            }
        });
    }

    /** Modal: 사용일 당일 취소시 환불 불가에 동의 → 「예」 (not the 환불규정 body text). */
    private void clickRefundAgreeYes(WebView view) {
        status("환불불가 동의 — 「예」 클릭");
        String js = ""
                + "(function(){"
                + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                + "function norm(s){ return String(s||'').replace(/\\s+/g,'').trim(); }"
                + "var body=(document.body&&document.body.innerText)||'';"
                + "var q=body.indexOf('환불 불가에 동의')>=0"
                + " || (body.indexOf('동의하십니까')>=0 && body.indexOf('당일 취소')>=0);"
                + "if(!q) return 'no-modal';"
                + "var yesEl=null, noEl=null;"
                + "var nodes=document.querySelectorAll('button,a,input,span,div');"
                + "for(var i=0;i<nodes.length;i++){"
                + " if(!visible(nodes[i])) continue;"
                + " var t=norm(nodes[i].innerText||nodes[i].value);"
                + " if(t==='예' && !yesEl) yesEl=nodes[i];"
                + " if(t==='아니오' && !noEl) noEl=nodes[i];"
                + "}"
                + "if(yesEl && noEl){ try{ yesEl.click(); return 'clicked:예'; }catch(e){} }"
                + "return 'missing';"
                + "})();";
        view.evaluateJavascript(js, v -> {
            Log.d(TAG, "refund yes=" + v);
            handler.postDelayed(() -> {
                lastBookingStepMs = 0;
                advanceBookingSteps(webView);
            }, 1200);
        });
    }

    /**
     * 약관 전체동의(+커스텀 원형 체크) 후 「결제하기」. 동의와 결제는 딜레이로 분리.
     */
    private void agreeTermsAndPay(WebView view) {
        status("약관동의·결제하기 진행");
        String js = ""
                + "(function(){"
                + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                + "function norm(s){ return String(s||'').replace(/\\s+/g,'').trim(); }"
                + "function tap(el){"
                + " if(!el) return false;"
                + " try{ el.scrollIntoView({block:'center'}); }catch(e){}"
                + " try{"
                + "  var r=el.getBoundingClientRect();"
                + "  var x=r.left+r.width/2, y=r.top+r.height/2;"
                + "  var opts={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y};"
                + "  el.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true}));"
                + "  el.dispatchEvent(new MouseEvent('mousedown',opts));"
                + "  el.dispatchEvent(new MouseEvent('mouseup',opts));"
                + "  el.dispatchEvent(new MouseEvent('click',opts));"
                + " }catch(e){}"
                + " try{ el.click(); return true; }catch(e){}"
                + " return false;"
                + "}"
                + "function findByText(want, exact){"
                + " var nodes=document.querySelectorAll('button,a,input,label,span,div,p,li,td,strong,em,i,h1,h2,h3,h4');"
                + " var hits=[];"
                + " for(var i=0;i<nodes.length;i++){"
                + "  if(!visible(nodes[i])) continue;"
                + "  var t=norm(nodes[i].innerText||nodes[i].value||nodes[i].textContent);"
                + "  if(!t) continue;"
                + "  if(exact ? t===want : t.indexOf(want)>=0){"
                + "   if(t.length>80) continue;"
                + "   hits.push(nodes[i]);"
                + "  }"
                + " }"
                + " hits.sort(function(a,b){ return norm(a.innerText||a.textContent).length-norm(b.innerText||b.textContent).length; });"
                + " return hits;"
                + "}"
                + "function clickAgreeRow(keyword){"
                + " var hits=findByText(keyword, false);"
                + " for(var i=0;i<hits.length;i++){"
                + "  var el=hits[i];"
                + "  var row=el;"
                + "  for(var u=0;u<6&&row;u++){"
                + "   var inp=row.querySelector&&row.querySelector('input[type=checkbox],input[type=radio]');"
                + "   if(inp&&visible(inp)){ tap(inp); tap(row); return true; }"
                + "   var kids=row.children||[];"
                + "   for(var c=0;c<kids.length;c++){"
                + "    var kid=kids[c];"
                + "    if(!visible(kid)) continue;"
                + "    var kt=norm(kid.innerText||kid.textContent);"
                + "    var cls=((kid.className||'')+'').toLowerCase();"
                + "    var looksBox=kt.length<=2 || cls.indexOf('check')>=0 || cls.indexOf('agree')>=0"
                + "     || cls.indexOf('round')>=0 || cls.indexOf('circle')>=0 || kid.tagName==='I' || kid.tagName==='IMG';"
                + "    if(looksBox) tap(kid);"
                + "   }"
                + "   tap(row);"
                + "   row=row.parentElement;"
                + "  }"
                + "  if(tap(el)) return true;"
                + " }"
                + " return false;"
                + "}"
                + "var log=[];"
                + "var body=(document.body&&document.body.innerText)||'';"
                + "var refundQ=body.indexOf('환불 불가에 동의')>=0"
                + " || (body.indexOf('동의하십니까')>=0 && body.indexOf('당일 취소')>=0);"
                + "var yesHits=findByText('예', true);"
                + "var noHits=findByText('아니오', true);"
                + "if(refundQ && yesHits.length && noHits.length){"
                + " tap(yesHits[0]);"
                + " return JSON.stringify({phase:'refund-yes',log:['modal-yes']});"
                + "}"
                // Agree only — pay is clicked after a short delay so UI can enable the button.
                + "var allOk=clickAgreeRow('전체동의');"
                + "if(!allOk) allOk=findByText('전체동의', true).length && tap(findByText('전체동의', true)[0]);"
                + "log.push('all:'+!!allOk);"
                + "var boxes=document.querySelectorAll('input[type=checkbox],input[type=radio]');"
                + "var nCheck=0;"
                + "for(var b=0;b<boxes.length;b++){"
                + " if(!visible(boxes[b])||boxes[b].disabled) continue;"
                + " if(!boxes[b].checked){"
                + "  tap(boxes[b]);"
                + "  try{ boxes[b].checked=true;"
                + "   boxes[b].dispatchEvent(new Event('input',{bubbles:true}));"
                + "   boxes[b].dispatchEvent(new Event('change',{bubbles:true}));"
                + "  }catch(e){}"
                + " }"
                + " if(boxes[b].checked) nCheck++;"
                + "}"
                + "log.push('boxes:'+nCheck);"
                + "clickAgreeRow('취소수수료');"
                + "clickAgreeRow('개인정보');"
                + "clickAgreeRow('필수');"
                + "return JSON.stringify({phase:'agree',log:log,all:!!allOk,boxes:nCheck});"
                + "})();";
        view.evaluateJavascript(js, v -> {
            Log.d(TAG, "agree/pay=" + v);
            String raw = v == null ? "" : v;
            if (raw.contains("refund-yes")) {
                status("환불불가 동의(예) 완료");
                handler.postDelayed(() -> {
                    lastBookingStepMs = 0;
                    agreeTermsAndPay(webView);
                }, 1000);
                return;
            }
            status("약관 체크 완료 — 결제하기…");
            handler.postDelayed(() -> {
                if (!bookingFlowDone) {
                    lastBookingStepMs = 0;
                    clickPayButtonOnly(webView);
                }
            }, 900);
        });
    }

    /** Second pass: only click 「결제하기」 after agreements. */
    private void clickPayButtonOnly(WebView view) {
        String js = ""
                + "(function(){"
                + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                + "function norm(s){ return String(s||'').replace(/\\s+/g,'').trim(); }"
                + "var body=(document.body&&document.body.innerText)||'';"
                // Already on confirm popup — click 「예」 instead of 결제하기 again.
                + "if(body.indexOf('결제하시겠습니까')>=0){"
                + " var ns=document.querySelectorAll('button,a,input,span,div');"
                + " for(var i=0;i<ns.length;i++){"
                + "  if(!visible(ns[i])) continue;"
                + "  var t=norm(ns[i].innerText||ns[i].value);"
                + "  if(t==='예'){ try{ ns[i].click(); return 'confirm-yes'; }catch(e){} }"
                + " }"
                + " return 'confirm-pending';"
                + "}"
                + "var nodes=document.querySelectorAll('button,a,input,div,span');"
                + "for(var i=0;i<nodes.length;i++){"
                + " if(!visible(nodes[i])) continue;"
                + " var t=norm(nodes[i].innerText||nodes[i].value);"
                + " if(t==='결제하기'){"
                + "  try{ nodes[i].scrollIntoView({block:'center'}); nodes[i].click(); return 'clicked'; }catch(e){}"
                + "  try{ nodes[i].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window})); return 'dispatched'; }catch(e){}"
                + " }"
                + "}"
                + "return 'missing';"
                + "})();";
        view.evaluateJavascript(js, v -> {
            Log.d(TAG, "pay-only=" + v);
            String raw = v == null ? "" : v;
            if (raw.contains("confirm-yes")) {
                status("결제 확인(예) — 카드 입력");
                partyDone = true;
                paymentTriggered = false;
                handler.postDelayed(() -> maybePay(webView), 1800);
                return;
            }
            if (raw.contains("clicked") || raw.contains("dispatched") || raw.contains("confirm-pending")) {
                status("결제하기 클릭 — 확인 팝업 대기");
                partyDone = true;
                handler.postDelayed(() -> {
                    if (!bookingFlowDone) {
                        clickPayConfirmYes(webView);
                    }
                }, 800);
                return;
            }
            handler.postDelayed(() -> {
                if (!bookingFlowDone) {
                    agreeTermsAndPay(webView);
                }
            }, 1500);
        });
    }

    /**
     * After 「결제하기」: modal "결제하시겠습니까?" → click blue 「예」.
     */
    private void clickPayConfirmYes(WebView view) {
        if (payConfirmTries > 12) {
            status("결제 확인 팝업 대기 초과 — 카드 단계 시도");
            payConfirmTries = 0;
            paymentTriggered = false;
            handler.postDelayed(() -> maybePay(webView), 1000);
            return;
        }
        payConfirmTries++;
        status("결제 확인 팝업 — 「예」 클릭");
        String js = ""
                + "(function(){"
                + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                + "function norm(s){ return String(s||'').replace(/\\s+/g,'').trim(); }"
                + "var body=(document.body&&document.body.innerText)||'';"
                + "if(body.indexOf('결제하시겠습니까')<0) return 'no-dialog';"
                + "var nodes=document.querySelectorAll('button,a,input,span,div');"
                + "for(var i=0;i<nodes.length;i++){"
                + " if(!visible(nodes[i])) continue;"
                + " var t=norm(nodes[i].innerText||nodes[i].value);"
                + " if(t==='예'){"
                + "  try{ nodes[i].scrollIntoView({block:'center'}); nodes[i].click(); return 'clicked'; }catch(e){}"
                + "  try{"
                + "   nodes[i].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));"
                + "   return 'dispatched';"
                + "  }catch(e){}"
                + " }"
                + "}"
                + "return 'missing';"
                + "})();";
        view.evaluateJavascript(js, v -> {
            Log.d(TAG, "pay confirm yes=" + v);
            String raw = v == null ? "" : v;
            if (raw.contains("clicked") || raw.contains("dispatched")) {
                payConfirmTries = 0;
                status("결제 확인(예) — 카드 입력");
                partyDone = true;
                paymentTriggered = false;
                handler.postDelayed(() -> maybePay(webView), 1800);
                return;
            }
            if (raw.contains("no-dialog")) {
                payConfirmTries = 0;
                paymentTriggered = false;
                handler.postDelayed(() -> maybePay(webView), 1200);
                return;
            }
            handler.postDelayed(() -> {
                if (!bookingFlowDone) {
                    clickPayConfirmYes(webView);
                }
            }, 900);
        });
    }

    /** Click AUC blue 「예약하기」 (notice / proceed gate). */
    private void clickReserveButton(WebView view) {
        status("「예약하기」 클릭");
        String js = ""
                + "(function(){"
                + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                + "function norm(s){ return String(s||'').replace(/\\s+/g,'').trim(); }"
                + "var nodes=document.querySelectorAll('button,a,input[type=button],input[type=submit],span,div,p');"
                + "for(var i=0;i<nodes.length;i++){"
                + " if(!visible(nodes[i])) continue;"
                + " var t=norm(nodes[i].innerText||nodes[i].value);"
                + " if(t!=='예약하기') continue;"
                + " try{"
                + "  nodes[i].scrollIntoView({block:'center'});"
                + "  nodes[i].click();"
                + "  return 'clicked';"
                + " }catch(e){ return 'fail'; }"
                + "}"
                + "return 'missing';"
                + "})();";
        view.evaluateJavascript(js, v -> {
            Log.d(TAG, "reserve click=" + v);
            String raw = v == null ? "" : v;
            if (raw.contains("clicked")) {
                reserveGateDone = true;
                partyTries = 0;
                status("예약하기 진행 — 다음 단계 대기");
                handler.postDelayed(() -> {
                    lastBookingStepMs = 0;
                    advanceBookingSteps(webView);
                }, 1800);
            } else {
                status("예약하기 버튼을 찾는 중…");
                handler.postDelayed(() -> {
                    if (!bookingFlowDone) {
                        lastBookingStepMs = 0;
                        advanceBookingSteps(webView);
                    }
                }, 1500);
            }
        });
    }
    /** Check white/available court checkboxes: preferred first, else any available (max 2). */
    private void selectAvailableCourts(WebView view) {
        String courts = store.get(SecureStore.KEY_PREFERRED_COURTS, "");
        boolean autoCourt = store.getBool(SecureStore.KEY_AUTO_COURT, true);
        String courtsJson = JSONObject.quote(courts);

        String js = String.format(Locale.US, ""
                        + "(function(courtsCsv, autoAny){"
                        + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                        + "function txt(el){ return ((el&&(el.innerText||el.textContent))||'').replace(/\\s+/g,' ').trim(); }"
                        + "function nums(s){ var m=String(s).match(/\\d+/g); return m?m:[]; }"
                        + "var prefer=[];"
                        + "if(courtsCsv){ courtsCsv.split(',').forEach(function(p){ nums(p.trim()).forEach(function(n){ prefer.push(n); }); }); }"
                        + "var boxes=document.querySelectorAll('input[type=checkbox]');"
                        + "var avail=[];"
                        + "for(var i=0;i<boxes.length;i++){"
                        + " var cb=boxes[i];"
                        + " if(!visible(cb)||cb.disabled||cb.readOnly) continue;"
                        + " var lab='';"
                        + " if(cb.id){ var l=document.querySelector('label[for=\"'+cb.id+'\"]'); if(l) lab=txt(l); }"
                        + " if(!lab){ var p=cb.parentElement; for(var u=0;u<4&&p;u++){ lab=txt(p); if(lab.length>0&&lab.length<40) break; p=p.parentElement; } }"
                        + " if(lab.indexOf('번')<0 && lab.indexOf('코트')<0 && !/\\d/.test(lab)) continue;"
                        + " var ns=nums(lab); var n=ns.length?ns[0]:'';"
                        + " avail.push({cb:cb,n:n,lab:lab});"
                        + "}"
                        + "function checkOne(item){"
                        + " if(item.cb.checked) return true;"
                        + " try{ item.cb.click(); }catch(e){}"
                        + " if(!item.cb.checked){ try{ item.cb.checked=true; item.cb.dispatchEvent(new Event('change',{bubbles:true})); }catch(e){} }"
                        + " return !!item.cb.checked;"
                        + "}"
                        + "var selected=[];"
                        + "var max=2;"
                        + "for(var pi=0;pi<prefer.length && selected.length<max;pi++){"
                        + " for(var ai=0;ai<avail.length;ai++){"
                        + "  if(avail[ai].n===prefer[pi] && selected.indexOf(avail[ai].n)<0){"
                        + "   if(checkOne(avail[ai])) selected.push(avail[ai].n);"
                        + "   break;"
                        + "  }"
                        + " }"
                        + "}"
                        + "if(selected.length===0 && autoAny){"
                        + " for(var bi=0;bi<avail.length && selected.length<max;bi++){"
                        + "  if(checkOne(avail[bi])) selected.push(avail[bi].n||avail[bi].lab);"
                        + " }"
                        + "}"
                        + "function clickNext(){"
                        + " var nodes=document.querySelectorAll('button,a,input[type=button],input[type=submit]');"
                        + " var labels=['저장','다음','확인','신청','예약'];"
                        + " for(var li=0;li<labels.length;li++){"
                        + "  for(var ni=0;ni<nodes.length;ni++){"
                        + "   var t=(nodes[ni].innerText||nodes[ni].value||'').replace(/\\s+/g,'').trim();"
                        + "   if(t===labels[li]||t.indexOf(labels[li])===0){"
                        + "    try{ nodes[ni].scrollIntoView({block:'center'}); nodes[ni].click(); return labels[li]; }catch(e){}"
                        + "   }"
                        + "  }"
                        + " }"
                        + " return 'none';"
                        + "}"
                        + "var next='none';"
                        + "if(selected.length){ next=clickNext(); }"
                        + "return JSON.stringify({ok:selected.length>0,selected:selected,next:next,avail:avail.length});"
                        + "})(%s, %s);",
                courtsJson, autoCourt ? "true" : "false");

        status("예약가능 코트(체크박스) 선택 중…");
        view.evaluateJavascript(js, v -> {
            Log.d(TAG, "courts=" + v);
            String raw = v == null ? "" : v;
            if (raw.contains("\"ok\":true") || raw.contains("\\\"ok\\\":true")) {
                courtsDone = true;
                status("코트 선택됨 — 인원/감면 단계 대기");
                handler.postDelayed(() -> advanceBookingSteps(webView), 1800);
            } else {
                status("선택 가능한 코트를 찾지 못함 — 재시도");
                handler.postDelayed(() -> {
                    if (!courtsDone && !bookingFlowDone) {
                        lastBookingStepMs = 0;
                        advanceBookingSteps(webView);
                    }
                }, 2000);
            }
        });
    }

    /** 사용인원 + 감면(경로우대/다자녀) 적용 후 다음 단계. */
    private void applyPartyAndDiscounts(WebView view) {
        if (partyDone || bookingFlowDone) {
            return;
        }
        int party = store.getInt(SecureStore.KEY_PARTY_SIZE, SecureStore.DEFAULT_PARTY_SIZE);
        int senior = store.getInt(SecureStore.KEY_DISCOUNT_SENIOR, SecureStore.DEFAULT_DISCOUNT_SENIOR);
        int multi = store.getInt(SecureStore.KEY_DISCOUNT_MULTI_CHILD, SecureStore.DEFAULT_DISCOUNT_MULTI_CHILD);
        if (party < 2) {
            party = SecureStore.DEFAULT_PARTY_SIZE;
        }
        partyTries++;
        if (partyTries > 25) {
            status("감면 단계 초과 — 약관/결제 단계로 진행");
            partyDone = true;
            partyTries = 0;
            lastBookingStepMs = 0;
            advanceBookingSteps(view);
            return;
        }

        // Phases: 0 set party, 1 open modal, 2 set discounts, 3 click 적용, 4 next/save
        String js = String.format(Locale.US, ""
                        + "(function(partyTarget, seniorTarget, multiTarget, phase){"
                        + "function visible(el){ if(!el) return false; var r=el.getBoundingClientRect(); return r.width>0&&r.height>0; }"
                        + "function norm(s){ return String(s||'').replace(/\\s+/g,'').trim(); }"
                        + "function txt(el){ return norm(el&&(el.innerText||el.textContent||el.value)); }"
                        + "function clickExact(want, allowBtnClass){"
                        + " var sels='button,a,input[type=button],input[type=submit]';"
                        + " if(allowBtnClass) sels+=',.btn,span,div,p,td,li';"
                        + " var nodes=document.querySelectorAll(sels);"
                        + " for(var i=0;i<nodes.length;i++){"
                        + "  if(!visible(nodes[i])) continue;"
                        + "  var t=txt(nodes[i]);"
                        + "  if(t!==want) continue;"
                        + "  if(allowBtnClass && nodes[i].tagName!=='BUTTON' && nodes[i].tagName!=='A' && nodes[i].tagName!=='INPUT'){"
                        + "   var cls=((nodes[i].className||'')+'').toLowerCase();"
                        + "   var role=nodes[i].getAttribute&&nodes[i].getAttribute('role');"
                        + "   if(cls.indexOf('btn')<0 && role!=='button' && !nodes[i].onclick) continue;"
                        + "  }"
                        + "  try{ nodes[i].scrollIntoView({block:'center'}); nodes[i].click(); return true; }catch(e){}"
                        + " }"
                        + " return false;"
                        + "}"
                        + "function findRow(keyword){"
                        + " var nodes=document.querySelectorAll('tr,li,div,dl,section,table,ul');"
                        + " var best=null, bestLen=1e9;"
                        + " for(var i=0;i<nodes.length;i++){"
                        + "  if(!visible(nodes[i])) continue;"
                        + "  var t=txt(nodes[i]);"
                        + "  if(t.indexOf(norm(keyword))<0) continue;"
                        + "  if(t.length>=4 && t.length<bestLen && t.length<180){ best=nodes[i]; bestLen=t.length; }"
                        + " }"
                        + " return best;"
                        + "}"
                        + "function readNum(row){"
                        + " if(!row) return -1;"
                        + " var inp=row.querySelector('input[type=number],input[type=text],input:not([type=hidden])');"
                        + " if(inp&&visible(inp)){ var v=parseInt(String(inp.value||'').replace(/\\D/g,''),10); if(!isNaN(v)) return v; }"
                        + " var els=row.querySelectorAll('span,em,strong,b,td,div,p,label');"
                        + " for(var i=0;i<els.length;i++){"
                        + "  if(!visible(els[i])) continue;"
                        + "  var t=txt(els[i]);"
                        + "  if(/^\\d{1,2}$/.test(t)) return parseInt(t,10);"
                        + " }"
                        + " var all=txt(row).match(/\\d+/g); return all&&all.length?parseInt(all[all.length-1],10):-1;"
                        + "}"
                        + "function findPlusMinus(row){"
                        + " var plus=null, minus=null;"
                        + " var buttons=row.querySelectorAll('button,a,span,i,em,div,img,input');"
                        + " for(var b=0;b<buttons.length;b++){"
                        + "  if(!visible(buttons[b])) continue;"
                        + "  var bt=txt(buttons[b]);"
                        + "  var alt=norm(buttons[b].getAttribute&& (buttons[b].getAttribute('alt')||buttons[b].getAttribute('title')||''));"
                        + "  var cls=((buttons[b].className||'')+'').toLowerCase();"
                        + "  if(bt==='+'||bt==='＋'||alt.indexOf('+')>=0||cls.indexOf('plus')>=0||cls.indexOf('increase')>=0||cls.indexOf('up')>=0) plus=buttons[b];"
                        + "  if(bt==='-'||bt==='－'||bt==='−'||alt.indexOf('-')>=0||cls.indexOf('minus')>=0||cls.indexOf('decrease')>=0||cls.indexOf('down')>=0) minus=buttons[b];"
                        + " }"
                        + " if(!plus||!minus){"
                        + "  var clickables=[];"
                        + "  for(var c=0;c<buttons.length;c++){"
                        + "   if(!visible(buttons[c])) continue;"
                        + "   var t2=txt(buttons[c]);"
                        + "   if(t2.length<=2 || buttons[c].tagName==='BUTTON' || buttons[c].tagName==='A' || buttons[c].tagName==='IMG') clickables.push(buttons[c]);"
                        + "  }"
                        + "  if(clickables.length>=2){ if(!minus) minus=clickables[0]; if(!plus) plus=clickables[clickables.length-1]; }"
                        + " }"
                        + " return {plus:plus,minus:minus};"
                        + "}"
                        + "function setRowCount(keyword, target){"
                        + " var row=findRow(keyword); if(!row) return 'missing:'+keyword;"
                        + " var inp=row.querySelector('input[type=number],input[type=text]');"
                        + " if(inp&&visible(inp)&&!inp.disabled){"
                        + "  try{"
                        + "   var desc=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value');"
                        + "   if(desc&&desc.set) desc.set.call(inp,String(target)); else inp.value=String(target);"
                        + "   inp.dispatchEvent(new Event('input',{bubbles:true}));"
                        + "   inp.dispatchEvent(new Event('change',{bubbles:true}));"
                        + "   return 'input:'+keyword+'='+inp.value;"
                        + "  }catch(e){}"
                        + " }"
                        + " var pm=findPlusMinus(row);"
                        + " for(var guard=0;guard<24;guard++){"
                        + "  var cur=readNum(row);"
                        + "  if(cur===target) return 'ok:'+keyword+'='+cur;"
                        + "  if(cur<0) break;"
                        + "  try{"
                        + "   if(cur<target && pm.plus) pm.plus.click();"
                        + "   else if(cur>target && pm.minus) pm.minus.click();"
                        + "   else break;"
                        + "  }catch(e){ break; }"
                        + " }"
                        + " return 'step:'+keyword+'='+readNum(row);"
                        + "}"
                        + "var body=txt(document.body);"
                        + "var hasModal=body.indexOf('경로우대')>=0 && (body.indexOf('다자녀')>=0||body.indexOf('국가유공')>=0);"
                        + "var openBtnVisible=false;"
                        + "var nodes=document.querySelectorAll('button,a,input,span,div');"
                        + "for(var oi=0;oi<nodes.length;oi++){"
                        + " if(visible(nodes[oi]) && txt(nodes[oi])==='감면대상적용'){ openBtnVisible=true; break; }"
                        + "}"
                        + "if(phase<=0){"
                        + " var p=setRowCount('사용인원', partyTarget);"
                        + " return JSON.stringify({phase:1,log:[p],hasModal:hasModal});"
                        + "}"
                        + "if(phase===1 || (phase<3 && !hasModal)){"
                        + " var opened=clickExact('감면대상적용', true);"
                        + " if(!opened){"
                        + "  for(var j=0;j<nodes.length;j++){"
                        + "   if(!visible(nodes[j])) continue;"
                        + "   var tj=txt(nodes[j]);"
                        + "   if(tj.indexOf('감면대상')>=0 && tj.indexOf('적용')>=0 && tj.indexOf('경로')<0){"
                        + "    try{ nodes[j].scrollIntoView({block:'center'}); nodes[j].click(); opened=true; break; }catch(e){}"
                        + "   }"
                        + "  }"
                        + " }"
                        + " return JSON.stringify({phase:2,log:['open:'+opened],opened:!!opened});"
                        + "}"
                        + "if(phase===2 || phase===3){"
                        + " if(!hasModal){ return JSON.stringify({phase:1,log:['modal-missing']}); }"
                        + " var logs=[];"
                        + " logs.push(setRowCount('경로우대', seniorTarget));"
                        + " var mk=body.indexOf('안양시다자녀')>=0?'안양시다자녀':'다자녀';"
                        + " logs.push(setRowCount(mk, multiTarget));"
                        + " if(phase===2){ return JSON.stringify({phase:3,log:logs,hasModal:true}); }"
                        + " var applied=clickExact('적용', true);"
                        + " if(!applied){"
                        + "  for(var k=0;k<nodes.length;k++){"
                        + "   if(!visible(nodes[k])) continue;"
                        + "   if(txt(nodes[k])==='적용'){ try{nodes[k].click(); applied=true; break;}catch(e){} }"
                        + "  }"
                        + " }"
                        + " return JSON.stringify({phase:4,log:logs.concat(['apply:'+applied]),applied:!!applied});"
                        + "}"
                        + "if(phase>=4){"
                        + " var stillModal=body.indexOf('경로우대')>=0 && body.indexOf('국가유공')>=0;"
                        + " if(stillModal){"
                        + "  var re=clickExact('적용', true);"
                        + "  return JSON.stringify({phase:4,log:['reapply:'+re],done:false});"
                        + " }"
                        + " var nextLabels=['저장','다음','확인','예약하기','결제하기','신청'];"
                        + " var next='';"
                        + " for(var ni=0;ni<nextLabels.length;ni++){ if(clickExact(nextLabels[ni], false)){ next=nextLabels[ni]; break; } }"
                        + " return JSON.stringify({phase:5,log:['next:'+(next||'none')],done:true,openBtn:openBtnVisible});"
                        + "}"
                        + "return JSON.stringify({phase:phase,log:['noop']});"
                        + "})(%d, %d, %d, %d);",
                party, senior, multi, partyPhase);

        status("감면 적용 중… (단계 " + partyPhase + ")");
        view.evaluateJavascript(js, v -> {
            Log.d(TAG, "party/discount=" + v);
            String raw = v == null ? "" : v;
            if (raw.contains("\"phase\":1") || raw.contains("\\\"phase\\\":1")) {
                partyPhase = 1;
            } else if (raw.contains("\"phase\":2") || raw.contains("\\\"phase\\\":2")) {
                partyPhase = 2;
            } else if (raw.contains("\"phase\":3") || raw.contains("\\\"phase\\\":3")) {
                partyPhase = 3;
            } else if (raw.contains("\"phase\":4") || raw.contains("\\\"phase\\\":4")) {
                partyPhase = 4;
            } else if (raw.contains("\"phase\":5") || raw.contains("\\\"phase\\\":5")) {
                partyPhase = 5;
            }

            if (raw.contains("\"done\":true") || raw.contains("\\\"done\\\":true")) {
                partyDone = true;
                courtsDone = true;
                status("경로우대·다자녀 감면 적용 완료");
                handler.postDelayed(() -> {
                    lastBookingStepMs = 0;
                    advanceBookingSteps(webView);
                }, 1500);
                return;
            }

            // Stay on this page until discounts are applied — do not call maybePay yet.
            long delay = partyPhase <= 2 ? 1000 : 1200;
            handler.postDelayed(() -> {
                if (!partyDone && !bookingFlowDone) {
                    lastBookingStepMs = 0;
                    applyPartyAndDiscounts(webView);
                }
            }, delay);
        });
    }

    private void maybePay(WebView view) {
        if (!confirmAccepted) {
            if (slotClicked) {
                clickRegisterConfirmIfPresent(view);
            }
            return;
        }
        // Before card form: handle pay-confirm / refund / terms / 결제하기.
        view.evaluateJavascript(
                "(function(){var b=(document.body&&document.body.innerText)||'';"
                        + "var hasYes=false,hasNo=false;"
                        + "var ns=document.querySelectorAll('button,a,span,div');"
                        + "for(var i=0;i<ns.length;i++){"
                        + " var t=(ns[i].innerText||'').replace(/\\s+/g,'').trim();"
                        + " if(t==='예') hasYes=true; if(t==='아니오') hasNo=true;"
                        + "}"
                        + "if(b.indexOf('결제하시겠습니까')>=0 && hasYes) return 'payConfirm';"
                        + "if((b.indexOf('환불 불가에 동의')>=0||(b.indexOf('동의하십니까')>=0&&b.indexOf('당일 취소')>=0))&&hasYes&&hasNo) return 'refund';"
                        + "if(b.indexOf('카드사선택')>=0||b.indexOf('주문자 약관')>=0||b.indexOf('Easy PAY')>=0||b.indexOf('EasyPAY')>=0) return 'easypay';"
                        + "if((b.indexOf('약관동의')>=0||b.indexOf('전체동의')>=0||b.indexOf('결제하기')>=0||b.indexOf('취소수수료')>=0)"
                        + " && b.indexOf('주문자')<0) return 'terms';"
                        + "if(b.indexOf('사용인원')>=0 && b.indexOf('감면대상')>=0 && b.indexOf('약관')<0) return 'party';"
                        + "return 'ok';})();",
                v -> {
                    String raw = v == null ? "" : v;
                    if (raw.contains("payConfirm")) {
                        partyDone = true;
                        clickPayConfirmYes(view);
                        return;
                    }
                    if (raw.contains("refund")) {
                        clickRefundAgreeYes(view);
                        return;
                    }
                    if (raw.contains("easypay")) {
                        partyDone = true;
                        if (!easyPayGateDone) {
                            easyPayGateDone = true;
                            status("EasyPAY — 약관동의·카드사 선택");
                            PaymentCardAutomator.runEasyPayGate(view, store, handler, () -> {
                                paymentTriggered = false;
                                handler.postDelayed(() -> maybePay(webView), 1200);
                            });
                        }
                        return;
                    }
                    if (raw.contains("terms")) {
                        partyDone = true;
                        agreeTermsAndPay(view);
                        return;
                    }
                    if (raw.contains("party") && !partyDone) {
                        advanceBookingSteps(view);
                        return;
                    }
                    if (paymentTriggered || bookingFlowDone
                            || !store.getBool(SecureStore.KEY_AUTO_TO_PAYMENT, true)) {
                        return;
                    }
                    paymentTriggered = true;
                    String method = store.get(SecureStore.KEY_PAYMENT_METHOD, "card");
                    status("결제 단계 시작");
                    handler.postDelayed(() -> {
                        if (bookingFlowDone) {
                            return;
                        }
                        if ("app_card".equals(method)) {
                            status("앱카드 결제 선택");
                            PaymentCardAutomator.selectAppCardOption(view);
                        } else {
                            status("카드 결제 입력");
                            PaymentCardAutomator.fillCardForm(view, store);
                        }
                        if (!store.getBool(SecureStore.KEY_AUTO_CLICK_PAY, false)) {
                            status("결제 버튼 자동클릭 OFF — 화면에서 확인 후 결제하세요");
                        }
                    }, 1200);
                });
    }

    private void status(String msg) {
        Log.i(TAG, msg);
        if (listener != null) {
            handler.post(() -> listener.onStatus(msg));
        }
    }

    private void finish(boolean ok, String msg) {
        bookingFlowDone = true;
        pageReadyToken++; // cancel pending onPageReady
        handler.removeCallbacksAndMessages(null);
        NotifyHelper.notify(context, NotifyHelper.NOTIF_STATUS,
                context.getString(R.string.app_name), msg);
        if (listener != null) {
            handler.post(() -> listener.onFinished(ok, msg));
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void openUrl(String url) {
            if (url == null || url.isEmpty()) {
                return;
            }
            handler.post(() -> {
                Log.i(TAG, "Bridge.openUrl: " + url);
                status("통합 로그인 URL 이동");
                webView.loadUrl(url);
            });
        }

        @JavascriptInterface
        public void onSlotClicked(String time) {
            handler.post(() -> {
                if ("none".equals(time)) {
                    slotClicked = false;
                    slotSelectionStarted = false;
                    status("선호 「가」 슬롯을 찾지 못함 — 재시도");
                    handler.postDelayed(() -> trySelectDateAndSlot(webView),
                            fastOpenPath ? 400 : 900);
                    return;
                }
                // Tentative only — real success = 등록확인 팝업 출현.
                slotSelectionStarted = false;
                status("슬롯 클릭 시도: " + time + " — 확인 팝업 검증");
                handler.postDelayed(() -> verifySlotConfirmDialog(time),
                        fastOpenPath ? 350 : 600);
            });
        }
    }

    /**
     * Slot click counts only if 「등록하시겠습니까?」 appears; otherwise reselect.
     */
    private void verifySlotConfirmDialog(String time) {
        if (bookingFlowDone || confirmAccepted) {
            return;
        }
        webView.evaluateJavascript(
                "(function(){var b=(document.body&&document.body.innerText)||'';"
                        + "if(b.indexOf('등록하시겠습니까')>=0||b.indexOf('선택하신 날짜')>=0) return 'dialog';"
                        + "return 'no';})();",
                v -> {
                    String raw = v == null ? "" : v;
                    if (raw.contains("dialog")) {
                        slotClicked = true;
                        confirmPollTries = 0;
                        status("시간 선택됨: " + time + " — 등록 확인");
                        clickRegisterConfirmIfPresent(webView);
                        return;
                    }
                    slotClicked = false;
                    slotSelectionStarted = false;
                    status("슬롯 미반영 — 재클릭");
                    handler.postDelayed(() -> {
                        if (!bookingFlowDone && !confirmAccepted) {
                            trySelectDateAndSlot(webView);
                        }
                    }, fastOpenPath ? 200 : 500);
                });
    }
}
