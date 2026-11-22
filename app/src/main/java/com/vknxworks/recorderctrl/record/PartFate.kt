package com.vknxworks.recorderctrl.record

/** What happens to a recording's `.part` file when recording ends. */
internal enum class PartFate {
    /** You chose not to keep it. */
    DISCARD,

    /** Stopped cleanly: save it. */
    SAVE,

    /** The recorder couldn't stop cleanly: keep it if it has readable audio ("never lose a recording"). */
    SALVAGE;

    companion object {
        fun of(save: Boolean, stopped: Boolean): PartFate = when {
            !save -> DISCARD
            stopped -> SAVE
            else -> SALVAGE
        }
    }
}
