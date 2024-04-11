package com.sickworm.intellij.jugg.ide;

import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;

// deprecated for hot reload not correctly working
public class PluginLoadListener implements DynamicPluginListener {

    private final Logger logger = Logger.getInstance("JuggPluginLoadListener");

    @Override
    public void beforePluginLoaded(@NotNull IdeaPluginDescriptor pluginDescriptor) {
        logger.info("beforePluginLoaded");
    }

    @Override
    public void pluginLoaded(@NotNull IdeaPluginDescriptor pluginDescriptor) {
        logger.info("pluginLoaded");
//        Project[] openedProjects = ProjectManager.getInstance().getOpenProjects();
//        logger.info("get opened projects: " + openedProjects.length);
//        for (Project project : openedProjects) {
//            JuggInitializer.INSTANCE.init(project);
//        }
    }

    @Override
    public void beforePluginUnload(@NotNull IdeaPluginDescriptor pluginDescriptor, boolean isUpdate) {
        logger.info("beforePluginUnload");
        // user reports that other plugins update is also callback here
//        JuggInitializer.INSTANCE.releaseAll();
    }

    @Override
    public void pluginUnloaded(@NotNull IdeaPluginDescriptor pluginDescriptor, boolean isUpdate) {
        logger.info("pluginUnloaded");
    }
}
