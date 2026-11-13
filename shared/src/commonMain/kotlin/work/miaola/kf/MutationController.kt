package work.miaola.kf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun AppController.confirmGuguAction() {
        val action = pendingGuguAction ?: return
        if (writeBusy) return
        if (action.inputLabel.isNotBlank() && !guguActionInput.trim().matches(Regex("[0-9]{1,9}"))) {
            guguError = "请输入非负整数后再提交"
            return
        }
        action.fields.forEach { field ->
            val value = guguActionValues[field.name].orEmpty().trim()
            if (field.numeric && !value.matches(Regex("[0-9]{1,9}"))) {
                guguError = "${field.label}必须是非负整数"
                return
            }
        }
        if (action.kind == GuguActionKind.RENAME &&
            guguActionValues["name"].orEmpty().trim().isEmpty()) {
            guguError = "角色名不能为空"
            return
        }
        guguActionBusy = true
        guguError = null
        val expected = origin
        scope.launch {
            try {
                when (val result = service.guguAction(GuguActionRequest(action, guguActionInput.trim(),
                    guguActionValues.mapValues { it.value.trim() }))) {
                    is GuguActionResult.Success -> if (origin == expected) {
                        result.snapshot?.let { gugu = it }
                        toast = result.message
                    }
                    is GuguActionResult.Rejected -> if (origin == expected) guguError = result.message
                    is GuguActionResult.Uncertain -> if (origin == expected) {
                        result.snapshot?.let { gugu = it }
                        guguError = result.message
                    }
                    is GuguActionResult.Choices -> if (origin == expected) {
                        result.snapshot?.let { gugu = it }
                        if (result.menu.actions.isEmpty()) guguError = "服务器没有返回可执行菜单选项"
                        else {
                            guguMenu = result.menu
                            guguError = null
                        }
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (origin == expected) guguError = "操作结果无法确认；客户端不会自动重试，请重新读取后核对"
            } finally {
                if (origin == expected) {
                    guguActionBusy = false
                    pendingGuguAction = null
                    guguActionInput = ""
                    guguActionValues = emptyMap()
                }
            }
        }
    }

fun AppController.cancelGuguAction() {
        if (guguActionBusy) return
        pendingGuguAction = null
        guguActionInput = ""
        guguActionValues = emptyMap()
    }

fun AppController.updateGuguActionValue(name: String, value: String) {
        val action = pendingGuguAction ?: return
        if (guguActionBusy) return
        val field = action.fields.firstOrNull { it.name == name } ?: return
        guguActionValues = guguActionValues + (name to value.take(field.maxLength))
    }

fun AppController.updateGuguActionInput(value: String) {
        if (pendingGuguAction == null || guguActionBusy) return
        guguActionInput = value.take(20)
    }

fun AppController.cancelGuguMenu() {
        if (guguActionBusy) return
        guguMenu = null
        guguError = null
    }

    /** Revalidates one exact option from the last menu before opening its confirmation. */
fun AppController.chooseGuguMenu(action: GuguAction) {
        val menu = guguMenu ?: return
        if (menu.actions.none { it.id == action.id && it.kind == action.kind }) {
            guguMenu = null
            guguError = "菜单选项已过期，请重新打开"
            return
        }
        guguMenu = null
        guguError = null
        requestGuguAction(action)
    }

    /** Asks for confirmation before any game mutation; nothing is sent yet. */
fun AppController.requestGuguAction(action: GuguAction) {
        if (guguActionBusy || pendingGuguAction != null || guguBusy || guguMenu != null) return
        guguActionInput = action.inputDefault
        guguActionValues = action.fields.associate { it.name to it.defaultValue }
        guguError = null
        pendingGuguAction = action
    }

fun AppController.loadGugu(force: Boolean = false) {
        if (guguBusy || guguActionBusy || (!force && gugu != null)) return
        guguBusy = true
        guguError = null
        guguMenu = null
        val expected = origin
        scope.launch {
            try {
                val snapshot = service.gugu()
                if (origin == expected) {
                    if (snapshot == null) guguError = "咕咕镇登录入口暂不可用，请先在网页版确认游戏入口。"
                    else gugu = snapshot
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (origin == expected) guguError = "咕咕镇暂时无法读取；游戏数据不会在本机缓存。"
            } finally { if (origin == expected) guguBusy = false }
        }
    }

    /** Downloads the selected mailbox entries through the site's action=down form. */
fun AppController.exportMessages(form: SiteForm, selectedFieldIds: Set<String>) {
        if (busy || writeBusy) return
        val selectionFields = form.fields.filter { it.name == "delid[]" }
        val selected = selectionFields.filter { it.id in selectedFieldIds }
            .flatMap { field -> field.options.map { option -> option.value } }
            .filter { it.matches(Regex("[0-9]{1,20}")) }
        if (selectionFields.isNotEmpty() && selected.isEmpty()) {
            error = "请选择要导出的消息"
            return
        }
        val values = form.hidden.filterNot { it.name == "action" }.toMutableList()
        selected.distinct().forEach { values.add(FormValue("delid[]", it)) }
        values.add(FormValue("action", "down"))
        messageExportBusy = true
        error = null
        val expected = origin
        val client = service
        scope.launch {
            try {
                when (val result = client.exportMessages(MessageExportRequest(form.sourceUrl, form, values))) {
                    is MessageExportResult.Success -> if (origin == expected) {
                        fileSaveRequest = NativeFileSave(result.fileName, result.bytes, result.mimeType)
                        toast = "消息已导出，请选择保存位置"
                    }
                    is MessageExportResult.Rejected -> if (origin == expected) error = result.message
                    is MessageExportResult.Uncertain -> if (origin == expected) error = result.message
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (origin == expected) error = "消息导出结果无法确认；请先核对，客户端不会自动重试"
            } finally { if (origin == expected) messageExportBusy = false }
        }
    }

fun AppController.confirmAvatarUpload() {
        val file = pendingAvatar ?: return
        if (busy || writeBusy || pendingBatch != null || pendingSiteDestination != null || pendingSiteSubmission != null || pendingReward != null || pendingPurchase != null || pendingMessageConfirm) return
        val form = page?.nativePage?.forms?.firstOrNull { form ->
            form.sourceUrl.substringBefore('?').endsWith("profile.php") &&
                form.sourceUrl.contains("action=modify") && form.multipart &&
                form.hidden.any { it.name == "facetype" }
        }
        if (form == null || avatarBusy) { error = "请从修改资料页面重新选择头像"; pendingAvatar = null; return }
        avatarBusy = true
        error = null
        val expected = origin
        val client = service
        scope.launch {
            try {
                when (val result = client.uploadAvatar(AvatarUploadRequest(form, file, 140, 200))) {
                    is AvatarUploadResult.Uploaded -> {
                        pendingAvatar = null
                        val profileUrl = home?.menu?.firstOrNull { it.url.contains("profile.php?action=show") }?.url ?: page?.url
                        if (profileUrl == null) {
                            error = "头像文件已上传，但无法读取资料页确认最终显示；请刷新资料后核对，不要自动重传。"
                            return@launch
                        }
                        val fresh = try { client.fetch(profileUrl) } catch (e: CancellationException) { throw e
                        } catch (_: Exception) { null }
                        if (origin != expected) return@launch
                        val shownAvatar = fresh?.nativePage?.profile?.avatar.orEmpty().substringBefore('?')
                        val uploadedAvatar = result.avatarUrl.substringBefore('?')
                        if (fresh != null) {
                            applyFreshPage(fresh)
                            accountProfile = fresh.nativePage?.profile ?: accountProfile
                            accountAvatar = fresh.nativePage?.profile?.avatar ?: accountAvatar
                        }
                        if (shownAvatar.isNotBlank() && shownAvatar == uploadedAvatar) {
                            toast = "新头像已在资料页显示"
                        } else {
                            error = "头像文件已上传，但资料页尚未显示对应新头像；资料保存状态未确认，请刷新核对后再决定下一步。"
                        }
                    }
                    is AvatarUploadResult.Rejected -> { pendingAvatar = null; error = result.message }
                    is AvatarUploadResult.Uncertain -> { pendingAvatar = null; error = result.message }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { pendingAvatar = null; error = "头像上传结果无法确认，请刷新资料页核对；不会自动重试" }
            finally { if (origin == expected) avatarBusy = false }
        }
    }

fun AppController.confirmReward() {
        val action = pendingReward ?: return
        if (busy || writeBusy || pendingBatch != null || pendingSiteDestination != null || pendingSiteSubmission != null || pendingAvatar != null || pendingPurchase != null) return
        pendingReward = null
        rewardBusy = true
        error = null
        val expected = origin
        scope.launch {
            try {
                when (val result = service.executeReward(action)) {
                    is RewardExecutionResult.Confirmed -> toast = result.message
                    is RewardExecutionResult.Rejected -> error = result.message
                    is RewardExecutionResult.Uncertain -> error = result.message
                }
                if (origin == expected && action.readbackUrl.startsWith("$origin/")) {
                    // Read-only refresh gives the user a chance to verify the
                    // status; a failure here does not rewrite the receipt.
                    runCatching { service.fetch(action.readbackUrl) }.getOrNull()?.let { fresh ->
                        if (origin == expected) {
                            applyFreshPage(fresh)
                        }
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { error = "奖励操作结果无法确认，请刷新奖励页面核对；不会自动重试" }
            finally { if (origin == expected) rewardBusy = false }
        }
    }

fun AppController.requestReward(action: RewardAction) {
        if (rewardBusy || busy || siteBusy || purchaseBusy || batchBusy || dailyBusy || avatarBusy || pendingBatch != null || pendingSiteDestination != null || pendingSiteSubmission != null || pendingAvatar != null || pendingPurchase != null || pendingMessageConfirm) return
        if (resolve(action.sourceUrl).effect != SiteEffect.READ) { error = "奖励来源页面不可信"; return }
        pendingReward = action
    }

internal fun AppController.clearSelectionRecovery(source: String) {
        managementRecoveries = managementRecoveries.filterNot { isSamePageList(it, source) }.toSet()
        recoveryState = recoveryState.copy(
            pendingManagement = recoveryState.pendingManagement.filterNot { isSamePageList(it, source) },
        )
        scheduleRecoveryPersist()
    }

internal fun AppController.executeBatch(requests: List<BatchPageRequest>) {
        batchBusy = true
        batchResult = null
        error = null
        val expectedOrigin = origin
        val client = service
        val transactionRecovery = recovery
        transactionRecovery.savePendingBatch(PendingBatch(sourcePages = requests.map { it.form.sourceUrl },
            itemIds = requests.flatMap { request -> request.items.map { it.identity } }, createdAt = currentEpochMillis()))
        cancelRequest()
        batchJob = scope.launch {
            val results = mutableListOf<BatchItemResult>()
            val uncertainSources = linkedSetOf<String>()
            var stop = false
            var currentIndex = -1
            var interrupted = false
            try {
                for ((index, request) in requests.withIndex()) {
                    currentIndex = index
                    if (origin != expectedOrigin) break
                    if (stop) {
                        results += request.items.map { BatchItemResult(it, OperationOutcome.NOT_ATTEMPTED, "前一项结果无法确认，本次不再提交") }
                        continue
                    }
                    // A transport that ignores cancellation must not append a
                    // pre-write snapshot over a post-write list.
                    timelines.values.distinct().filter { isSamePageList(it.first.url, request.form.sourceUrl) }.forEach { state ->
                        state.requestId++; state.loading = false; state.refreshing = false
                    }
                    val response = try {
                        client.submitSite(SiteSubmission(request.form, request.values, emptyList(), request.submit))
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) {
                        SiteActionResult.Uncertain("请求中断，无法确认服务器是否执行")
                    }
                    when (response) {
                        is SiteActionResult.Rejected -> {
                            results += request.items.map { BatchItemResult(it, OperationOutcome.REJECTED, response.message) }
                            clearSelectionRecovery(request.form.sourceUrl)
                        }
                        is SiteActionResult.Uncertain -> {
                            invalidateManagement(request.form.sourceUrl)
                            uncertainSources.add(request.form.sourceUrl)
                            results += request.items.map { BatchItemResult(it, OperationOutcome.UNCERTAIN, response.message) }
                            stop = true
                        }
                        is SiteActionResult.Response -> {
                            if (response.page.authStatus == AuthStatus.LOGIN_REQUIRED) {
                                invalidateManagement(request.form.sourceUrl)
                                uncertainSources.add(request.form.sourceUrl)
                                results += request.items.map { BatchItemResult(it, OperationOutcome.UNCERTAIN, "登录状态已失效，无法确认提交结果") }
                                stop = true
                            } else {
                                invalidateManagement(request.form.sourceUrl)
                                // Prefer a response that is already a verified
                                // copy of the source list. Otherwise GET exactly
                                // the source page; never infer completion from
                                // the mutation response page or HTTP status.
                                val readback = if (validManagementReadback(request.form.sourceUrl, response.page)) {
                                    response.page
                                } else try {
                                    client.fetch(request.form.sourceUrl)
                                } catch (e: CancellationException) { throw e
                                } catch (_: Exception) { null }
                                if (readback == null || !validManagementReadback(request.form.sourceUrl, readback)) {
                                    uncertainSources.add(request.form.sourceUrl)
                                    results += request.items.map { BatchItemResult(it, OperationOutcome.UNCERTAIN, "操作已返回，但来源列表未能安全读回") }
                                    stop = true
                                    continue
                                }
                                if (pageNumber(readback.url) != pageNumber(request.form.sourceUrl)) {
                                    uncertainSources.add(request.form.sourceUrl)
                                    results += request.items.map { BatchItemResult(it, OperationOutcome.UNCERTAIN,
                                        "删除后分页边界发生变化，无法仅凭当前页确认项目状态") }
                                    stop = true
                                    continue
                                }
                                val remaining = readback.nativePage?.forms.orEmpty()
                                    .filter { isSamePageList(it.sourceUrl, request.form.sourceUrl) }
                                    .flatMap { form -> form.fields.filter { field -> field.name == "delid[]" } }
                                    .flatMap { field -> field.options.map { option -> option.value } }.toSet()
                                request.items.forEach { item ->
                                    val stillThere = item.stableId in remaining
                                    if (!request.verifyAbsent) {
                                        uncertainSources.add(request.form.sourceUrl)
                                        results += BatchItemResult(item, OperationOutcome.UNCERTAIN,
                                            "请求已提交并读回列表；目标状态需人工核对")
                                    } else {
                                        val success = !stillThere
                                        results += BatchItemResult(item,
                                            if (success) OperationOutcome.SUCCESS else OperationOutcome.REJECTED,
                                            if (success) "" else "项目仍在列表中，可能未执行")
                                    }
                                }
                                if (request.verifyAbsent) {
                                    clearSelectionRecovery(request.form.sourceUrl)
                                    transactionRecovery.clearPendingBatchSource(request.form.sourceUrl)
                                }
                                val freshState = timelineFor(readback)
                                freshState.reset(readback)
                                rememberCachedPage(readback, request.form.sourceUrl)
                                if (page?.url?.let { isSamePageList(it, request.form.sourceUrl) } == true) page = readback
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                interrupted = true
                requests.getOrNull(currentIndex)?.let { request ->
                    invalidateManagement(request.form.sourceUrl)
                    uncertainSources.add(request.form.sourceUrl)
                    val known = results.map { it.item.identity }.toSet()
                    request.items.filterNot { it.identity in known }.forEach { item ->
                        results += BatchItemResult(item, OperationOutcome.UNCERTAIN, "操作中断，服务器可能已执行；请先核对来源列表")
                    }
                }
                requests.drop((currentIndex + 1).coerceAtLeast(0)).forEach { request ->
                    request.items.forEach { item ->
                        results += BatchItemResult(item, OperationOutcome.NOT_ATTEMPTED, "前一项中断，本项未自动提交")
                    }
                }
                throw cancelled
            } catch (_: Exception) {
                interrupted = true
                requests.getOrNull(currentIndex)?.let { request ->
                    invalidateManagement(request.form.sourceUrl)
                    uncertainSources.add(request.form.sourceUrl)
                    val known = results.map { it.item.identity }.toSet()
                    request.items.filterNot { it.identity in known }.forEach { item ->
                        results += BatchItemResult(item, OperationOutcome.UNCERTAIN, "处理回执时发生错误，服务器可能已执行")
                    }
                }
                requests.drop((currentIndex + 1).coerceAtLeast(0)).forEach { request ->
                    request.items.forEach { item ->
                        results += BatchItemResult(item, OperationOutcome.NOT_ATTEMPTED, "前一项无法确认，本项未自动提交")
                    }
                }
            } finally {
                if (origin == expectedOrigin) {
                    if (uncertainSources.isNotEmpty()) {
                        transactionRecovery.savePendingBatch(PendingBatch(
                            sourcePages = uncertainSources.toList(),
                            itemIds = results.filter { it.outcome == OperationOutcome.UNCERTAIN }.map { it.item.identity },
                            createdAt = currentEpochMillis(),
                        ))
                    } else if (!interrupted) {
                        transactionRecovery.clearPendingBatch()
                    }
                    if (results.isNotEmpty()) batchResult = classifyBatchResult(results)
                    batchBusy = false
                    if (batchResult != null) toast = batchResult?.summary()
                }
            }
        }
    }

fun AppController.confirmBatch() {
        val requests = pendingBatch ?: return
        if (busy || writeBusy ||
            pendingSiteDestination != null || pendingSiteSubmission != null || pendingReward != null || pendingAvatar != null || pendingPurchase != null) return
        pendingBatch = null
        executeBatch(requests)
    }

fun AppController.requestBatchSiteSubmit(requests: List<BatchPageRequest>) {
        if (busy || siteBusy || purchaseBusy || batchBusy || dailyBusy || rewardBusy || avatarBusy || pendingBatch != null || pendingSiteDestination != null || pendingSiteSubmission != null || pendingReward != null || pendingAvatar != null || pendingPurchase != null) return
        if (requests.isEmpty() || requests.size > 8) { error = "批量操作的项目数量无效"; return }
        if (requests.map { it.form.sourceUrl }.distinct().size != requests.size) {
            error = "同一来源页只能提交一次，请重新选择"; return
        }
        if (requests.drop(1).any { !isSamePageList(requests.first().form.sourceUrl, it.form.sourceUrl) }) {
            error = "所选项目不属于同一个列表，未提交"; return
        }
        val identities = requests.flatMap { request -> request.items.map { it.identity } }
        if (identities.isEmpty() || identities.distinct().size != identities.size) {
            error = "批量项目重复，请重新选择"; return
        }
        if (requests.any { managementRecoveryFor(it.form.sourceUrl) != null }) {
            error = "有来源页待核对，请先重新加载列表"; return
        }
        if (requests.any { it.items.isEmpty() }) { error = "没有可提交的项目"; return }
        pendingBatch = requests
    }

internal fun AppController.startSiteAction(form: SiteSubmission?, link: SiteDestination?) {
        if (writeBusy || pendingBatch != null || pendingReward != null || pendingAvatar != null || pendingPurchase != null) return
        cancelRequest()
        if (link?.effect == SiteEffect.EXTERNAL) { pendingSiteDestination = null; browser(origin, link.url); return }
        if (link?.effect == SiteEffect.DOWNLOAD) {
            pendingSiteDestination = null
            downloadRequest = NativeDownloadRequest(link.url, link.url.substringBefore('?').substringAfterLast('/').ifBlank { "attachment" })
            return
        }
        siteBusy = true; error = null
        val direct = if (form != null) !SiteActionRisk.needsConfirmation(form) else link?.let { !SiteActionRisk.needsConfirmation(it) } == true
        val actionPage = page
        val messageFlow = form?.form?.sourceUrl?.substringBefore('?')?.endsWith("message.php") == true ||
            link?.url?.substringBefore('?')?.endsWith("message.php") == true
        val favoritesFlow = form?.form?.sourceUrl?.let { it.substringBefore('?').endsWith("profile.php") && it.contains("action=favor") } == true
        val messageSource = if (messageFlow && (form != null || Regex("[?&]action=(del|unread)(?:&|$)").containsMatchIn(link?.url.orEmpty()))) {
            val candidate = form?.form?.sourceUrl ?: messageListUrl
            val requestedBox = Regex("[?&]towhere=(receivebox|sendbox|scout)(?:&|$)").find(link?.url.orEmpty())?.groupValues?.get(1)
            when {
                form?.form?.fields?.any { it.name == "banidinfo" } == true -> form.form.sourceUrl
                candidate != null && resolve(candidate).effect == SiteEffect.READ &&
                    (requestedBox == null || candidate.contains("action=$requestedBox") || requestedBox == "receivebox" && !candidate.contains("action=")) -> candidate
                requestedBox != null -> "$origin/message.php?action=$requestedBox"
                else -> null
            }
        } else null
        val managedReturn = when {
            favoritesFlow -> actionPage?.url?.takeIf { isSamePageList(it, form.form.sourceUrl) } ?: form.form.sourceUrl
            messageFlow -> messageSource
            else -> null
        }
        if (managedReturn != null && managementRecoveryFor(managedReturn) != null) {
            pendingSiteSubmission = null; pendingSiteDestination = null; siteBusy = false
            error = "请先重新加载并核对列表，再选择项目"
            return
        }
        val managedRecoveryStore = recovery
        if (managedReturn != null) {
            managedRecoveryStore.savePendingBatch(PendingBatch(
                sourcePages = listOf(managedReturn),
                itemIds = form?.values?.filter { it.name == "delid[]" }?.map { "$managedReturn#${it.value}" }.orEmpty(),
                createdAt = currentEpochMillis(),
            ))
        }
        // Stop old appends before the write; even a cancellation-ignoring
        // transport must not append a pre-write snapshot after the response.
        if (managedReturn != null) timelines.values.distinct().filter { isSamePageList(it.first.url, managedReturn) }.forEach { state ->
            state.requestId++; state.loading = false; state.refreshing = false
        }
        siteJob = scope.launch {
            try {
                val response = if (form != null) service.submitSite(form) else service.executeSite(link!!)
                if (managedReturn != null && response !is SiteActionResult.Rejected) invalidateManagement(managedReturn)
                val result = if (managedReturn != null && response is SiteActionResult.Response && response.page.authStatus != AuthStatus.LOGIN_REQUIRED) {
                    // Read back the source list, not a legacy receipt/action URL.
                    val fresh = try { service.fetch(managedReturn) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (fresh != null && fresh.authStatus == AuthStatus.LOGIN_REQUIRED) SiteActionResult.Response(fresh)
                    else if (fresh != null && validManagementReadback(managedReturn, fresh))
                        SiteActionResult.Response(fresh, response.uncertainReceipt)
                    else {
                        error = "操作已返回，但列表未能重新加载；请先核对，勿重复提交"
                        return@launch
                    }
                } else response
                when (result) {
                    is SiteActionResult.Response -> {
                        val stillHere = page?.url == actionPage?.url
                        val managedFresh = managedReturn != null && isSamePageList(managedReturn, result.page.url)
                        if (managedFresh) {
                            if (result.uncertainReceipt == null) {
                                clearSelectionRecovery(managedReturn)
                                managedRecoveryStore.clearPendingBatchSource(managedReturn)
                            } else {
                                // A structurally valid list is useful to inspect,
                                // but does not by itself prove a multi-item change.
                                invalidateManagement(managedReturn)
                            }
                            managementRevision++
                            // Deleting/moving changes page boundaries. Old tails,
                            // selection contexts and in-flight appends are invalid.
                            val affected = timelines.values.distinct().filter { isSamePageList(it.first.url, managedReturn) }
                            val retained = affected.firstOrNull { it.first.url == managedReturn || it.first.url == result.page.url }
                            affected.forEach { state -> state.requestId++; state.loading = false; state.refreshing = false; state.reset(result.page) }
                            timelines.keys.filter { key -> isSamePageList(key, managedReturn) }.toList().forEach { key -> timelineJobs.remove(key)?.cancel(); timelines.remove(key) }
                            retained?.let { timelines[result.page.url] = it; timelines[managedReturn] = it }
                            pageCache.keys.filter { key -> isSamePageList(resolve(key).url, managedReturn) }.toList().forEach(pageCache::remove)
                            if (stillHere) page = result.page
                            rememberCachedPage(result.page, managedReturn)
                            if (stillHere && messageFlow) pendingNativePage = managedReturn
                        } else if (!direct && stillHere) { rememberNavigationSource(); page = result.page }
                        if (favoritesFlow) {
                            siteFavorites = null
                        }
                        rememberCachedPage(result.page)
                        if (messageFlow && !managedFresh && stillHere && result.page.url != actionPage?.url) pendingNativePage = result.page.url
                        showSiteTools = !direct && !messageFlow && !favoritesFlow
                        if (showSiteTools) pendingNativePage = result.page.url
                        if (result.page.authStatus == AuthStatus.AUTHENTICATED) {
                            showLogin = false
                            if (result.page.url.substringBefore('?').endsWith("index.php")) { home = result.page; showSiteTools = false }
                        }
                        if (result.page.authStatus == AuthStatus.LOGIN_REQUIRED) {
                            home = result.page; backStack.clear(); editor = null; showLogin = true; showSiteTools = false
                            pendingNativePage = null
                            toast = result.page.notice.takeIf { it.isNotBlank() }
                            return@launch
                        }
                        formFiles = emptyMap()
                        toast = when {
                            // Entering the rating workflow opens the site's own
                            // form; it is not a completed mutation, so do not
                            // reuse the generic "submitted, refresh" receipt.
                            result.uncertainReceipt != null && link?.url?.let { url ->
                                url.substringBefore('?').endsWith("/kf_fw_1wkfb.php") &&
                                    Regex("[?&]do=1(?:&|$)").containsMatchIn(url)
                            } == true && result.page.nativePage?.forms?.any { it.title == "帖子评分" } == true ->
                                result.page.notice.ifBlank { "已进入评分页，请核对后提交" }
                            result.uncertainReceipt != null -> "操作已提交，请刷新核对结果"
                            managedFresh -> response.let { (it as? SiteActionResult.Response)?.page?.notice }.orEmpty().ifBlank { "列表已重新加载，请核对操作结果" }
                            direct -> result.page.notice.ifBlank { SiteActionRisk.successMessage(link) }
                            else -> result.page.notice.ifBlank { "操作已完成" }
                        }
                    }
                    is SiteActionResult.Rejected -> {
                        managedReturn?.let(managedRecoveryStore::clearPendingBatchSource)
                        error = result.message
                    }
                    is SiteActionResult.Uncertain -> {
                        val bank = form?.form?.actionUrl?.contains("H_name=bank") == true ||
                            link?.url?.contains("H_name=bank") == true
                        error = if (bank) "服务器可能已执行该银行操作，但回执无法确认。请打开银行日志只读核对，不要自动重试。"
                        else result.message
                    }
                }
            } catch (e: CancellationException) {
                // The request may already have crossed the wire. Keep both the
                // durable marker and the in-memory read-only recovery gate.
                if (managedReturn != null) invalidateManagement(managedReturn)
                throw e
            }
            catch (e: Exception) {
                if (managedReturn != null) invalidateManagement(managedReturn)
                error = friendlyError(e)
            }
            finally { pendingSiteSubmission = null; pendingSiteDestination = null; siteBusy = false }
        }
    }

fun AppController.confirmSiteAction() {
        val form = pendingSiteSubmission
        val link = pendingSiteDestination
        if (form == null && link == null) return
        if (batchBusy || rewardBusy || avatarBusy || purchaseBusy || dailyBusy || pendingBatch != null || pendingReward != null || pendingAvatar != null || pendingPurchase != null) return
        startSiteAction(form, link)
    }

fun AppController.requestSiteSubmit(form: SiteForm, state: Map<String, List<String>>, submit: SiteSubmit) {
        if (busy || siteBusy || purchaseBusy || dailyBusy || batchBusy || rewardBusy || avatarBusy || pendingBatch != null || pendingReward != null || pendingAvatar != null) return
        if (managementRecoveryFor(form.sourceUrl) != null) { error = "请先重新加载并核对列表，再选择项目"; return }
        try {
            val selectedBulkValues = form.fields.filter { it.name == "delid[]" }
                .flatMap { field -> field.options.filter { option -> option.value in state[field.id].orEmpty() } }
                .map { it.value }.toSet()
            if (selectedBulkValues.isNotEmpty()) {
                val built = buildBatchPageRequest(form, selectedBulkValues, state)
                if (built == null) { error = "所选项目无法安全核对，未提交"; return }
                val values = buildSiteFormValues(form, state, submit)
                val operation = values.lastOrNull { it.name in setOf("job", "action") }?.value
                requestBatchSiteSubmit(listOf(built.copy(values = values, submit = submit,
                    verifyAbsent = operation !in setOf("change", "move", "unread"))))
                return
            }
            val values = buildSiteFormValues(form, state, submit)
            val files = form.fields.filter { it.kind == SiteFieldKind.FILE }.flatMap { field ->
                formFiles["${form.id}@${field.id}"].orEmpty().map { FilePart(field.name, it) }
            }
            val submission = SiteSubmission(form, values, files, submit)
            if (!SiteActionRisk.needsConfirmation(submission)) {
                pendingSiteSubmission = null
                startSiteAction(submission, null)
            } else pendingSiteSubmission = submission
        } catch (e: Exception) { error = e.message ?: "请检查表单" }
    }

fun AppController.receiveAvatar(file: LocalUpload?, failure: String? = null) {
        avatarPickerRequested = false
        failure?.let { error = it; pendingAvatar = null; return }
        if (file == null) return
        pendingAvatar = file
    }

fun AppController.requestAvatarPicker() {
        if (!busy && !siteBusy && !avatarBusy && !purchaseBusy && !batchBusy && !rewardBusy &&
            pendingSiteDestination == null && pendingSiteSubmission == null && pendingBatch == null && pendingReward == null && pendingPurchase == null) {
            avatarPickerRequested = true
        }
    }

fun AppController.confirmPurchase() {
        val offer = pendingPurchase ?: return
        if (busy || writeBusy || pendingBatch != null || pendingReward != null || pendingAvatar != null) return
        purchaseBusy = true; error = null
        purchaseJob = scope.launch {
            try {
                when (val result = service.purchase(offer)) {
                    is PurchaseResult.Success -> {
                        refreshPurchaseState(result.page)
                        val activeUrl = page?.url
                        rememberCachedPage(result.page)
                        if (activeUrl == result.page.url || activeUrl == null) page = result.page
                        pendingPurchase = null
                        uncertainPurchases = uncertainPurchases - offer.id
                        toast = result.message
                    }
                    is PurchaseResult.PriceChanged -> { pendingPurchase = result.offer; toast = "售价或币种已变化，请核对后重新确认" }
                    is PurchaseResult.Rejected -> { pendingPurchase = null; error = result.message }
                    is PurchaseResult.Uncertain -> {
                        pendingPurchase = null; uncertainPurchases = uncertainPurchases + offer.id; error = result.message
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                pendingPurchase = null
                if (store.get(purchasePendingKey(origin, offer.id)) != null) {
                    uncertainPurchases = uncertainPurchases + offer.id
                    error = "购买结果无法确认，请先刷新或查看站点记录，避免重复扣费"
                } else error = "购买信息复核失败，未提交购买，请刷新后重试"
            } finally { purchaseBusy = false }
        }
    }

fun AppController.requestPurchase(offer: PurchaseOffer) {
        if (busy || writeBusy || pendingBatch != null || pendingReward != null || pendingAvatar != null) return
        if (!authenticated) { loginReturnUrl = page?.url?.let(::loginDestination); showLogin = true; return }
        if (visiblePosts().flatMap { it.purchases }.none { it == offer }) return
        if (offer.id in uncertainPurchases) { error = "购买结果待确认，请先刷新"; return }
        page?.url?.let { url ->
            timelines[url]?.let { state ->
                if (state.loading) {
                    state.requestId++
                    timelineJobs.remove(url)?.cancel()
                    state.loading = false
                }
            }
        }
        pendingPurchase = offer
    }

internal fun AppController.refreshPurchaseState(result: PageData) {
        val previous = visiblePosts().flatMap { it.purchases }
        previous.forEach { offer ->
            result.posts.firstOrNull { isSamePageList(offer.threadUrl, result.url) && it.id == offer.postId && it.purchases.isEmpty() && !it.purchaseLocked }?.let {
                store.remove(purchasePendingKey(origin, offer.id))
            }
        }
        val offers = visiblePosts().filterNot { old -> result.posts.any { it.id == old.id } }.flatMap { it.purchases } + result.posts.flatMap { it.purchases }
        uncertainPurchases = offers.filter { store.get(purchasePendingKey(origin, it.id)) != null }.map { it.id }.toSet()
    }

fun AppController.claimDailyReward() {
        if (busy || writeBusy ||
            pendingSiteDestination != null || pendingSiteSubmission != null || pendingBatch != null || pendingReward != null || pendingAvatar != null) return
        if (dailySigned) {
            toast = "今天已经签过了，明天再来"
            return
        }
        if (!authenticated) {
            loginReturnUrl = page?.url?.let(::loginDestination)
            showLogin = true
            return
        }
        dailyBusy = true
        error = null
        val expectedOrigin = origin
        val client = service
        val beforeKfb = kfbAmount(accountProfile)
        dailyClaimJob = scope.launch {
            try {
                val page = try {
                    client.fetch("kf_growup.php")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (origin == expectedOrigin) error = friendlyError(e)
                    return@launch
                }
                if (origin != expectedOrigin || !authenticated) return@launch
                if (page.authStatus == AuthStatus.LOGIN_REQUIRED) {
                    dailySigned = false
                    loginReturnUrl = page.url.takeIf { it.contains("kf_growup.php") }
                    showLogin = true
                    return@launch
                }
                val growth = page.nativePage?.growth
                var notice = page.notice
                var claimed = growth?.claimStatus == StatusKind.COMPLETED
                var claimReceiptUncertain = false
                val claimUrl = growth?.claimUrl
                if (!claimed && claimUrl != null) {
                    val destination = client.destination(claimUrl)
                    when (destination.effect) {
                        SiteEffect.READ -> client.fetch(destination.url)
                        SiteEffect.CHANGE -> when (val result = client.executeSite(destination)) {
                            is SiteActionResult.Response -> {
                                notice = result.page.notice.ifBlank { notice }
                                claimReceiptUncertain = result.uncertainReceipt != null
                            }
                            is SiteActionResult.Rejected -> { error = result.message; return@launch }
                            is SiteActionResult.Uncertain -> { error = result.message; return@launch }
                        }
                        else -> { error = "签到入口暂不支持"; return@launch }
                    }
                } else if (!claimed && growth?.claimForm == true) {
                    val form = page.nativePage.forms.firstOrNull { it.sourceUrl.contains("kf_growup.php") }
                    val submit = form?.submits?.firstOrNull()
                    if (form == null || submit == null) {
                        error = "未找到签到入口"
                        return@launch
                    }
                    val values = buildSiteFormValues(form, form.fields.associate { it.id to it.initial }, submit)
                    when (val result = client.submitSite(SiteSubmission(form, values, emptyList(), submit))) {
                            is SiteActionResult.Response -> {
                                notice = result.page.notice.ifBlank { notice }
                                claimReceiptUncertain = result.uncertainReceipt != null
                            }
                        is SiteActionResult.Rejected -> { error = result.message; return@launch }
                        is SiteActionResult.Uncertain -> { error = result.message; return@launch }
                    }
                }
                if (!claimed) {
                    // The reward page renders a fresh status after the claim.
                    val statusPage = try { client.fetch("kf_growup.php") } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (statusPage != null) {
                        notice = statusPage.notice.ifBlank { notice }
                        claimed = statusPage.nativePage?.growth?.claimStatus == StatusKind.COMPLETED
                        if (statusPage.nativePage?.growth?.claimStatus == StatusKind.AVAILABLE) claimReceiptUncertain = true
                    }
                }
                if (origin != expectedOrigin || !authenticated) return@launch
                if (claimed) {
                    dailySigned = true
                    store.put("daily_signed@$origin", localDayKey())
                } else {
                    error = if (claimReceiptUncertain) {
                        "服务器可能已执行签到，但当前状态无法确认。请刷新签到页面核对，不要重复领取。"
                    } else {
                        "站点没有提供可确认的签到状态；请刷新状态后再决定是否操作。"
                    }
                    return@launch
                }
                val fresh = client.fetch("index.php")
                if (origin != expectedOrigin || !authenticated) return@launch
                home = fresh
                rememberCachedPage(fresh, "index.php")
                val profileUrl = fresh.menu.firstOrNull { it.url.contains("profile.php?action=show") }?.url
                val profile = try {
                    profileUrl?.let { client.fetch(it).nativePage?.profile }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (origin != expectedOrigin) return@launch
                if (profile != null) {
                    accountProfile = profile
                    if (!profile.avatar.isNullOrBlank()) accountAvatar = profile.avatar
                }
                val afterKfb = kfbAmount(profile)
                toast = when {
                    notice.isNotBlank() -> notice
                    beforeKfb > 0 && afterKfb > beforeKfb -> "签到成功，KFB +${afterKfb - beforeKfb}"
                    else -> "签到完成"
                }
                sessionNotice = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (origin == expectedOrigin) error = friendlyError(e)
            } finally {
                dailyBusy = false
            }
        }
    }

    /** Read the same claimed marker the website uses, without claiming. */
fun AppController.refreshDailyStatus() {
        if (!authenticated || dailyBusy || dailyStatusJob?.isActive == true) return
        val expectedOrigin = origin
        val client = service
        dailyStatusJob = scope.launch {
            try {
                val page = client.fetch("kf_growup.php")
                if (origin != expectedOrigin || !authenticated) return@launch
                if (page.authStatus == AuthStatus.LOGIN_REQUIRED) {
                    dailySigned = false
                    return@launch
                }
                val growth = page.nativePage?.growth ?: return@launch
                when (growth.claimStatus) {
                    StatusKind.COMPLETED -> {
                        dailySigned = true
                        store.put("daily_signed@$origin", localDayKey())
                    }
                    StatusKind.AVAILABLE -> {
                        dailySigned = false
                        store.remove("daily_signed@$origin")
                    }
                    // Unknown / waiting / failed are not evidence that the
                    // daily claim is available or already completed.
                    StatusKind.WAITING, StatusKind.EXPIRED, StatusKind.FAILED, StatusKind.UNKNOWN -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the last known state when the status cannot be read.
            }
        }
    }
