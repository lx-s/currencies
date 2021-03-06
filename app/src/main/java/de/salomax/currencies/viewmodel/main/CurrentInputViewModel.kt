package de.salomax.currencies.viewmodel.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import de.salomax.currencies.repository.Database
import de.salomax.currencies.model.Rate
import org.mariuszgromada.math.mxparser.Expression
import java.lang.StringBuilder
import java.math.RoundingMode

class CurrentInputViewModel(application: Application) : AndroidViewModel(application) {

    /*
     * calculations ================================================================================
     */

    private fun isCalculating(): Boolean {
        return !currentCalculation.value.isNullOrBlank()
    }

    private val currentValue: MutableLiveData<String> by lazy {
        MutableLiveData<String>("0")
    }

    private val currentValueConverted: MutableLiveData<String> by lazy {
        MutableLiveData<String>("0")
    }

    private val currentCalculation: MutableLiveData<String?> by lazy {
        MutableLiveData<String?>(null)
    }

    fun getCurrentInput(): LiveData<String> {
        return Transformations.map(currentValue) {
            it.humanReadable()
        }
    }

    fun getCurrentInputConverted(): LiveData<String> {
        return Transformations.map(currentValueConverted) {
            it.humanReadable()
        }
    }

    fun getCalculationInput(): LiveData<String?> {
        return currentCalculation
    }

    fun getCurrencyFrom(): LiveData<String?> {
        return Transformations.map(currentCurrencyFrom) {
            getCurrencySymbol(it)
        }
    }

    fun getCurrencyTo(): LiveData<String?> {
        return Transformations.map(currentCurrencyTo) {
            getCurrencySymbol(it)
        }
    }

    fun addNumber(value: String) {
        // in calculation mode: add to upper row
        if (isCalculating()) {
            // last number is "0"
            if (currentCalculation.value!!.split(" ").last().trim() == "0") {
                // replace "0" with any other number
                if (value != "0")
                    currentCalculation.value = currentCalculation.value?.trim()?.dropLast(1)?.plus(value)
            } else
                currentCalculation.value += value
        }
        // add to lower row
        else {
            currentValue.value =
                if (currentValue.value == "0") value
                else currentValue.value.plus(value)
        }
        recalculate()
    }

    fun addDecimal() {
        // in calculation mode: add to upper row
        if (isCalculating()) {
            if (!currentCalculation.value!!.substringAfterLast(" ").contains(".")) {
                // if last char is not a number: add 0
                if (currentCalculation.value!!.trim().last().isDigit().not())
                    currentCalculation.value += "0"
                currentCalculation.value += "."
            }
        }
        // add to lower row
        else
            if (!currentValue.value!!.contains("."))
                currentValue.value += "."
    }

    fun delete() {
        // in calculation mode: delete from upper row
        if (isCalculating()) {
            currentCalculation.value = currentCalculation.value!!.trim().dropLast(1)
            // if last char is a number: trim!
            if (currentCalculation.value!!.trim().last().isDigit())
                currentCalculation.value = currentCalculation.value!!.trim()
            // if only a number is left without an operator, delete it completely
            if (!currentCalculation.value!!.contains("[\\u002B\\u2212\\u00D7\\u00F7]".toRegex()))
                currentCalculation.value = null
        }
        // delete from lower row
        else {
            if (currentValue.value!!.length > 1)
                currentValue.value = currentValue.value?.dropLast(1)
            else
                clear()
        }
        recalculate()
    }

    fun clear() {
        currentValue.value = "0"
        currentCalculation.value = null
        recalculate()
    }

    fun addition() {
        addOperator("\u002B")
    }

    fun subtraction() {
        addOperator("\u2212")
    }

    fun multiplication() {
        addOperator("\u00D7")
    }

    fun division() {
        addOperator("\u00F7")
    }

    private fun addOperator(operator: String) {
        // in calculation mode & already has operator at end position: exchange it!
        if (isCalculating() && currentCalculation.value!!.trim().last().isOperator())
            currentCalculation.value = currentCalculation.value?.trim()?.dropLast(1) + "$operator "
        // in calculation mode & last position is '.' -> remove it and add operator
        else if (isCalculating() && currentCalculation.value!!.trim().last() == '.')
            currentCalculation.value = currentCalculation.value?.trim()?.dropLast(1) + " $operator "
        else {
            // switch to calculation mode if necessary
            if (!isCalculating())
                currentCalculation.value = currentValue.value
            // add operator
            currentCalculation.value = currentCalculation.value?.trim().plus(" $operator ")
        }
    }

    /*
     * selected currencies =========================================================================
     */

    private fun Char.isOperator(): Boolean {
        return when (this) {
            '\u002B' -> true // +
            '\u2212' -> true // -
            '\u00D7' -> true // *
            '\u00F7' -> true // /
            else -> false
        }
    }

    private val currentCurrencyFrom: MutableLiveData<Rate> by lazy {
        MutableLiveData<Rate>()
    }
    private val currentCurrencyTo: MutableLiveData<Rate> by lazy {
        MutableLiveData<Rate>()
    }

    fun setCurrencyFrom(rate: Rate) {
        currentCurrencyFrom.value = rate
        recalculate()
        saveSelectedCurrencies()
        // hack: refresh currency symbol
        currentValue.value = currentValue.value
    }

    fun setCurrencyTo(rate: Rate) {
        currentCurrencyTo.value = rate
        recalculate()
        saveSelectedCurrencies()
    }

    /**
     * @return the name of the rate; e.g. "AUD", "EUR" or "USD"
     */
    fun getLastRateFrom(): String? {
        return Database.getInstance(getApplication()).getLastRateFrom()
    }

    /**
     * @return the name of the rate; e.g. "AUD", "EUR" or "USD"
     */
    fun getLastRateTo(): String? {
        return Database.getInstance(getApplication()).getLastRateTo()
    }

    /*
     * helpers =====================================================================================
     */

    /**
     * Saves currencyFrom and currencyTo to the database in order to restore them after restart
     */
    private fun saveSelectedCurrencies() {
        Database.getInstance(getApplication()).saveLastUsedRates(
            currentCurrencyFrom.value?.name,
            currentCurrencyTo.value?.name
        )
    }

    /**
     * Updates all numbers: currencyTo (if in calculation mode) and currencyFrom
     */
    private fun recalculate() {
        if (isCalculating()) {
            currentValue.value = currentCalculation.value!!
                .evaluateMathExpression()
                .scientificToNatural()
        }
        val rateFrom = currentCurrencyFrom.value?.value
        val rateTo = currentCurrencyTo.value?.value
        if (rateFrom != null && rateTo != null)
            currentValueConverted.value = currentValue.value
                ?.toDouble()
                ?.div(rateFrom)
                ?.times(rateTo)
                ?.toString()
                ?.scientificToNatural()
    }

}

/**
 * Turns e.g. "1 + 2 × 4" to "9"
 */
fun String.evaluateMathExpression(): String {
    // change nice operators to proper computer operators
    var s = this
        .replace(" ", "")
        .replace("\u2212", "-")
        .replace("\u00D7", "*")
        .replace("\u00F7", "/")
    // fill, if last character is an operator
    when {
        s.trim().last() == '/' -> s += '1'
        s.trim().last() == '*' -> s += '1'
        s.trim().last() == '+' -> s += '0'
        s.trim().last() == '-' -> s += '0'
        s.trim().last() == '.' -> s += '0'
    }
    // calculate
    return Expression(s).calculate().toString()
}

/**
 * Prevents scientific notation (123456789.123456789 instead of 1.23456789123456789E8).
 * Also rounds to maximal two decimal places
 */
fun String.scientificToNatural(): String {
    return if (this == "NaN")
        "0"
    else this
        .toBigDecimal() // prevents scientific
        .setScale(2, RoundingMode.HALF_EVEN) // round with bankers' rounding
        .toPlainString()
        .replace("0$".toRegex(), "")
        .replace("\\.0$".toRegex(), "")
}

/**
 * Changes "12345678.12" to "12 345 678.12"
 */
fun String.humanReadable(): String {

    fun String.groupNumbers(): String {
        val sb = StringBuilder(this.length * 2)
        for ((i, c) in this.reversed().withIndex()) {
            if (i % 3 == 0)
                sb.append(' ')
            sb.append(c)
        }
        return sb.toString().reversed().trim().replace("- ", "-")
    }

    return if (this.contains('.')) {
        val split = this.split('.')
        split[0].groupNumbers() + '.' + split[1]
    } else
        this.groupNumbers()
}

/**
 * Adds the proper currency symbol as prefix to the given String
 */
private fun getCurrencySymbol(rate: Rate?): String? {
    return when (rate?.name) {
        "AUD" -> "$"
        "BGN" -> "лв"
        "BRL" -> "R$"
        "CAD" -> "$"
        "CHF" -> "fr."
        "CNY" -> "¥"
        "CZK" -> "Kč"
        "DKK" -> "kr"
        "EUR" -> "€"
        "GBP" -> "£"
        "HKD" -> "$"
        "HRK" -> "kn"
        "HUF" -> "Ft"
        "IDR" -> "Rp"
        "ILS" -> "₪"
        "INR" -> "₹"
        "ISK" -> "kr"
        "JPY" -> "¥"
        "KRW" -> "₩"
        "MXN" -> "$"
        "MYR" -> "RM"
        "NOK" -> "kr"
        "NZD" -> "$"
        "PHP" -> "₱"
        "PLN" -> "zł"
        "RON" -> "lei"
        "RUB" -> "₽"
        "SEK" -> "kr"
        "SGD" -> "$"
        "THB" -> "฿"
        "TRY" -> "₺"
        "USD" -> "$"
        "ZAR" -> "R"
        else -> null
    }
}
