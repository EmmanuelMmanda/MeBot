package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.UnitPriceComparison
import mebot.nguli.com.ui.AppCard
import mebot.nguli.com.ui.ScreenContainer
import mebot.nguli.com.ui.ScreenTitle

/** No network, account, permissions or personal data required. */
@Composable
fun UnitPriceScreen() {
    var priceA by rememberSaveable { mutableStateOf("") }
    var amountA by rememberSaveable { mutableStateOf("") }
    var priceB by rememberSaveable { mutableStateOf("") }
    var amountB by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val result = remember(priceA, amountA, priceB, amountB) { UnitPriceComparison.compare(priceA, amountA, priceB, amountB) }
    ScreenContainer {
        ScreenTitle(stringResource(R.string.unit_price_title), stringResource(R.string.unit_price_body))
        Text(stringResource(R.string.unit_price_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(unit, { unit = it.take(30) }, label = { Text(stringResource(R.string.unit_price_unit)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("unit-price-unit"))
        OutlinedTextField(currency, { currency = it.take(12) }, label = { Text(stringResource(R.string.unit_price_currency)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("unit-price-currency"))
        PriceOption(stringResource(R.string.unit_price_first), "a", priceA, amountA,
            { priceA = it; submitted = false }, { amountA = it; submitted = false })
        PriceOption(stringResource(R.string.unit_price_second), "b", priceB, amountB,
            { priceB = it; submitted = false }, { amountB = it; submitted = false })
        Button(onClick = { submitted = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("unit-price-compare")) {
            Icon(Icons.Outlined.CompareArrows, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.unit_price_calculate))
        }
        if (submitted && result == null) Text(stringResource(R.string.unit_price_invalid), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("unit-price-error"))
        if (submitted && result != null) AppCard(modifier = Modifier.testTag("unit-price-result")) {
            val a = stringResource(R.string.unit_price_first)
            val b = stringResource(R.string.unit_price_second)
            val unitLabel = unit.ifBlank { stringResource(R.string.unit_price_default_unit) }
            Text(if (result.cheaper == 0) stringResource(R.string.unit_price_equal)
                else stringResource(R.string.unit_price_result, if (result.cheaper < 0) a else b), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.unit_price_per, a, UnitPriceComparison.display(result.firstPerUnit), currency, unitLabel))
            Text(stringResource(R.string.unit_price_per, b, UnitPriceComparison.display(result.secondPerUnit), currency, unitLabel))
            if (result.cheaper != 0) Text(stringResource(R.string.unit_price_saving, UnitPriceComparison.display(result.savingPercent)),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
        TextButton(onClick = { priceA = ""; amountA = ""; priceB = ""; amountB = ""; unit = ""; currency = ""; submitted = false },
            modifier = Modifier.fillMaxWidth().testTag("unit-price-reset")) { Text(stringResource(R.string.unit_price_reset)) }
    }
}

@Composable
private fun PriceOption(title: String, tag: String, price: String, amount: String, onPrice: (String) -> Unit, onAmount: (String) -> Unit) {
    AppCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(price, { if (it.length <= 20) onPrice(it) }, label = { Text(stringResource(R.string.unit_price_cost)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("unit-price-$tag-cost"))
        OutlinedTextField(amount, { if (it.length <= 20) onAmount(it) }, label = { Text(stringResource(R.string.unit_price_amount)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("unit-price-$tag-amount"))
    }
}
