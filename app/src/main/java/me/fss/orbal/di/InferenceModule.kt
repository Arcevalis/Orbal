package me.fss.orbal.di

import android.content.Context
import me.fss.orbal.ai.InferenceEngine
import me.fss.orbal.ai.ModelManager
import me.fss.orbal.ai.remote.LmStudioClient
import me.fss.orbal.ai.remote.RemoteInferenceEngine
import me.fss.orbal.data.repository.ChatRepository
import me.fss.orbal.data.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object InferenceModule {

    @Provides
    @Singleton
    fun provideInferenceEngine(settingsRepository: SettingsRepository): InferenceEngine = InferenceEngine(settingsRepository)

    @Provides
    @Singleton
    fun provideLmStudioClient(): LmStudioClient = LmStudioClient()

    @Provides
    @Singleton
    fun provideRemoteInferenceEngine(
        lmStudioClient: LmStudioClient,
        settingsRepository: SettingsRepository,
        toolExecutor: me.fss.orbal.ai.tools.ToolExecutor,
    ): RemoteInferenceEngine = RemoteInferenceEngine(lmStudioClient, settingsRepository, toolExecutor)

    @Provides
    @Singleton
    fun provideModelManager(
        @ApplicationContext context: Context,
        chatRepository: ChatRepository,
        settingsRepository: SettingsRepository,
        inferenceEngine: InferenceEngine,
    ): ModelManager = ModelManager(context, chatRepository, settingsRepository, inferenceEngine)
}
