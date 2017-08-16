/*
 * Copyright 2017 Duncan C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dunciboy.discord_bot


import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.audit.ActionType
import net.dv8tion.jda.core.audit.AuditLogEntry
import net.dv8tion.jda.core.audit.AuditLogOption
import net.dv8tion.jda.core.entities.User
import net.dv8tion.jda.core.events.ReadyEvent
import net.dv8tion.jda.core.events.guild.GuildBanEvent
import net.dv8tion.jda.core.events.guild.GuildUnbanEvent
import net.dv8tion.jda.core.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.core.events.guild.member.GuildMemberLeaveEvent
import net.dv8tion.jda.core.events.guild.member.GuildMemberNickChangeEvent
import net.dv8tion.jda.core.events.message.MessageBulkDeleteEvent
import net.dv8tion.jda.core.events.message.guild.GuildMessageDeleteEvent
import net.dv8tion.jda.core.events.message.guild.GuildMessageUpdateEvent
import net.dv8tion.jda.core.events.user.UserNameUpdateEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import net.dv8tion.jda.core.utils.SimpleLog
import java.awt.Color
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.Serializable
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * This file will create the listeners and the appropriate action to be taken
 * upon each of the listener.
 *
 *
 * IMPORTANT READ BEFORE MODIFYING CODE:
 * The modifying the lastCheckedMessageDeleteEntries HashMap needs to happen using the guildLoggerExecutor because it's
 * thread are executed sequentially there is no need to lock the object, however if you try to do this without using the
 * service the chances of hitting a ConcurrentModificationException are 100%.
 *
 * @author Duncan
 * @since 1.0
 */
class GuildLogger internal constructor(private val logger: LogToChannel, private val settings: Settings) : ListenerAdapter() {

    private val guildLoggerExecutor: ScheduledExecutorService
    private val lastCheckedMessageDeleteEntries: HashMap<Long, AuditLogEntry>

    init {
        this.guildLoggerExecutor = Executors.newSingleThreadScheduledExecutor { r ->
            val thread = Thread(ThrowableSafeRunnable(r, LOG), GuildLogger::class.java.simpleName)
            thread.isDaemon = true
            thread
        }
        this.lastCheckedMessageDeleteEntries = HashMap()
    }

    override fun onReady(event: ReadyEvent?) {
        logger.initChannelList(event!!.jda)
        logger.logChannels.forEach { textChannel ->
            textChannel.guild.auditLogs.type(ActionType.MESSAGE_DELETE).limit(1).cache(false).queue { auditLogEntries ->
                val auditLogEntry = auditLogEntries[0]
                lastCheckedMessageDeleteEntries.put(auditLogEntry.guild.idLong, auditLogEntry)
            }
        }
    }

    override fun onGuildMessageUpdate(event: GuildMessageUpdateEvent?) {
        if (!settings.isLogMessageUpdate) {
            return
        }

        val guild = event!!.guild
        val channel = event.channel
        if (settings.isExceptedFromLogging(channel.idLong)) {
            return
        }

        var history: MessageHistory? = null
        for (messageHistory in MessageHistory.getInstanceList()) {
            try {
                if (event.jda === messageHistory.instance) {
                    history = messageHistory
                }
            } catch (ignored: MessageHistory.EmptyCacheException) {
            }

        }

        if (history == null) {
            return
        }

        val oldMessage = history.getMessage(java.lang.Long.parseUnsignedLong(event.messageId), false)
        if (oldMessage != null) {
            val name: String = try {
                JDALibHelper.getEffectiveNameAndUsername(oldMessage.guild.getMember(oldMessage.author))
            } catch (e: IllegalArgumentException) {
                oldMessage.author.name
            }
            val logEmbed = EmbedBuilder()
                    .setTitle("#" + channel.name + ": Message was modified!")
                    .setDescription("Old message was:\n" + oldMessage.content)
                    .setColor(LIGHT_BLUE)
                    .addField("Author", name, true)
            guildLoggerExecutor.execute { logger.log(logEmbed, oldMessage.author, guild, oldMessage.embeds) }
        }
    }

    /**
     * This functions will be called each time a message is deleted on a discord
     * server.
     *
     * @param event The event that trigger this method
     */
    override fun onGuildMessageDelete(event: GuildMessageDeleteEvent?) {
        if (!settings.isLogMessageDelete) {
            return
        }
        val guild = event!!.guild
        val channel = event.channel
        if (settings.isExceptedFromLogging(channel.idLong)) {
            return
        }

        var history: MessageHistory? = null
        for (messageHistory in MessageHistory.getInstanceList()) {
            try {
                if (event.jda === messageHistory.instance) {
                    history = messageHistory
                }
            } catch (ignored: MessageHistory.EmptyCacheException) {
            }

        }

        if (history == null) {
            guildLoggerExecutor.execute {
                val logEntry = event.guild.auditLogs.type(ActionType.MESSAGE_DELETE).cache(false).limit(1).complete()[0]
                lastCheckedMessageDeleteEntries.put(event.guild.idLong, logEntry)
            }
            return
        }

        val oldMessage = history.getMessage(java.lang.Long.parseUnsignedLong(event.messageId))
        if (oldMessage != null) {
            val attachmentString = history.getAttachmentsString(java.lang.Long.parseUnsignedLong(event.messageId))

            val name: String = try {
                JDALibHelper.getEffectiveNameAndUsername(oldMessage.guild.getMember(oldMessage.author))
            } catch (e: IllegalArgumentException) {
                oldMessage.author.name
            }
            guildLoggerExecutor.schedule({
                var moderator: User? = null
                run {
                    var i = 0
                    var firstCheckedAuditLogEntry: AuditLogEntry? = null
                    for (logEntry in event.guild.auditLogs.type(ActionType.MESSAGE_DELETE).cache(false).limit(LOG_ENTRY_CHECK_LIMIT)) {
                        if (i == 0) {
                            firstCheckedAuditLogEntry = logEntry
                        }
                        if (!lastCheckedMessageDeleteEntries.containsKey(event.guild.idLong)) {
                            i = LOG_ENTRY_CHECK_LIMIT
                        } else {
                            val cachedAuditLogEntry = lastCheckedMessageDeleteEntries[event.guild.idLong]
                            if (logEntry.idLong == cachedAuditLogEntry?.idLong) {
                                if (logEntry.targetIdLong == oldMessage.author.idLong && logEntry.getOption<Any>(AuditLogOption.COUNT) != cachedAuditLogEntry.getOption<Any>(AuditLogOption.COUNT)) {
                                    moderator = logEntry.user
                                }
                                break
                            }
                        }
                        if (logEntry.targetIdLong == oldMessage.author.idLong) {
                            moderator = logEntry.user
                            break
                        }
                        i++
                        if (i >= LOG_ENTRY_CHECK_LIMIT) {
                            break
                        }
                    }
                    if (firstCheckedAuditLogEntry != null) {
                        lastCheckedMessageDeleteEntries.put(event.guild.idLong, firstCheckedAuditLogEntry)
                    }
                }

                val logEmbed = EmbedBuilder()
                        .setTitle("#" + channel.name + ": Message was deleted!")
                        .setDescription("Old message was:\n" + oldMessage.content)
                if (attachmentString != null) {
                    logEmbed.addField("Attachment(s)", attachmentString, false)
                }
                logEmbed.addField("Author", name, true)
                if (moderator != null) {
                    logEmbed.addField("Deleted by", JDALibHelper.getEffectiveNameAndUsername(event.guild.getMember(moderator)), true)
                            .setColor(Color.YELLOW)
                } else {
                    logEmbed.setColor(LIGHT_BLUE)
                }
                logger.log(logEmbed, oldMessage.author, guild, oldMessage.embeds)
            }, 1, TimeUnit.SECONDS)
        }
    }

    override fun onMessageBulkDelete(event: MessageBulkDeleteEvent?) {
        if (!settings.isLogMessageDelete) {
            return
        }
        val channel = event!!.channel
        if (settings.isExceptedFromLogging(channel.idLong)) {
            return
        }

        val logEmbed = EmbedBuilder()
                .setColor(LIGHT_BLUE)
                .setTitle("#" + event.channel.name + ": Bulk delete")
                .addField("Amount of deleted messages", event.messageIds.size.toString(), false)

        var history: MessageHistory? = null
        for (messageHistory in MessageHistory.getInstanceList()) {
            try {
                if (event.jda === messageHistory.instance) {
                    history = messageHistory
                }
            } catch (ignored: MessageHistory.EmptyCacheException) {
            }

        }

        if (history == null) {
            logBulkDelete(event, logEmbed)
            return
        }

        var bulkDeleteLog: Path? = null
        val logWriter: BufferedWriter
        try {
            bulkDeleteLog = Files.createTempFile(event.channel.name + " " + OffsetDateTime.now().format(DATE_TIME_FORMATTER), ".log")
            logWriter = Files.newBufferedWriter(bulkDeleteLog!!, Charset.forName("UTF-8"), StandardOpenOption.WRITE)
        } catch (e: IOException) {
            if (bulkDeleteLog != null) {
                ioCleanup(bulkDeleteLog.toFile(), e)
            } else {
                LOG.log(e)
            }
            logBulkDelete(event, logEmbed)
            return
        }

        try {
            logWriter.append(event.channel.toString()).append("\n")
        } catch (e: IOException) {
            LOG.log(e)
        }

        var messageLogged = false
        event.messageIds.forEach { id ->
            val message = history?.getMessage(java.lang.Long.parseUnsignedLong(id))
            if (message != null) {
                messageLogged = true
                try {
                    logWriter.append(message.author.toString()).append(":\n").append(message.content).append("\n")
                    val attachmentString = history?.getAttachmentsString(java.lang.Long.parseUnsignedLong(id))
                    if (attachmentString != null) {
                        logWriter.append("Attachment(s):\n").append(attachmentString).append("\n")
                    } else {
                        logWriter.append("\n")
                    }
                } catch (e: IOException) {
                    LOG.log(e)
                }

            }
        }
        try {
            logWriter.close()
            if (messageLogged) {
                logBulkDelete(event, logEmbed, bulkDeleteLog)
            } else {
                logBulkDelete(event, logEmbed)
                ioCleanup(bulkDeleteLog.toFile(), null)
            }
        } catch (e: IOException) {
            ioCleanup(bulkDeleteLog.toFile(), e)
            logBulkDelete(event, logEmbed)
        }

    }

    private fun ioCleanup(file: File, e: IOException?) {
        if (e != null) {
            LOG.log(e)
        }
        if (file.exists() && !file.delete()) {
            file.deleteOnExit()
        }
    }

    private fun logBulkDelete(event: MessageBulkDeleteEvent, logEmbed: EmbedBuilder, file: Path? = null) {
        guildLoggerExecutor.execute { logger.log(logEmbed, null, event.guild, null, file) }
    }

    override fun onGuildMemberLeave(event: GuildMemberLeaveEvent?) {
        if (!settings.isLogMemberRemove) {
            return
        }

        guildLoggerExecutor.schedule({
            var moderator: User? = null
            var reason: String? = null
            run {
                var i = 0
                for (logEntry in event!!.guild.auditLogs.type(ActionType.KICK).cache(false).limit(LOG_ENTRY_CHECK_LIMIT)) {
                    if (logEntry.targetIdLong == event.member.user.idLong) {
                        moderator = logEntry.user
                        reason = logEntry.reason
                        break
                    }
                    i++
                    if (i >= LOG_ENTRY_CHECK_LIMIT) {
                        break
                    }
                }
            }

            if (moderator != null && moderator === event!!.jda.selfUser) {
                return@schedule  //Bot is kicking no need to log, if needed it will be placed in the module that is issuing the kick.
            }

            val logEmbed = EmbedBuilder()
                    .setColor(Color.RED)
                    .addField("User", JDALibHelper.getEffectiveNameAndUsername(event!!.member), true)
            if (moderator == null) {
                logEmbed.setTitle("SERVER NOTIFICATION: User left")
            } else {
                logEmbed.setTitle("SERVER NOTIFICATION: User kicked | Case: " + getCaseNumberSerializable(event.guild.idLong))
                logEmbed.addField("Moderator", JDALibHelper.getEffectiveNameAndUsername(event.guild.getMember(moderator)), true)
                if (reason != null) {
                    logEmbed.addField("Reason", reason, false)
                }
            }
            logger.log(logEmbed, event.member.user, event.guild, null)
        }, 1, TimeUnit.SECONDS)

    }

    override fun onGuildBan(event: GuildBanEvent?) {
        if (!settings.isLogMemberBan) {
            return
        }

        guildLoggerExecutor.schedule({
            var moderator: User? = null
            var reason: String? = null
            run {
                var i = 0
                for (logEntry in event!!.guild.auditLogs.type(ActionType.BAN).cache(false).limit(LOG_ENTRY_CHECK_LIMIT)) {
                    if (logEntry.targetIdLong == event.user.idLong) {
                        moderator = logEntry.user
                        reason = logEntry.reason
                        break
                    }
                    i++
                    if (i >= LOG_ENTRY_CHECK_LIMIT) {
                        break
                    }
                }
            }

            if (moderator != null && moderator === event!!.jda.selfUser) {
                return@schedule  //Bot is banning no need to log, if needed it will be placed in the module that is issuing the ban.
            }

            val logEmbed = EmbedBuilder()
                    .setColor(Color.RED)
                    .setTitle("SERVER NOTIFICATION: User banned | Case: " + getCaseNumberSerializable(event!!.guild.idLong))
                    .addField("User", event.user.name, true)
            if (moderator != null) {
                logEmbed.addField("Moderator", JDALibHelper.getEffectiveNameAndUsername(event.guild.getMember(moderator)), true)
                if (reason != null) {
                    logEmbed.addField("Reason", reason, false)
                }
            }
            logger.log(logEmbed, event.user, event.guild, null)
        }, 1, TimeUnit.SECONDS)
    }

    override fun onGuildMemberJoin(event: GuildMemberJoinEvent?) {
        if (!settings.isLogMemberAdd) {
            return
        }

        val logEmbed = EmbedBuilder()
                .setColor(Color.GREEN)
                .setTitle("SERVER NOTIFICATION: User joined", null)
                .addField("User", event!!.member.user.name, false)
                .addField("Account created", event.member.user.creationTime.format(DATE_TIME_FORMATTER), false)
        guildLoggerExecutor.execute { logger.log(logEmbed, event.member.user, event.guild, null) }
    }


    override fun onGuildUnban(event: GuildUnbanEvent?) {
        if (!settings.isLogMemberRemoveBan) {
            return
        }

        guildLoggerExecutor.schedule({
            var moderator: User? = null
            run {
                var i = 0
                for (logEntry in event!!.guild.auditLogs.type(ActionType.UNBAN).cache(false).limit(LOG_ENTRY_CHECK_LIMIT)) {
                    if (logEntry.targetIdLong == event.user.idLong) {
                        moderator = logEntry.user
                        break
                    }
                    i++
                    if (i >= LOG_ENTRY_CHECK_LIMIT) {
                        break
                    }
                }
            }

            val logEmbed = EmbedBuilder()
                    .setColor(Color.GREEN)
                    .setTitle("SERVER NOTIFICATION: User ban revoked", null)
                    .addField("User", event!!.user.name, true)
            if (moderator != null) {
                logEmbed.addField("Moderator", JDALibHelper.getEffectiveNameAndUsername(event.guild.getMember(moderator)), true)
            }
            logger.log(logEmbed, event.user, event.guild, null)
        }, 1, TimeUnit.SECONDS)
    }

    override fun onUserNameUpdate(event: UserNameUpdateEvent?) {
        for (guild in logger.userOnGuilds(event!!.user)) {
            val logEmbed = EmbedBuilder()
                    .setColor(LIGHT_BLUE)
                    .setTitle("User has changed username")
                    .addField("Old username & discriminator", event.oldName + "#" + event.oldDiscriminator, false)
                    .addField("New username & discriminator", event.user.name + "#" + event.user.discriminator, false)
            guildLoggerExecutor.execute { logger.log(logEmbed, event.user, guild, null) }
        }
    }

    override fun onGuildMemberNickChange(event: GuildMemberNickChangeEvent?) {
        guildLoggerExecutor.schedule({
            var moderator: User? = null
            run {
                var i = 0
                for (logEntry in event!!.guild.auditLogs.type(ActionType.MEMBER_UPDATE).cache(false).limit(LOG_ENTRY_CHECK_LIMIT)) {
                    if (logEntry.targetIdLong == event.member.user.idLong) {
                        moderator = logEntry.user
                        break
                    }
                    i++
                    if (i >= LOG_ENTRY_CHECK_LIMIT) {
                        break
                    }
                }
            }

            val logEmbed = EmbedBuilder()
                    .setColor(LIGHT_BLUE)
                    .addField("User", event!!.member.user.name, false)
                    .addField("Old nickname", if (event.prevNick != null) event.prevNick else "None", true)
                    .addField("New nickname", if (event.newNick != null) event.newNick else "None", true)
            if (moderator == null || moderator === event.member.user) {
                logEmbed.setTitle("User has changed nickname")
            } else {
                logEmbed.setTitle("Moderator has changed nickname")
                        .addField("Moderator", JDALibHelper.getEffectiveNameAndUsername(event.guild.getMember(moderator)), false)
            }
            logger.log(logEmbed, event.member.user, event.guild, null)
        }, 1, TimeUnit.SECONDS)
    }

    companion object {

        //private static final String SEPARATOR = "\n------------------------------------------------------------";
        private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd-M-yyyy hh:mm a O")
        private val LOG = SimpleLog.getLog(GuildLogger::class.java.simpleName)
        private val LIGHT_BLUE = Color(52, 152, 219)
        private val LOG_ENTRY_CHECK_LIMIT = 5

        fun getCaseNumberSerializable(guildId: Long): Serializable {
            val caseNumber: Long = try {
                CaseSystem(guildId).newCaseNumber
            } catch (e: IOException) {
                -1
            }

            return if (caseNumber != -1L) caseNumber else "IOException - failed retrieving number"
        }
    }
}