package piuk.blockchain.android.data.websocket;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.support.annotation.StringRes;
import android.support.v4.content.LocalBroadcastManager;

import com.fasterxml.jackson.databind.ObjectMapper;

import info.blockchain.balance.CryptoCurrency;
import info.blockchain.wallet.exceptions.DecryptionException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.web3j.utils.Convert;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.ObservableSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import piuk.blockchain.android.R;
import piuk.blockchain.android.ui.launcher.LauncherActivity;
import piuk.blockchain.androidcore.data.access.AccessState;
import piuk.blockchain.androidcore.data.bitcoincash.BchDataManager;
import com.blockchain.network.EnvironmentUrls;
import piuk.blockchain.androidcore.data.currency.BTCDenomination;
import piuk.blockchain.androidcore.data.currency.CurrencyFormatManager;
import piuk.blockchain.androidcore.data.ethereum.EthDataManager;
import piuk.blockchain.androidcore.data.ethereum.models.CombinedEthModel;
import piuk.blockchain.androidcore.data.payload.PayloadDataManager;
import piuk.blockchain.androidcore.data.websockets.WebSocketReceiveEvent;
import piuk.blockchain.androidcore.utils.rxjava.IgnorableDefaultObserver;
import piuk.blockchain.androidcore.data.rxjava.RxBus;
import piuk.blockchain.android.data.rxjava.RxUtil;
import piuk.blockchain.android.data.websocket.models.EthWebsocketResponse;
import piuk.blockchain.android.ui.balance.BalanceFragment;
import piuk.blockchain.androidcoreui.ui.customviews.ToastCustom;
import piuk.blockchain.android.ui.home.MainActivity;
import piuk.blockchain.androidcoreui.utils.AppUtil;
import com.blockchain.notifications.NotificationsUtil;
import piuk.blockchain.androidcore.utils.annotations.Thunk;
import timber.log.Timber;


@SuppressWarnings("WeakerAccess")
class WebSocketHandler {

    private final static long RETRY_INTERVAL = 5 * 1000L;
    /**
     * Websocket status code as defined by <a href="http://tools.ietf.org/html/rfc6455#section-7.4">Section
     * 7.4 of RFC 6455</a>
     */
    private static final int STATUS_CODE_NORMAL_CLOSURE = 1000;

    private boolean stoppedDeliberately = false;
    private String[] xpubsBtc;
    private String[] addrsBtc;
    private String[] xpubsBch;
    private String[] addrsBch;
    private String ethAccount;
    private EthDataManager ethDataManager;
    @Thunk BchDataManager bchDataManager;
    private NotificationManager notificationManager;
    private String guid;
    private HashSet<String> btcSubHashSet = new HashSet<>();
    private HashSet<String> btcOnChangeHashSet = new HashSet<>();
    private HashSet<String> bchSubHashSet = new HashSet<>();
    private EnvironmentUrls environmentUrls;
    private CurrencyFormatManager currencyFormatManager;
    private Context context;
    private OkHttpClient okHttpClient;
    private WebSocket btcConnection, ethConnection, bchConnection;
    boolean connected;
    @Thunk PayloadDataManager payloadDataManager;
    @Thunk CompositeDisposable compositeDisposable = new CompositeDisposable();
    private RxBus rxBus;
    private AccessState accessState;
    private AppUtil appUtil;

    public WebSocketHandler(Context context,
                            OkHttpClient okHttpClient,
                            PayloadDataManager payloadDataManager,
                            EthDataManager ethDataManager,
                            BchDataManager bchDataManager,
                            NotificationManager notificationManager,
                            EnvironmentUrls environmentUrls,
                            CurrencyFormatManager currencyFormatManager,
                            String guid,
                            String[] xpubsBtc,
                            String[] addrsBtc,
                            String[] xpubsBch,
                            String[] addrsBch,
                            String ethAccount,
                            RxBus rxBus,
                            AccessState accessState,
                            AppUtil appUtil) {

        this.context = context;
        this.okHttpClient = okHttpClient;
        this.payloadDataManager = payloadDataManager;
        this.ethDataManager = ethDataManager;
        this.bchDataManager = bchDataManager;
        this.notificationManager = notificationManager;
        this.environmentUrls = environmentUrls;
        this.currencyFormatManager = currencyFormatManager;
        this.guid = guid;
        this.xpubsBtc = xpubsBtc;
        this.addrsBtc = addrsBtc;
        this.xpubsBch = xpubsBch;
        this.addrsBch = addrsBch;
        this.ethAccount = ethAccount;
        this.rxBus = rxBus;
        this.accessState = accessState;
        this.appUtil = appUtil;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Bitcoin
    ///////////////////////////////////////////////////////////////////////////
    public void subscribeToXpubBtc(String xpub) {
        if (xpub != null && !xpub.isEmpty()) {
            sendToBtcConnection("{\"op\":\"xpub_sub\", \"xpub\":\"" + xpub + "\"}");
        }
    }

    public void subscribeToAddressBtc(String address) {
        if (address != null && !address.isEmpty()) {
            sendToBtcConnection("{\"op\":\"addr_sub\", \"addr\":\"" + address + "\"}");
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Ethereum
    ///////////////////////////////////////////////////////////////////////////
    private void subscribeToEthAccount(String ethAddress) {
        if (ethAddress != null && !ethAddress.isEmpty()) {
            sendToEthConnection("{\"op\":\"account_sub\", \"account\":\"" + ethAddress + "\"}");
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Bitcoin Cash
    ///////////////////////////////////////////////////////////////////////////
    public void subscribeToXpubBch(String xpub) {
        if (xpub != null && !xpub.isEmpty()) {
            sendToBchConnection("{\"op\":\"xpub_sub\", \"xpub\":\"" + xpub + "\"}");
        }
    }

    public void subscribeToAddressBch(String address) {
        if (address != null && !address.isEmpty()) {
            sendToBchConnection("{\"op\":\"addr_sub\", \"addr\":\"" + address + "\"}");
        }
    }

    /**
     * Starts listening for updates to subscribed xpubsBtc and addresses. Will attempt reconnection
     * every 5 seconds if it cannot connect immediately.
     */
    public void start() {
        stop();
        stoppedDeliberately = false;
        connectToWebSocket()
                .doOnError(throwable -> attemptReconnection())
                .subscribe(new IgnorableDefaultObserver<>());
    }

    /**
     * Halts and disconnects the WebSocket service whilst preventing reconnection until {@link
     * #start()} is called
     */
    public void stopPermanently() {
        stoppedDeliberately = true;
        stop();
    }

    private void stop() {
        if (isConnected()) {
            btcConnection.close(STATUS_CODE_NORMAL_CLOSURE, "BTC Websocket deliberately stopped");
            ethConnection.close(STATUS_CODE_NORMAL_CLOSURE, "ETH Websocket deliberately stopped");
            bchConnection.close(STATUS_CODE_NORMAL_CLOSURE, "BCH Websocket deliberately stopped");
            btcConnection = ethConnection = bchConnection = null;
        }
    }

    private void sendToBtcConnection(String message) {
        // Make sure each message is only sent once per socket lifetime
        if (!btcSubHashSet.contains(message)) {
            try {
                if (isConnected()) {
                    btcConnection.send(message);
                    btcSubHashSet.add(message);
                }
            } catch (Exception e) {
                Timber.e(e, "Send to BTC websocket failed");
            }
        }
    }

    private void sendToEthConnection(String message) {
        // Make sure each message is only sent once per socket lifetime
        if (!btcSubHashSet.contains(message)) {
            try {
                if (isConnected()) {
                    ethConnection.send(message);
                    btcSubHashSet.add(message);
                }
            } catch (Exception e) {
                Timber.e(e, "Send to ETH websocket failed");
            }
        }
    }

    private void sendToBchConnection(String message) {
        // Make sure each message is only sent once per socket lifetime
        if (!bchSubHashSet.contains(message)) {
            try {
                if (isConnected()) {
                    bchConnection.send(message);
                    bchSubHashSet.add(message);
                }
            } catch (Exception e) {
                Timber.e(e, "Send to BCH websocket failed");
            }
        }
    }

    @Thunk
    void subscribe() {
        if (guid == null) {
            return;
        }
        sendToBtcConnection("{\"op\":\"wallet_sub\",\"guid\":\"" + guid + "\"}");

        for (String xpub : xpubsBtc) subscribeToXpubBtc(xpub);
        for (String addr : addrsBtc) subscribeToAddressBtc(addr);

        for (String xpub : xpubsBch) subscribeToXpubBch(xpub);
        for (String addr : addrsBch) subscribeToAddressBch(addr);

        subscribeToEthAccount(ethAccount);
    }

    @Thunk
    void attemptReconnection() {
        if (compositeDisposable.size() == 0 && !stoppedDeliberately) {
            compositeDisposable.add(
                    getReconnectionObservable()
                            .subscribe(
                                    value -> Timber.d("attemptReconnection: %s", value),
                                    throwable -> Timber.e(throwable, "Attempt reconnection failed")));
        }
    }

    private Observable<Long> getReconnectionObservable() {
        return Observable.interval(RETRY_INTERVAL, TimeUnit.MILLISECONDS)
                .takeUntil((ObservableSource<Object>) aLong -> isConnected())
                .doOnNext(tick -> start());
    }

    private boolean isConnected() {
        return btcConnection != null && ethConnection != null && bchConnection != null && connected;
    }

    private void updateBtcBalancesAndTransactions() {
        payloadDataManager.updateAllBalances()
                .andThen(payloadDataManager.updateAllTransactions())
                .doOnComplete(this::sendBroadcast)
                .subscribe(new IgnorableDefaultObserver<>());
    }

    private void updateBchBalancesAndTransactions() {
        bchDataManager.updateAllBalances()
                .andThen(bchDataManager.getWalletTransactions(50, 0))
                .doOnComplete(this::sendBroadcast)
                .subscribe(new IgnorableDefaultObserver<>());
    }

    @Thunk
    void sendBroadcast() {
        Intent intent = new Intent(BalanceFragment.ACTION_INTENT);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    private void startWebSocket() {
        Request btcRequest = new Request.Builder()
                .url(environmentUrls.websocketUrl(CryptoCurrency.BTC))
                .addHeader("Origin", "https://blockchain.info")
                .build();

        Request ethRequest = new Request.Builder()
                .url(environmentUrls.websocketUrl(CryptoCurrency.ETHER))
                .addHeader("Origin", "https://blockchain.info")
                .build();

        Request bchRequest = new Request.Builder()
                .url(environmentUrls.websocketUrl(CryptoCurrency.BCH))
                .addHeader("Origin", "https://blockchain.info")
                .build();

        btcConnection = okHttpClient.newWebSocket(btcRequest, new BtcWebsocketListener());
        ethConnection = okHttpClient.newWebSocket(ethRequest, new EthWebsocketListener());
        bchConnection = okHttpClient.newWebSocket(bchRequest, new BchWebsocketListener());
    }

    private Completable connectToWebSocket() {
        return Completable.fromCallable(() -> {
            btcSubHashSet.clear();
            bchSubHashSet.clear();
            startWebSocket();
            return Void.TYPE;
        }).compose(RxUtil.applySchedulersToCompletable());
    }

    @Thunk
    void attemptParseEthMessage(String message) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            EthWebsocketResponse response = mapper.readValue(message, EthWebsocketResponse.class);

            String from = response.getTx().getFrom();
            String to = response.getTx().getTo();
            // Check if money was received or sent
            if (ethAccount != null && !ethAccount.equals(from) && ethAccount.equals(to)) {
                String title = context.getString(R.string.app_name);
                String marquee = context.getString(R.string.received_ethereum)
                        + " "
                        + Convert.fromWei(new BigDecimal(response.getTx().getValue()), Convert.Unit.ETHER)
                        + " ETH";

                String text = marquee
                        + " "
                        + context.getString(R.string.from).toLowerCase(Locale.US) + " " + response.getTx().getFrom();

                triggerNotification(title, marquee, text);
            }

            if (ethDataManager.getEthWallet() != null) {
                downloadEthTransactions()
                        .subscribe(
                                combinedEthModel -> sendBroadcast(),
                                throwable -> Timber.e(throwable, "downloadEthTransactions failed"));
            }
        } catch (Exception e) {
            Timber.e(e);
        }
    }

    // TODO: 20/09/2017 Here we should probably parse this info into objects rather than doing it manually
    // TODO: 20/09/2017 Get a list of all possible payloads construct objects
    @Thunk
    void attemptParseBtcMessage(String message, JSONObject jsonObject) {
        try {
            String op = (String) jsonObject.get("op");
            if (op.equals("utx") && jsonObject.has("x")) {
                JSONObject objX = (JSONObject) jsonObject.get("x");

                long value = 0L;
                long totalValue = 0L;
                String inAddr = null;

                if (objX.has("inputs")) {
                    JSONArray inputArray = (JSONArray) objX.get("inputs");
                    JSONObject inputObj;
                    for (int j = 0; j < inputArray.length(); j++) {
                        inputObj = (JSONObject) inputArray.get(j);
                        if (inputObj.has("prev_out")) {
                            JSONObject prevOutObj = (JSONObject) inputObj.get("prev_out");
                            if (prevOutObj.has("value")) {
                                value = prevOutObj.getLong("value");
                            }
                            if (prevOutObj.has("xpub")) {
                                totalValue -= value;
                            } else if (prevOutObj.has("addr")) {
                                if (payloadDataManager.getWallet().containsLegacyAddress((String) prevOutObj.get("addr"))) {
                                    totalValue -= value;
                                } else if (inAddr == null) {
                                    inAddr = (String) prevOutObj.get("addr");
                                }
                            }
                        }
                    }
                }

                if (objX.has("out")) {
                    JSONArray outArray = (JSONArray) objX.get("out");
                    JSONObject outObj;
                    for (int j = 0; j < outArray.length(); j++) {
                        outObj = (JSONObject) outArray.get(j);
                        if (outObj.has("value")) {
                            value = outObj.getLong("value");
                        }
                        if (outObj.has("addr") && objX.has("hash")) {
                            rxBus.emitEvent(WebSocketReceiveEvent.class, new WebSocketReceiveEvent(
                                    (String) outObj.get("addr"),
                                    (String) objX.get("hash")
                            ));
                        }
                        if (outObj.has("xpub")) {
                            totalValue += value;
                        } else if (outObj.has("addr")) {
                            if (payloadDataManager.getWallet().containsLegacyAddress((String) outObj.get("addr"))) {
                                totalValue += value;
                            }
                        }
                    }
                }

                updateBtcBalancesAndTransactions();

            } else if (op.equals("on_change")) {
                final String localChecksum = payloadDataManager.getPayloadChecksum();
                boolean isSameChecksum = false;
                if (jsonObject.has("x")) {
                    JSONObject x = jsonObject.getJSONObject("x");
                    if (x.has("checksum")) {
                        final String remoteChecksum = x.getString("checksum");
                        isSameChecksum = remoteChecksum.equals(localChecksum);
                    }
                }

                if (!btcOnChangeHashSet.contains(message) && !isSameChecksum) {
                    // Remote update to wallet data detected
                    if (payloadDataManager.getTempPassword() != null) {
                        // Download changed payload
                        //noinspection ThrowableResultOfMethodCallIgnored
                        downloadChangedPayload().subscribe(
                                () -> showToast(R.string.wallet_updated).subscribe(new IgnorableDefaultObserver<>()),
                                Timber::e);
                    }

                    btcOnChangeHashSet.add(message);
                }
            }
        } catch (Exception e) {
            Timber.e(e, "attemptParseBtcMessage");
        }
    }

    @Thunk
    void attemptParseBchMessage(JSONObject jsonObject) {
        try {
            String op = (String) jsonObject.get("op");
            if (op.equals("utx") && jsonObject.has("x")) {
                JSONObject objX = (JSONObject) jsonObject.get("x");

                long value = 0L;
                long totalValue = 0L;
                String inAddr = null;

                if (objX.has("inputs")) {
                    JSONArray inputArray = (JSONArray) objX.get("inputs");
                    JSONObject inputObj;
                    for (int j = 0; j < inputArray.length(); j++) {
                        inputObj = (JSONObject) inputArray.get(j);
                        if (inputObj.has("prev_out")) {
                            JSONObject prevOutObj = (JSONObject) inputObj.get("prev_out");
                            if (prevOutObj.has("value")) {
                                value = prevOutObj.getLong("value");
                            }
                            if (prevOutObj.has("xpub")) {
                                totalValue -= value;
                            } else if (prevOutObj.has("addr")) {
                                //noinspection RedundantCast
                                if (bchDataManager.getLegacyAddressStringList().contains((String) prevOutObj.get("addr"))) {
                                    totalValue -= value;
                                } else if (inAddr == null) {
                                    inAddr = (String) prevOutObj.get("addr");
                                }
                            }
                        }
                    }
                }

                if (objX.has("out")) {
                    JSONArray outArray = (JSONArray) objX.get("out");
                    JSONObject outObj;
                    for (int j = 0; j < outArray.length(); j++) {
                        outObj = (JSONObject) outArray.get(j);
                        if (outObj.has("value")) {
                            value = outObj.getLong("value");
                        }
                        if (outObj.has("addr") && objX.has("hash")) {
                            rxBus.emitEvent(WebSocketReceiveEvent.class, new WebSocketReceiveEvent(
                                    (String) outObj.get("addr"),
                                    (String) objX.get("hash")
                            ));
                        }
                        if (outObj.has("xpub")) {
                            totalValue += value;
                        } else if (outObj.has("addr")) {
                            //noinspection RedundantCast
                            if (bchDataManager.getLegacyAddressStringList().contains((String) outObj.get("addr"))) {
                                totalValue += value;
                            }
                        }
                    }
                }

                String title = context.getString(R.string.app_name);
                if (totalValue > 0L) {
                    String marquee = context.getString(R.string.received_bitcoin_cash)
                            + " "
                            + currencyFormatManager.getFormattedBchValueWithUnit(BigDecimal.valueOf(totalValue), BTCDenomination.SATOSHI);
                    String text = marquee;
                    text += " "
                            + context.getString(R.string.from).toLowerCase(Locale.US)
                            + " "
                            + inAddr;

                    triggerNotification(title, marquee, text);
                }

                updateBchBalancesAndTransactions();
            }
        } catch (Exception e) {
            Timber.e(e, "attemptParseBchMessage");
        }
    }

    private Completable showToast(@StringRes int message) {
        return Completable.fromRunnable(
                () -> ToastCustom.makeText(
                        context,
                        context.getString(message),
                        ToastCustom.LENGTH_SHORT,
                        ToastCustom.TYPE_GENERAL))
                .subscribeOn(AndroidSchedulers.mainThread());
    }

    private Completable downloadChangedPayload() {
        return payloadDataManager.initializeAndDecrypt(
                payloadDataManager.getWallet().getSharedKey(),
                payloadDataManager.getWallet().getGuid(),
                payloadDataManager.getTempPassword()
        ).compose(RxUtil.applySchedulersToCompletable())
                .doOnComplete(this::updateBtcBalancesAndTransactions)
                .doOnError(throwable -> {
                    if (throwable instanceof DecryptionException) {
                        showToast(R.string.password_changed).subscribe(new IgnorableDefaultObserver<>());
                        // Password was changed on web, logout to force re-entry of password when
                        // app restarts
                        accessState.unpairWallet();
                        appUtil.restartApp(LauncherActivity.class);
                    }
                });
    }

    private Observable<CombinedEthModel> downloadEthTransactions() {
        return ethDataManager.fetchEthAddress()
                .compose(RxUtil.applySchedulersToObservable());
    }

    private void triggerNotification(String title, String marquee, String text) {
        Intent notifyIntent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                notifyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        new NotificationsUtil(context, notificationManager).triggerNotification(
                title,
                marquee,
                text,
                R.drawable.ic_launcher_round,
                pendingIntent,
                1000);
    }

    private class BchWebsocketListener extends BaseWebsocketListener {
        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            super.onMessage(webSocket, text);
            Timber.d("BchWebsocketListener onMessage %s", text);

            if (payloadDataManager.getWallet() != null) {
                try {
                    JSONObject jsonObject = new JSONObject(text);
                    attemptParseBchMessage(jsonObject);
                } catch (JSONException je) {
                    Timber.e(je);
                }
            }
        }
    }

    private class EthWebsocketListener extends BaseWebsocketListener {
        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            super.onMessage(webSocket, text);
            Timber.d("EthWebsocketListener onMessage %s", text);

            if (text.contains("account_sub")) {
                attemptParseEthMessage(text);
            }
        }
    }

    private class BtcWebsocketListener extends BaseWebsocketListener {
        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            super.onMessage(webSocket, text);
            Timber.d("BtcWebsocketListener onMessage %s", text);

            if (payloadDataManager.getWallet() != null) {
                try {
                    JSONObject jsonObject = new JSONObject(text);
                    attemptParseBtcMessage(text, jsonObject);
                } catch (JSONException je) {
                    Timber.e(je);
                }
            }
        }
    }

    private class BaseWebsocketListener extends WebSocketListener {
        @CallSuper
        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            super.onMessage(webSocket, text);
            sendBroadcast();
        }

        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            super.onOpen(webSocket, response);
            connected = true;
            compositeDisposable.clear();
            subscribe();
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            super.onClosed(webSocket, code, reason);
            connected = false;
            attemptReconnection();
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
            super.onFailure(webSocket, t, response);
            connected = false;
            attemptReconnection();
        }
    }

}
