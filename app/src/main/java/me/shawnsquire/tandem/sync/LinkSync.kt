package me.shawnsquire.tandem.sync

/** Keeps joiner-side linked rows in step with the owner's published alarms. Real implementation: L4. */
interface LinkSync {
    /** Watches PartnerRepository and keeps linked rows' copied fields in step with the owner's doc (I2: copied fields are only written here). */
    fun start()

    /** Applies one owner doc read (the pre-fire Source.SERVER read) to the linked rows, as a watch pass would. */
    suspend fun apply(owner: PartnerState)

    /** Unpair: removes every linked row. */
    suspend fun removeAll()
}
