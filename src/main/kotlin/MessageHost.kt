package top.mrxiaom.loliyouwant

import net.mamoe.mirai.console.permission.Permission
import net.mamoe.mirai.console.permission.PermissionService.Companion.testPermission
import net.mamoe.mirai.console.permission.PermitteeId
import net.mamoe.mirai.console.permission.PermitteeId.Companion.permitteeId
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.User
import net.mamoe.mirai.event.EventHandler
import net.mamoe.mirai.event.SimpleListenerHost
import net.mamoe.mirai.event.events.FriendMessageEvent
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.MessageReceipt
import net.mamoe.mirai.message.data.*
import java.io.File
import java.io.FileInputStream

object MessageHost : SimpleListenerHost() {

    @EventHandler
    suspend fun onGroupMessage(event: GroupMessageEvent) {
        // 权限
        if (!LoliConfig.enableGroups.contains(event.group.id) && !anyHasPerm(
                LoliYouWant.PERM_RANDOM,
                event.group,
                event.sender
            )
        ) return

        // 捕捉关键词
        val at = event.message.filterIsInstance<At>().any { it.target == event.bot.id }
        val keyword = LoliConfig.resolveKeyword(event.message, at) ?: return

        val replacement = mutableMapOf("quote" to QuoteReply(event.source), "at" to At(event.sender))

        // 冷却
        if (!anyHasPerm(LoliYouWant.PERM_BYPASS_COOLDOWN, event.group, event.sender)) {
            val cd = LoliYouWant.cooldown.getOrDefault(event.group.id, 0)
            if (cd >= System.currentTimeMillis()) {
                replacement["cd"] = PlainText(((cd - System.currentTimeMillis()) / 1000L).toString())
                event.group.sendMessage(LoliConfig.replyCooldown.replace(replacement))
                return
            }

            // 无权限时才设置冷却
            LoliYouWant.cooldown[event.group.id] = System.currentTimeMillis() + LoliConfig.cooldown * 1000
        }

        // 获取图片并发送
        val result =
            if (keyword.count > 1) sendLoliPictureCollection(event.group, keyword, replacement) else sendLoliPicture(
                event.group,
                keyword,
                replacement
            )
        if (keyword.recallFetchingMessage) result.second.recallIgnoreError()
        if (!result.first) {
            event.group.sendMessage(keyword.replyFail.replace(replacement))
            LoliYouWant.cooldown[event.group.id] = System.currentTimeMillis() + LoliConfig.failCooldown * 1000
        }
    }

    @EventHandler
    suspend fun onFriendMessage(event: FriendMessageEvent) {
        // 权限
        if (!anyHasPerm(LoliYouWant.PERM_RANDOM, event.sender)) return

        // 捕捉关键词
        val at = event.message.filterIsInstance<At>().any { it.target == event.bot.id }
        val keyword = LoliConfig.resolveKeyword(event.message, at) ?: return

        val replacement = mutableMapOf<String, SingleMessage>("quote" to QuoteReply(event.source))

        // 冷却
        if (!anyHasPerm(LoliYouWant.PERM_BYPASS_COOLDOWN, event.sender)) {
            val cd = LoliYouWant.cooldownFriend.getOrDefault(event.sender.id, 0)
            if (cd >= System.currentTimeMillis()) {
                replacement["cd"] = PlainText(((cd - System.currentTimeMillis()) / 1000L).toString())
                event.sender.sendMessage(LoliConfig.replyCooldown.replace(replacement))
                return
            }

            // 无权限时才设置冷却
            LoliYouWant.cooldownFriend[event.sender.id] = System.currentTimeMillis() + LoliConfig.cooldown * 1000
        }

        // 获取图片并发送
        val result =
            if (keyword.count > 1) sendLoliPictureCollection(event.friend, keyword, replacement) else sendLoliPicture(
                event.sender,
                keyword,
                replacement
            )
        if (keyword.recallFetchingMessage) result.second.recallIgnoreError()
        if (!result.first) {
            event.sender.sendMessage(keyword.replyFail.replace(replacement))
            LoliYouWant.cooldownFriend[event.friend.id] = System.currentTimeMillis() + LoliConfig.failCooldown * 1000
        }
    }

    private suspend fun sendLoliPicture(
        contact: Contact,
        keyword: LoliConfig.Keyword,
        replacement: MutableMap<String, SingleMessage>
    ): Pair<Boolean, MessageReceipt<Contact>> {
        val receipt = contact.sendMessage(keyword.replyFetching.replace(replacement))

        val tags = keyword.resolveTagsParams()
        val loli = LoliYouWant.searchLolis(Lolibooru.get(10, 1, "order:random -rating:e -video $tags")).randomOrNull()
            ?: return Pair(false, receipt)

        replacement.putAll(loli.toReplacement(contact, keyword))

        contact.sendMessage(keyword.replySuccess.replace(replacement))
        return Pair(true, receipt)
    }

    private suspend fun sendLoliPictureCollection(
        contact: Contact,
        keyword: LoliConfig.Keyword,
        defReplacement: MutableMap<String, SingleMessage>
    ): Pair<Boolean, MessageReceipt<Contact>> {
        val receipt = contact.sendMessage(keyword.replyFetching.replace(defReplacement))

        val tags = keyword.tags.filter { !it.contains("rating:") }.joinToString(" ")
        val lolies = LoliYouWant.searchLolis(Lolibooru.get(40, 1, "order:random -rating:e -video $tags"))
        if (lolies.isEmpty()) return Pair(false, receipt)

        val forward = ForwardMessageBuilder(contact.bot.asFriend)

        var count = 0
        for (loli in lolies) {
            if (count >= keyword.count) break

            val replacement = defReplacement.toMutableMap()
            replacement.putAll(loli.toReplacement(contact, keyword))

            forward.add(
                contact.bot,
                keyword.replySuccess.replace(replacement),
                (System.currentTimeMillis() / 1000).toInt()
            )
            count++
        }
        contact.sendMessage(forward.build())
        return Pair(true, receipt)
    }
}

fun Loli.toReplacement(contact: Contact, keyword: LoliConfig.Keyword): Map<String, SingleMessage> {
    val picUrl = when (keyword.quality) {
        "FILE" -> url
        "PREVIEW" -> urlPreview
        else -> urlSample
    }.replace(" ", "%20")
    return mapOf(
        "id" to PlainText(id.toString()),
        "previewUrl" to PlainText(urlPreview.replace(" ", "%20")),
        "sampleUrl" to PlainText(urlSample.replace(" ", "%20")),
        "fileUrl" to PlainText(url.replace(" ", "%20")),
        "url" to PlainText(picUrl.replace(" ", "%20")),
        "tags" to PlainText(tags),
        "rating" to PlainText(rating),
        "pic" to PrepareUploadImage.url(
            contact, picUrl, keyword.imageFailDownload, keyword.timeout
        ) { input ->
            if (!keyword.download) return@url input
            val folder =
                LoliYouWant.resolveDataFile(keyword.overrideDownloadPath.replace("\\", "/").removeSurrounding("/"))
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, picUrl.substringAfterLast('/').replace("%20", " "))

            file.writeBytes(input.readBytes())
            return@url FileInputStream(file)
        }
    )
}

fun LoliConfig.Keyword.resolveTagsParams() = mutableListOf("order:random", "-rating:e", "-video").also { paramTags ->
    if (LoliConfig.doesAddTagsToParams) paramTags.addAll(LoliYouWant.blacklistTags.map { "-$it" })
    paramTags.addAll(tags.filter { !paramTags.contains("-$it") && !it.contains("rating:") && !it.contains("order:") })
}.joinToString(" ")

val Contact.permitteeIdOrNull: PermitteeId?
    get() = when (this) {
        is User -> this.permitteeId
        is Group -> this.permitteeId
        else -> null
    }

fun anyHasPerm(p: Permission, vararg users: Contact): Boolean = users.any {
    p.testPermission(it.permitteeIdOrNull ?: return@any false)
}
