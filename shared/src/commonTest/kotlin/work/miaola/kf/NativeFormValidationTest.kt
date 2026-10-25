package work.miaola.kf

import kotlin.test.*

class NativeFormValidationTest {
    private val submit = SiteSubmit("保存", "save", "1")
    private fun form(field: SiteField) = SiteForm("f", "测试", "https://kf.miaola.work/profile.php", "https://kf.miaola.work/profile.php", "POST", listOf(FormValue("verify", "synthetic")), listOf(field), listOf(submit))
    @Test fun requiredAndLengthLimitsAreCheckedBeforeSubmission() {
        val field = SiteField("name", "name", "用户名", SiteFieldKind.TEXT, required = true, maxLength = 3)
        assertFails { buildSiteFormValues(form(field), mapOf("name" to listOf("")), submit) }
        assertFails { buildSiteFormValues(form(field), mapOf("name" to listOf("1234")), submit) }
    }
    @Test fun unrecognizedOptionsAreRejected() {
        val field = SiteField("type", "type", "分类", SiteFieldKind.SELECT, options = listOf(FormOption("1", "一")))
        assertFails { buildSiteFormValues(form(field), mapOf("type" to listOf("2")), submit) }
    }
    @Test fun uncheckedCheckboxIsOmittedAndOnlyClickedSubmitIsIncluded() {
        val field = SiteField("x", "delid[]", "选择", SiteFieldKind.CHECKBOX, options = listOf(FormOption("1", "一")))
        val result = buildSiteFormValues(form(field), emptyMap(), submit)
        assertEquals(listOf("verify", "save"), result.map { it.name })
        assertFalse(result.toString().contains("synthetic"))
    }
    @Test fun financialFormsRejectEmptyOrNegativeAmounts() {
        val recipient = SiteField("recipient", "pwuser", "收款人", SiteFieldKind.TEXT, listOf("测试会员"))
        val amount = SiteField("amount", "to_money", "金额", SiteFieldKind.TEXT)
        val bank = form(amount).copy(actionUrl = "https://kf.miaola.work/hack.php?H_name=bank", fields = listOf(recipient, amount))
        assertFails { buildSiteFormValues(bank, mapOf("amount" to listOf("-1")), submit) }
        assertFails { buildSiteFormValues(bank, mapOf("amount" to listOf("NaN")), submit) }
        assertTrue(buildSiteFormValues(bank, mapOf("amount" to listOf("1.5")), submit).any { it.name == "to_money" && it.value == "1.5" })
    }
}
