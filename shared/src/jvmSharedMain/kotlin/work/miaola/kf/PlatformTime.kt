package work.miaola.kf

import java.time.LocalDate

actual fun localDayKey(): String = LocalDate.now().toString()
