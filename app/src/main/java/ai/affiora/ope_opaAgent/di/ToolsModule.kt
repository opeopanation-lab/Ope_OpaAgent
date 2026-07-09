package ai.affiora.ope_opaAgent.di

import android.content.Context
import ai.affiora.ope_opaAgent.connectors.ConnectorManager
import ai.affiora.ope_opaAgent.tools.AlarmTimerTool
import ai.affiora.ope_opaAgent.tools.AndroidTool
import ai.affiora.ope_opaAgent.tools.AppLauncherTool
import ai.affiora.ope_opaAgent.tools.BrightnessTool
import ai.affiora.ope_opaAgent.tools.CalendarTool
import ai.affiora.ope_opaAgent.tools.ChannelTool
import ai.affiora.ope_opaAgent.channels.ChannelManager
import ai.affiora.ope_opaAgent.tools.CallLogTool
import ai.affiora.ope_opaAgent.tools.SubAgentTool
import ai.affiora.ope_opaAgent.tools.SessionHistoryTool
import ai.affiora.ope_opaAgent.agent.ClaudeApiClient
import ai.affiora.ope_opaAgent.data.db.ChatMessageDao
import ai.affiora.ope_opaAgent.data.db.ConversationDao
import ai.affiora.ope_opaAgent.tools.MemoryTool
import ai.affiora.ope_opaAgent.tools.ClipboardTool
import ai.affiora.ope_opaAgent.tools.ContactsTool
import ai.affiora.ope_opaAgent.tools.FileSystemTool
import ai.affiora.ope_opaAgent.tools.FlashlightTool
import ai.affiora.ope_opaAgent.tools.HttpTool
import ai.affiora.ope_opaAgent.tools.NavigationTool
import ai.affiora.ope_opaAgent.tools.OpenAiTool
import ai.affiora.ope_opaAgent.tools.PhotoTool
import ai.affiora.ope_opaAgent.tools.ScheduleTool
import ai.affiora.ope_opaAgent.tools.MediaControlTool
import ai.affiora.ope_opaAgent.tools.NotificationCache
import ai.affiora.ope_opaAgent.tools.NotificationTool
import ai.affiora.ope_opaAgent.tools.PhoneCallTool
import ai.affiora.ope_opaAgent.tools.ScreenCaptureTool
import ai.affiora.ope_opaAgent.tools.SkillAuthorTool
import ai.affiora.ope_opaAgent.tools.SmsTool
import ai.affiora.ope_opaAgent.tools.SystemInfoTool
import ai.affiora.ope_opaAgent.tools.TelegramTool
import ai.affiora.ope_opaAgent.tools.UIAutomationTool
import ai.affiora.ope_opaAgent.tools.VolumeTool
import ai.affiora.ope_opaAgent.tools.WebBrowserTool
import ai.affiora.ope_opaAgent.tools.ClawNotificationListener
import ai.affiora.ope_opaAgent.agent.ScheduleEngine
import ai.affiora.ope_opaAgent.data.prefs.UserPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ToolsModule {

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient {
        return HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(io.ktor.client.plugins.HttpTimeout) {
                // Values live in HttpTimeouts so ToolsModule and the user-facing
                // error messages in AgentRuntime.formatNetworkError stay in sync.
                requestTimeoutMillis = ai.affiora.ope_opaAgent.agent.HttpTimeouts.REQUEST_MS
                connectTimeoutMillis = ai.affiora.ope_opaAgent.agent.HttpTimeouts.CONNECT_MS
                socketTimeoutMillis = ai.affiora.ope_opaAgent.agent.HttpTimeouts.SOCKET_MS
            }
            // NOTE: WebSockets plugin intentionally NOT installed here.
            // Ktor's WebSocket pipeline interceptor can interfere with regular
            // HTTP long-polling (Telegram getUpdates). SlackChannel, the only
            // consumer of webSocket(), creates its own client with the plugin.
        }
    }

    @Provides
    @Singleton
    fun provideSmsTool(@ApplicationContext context: Context): SmsTool {
        return SmsTool(context)
    }

    @Provides
    @Singleton
    fun provideCallLogTool(@ApplicationContext context: Context): CallLogTool {
        return CallLogTool(context)
    }

    @Provides
    @Singleton
    fun provideContactsTool(@ApplicationContext context: Context): ContactsTool {
        return ContactsTool(context)
    }

    @Provides
    @Singleton
    fun provideCalendarTool(@ApplicationContext context: Context): CalendarTool {
        return CalendarTool(context)
    }

    @Provides
    @Singleton
    fun provideNotificationCache(): NotificationCache {
        return ClawNotificationListener.Companion
    }

    @Provides
    @Singleton
    fun provideNotificationTool(notificationCache: NotificationCache): NotificationTool {
        return NotificationTool(notificationCache)
    }

    @Provides
    @Singleton
    fun provideWebBrowserTool(
        @ApplicationContext context: Context,
        httpClient: HttpClient,
    ): WebBrowserTool {
        return WebBrowserTool(context, httpClient)
    }

    @Provides
    @Singleton
    fun provideSkillAuthorTool(
        @ApplicationContext context: Context,
        userPreferences: UserPreferences,
        skillInstaller: ai.affiora.ope_opaAgent.skills.SkillInstaller,
    ): SkillAuthorTool {
        return SkillAuthorTool(context, userPreferences, skillInstaller)
    }

    @Provides
    @Singleton
    fun provideAppLauncherTool(@ApplicationContext context: Context): AppLauncherTool {
        return AppLauncherTool(context)
    }

    @Provides
    @Singleton
    fun provideClipboardTool(@ApplicationContext context: Context): ClipboardTool {
        return ClipboardTool(context)
    }

    @Provides
    @Singleton
    fun provideAlarmTimerTool(@ApplicationContext context: Context): AlarmTimerTool {
        return AlarmTimerTool(context)
    }

    @Provides
    @Singleton
    fun provideFlashlightTool(@ApplicationContext context: Context): FlashlightTool {
        return FlashlightTool(context)
    }

    @Provides
    @Singleton
    fun provideVolumeTool(@ApplicationContext context: Context): VolumeTool {
        return VolumeTool(context)
    }

    @Provides
    @Singleton
    fun provideBrightnessTool(@ApplicationContext context: Context): BrightnessTool {
        return BrightnessTool(context)
    }

    @Provides
    @Singleton
    fun provideMediaControlTool(@ApplicationContext context: Context): MediaControlTool {
        return MediaControlTool(context)
    }

    @Provides
    @Singleton
    fun provideSystemInfoTool(@ApplicationContext context: Context): SystemInfoTool {
        return SystemInfoTool(context)
    }

    @Provides
    @Singleton
    fun provideUIAutomationTool(@ApplicationContext context: Context): UIAutomationTool {
        return UIAutomationTool(context)
    }

    @Provides
    @Singleton
    fun provideScreenCaptureTool(@ApplicationContext context: Context): ScreenCaptureTool {
        return ScreenCaptureTool(context)
    }

    @Provides
    @Singleton
    fun provideFileSystemTool(@ApplicationContext context: Context): FileSystemTool {
        return FileSystemTool(context)
    }

    @Provides
    @Singleton
    fun providePhoneCallTool(@ApplicationContext context: Context): PhoneCallTool {
        return PhoneCallTool(context)
    }

    @Provides
    @Singleton
    fun provideHttpTool(
        @ApplicationContext context: Context,
        httpClient: HttpClient,
        connectorManager: ConnectorManager,
    ): HttpTool {
        return HttpTool(context, httpClient, connectorManager)
    }

    @Provides
    @Singleton
    fun provideNavigationTool(@ApplicationContext context: Context): NavigationTool {
        return NavigationTool(context)
    }

    @Provides
    @Singleton
    fun provideScheduleTool(
        @ApplicationContext context: Context,
        scheduleEngine: ScheduleEngine,
    ): ScheduleTool {
        return ScheduleTool(context, scheduleEngine)
    }

    @Provides
    @Singleton
    fun provideOpenAiTool(
        @ApplicationContext context: Context,
        httpClient: HttpClient,
        userPreferences: UserPreferences,
    ): OpenAiTool {
        return OpenAiTool(context, httpClient, userPreferences)
    }

    @Provides
    @Singleton
    fun providePhotoTool(@ApplicationContext context: Context): PhotoTool {
        return PhotoTool(context)
    }

    @Provides
    @Singleton
    fun provideTelegramTool(
        httpClient: HttpClient,
        connectorManager: ConnectorManager,
    ): TelegramTool {
        return TelegramTool(httpClient, connectorManager)
    }

    @Provides
    @Singleton
    fun provideMemoryTool(memoryStore: ai.affiora.ope_opaAgent.agent.MemoryStore): MemoryTool {
        return MemoryTool(memoryStore)
    }

    @Provides
    @Singleton
    fun provideChannelTool(
        channelManager: dagger.Lazy<ChannelManager>,
        @ApplicationContext context: Context,
    ): ChannelTool {
        return ChannelTool(channelManager, context)
    }

    @Provides
    @Singleton
    fun provideSubAgentTool(
        claudeApiClient: ClaudeApiClient,
        userPreferences: UserPreferences,
    ): SubAgentTool {
        return SubAgentTool(claudeApiClient, userPreferences)
    }

    @Provides
    @Singleton
    fun provideSessionHistoryTool(
        chatMessageDao: ChatMessageDao,
        conversationDao: ConversationDao,
    ): SessionHistoryTool {
        return SessionHistoryTool(chatMessageDao, conversationDao)
    }

    @Provides
    @Singleton
    fun provideToolRegistry(
        smsTool: SmsTool,
        callLogTool: CallLogTool,
        contactsTool: ContactsTool,
        calendarTool: CalendarTool,
        notificationTool: NotificationTool,
        webBrowserTool: WebBrowserTool,
        skillAuthorTool: SkillAuthorTool,
        appLauncherTool: AppLauncherTool,
        clipboardTool: ClipboardTool,
        alarmTimerTool: AlarmTimerTool,
        flashlightTool: FlashlightTool,
        volumeTool: VolumeTool,
        brightnessTool: BrightnessTool,
        mediaControlTool: MediaControlTool,
        systemInfoTool: SystemInfoTool,
        uiAutomationTool: UIAutomationTool,
        screenCaptureTool: ScreenCaptureTool,
        fileSystemTool: FileSystemTool,
        phoneCallTool: PhoneCallTool,
        httpTool: HttpTool,
        navigationTool: NavigationTool,
        scheduleTool: ScheduleTool,
        openAiTool: OpenAiTool,
        photoTool: PhotoTool,
        telegramTool: TelegramTool,
        memoryTool: MemoryTool,
        channelTool: ChannelTool,
        subAgentTool: SubAgentTool,
        sessionHistoryTool: SessionHistoryTool,
    ): Map<String, AndroidTool> {
        val tools: List<AndroidTool> = listOf(
            smsTool,
            callLogTool,
            contactsTool,
            calendarTool,
            notificationTool,
            webBrowserTool,
            skillAuthorTool,
            appLauncherTool,
            clipboardTool,
            alarmTimerTool,
            flashlightTool,
            volumeTool,
            brightnessTool,
            mediaControlTool,
            systemInfoTool,
            uiAutomationTool,
            screenCaptureTool,
            fileSystemTool,
            phoneCallTool,
            httpTool,
            navigationTool,
            scheduleTool,
            openAiTool,
            photoTool,
            telegramTool,
            memoryTool,
            channelTool,
            subAgentTool,
            sessionHistoryTool,
        )
        return tools.associateBy { it.name }
    }
}
