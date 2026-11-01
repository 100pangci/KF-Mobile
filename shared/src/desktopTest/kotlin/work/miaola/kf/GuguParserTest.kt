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
        <p>战胜NPC最大等级<span class='pull-right'> 32级</span></p>
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
        assertEquals("7 / 12", value.character.monthlyNpc)
        assertEquals("32级", value.character.monthlyNpcLevel)
        assertEquals("0 / 0", value.character.monthlyPvp)
        assertEquals(1, value.equipped.size)
        assertEquals(1, value.inventory.size)
        assertEquals("合成武器", value.equipped.single().name)
        assertEquals("12", value.equipped.single().level)
        assertEquals("优秀", value.equipped.single().quality)
        assertTrue(value.equipped.single().stats.any { it.contains("攻击") })
        assertTrue(value.equipped.single().actions.any { it.kind == GuguActionKind.UNEQUIP_ITEM })
        assertEquals("1320 分钟", value.cooldowns.single().remaining)
        assertTrue(value.readOnlyNote.contains("确认"))
    }

    @Test fun winRateMatrixPreservesCardOrderAndAttackDefenceRows() {
        val stat = """<html><body>
            <div class='panel panel-primary'><div class='panel-heading'>全站战绩统计</div>
            <div class='panel-body'>注意：合成说明。</div></div>
            <div class='panel panel-info'><div class='panel-heading'>卡片对战胜率统计（当前所在段位）</div>
            <div class='panel-body fyg_tc'>
            <div class='row'>
            <div class='col-md-2 fyg_f18 text-danger'>B段位</div>
            <div class='col-md-1 bg-primary'>舞</div>
            <div class='col-md-1 bg-primary'>默</div>
            </div>
            <div class='col-md-2 bg-danger'>舞 (进攻)</div>
            <div class='col-md-1'>58%</div><div class='col-md-1'>41%</div>
            <div class='col-md-2 bg-danger'>舞 (防守)</div>
            <div class='col-md-1'>42%</div><div class='col-md-1'>37%</div>
            <div class='col-md-12'>&nbsp;</div>
            <div class='col-md-2 bg-danger'>默 (进攻)</div>
            <div class='col-md-1'>63%</div><div class='col-md-1'>53%</div>
            <div class='col-md-2 bg-danger'>默 (防守)</div>
            <div class='col-md-1'>59%</div><div class='col-md-1'>47%</div>
            </div></div></body></html>"""
        val statSnapshot = assertNotNull(GuguProtocol().parseSnapshotForTest(index, "", null, null, null, statHtml = stat))
        val table = assertNotNull(statSnapshot.winRates)
        assertEquals("B", table.rank)
        assertEquals(listOf("舞", "默"), table.cards)
        assertEquals(listOf("舞", "默"), table.rows.map { it.card })
        assertEquals(listOf("58%", "41%"), table.rows[0].attack)
        assertEquals(listOf("42%", "37%"), table.rows[0].defense)
        assertTrue(table.note.contains("合成说明"))
    }

    @Test fun shopListingKeepsPriceAndDailyLimitWithoutAnyPurchaseRequest() {
        val shop = """<html><body>
            <button class='btn' type='button' onclick='zshopts(5)'><span class='fyg_f18'>[日限]10W贝壳</span><br>价格：<span class='text-primary'>1星沙</span></button>
            <button class='btn' type='button' onclick='zshopts(4)'><span class='fyg_f18'>100W贝壳</span><br>价格：<span class='text-primary'>50星沙</span></button>
            </body></html>"""
        val snapshot = assertNotNull(GuguProtocol().parseSnapshotForTest(index, "", null, null, null, shopHtml = shop))
        assertEquals(2, snapshot.shop.size)
        assertEquals("10W贝壳", snapshot.shop[0].name)
        assertEquals("1星沙", snapshot.shop[0].price)
        assertTrue(snapshot.shop[0].dailyLimit)
        assertFalse(snapshot.shop[1].dailyLimit)
    }

    @Test fun updateLogEntriesKeepDateAndLineBreaks() {
        val ulog = """<html><body>
            <div class='panel panel-primary'><div class='panel-heading'>更新日志</div>
            <div class='panel-body'>
            <div class='alert'><h3>2026/08/02</h3>第一行更新<br>第二行更新<br></div>
            <div class='alert'><h3>2025/01/24</h3>旧更新<br></div>
            </div></div></body></html>"""
        val snapshot = assertNotNull(GuguProtocol().parseSnapshotForTest(index, "", null, null, null, ulogHtml = ulog))
        assertEquals(listOf("2026/08/02", "2025/01/24"), snapshot.updateLog.map { it.date })
        assertEquals(listOf("第一行更新", "第二行更新"), snapshot.updateLog[0].lines)
    }

    @Test fun unknownOrMalformedGamePageDoesNotInventACharacter() {
        assertNull(GuguProtocol().parseSnapshotForTest("<html><body>登录</body></html>", "", null, null, null))
        assertNull(GuguProtocol().parseSnapshotForTest("<html></html>", "", null, null, null))
    }

    @Test fun consumableMenuControlsDoNotPretendToBeEquipment() {
        val inventory = """<button type='button' class='btn fyg_colpzbg' title="体能刺激药水 x5"
            onclick="omenu('4','3001')">5</button>
            <button type='button' class='btn fyg_colpzbg' title="宝石原石 x6"
            onclick="omenu(6)">6</button>"""
        val snapshot = assertNotNull(GuguProtocol().parseSnapshotForTest(index, "", null, inventory, null))
        assertEquals(listOf("体能刺激药水", "宝石原石"), snapshot.inventory.map { it.name })
        assertEquals(listOf("menu:4:3001", "menu:6:_"), snapshot.inventory.map { it.id })
        assertEquals(listOf(GuguActionKind.MENU_OPEN, GuguActionKind.MENU_OPEN),
            snapshot.inventory.map { it.actions.single().kind })
        assertTrue(snapshot.inventory.all { item ->
            item.actions.none { it.kind == GuguActionKind.EQUIP_ITEM || it.kind == GuguActionKind.DELETE_ITEM ||
                it.kind == GuguActionKind.PIERCE_ITEM }
        })
        assertEquals(0, snapshot.actions.count { it.kind == GuguActionKind.MENU_OPEN },
            "Inventory menus are attached to their item, not duplicated as page actions")
    }

    @Test fun flipGiftWishAndGemPagesExposeOnlyExactScriptControls() {
        val indexWithMenus = index.replace("</body>", """
            <button type='button' onclick="omenu('4','3001')">获取额外奖励</button>
            <button type='button' onclick="omenu(1)">锻造指定绿色以上装备</button>
            <button type='button' onclick="omenu(6)">选择需要提升的宝石</button>
            </body>""")
        val flip = """<button onclick="oclick(30, 9001)">翻开</button>
            <button onclick="oclickth(31,'9002','5',1)">升级</button>
            <button onclick="oclick(17,1)">支付星晶</button>
            <button onclick="oclick(9,2)">熔炼</button>
            <button onclick="oclick(30,3);alert(1)">坏控件</button>"""
        val gifts = """<button onclick="javascript:giftop(77)">礼物</button>"""
        val wish = """<button onclick="omenu('2','4001')">许愿</button>"""
        val gem = """<button onclick="oclick(34,9003)">提升宝石</button>"""
        val snapshot = assertNotNull(GuguProtocol().parseSnapshotForTest(indexWithMenus, "", null, null, null,
            flipHtml = flip, giftsHtml = gifts, wishHtml = wish, gemHtml = gem))
        val byId = snapshot.actions.associateBy { it.id }
        assertEquals(GuguActionKind.MENU_OPEN, byId["menu:4:3001"]?.kind)
        assertEquals(GuguActionKind.MENU_OPEN, byId["menu:1:_"]?.kind)
        assertEquals(GuguActionKind.MENU_OPEN, byId["menu:6:_"]?.kind)
        assertEquals(GuguActionKind.MENU_CHOICE, byId["choice:30:9001"]?.kind)
        assertEquals(GuguActionKind.MENU_CHOICE_EXTRA, byId["choicex:31:9002:5"]?.kind)
        assertEquals(GuguActionKind.GIFT_OPEN, byId["gift:77"]?.kind)
        assertEquals(GuguActionKind.MENU_OPEN, byId["menu:2:4001"]?.kind)
        assertEquals(GuguActionKind.MENU_CHOICE, byId["choice:34:9003"]?.kind)
        assertTrue(byId.values.filter { it.kind == GuguActionKind.MENU_CHOICE || it.kind == GuguActionKind.MENU_CHOICE_EXTRA }
            .all { it.destructive })
        assertTrue(byId["choice:30:9001"]?.detail.orEmpty().contains("翻牌"))
        assertTrue(byId["menu:2:4001"]?.detail.orEmpty().contains("许愿池"))
        assertNull(byId["choice:17:1"], "Real-money payment must never be exposed")
        assertNull(byId["choice:9:2"], "A command needing an extra yz field must not use the wrong wire")
        assertEquals(GuguActionKind.PIERCE_ITEM, byId["pierce:2"]?.kind,
            "A captured single-parameter c=9 control must still expose its yz input")
        assertTrue(byId["pierce:2"]?.inputLabel.orEmpty().isNotBlank())
        assertNull(byId["choice:30:3"], "A concatenated script payload must be rejected")
    }

    @Test fun attributeHaloAndMaxLevelControlsBecomeConfirmedActions() {
        val statInputs = listOf("sjll", "sjmj", "sjzl", "sjtp", "sjjs", "sjyz")
            .mapIndexed { index, id -> "<input id='$id' placeholder='属性 ${index + 1}' value='${index + 1}'>" }
            .joinToString("")
        val statAlloc = """<div>$statInputs<button onclick="updstat(77)">保存属性</button></div>"""
        val halo = """<div>
            <input type='checkbox' name='tfcheckbox' value='11' checked>
            <input type='checkbox' name='tfcheckbox' value='12'>
            <button onclick="halosave()">保存天赋</button>
            </div>"""
        val strengthen = """<button onclick="cmaxup(88)">满级提升</button><button onclick="ocuz(89)">使用卡片</button>"""
        val dice = """<button onclick="tzzz(10)">开始战斗</button><button onclick="tzzz(0)">坏控件</button>"""
        val snapshot = assertNotNull(GuguProtocol().parseSnapshotForTest(index, "", null, null, null,
            haloHtml = halo, statAllocHtml = statAlloc, strengthenHtml = strengthen, diceHtml = dice))
        val byId = snapshot.actions.associateBy { it.id }
        val stat = assertNotNull(byId["stat:77"])
        assertEquals(GuguActionKind.STAT_ALLOCATE, stat.kind)
        assertEquals(6, stat.fields.size)
        assertEquals(listOf("add01", "add02", "add03", "add04", "add05", "add06"),
            stat.fields.map { it.name })
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), stat.fields.map { it.defaultValue })
        val haloAction = assertNotNull(byId["halo:save"])
        assertEquals(GuguActionKind.HALO_SAVE, haloAction.kind)
        assertEquals("11", haloAction.fields.single().defaultValue)
        assertEquals(GuguActionKind.MAX_UP, byId["maxup:88"]?.kind)
        assertEquals(GuguActionKind.MENU_CHOICE, byId["choice:33:89"]?.kind)
        assertEquals(GuguActionKind.DICE_BATTLE, byId["dice:10"]?.kind)
        assertNull(byId["dice:0"], "A zero-round loop is not a valid single battle")
    }

    @Test fun renameOnlyAppearsWhenTheSiteEnablesItsOwnInput() {
        val enabled = index.replace("</body>", """
            <input id='mdnewname' type='text' value='旧名字'>
            <button onclick="upnewname()">确定修改</button>
            </body>""")
        val disabled = index.replace("</body>", """
            <input id='mdnewname' type='text' value='旧名字' disabled>
            <button onclick="upnewname()">确定修改</button>
            </body>""")
        val open = assertNotNull(GuguProtocol().parseSnapshotForTest(enabled, "", null, null, null))
        val rename = assertNotNull(open.actions.firstOrNull { it.id == "rename:6" })
        assertEquals(GuguActionKind.RENAME, rename.kind)
        assertEquals("旧名字", rename.fields.single().defaultValue)
        val closed = assertNotNull(GuguProtocol().parseSnapshotForTest(disabled, "", null, null, null))
        assertNull(closed.actions.firstOrNull { it.id == "rename:6" },
            "A disabled site input must not be turned into an action")
    }

    @Test fun menuAndActivityWiresMatchCapturedFormFields() {
        val protocol = GuguProtocol()
        fun action(id: String, kind: GuguActionKind, inputLabel: String = "") =
            GuguAction(id, kind, "操作", "确认？", inputLabel = inputLabel)
        assertEquals(GuguProtocol.Wire("fyg_menu.php", listOf("m" to "4", "id" to "3001")),
            protocol.wireForTest(action("menu:4:3001", GuguActionKind.MENU_OPEN)))
        assertEquals(GuguProtocol.Wire("fyg_menu.php", listOf("m" to "6", "id" to "undefined")),
            protocol.wireForTest(action("menu:6:_", GuguActionKind.MENU_OPEN)),
            "omenu(id) without a second argument must keep the site's own undefined id")
        assertEquals(GuguProtocol.Wire("fyg_click.php",
            listOf("c" to "30", "id" to "9001", "safeid" to "test1234")),
            protocol.wireForTest(action("choice:30:9001", GuguActionKind.MENU_CHOICE)))
        assertEquals(GuguProtocol.Wire("fyg_click.php",
            listOf("c" to "31", "id" to "9002", "id2" to "5", "safeid" to "test1234")),
            protocol.wireForTest(action("choicex:31:9002:5", GuguActionKind.MENU_CHOICE_EXTRA)))
        assertEquals(GuguProtocol.Wire("fyg_click.php",
            listOf("c" to "8", "id" to "77", "safeid" to "test1234")),
            protocol.wireForTest(action("gift:77", GuguActionKind.GIFT_OPEN)))
        assertEquals(GuguProtocol.Wire("fyg_click.php",
            listOf("c" to "9", "id" to "58", "yz" to "3", "safeid" to "test1234")),
            protocol.wireForTest(action("pierce:58", GuguActionKind.PIERCE_ITEM, "投入数量"), input = "3"))
        assertEquals(GuguProtocol.Wire("fyg_click.php",
            listOf("c" to "10", "num" to "88", "safeid" to "test1234")),
            protocol.wireForTest(action("maxup:88", GuguActionKind.MAX_UP)))
        assertEquals(GuguProtocol.Wire("fyg_click.php",
            listOf("c" to "2", "id" to "77",
                "add01" to "1", "add02" to "2", "add03" to "3",
                "add04" to "4", "add05" to "5", "add06" to "6",
                "safeid" to "test1234")),
            protocol.wireForTest(action("stat:77", GuguActionKind.STAT_ALLOCATE),
                values = (1..6).associate { "add0$it" to "$it" }))
        assertEquals(GuguProtocol.Wire("fyg_click.php",
            listOf("c" to "4", "arr" to "11,12", "safeid" to "test1234")),
            protocol.wireForTest(action("halo:save", GuguActionKind.HALO_SAVE),
                values = mapOf("arr" to "11,12")))
        assertEquals(GuguProtocol.Wire("fyg_click.php",
            listOf("c" to "6", "name" to "新名字", "safeid" to "test1234")),
            protocol.wireForTest(action("rename:6", GuguActionKind.RENAME),
                values = mapOf("name" to "新名字")))
        assertEquals(GuguProtocol.Wire("fyg_s_int.php", listOf("safeid" to "test1234")),
            protocol.wireForTest(action("dice:10", GuguActionKind.DICE_BATTLE)))
    }

    @Test fun menuResponseKeepsOnlyKnownSingleClickChoices() {
        val html = """<button onclick="oclick(30,9001)">使用</button>
            <button onclick="oclickth(31,9002,5)">强化</button>
            <button onclick="oclickth(9,9100,2)">熔炼</button>
            <button onclick="puton(55)">装备</button>
            <button onclick="stdel(56)">丢弃</button>
            <button onclick="stpick(57)">拾取</button>
            <button onclick="pirl(58)">熔炼数量</button>
            <button onclick="oclick(17,1)">支付</button>
            <button onclick="oclick(2,1)">属性</button>
            <button onclick="oclick(30,1);alert(1)">坏控件</button>
            <button onclick="giftop(77)">礼物</button>
            <a href="javascript:oclick(35,9004)">链接选项</a>
            <button onclick="omenu(6)">继续选择</button>"""
        val choices = GuguProtocol().parseMenuForTest(html)
        assertEquals(listOf("choice:30:9001", "choicex:31:9002:5",
            "choice:3:55", "choice:7:56", "choice:1:57", "pierce:58", "gift:77",
            "choice:35:9004", "menu:6:_"),
            choices.map { it.id })
        assertEquals(GuguActionKind.PIERCE_ITEM, choices.first { it.id == "pierce:58" }.kind)
        assertTrue(choices.first { it.id == "pierce:58" }.inputLabel.isNotBlank())
        assertTrue(choices.none { it.id.startsWith("choicex:9:") },
            "oclickth(9,...) carries an id2 the c=9 wire does not use")
        assertTrue(choices.all { it.kind != GuguActionKind.MENU_CHOICE || it.destructive })
    }

    @Test fun capturedMutationButtonsBecomeConfirmableActionsWithoutClientLoops() {
        val beach = """<button onclick="sttzok()">清理沙滩</button>
            <button onclick="gx_sxstok()">强制刷新</button>"""
        val inventory = """<button type='button' class='btn fyg_colpzbg' title="Lv.<span>12</span><br>合成武器"
            onclick="zbtip('123','2')">12</button>"""
        val cards = """<div onclick="xxcard(3000)"><span class="fyg_f24">舞</span>(出战中)</div>"""
        val shop = """<button onclick="zshopts(5)"><span class="fyg_f18">[日限]10W贝壳</span><br>价格：<span class="text-primary">1星沙</span></button>"""
        val pk = """<button onclick="jgjg('7')">挑战</button><button onclick="gx_sxds()">刷新</button>
            <button onclick="gox()">继续</button><script>function jgjg(xzid){} function gx_sxds(){}</script>"""
        val keys = """<button onclick="okll(1)">生成新的密钥</button><button onclick="okll(4)">切换为禁止</button>"""
        val snapshot = assertNotNull(GuguProtocol().parseSnapshotForTest(index, beach, null, inventory, cards,
            shopHtml = shop, pkHtml = pk, keysHtml = keys))
        assertTrue(snapshot.actions.any { it.kind == GuguActionKind.BEACH_CLEAR })
        assertTrue(snapshot.actions.any { it.kind == GuguActionKind.BEACH_REFRESH })
        assertTrue(snapshot.actions.any { it.id == "pk:7" && it.kind == GuguActionKind.PK_STEP })
        assertTrue(snapshot.actions.any { it.kind == GuguActionKind.PK_REFRESH })
        assertTrue(snapshot.actions.any { it.kind == GuguActionKind.PK_GO })
        assertTrue(snapshot.actions.any { it.kind == GuguActionKind.KEY_GENERATE })
        assertTrue(snapshot.actions.any { it.kind == GuguActionKind.KEY_SHOP_DENY })
        assertFalse(snapshot.actions.any { it.id.contains("99") }, "Never expose the web client's forward-to-end loop")
        assertEquals(listOf("装备", "丢弃", "熔炼"), snapshot.inventory.single().actions.map { it.label })
        assertEquals(GuguActionKind.CARD_EQUIP, snapshot.cards.single().actions.single().kind)
        assertEquals(GuguActionKind.SHOP_BUY, snapshot.shop.single().action?.kind)
    }
}
