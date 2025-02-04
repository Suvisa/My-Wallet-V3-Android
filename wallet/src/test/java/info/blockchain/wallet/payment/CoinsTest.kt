package info.blockchain.wallet.payment

import com.blockchain.testutils.satoshi
import info.blockchain.api.data.UnspentOutput
import info.blockchain.api.data.UnspentOutputs
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import org.amshove.kluent.`should equal`
import org.bitcoinj.params.BitcoinMainNetParams
import org.junit.Test

class CoinsTest {

    @Test
    fun `max spendable 1`() {
        maximumSpendable(unspentOutputs(10000.satoshi()), 100)
            .also { (balance: CryptoValue, fee: CryptoValue) ->
                balance `should equal` 9980.satoshi()
                fee `should equal` 20.satoshi()
            }
    }

    @Test
    fun `max spendable 2`() {
        maximumSpendable(unspentOutputs(10000.satoshi(), 1000.satoshi()), 100)
            .also { (balance: CryptoValue, fee: CryptoValue) ->
                balance `should equal` 10966.satoshi()
                fee `should equal` 34.satoshi()
            }
    }

    @Test
    fun `no max spendable and getMinimumCoinsForPayment discrepancies`() {
        val unspentOutputs = unspentOutputs(10000.satoshi(), 1000.satoshi())
        (6753..10999).forEach { fee ->
            val maximumSpendable = maximumSpendable(unspentOutputs, fee)
            val max = maximumSpendable.first
            val absoluteFee = maximumSpendable.second
            val bundle = spendableUnspentOutputs(unspentOutputs, max, fee)
            println("Fee $fee, ${absoluteFee.toStringWithSymbol()} Max ${max.toStringWithSymbol()}")
            println("Spendable outputs: ${bundle.spendableOutputs.size}")

            val payment = Payment()
            payment.makeSimpleTransaction(
                BitcoinMainNetParams.get(),
                bundle.spendableOutputs,
                hashMapOf("1GYkgRtJmEp355xUtVFfHSFjFdbqjiwKmb" to max.amount),
                absoluteFee.amount,
                "1GiEQZt9aX2XfDcj14tCC4xAWEJtq9EXW7"
            )
        }
    }

    @Test
    fun `fee exceed coin`() {
        val unspentOutputs = unspentOutputs(10000.satoshi(), 1000.satoshi())
        maximumSpendable(unspentOutputs, 6756)
            .also { (balance: CryptoValue, fee: CryptoValue) ->
                balance `should equal` 8702.satoshi()
                fee `should equal` 1298.satoshi()
            }
        maximumSpendable(unspentOutputs, 6757)
            .also { (balance: CryptoValue, fee: CryptoValue) ->
                balance `should equal` 8702.satoshi()
                fee `should equal` 1298.satoshi()
            }
        minimumCoinsForPayment(8702.satoshi(), unspentOutputs, 6756).apply {
            coinCount `should equal` 1
            coinValue `should equal` 10000.satoshi()
        }
        minimumCoinsForPayment(8702.satoshi(), unspentOutputs, 6757).apply {
            coinCount `should equal` 1
            coinValue `should equal` 10000.satoshi()
        }
    }
}

private fun maximumSpendable(
    unspentOutputs: UnspentOutputs,
    fee: Int
) = Coins.getMaximumAvailable(unspentOutputs, fee.toBigInteger(), false)
    .let { (balance, fee) -> CryptoValue(CryptoCurrency.BTC, balance) to CryptoValue(CryptoCurrency.BTC, fee) }

private class MinCoinsResult(
    val coinCount: Int,
    val coinValue: CryptoValue
)

private fun minimumCoinsForPayment(
    value: CryptoValue,
    unspentOutputs: UnspentOutputs,
    fee: Int
) = spendableUnspentOutputs(unspentOutputs, value, fee)
    .let {
        MinCoinsResult(
            coinCount = it.spendableOutputs.size,
            coinValue = it.spendableOutputs.sumBy { v -> v.value.toInt() }.satoshi()
        )
    }

private fun spendableUnspentOutputs(
    unspentOutputs: UnspentOutputs,
    value: CryptoValue,
    fee: Int
) = Coins.getMinimumCoinsForPayment(unspentOutputs, value.amount, fee.toBigInteger(), false)

private fun unspentOutputs(vararg values: CryptoValue): UnspentOutputs {
    return UnspentOutputs().apply {
        unspentOutputs = ArrayList(values.map {
            UnspentOutput().apply {
                value = it.amount
                script = "76a91469dec09e9b32ffd447c80d413d58f0413e99208e88ac"
                txHash = "0000000000000000000000000000000000000000000000000000000000000000"
            }
        })
    }
}