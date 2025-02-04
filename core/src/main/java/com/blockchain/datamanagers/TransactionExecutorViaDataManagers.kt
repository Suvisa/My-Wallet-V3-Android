package com.blockchain.datamanagers

import com.blockchain.account.DefaultAccountDataManager
import com.blockchain.datamanagers.fees.BitcoinLikeFees
import com.blockchain.datamanagers.fees.EthereumFees
import com.blockchain.datamanagers.fees.NetworkFees
import com.blockchain.datamanagers.fees.XlmFees
import com.blockchain.datamanagers.fees.feeForType
import com.blockchain.fees.FeeType
import com.blockchain.transactions.Memo
import com.blockchain.transactions.SendDetails
import com.blockchain.transactions.TransactionSender
import com.blockchain.transactions.sendFundsOrThrow
import info.blockchain.api.data.UnspentOutputs
import info.blockchain.balance.AccountReference
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.wallet.coin.GenericMetadataAccount
import info.blockchain.wallet.ethereum.EthereumAccount
import info.blockchain.wallet.payload.data.Account
import info.blockchain.wallet.payment.SpendableUnspentOutputs
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import org.bitcoinj.core.ECKey
import org.web3j.crypto.RawTransaction
import piuk.blockchain.androidcore.data.erc20.Erc20Account
import piuk.blockchain.androidcore.data.ethereum.EthDataManager
import piuk.blockchain.androidcore.data.ethereum.EthereumAccountWrapper
import piuk.blockchain.androidcore.data.ethereum.exceptions.TransactionInProgressException
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import piuk.blockchain.androidcore.data.payments.SendDataManager
import timber.log.Timber
import java.math.BigInteger

internal class TransactionExecutorViaDataManagers(
    private val payloadDataManager: PayloadDataManager,
    private val ethDataManager: EthDataManager,
    private val erc20Account: Erc20Account,
    private val sendDataManager: SendDataManager,
    private val addressResolver: AddressResolver,
    private val accountLookup: AccountLookup,
    private val defaultAccountDataManager: DefaultAccountDataManager,
    private val ethereumAccountWrapper: EthereumAccountWrapper,
    private val xlmSender: TransactionSender
) : TransactionExecutor {

    override fun executeTransaction(
        amount: CryptoValue,
        destination: String,
        sourceAccount: AccountReference,
        fees: NetworkFees,
        feeType: FeeType,
        memo: Memo?
    ): Single<String> =
        when (amount.currency) {
            CryptoCurrency.BTC -> sendBtcTransaction(
                amount,
                destination,
                sourceAccount.toJsonAccount(),
                (fees as BitcoinLikeFees).feeForType(feeType)
            )
            CryptoCurrency.ETHER -> sendEthTransaction(
                amount,
                destination,
                sourceAccount.toJsonAccount(),
                fees as EthereumFees,
                feeType
            )
            CryptoCurrency.BCH -> sendBchTransaction(
                amount,
                destination,
                sourceAccount.toJsonAccount(),
                (fees as BitcoinLikeFees).feeForType(feeType)
            )
            CryptoCurrency.XLM -> xlmSender.sendFundsOrThrow(
                SendDetails(
                    sourceAccount,
                    amount,
                    destination,
                    (fees as XlmFees).feeForType(feeType),
                    memo
                )
            ).map { it.hash!! }
            CryptoCurrency.PAX ->
                sendPaxTransaction(fees as EthereumFees, destination, amount)
        }

    private fun sendPaxTransaction(
        feeOptions: EthereumFees,
        receivingAddress: String,
        cryptoValue: CryptoValue
    ): Single<String> =
        createPaxTransaction(feeOptions, receivingAddress, cryptoValue.amount)
            .flatMap {
                val ecKey = EthereumAccount.deriveECKey(payloadDataManager.wallet!!.hdWallets[0].masterKey, 0)
                return@flatMap ethDataManager.signEthTransaction(it, ecKey)
            }
            .flatMap { ethDataManager.pushEthTx(it) }
            .flatMap { ethDataManager.setLastTxHashObservable(it, System.currentTimeMillis()) }
            .subscribeOn(Schedulers.io())
            .singleOrError()

    private fun createPaxTransaction(
        ethFees: EthereumFees,
        receivingAddress: String,
        amount: BigInteger
    ): Observable<RawTransaction> {
        val feeWei = ethFees.gasPriceRegularInWei

        return ethDataManager.fetchEthAddress()
            .map { ethDataManager.getEthResponseModel()!!.getNonce() }
            .map {
                erc20Account.createTransaction(
                    nonce = it,
                    to = receivingAddress,
                    contractAddress = ethDataManager.getErc20TokenData(CryptoCurrency.PAX).contractAddress,
                    gasPriceWei = feeWei,
                    gasLimitGwei = ethFees.gasLimitInGwei,
                    amount = amount)
            }
    }

    override fun getMaximumSpendable(
        account: AccountReference,
        fees: NetworkFees,
        feeType: FeeType
    ): Single<CryptoValue> =
        when (account) {
            is AccountReference.BitcoinLike ->
                account.getMaximumSpendable(
                    fees as BitcoinLikeFees,
                    feeType
                )
            is AccountReference.Ethereum -> getMaxEther(fees as EthereumFees, feeType)
            is AccountReference.Xlm -> defaultAccountDataManager.getMaxSpendableAfterFees(feeType)
            is AccountReference.Pax -> getMaxSpendablePax()
        }

    override fun getFeeForTransaction(
        amount: CryptoValue,
        sourceAccount: AccountReference,
        fees: NetworkFees,
        feeType: FeeType
    ): Single<CryptoValue> =
        when (sourceAccount) {
            is AccountReference.BitcoinLike -> calculateBitcoinLikeFee(
                sourceAccount,
                amount,
                (fees as BitcoinLikeFees).feeForType(feeType)
            )
            is AccountReference.Pax,
            is AccountReference.Ethereum -> {
                when (feeType) {
                    FeeType.Regular -> (fees as EthereumFees).absoluteRegularFeeInWei.just()
                    FeeType.Priority -> (fees as EthereumFees).absolutePriorityFeeInWei.just()
                }
            }
            is AccountReference.Xlm -> (fees as XlmFees).feeForType(feeType).just()
        }

    override fun getChangeAddress(
        accountReference: AccountReference
    ): Single<String> =
        addressResolver.addressPairForAccount(accountReference).map { it.changeAddress }

    override fun getReceiveAddress(
        accountReference: AccountReference
    ): Single<String> =
        addressResolver.addressPairForAccount(accountReference).map { it.receivingAddress }

    private fun calculateBitcoinLikeFee(
        account: AccountReference.BitcoinLike,
        amount: CryptoValue,
        feePerKb: BigInteger
    ): Single<CryptoValue> =
        getUnspentOutputs(account.xpub, amount.currency)
            .map { getSuggestedAbsoluteFee(it, amount, feePerKb) }
            .map { CryptoValue(amount.currency, it) }

    private fun getSuggestedAbsoluteFee(
        coins: UnspentOutputs,
        amountToSend: CryptoValue,
        feePerKb: BigInteger
    ): BigInteger =
        sendDataManager.getSpendableCoins(coins, amountToSend, feePerKb).absoluteFee

    private fun AccountReference.BitcoinLike.getMaximumSpendable(
        fees: BitcoinLikeFees,
        feeType: FeeType
    ): Single<CryptoValue> =
        getUnspentOutputs(xpub, cryptoCurrency)
            .map {
                CryptoValue(
                    cryptoCurrency,
                    sendDataManager.getMaximumAvailable(
                        cryptoCurrency,
                        it,
                        fees.feeForType(feeType)
                    ).left
                )
            }
            .doOnError { Timber.e(it) }
            .onErrorReturn { CryptoValue.zero(cryptoCurrency) }

    private fun getMaxEther(fees: EthereumFees, feeType: FeeType): Single<CryptoValue> =
        ethDataManager.fetchEthAddress()
            .map {
                val fee = when (feeType) {
                    is FeeType.Regular -> fees.absoluteRegularFeeInWei
                    is FeeType.Priority -> fees.absolutePriorityFeeInWei
                }
                (it.getAddressResponse()!!.balance - fee.amount).max(BigInteger.ZERO)
            }
            .map { CryptoValue.etherFromWei(it) }
            .doOnError { Timber.e(it) }
            .onErrorReturn { CryptoValue.ZeroEth }
            .singleOrError()

    private fun getMaxSpendablePax(): Single<CryptoValue> =
        erc20Account.getBalance().map { CryptoValue.usdPaxFromMinor(it) }
            .doOnError { Timber.e(it) }
            .onErrorReturn { CryptoValue.ZeroPax }

    private fun sendBtcTransaction(
        amount: CryptoValue,
        destination: String,
        account: Account,
        feePerKb: BigInteger
    ): Single<String> = sendBitcoinStyleTransaction(
        amount,
        destination,
        account,
        feePerKb,
        addressResolver.getChangeAddress(account)
    )

    private fun sendBchTransaction(
        amount: CryptoValue,
        destination: String,
        account: GenericMetadataAccount,
        feePerKb: BigInteger
    ): Single<String> = sendBitcoinStyleTransaction(
        amount,
        destination,
        account.getHdAccount(),
        feePerKb,
        addressResolver.getChangeAddress(account)
    )

    private fun sendBitcoinStyleTransaction(
        amount: CryptoValue,
        destination: String,
        account: Account,
        feePerKb: BigInteger,
        changeAddress: Single<String>
    ): Single<String> = getSpendableCoins(account.xpub, amount, feePerKb)
        .flatMap { spendable ->
            getSigningKeys(account, spendable)
                .flatMap { signingKeys ->
                    changeAddress
                        .flatMap {
                            submitBitcoinStylePayment(
                                amount,
                                spendable,
                                signingKeys,
                                destination,
                                it,
                                spendable.absoluteFee
                            )
                        }
                }
        }

    private fun sendEthTransaction(
        amount: CryptoValue,
        destination: String,
        account: EthereumAccount,
        fees: EthereumFees,
        feeType: FeeType
    ): Single<String> =
        ethDataManager.isLastTxPending()
            .singleOrError()
            .doOnSuccess {
                if (it == true)
                    throw TransactionInProgressException("Transaction pending, user cannot send funds at this time")
            }
            .flatMap {
                ethDataManager.fetchEthAddress()
                    .map {
                        val gasPrice = when (feeType) {
                            FeeType.Regular -> fees.gasPriceRegularInWei
                            FeeType.Priority -> fees.gasPricePriorityInWei
                        }
                        ethDataManager.createEthTransaction(
                            nonce = ethDataManager.getEthResponseModel()!!.getNonce(),
                            to = destination,
                            gasPriceWei = gasPrice,
                            gasLimitGwei = fees.gasLimitInGwei,
                            weiValue = amount.amount
                        )
                    }
                    .map {
                        account.signTransaction(
                            it,
                            ethereumAccountWrapper.deriveECKey(payloadDataManager.masterKey, 0)
                        )
                    }
                    .flatMap { ethDataManager.pushEthTx(it) }
                    .flatMap { ethDataManager.setLastTxHashObservable(it, System.currentTimeMillis()) }
                    .subscribeOn(Schedulers.io())
                    .singleOrError()
            }

    private fun getSpendableCoins(
        address: String,
        amount: CryptoValue,
        feePerKb: BigInteger
    ): Single<SpendableUnspentOutputs> = getUnspentOutputs(address, amount.currency)
        .subscribeOn(Schedulers.io())
        .map { sendDataManager.getSpendableCoins(it, amount, feePerKb) }

    private fun getUnspentOutputs(
        address: String,
        currency: CryptoCurrency
    ): Single<UnspentOutputs> =
        when (currency) {
            CryptoCurrency.BTC -> sendDataManager.getUnspentOutputs(address)
            CryptoCurrency.BCH -> sendDataManager.getUnspentBchOutputs(address)
            CryptoCurrency.ETHER -> throw IllegalArgumentException("Ether does not have unspent outputs")
            CryptoCurrency.XLM -> throw IllegalArgumentException("Xlm does not have unspent outputs")
            CryptoCurrency.PAX -> throw IllegalArgumentException("PAX does not have unspent outputs")
        }.subscribeOn(Schedulers.io())
            .singleOrError()

    private fun submitBitcoinStylePayment(
        amount: CryptoValue,
        unspent: SpendableUnspentOutputs,
        signingKeys: List<ECKey>,
        depositAddress: String,
        changeAddress: String,
        absoluteFee: BigInteger
    ): Single<String> = when (amount.currency) {
        CryptoCurrency.BTC -> sendDataManager.submitBtcPayment(
            unspent,
            signingKeys,
            depositAddress,
            changeAddress,
            absoluteFee,
            amount.amount
        )
        CryptoCurrency.BCH -> sendDataManager.submitBchPayment(
            unspent,
            signingKeys,
            depositAddress,
            changeAddress,
            absoluteFee,
            amount.amount
        )
        CryptoCurrency.ETHER -> throw IllegalArgumentException("Ether not supported by this method")
        CryptoCurrency.XLM -> throw IllegalArgumentException("XLM not supported by this method")
        CryptoCurrency.PAX -> throw IllegalArgumentException("PAX not supported by this method")
    }.subscribeOn(Schedulers.io())
        .singleOrError()

    private fun getSigningKeys(
        account: Account,
        spendable: SpendableUnspentOutputs
    ): Single<List<ECKey>> =
        payloadDataManager.getHDKeysForSigning(account, spendable).just()

    private fun GenericMetadataAccount.getHdAccount(): Account =
        payloadDataManager.getAccountForXPub(this.xpub)

    private fun <T> T.just(): Single<T> = Single.just(this)

    private fun BitcoinLikeFees.feeForType(feeType: FeeType): BigInteger = when (feeType) {
        FeeType.Regular -> this.regularFeePerKb
        FeeType.Priority -> this.priorityFeePerKb
    }

    private inline fun <reified T> AccountReference.toJsonAccount() =
        accountLookup.getAccountFromAddressOrXPub(this) as T
}
