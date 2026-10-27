package work.miaola.kf

import kotlin.test.*

class GuguParserTest {
    private val index = """<html><body>
        <button class='btn btn-lg btn-primary' onclick="window.location.href='fyg_index.php'">合成角色</button>
        <p class='fyg_lh40 with-padding bg-danger'>段位<span class='pull-right fyg_f24'> B</span></p>
        <p class='fyg_lh40 with-padding bg-success'>等级<span class='pull-right fyg_f18'> 166</span></p>
        <p class='with-padding hl-gray'>贝壳<span class='pull-right'> 6403177</span></p>
        <p>本月战斗<span class='pull-right'> 7 / 12</span></p>
        <p>对NPC战斗<span class='pull-right'> 7 / 12</span></p>
        <p>对玩家战斗<span class='pull-right'> 0 / 0</span></p>
        </body></html>"""
    private val item = """<button type='button' class='btn fyg_colpzbg' style='background-image:url(ys/icon/z/z1.gif);'
        data-tip-class='popover-success' data-content="<p>攻击 +12</p><p>暴击 +1%</p>"
        title="Lv.<span class='fyg_f18'>12</span><br>合成武器" onclick="zbtip('123','4')">&nbsp;<br>12</button>"""

    @Test fun readOnlySnapshotKeepsCharacterStatsItemsAndCooldownWithoutMixins() {
        val snapshot = GuguProtocol().parseSnapshotForTest(index,
            "<div>距离下次随机物品冲上沙滩还有 1320 分钟</div>", item, item, null)
        val value = assertNotNull(snapshot)
        assertEquals("合成角色", value.character.name)
        assertEquals("B", value.character.rank)
        assertEquals("166", value.character.level)
        assertEquals("6403177", value.character.shells)
        assertEquals("7 / 12", value.character.monthlyWins)
        assertEquals(1, value.equipped.size)
        assertEquals(1, value.inventory.size)
        assertEquals("合成武器", value.equipped.single().name)
        assertEquals("12", value.equipped.single().level)
        assertEquals("优秀", value.equipped.single().quality)
        assertTrue(value.equipped.single().stats.any { it.contains("攻击") })
        assertEquals("1320 分钟", value.cooldowns.single().remaining)
        assertTrue(value.readOnlyNote.contains("只读"))
    }

    @Test fun unknownOrMalformedGamePageDoesNotInventACharacter() {
        assertNull(GuguProtocol().parseSnapshotForTest("<html><body>登录</body></html>", "", null, null, null))
        assertNull(GuguProtocol().parseSnapshotForTest("<html></html>", "", null, null, null))
    }
}
