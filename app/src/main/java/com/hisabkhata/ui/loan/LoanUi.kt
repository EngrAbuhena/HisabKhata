package com.hisabkhata.ui.loan

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.hisabkhata.R
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.domain.MoneyFormatter
import java.math.BigInteger

@Composable
internal fun loanTaka(amount: BigInteger): String = stringResource(
    if (amount.signum() < 0) R.string.negative_taka_amount else R.string.taka_amount,
    MoneyFormatter.formatAbsolute(amount)
)

@Composable
internal fun personTypeLabel(type: PersonType): String = stringResource(
    if (type == PersonType.LENT) R.string.person_type_lent else R.string.person_type_borrowed
)

/** A positive due is outstanding (red); exactly zero is settled (green). */
@Composable
internal fun dueColor(due: BigInteger): Color = when {
    due.signum() > 0 -> MaterialTheme.colorScheme.error
    due.signum() == 0 -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}
