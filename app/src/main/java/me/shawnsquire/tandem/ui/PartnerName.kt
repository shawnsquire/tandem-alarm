package me.shawnsquire.tandem.ui

import me.shawnsquire.tandem.sync.PairingStatus
import me.shawnsquire.tandem.sync.PartnerState
import me.shawnsquire.tandem.ui.partner.FALLBACK_NAME

/** The name to show for the partner: the pairing's own, else their published display name, else a fallback. */
internal fun resolvePartnerName(status: PairingStatus, partner: PartnerState?): String =
    (status as? PairingStatus.Paired)?.partnerName?.takeIf { it.isNotBlank() }
        ?: partner?.displayName?.takeIf { it.isNotBlank() }
        ?: FALLBACK_NAME
