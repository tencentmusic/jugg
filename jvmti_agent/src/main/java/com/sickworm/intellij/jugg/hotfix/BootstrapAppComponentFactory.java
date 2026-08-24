package com.sickworm.intellij.jugg.hotfix;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.*;
import android.content.pm.ApplicationInfo;

import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig;

public class BootstrapAppComponentFactory extends AppComponentFactory {

    private static final String TAG = HotfixLoader.TAG + "#BootstrapAppComponentFactory";

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo aInfo) {
        try {
            if (BootstrapApplication.rawAppComponentFactory == null) {
                BootstrapApplication.rawAppComponentFactory =
                        BootstrapApplication.createRawAppComponentFactory(aInfo, cl);
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            BootstrapApplication.rawAppComponentFactory = null;
            LogUtils.w(TAG, "instantiateClassLoader: failed to create raw AppComponentFactory, using default: " +
                    e);
        }

        if (BootstrapApplication.rawAppComponentFactory == null) {
            return super.instantiateClassLoader(cl, aInfo);
        }

        ApplicationInfo rawApplicationInfo = new ApplicationInfo(aInfo);
        rawApplicationInfo.className = aInfo.metaData.getString(BuildConfig.META_DATA_LABEL_RAW_APPLICATION);
        rawApplicationInfo.appComponentFactory = aInfo.metaData.getString(
                BuildConfig.META_DATA_LABEL_RAW_APP_COMPONENT_FACTORY
        );
        ClassLoader rawClassLoader = BootstrapApplication.rawAppComponentFactory.instantiateClassLoader(
                cl, rawApplicationInfo);
        LogUtils.i(TAG, "instantiateClassLoader: raw classLoader: " + rawClassLoader);
        return rawClassLoader;
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        return super.instantiateApplication(cl, className);
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, Intent intent) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (BootstrapApplication.rawAppComponentFactory != null) {
            return BootstrapApplication.rawAppComponentFactory.instantiateActivity(cl, className, intent);
        }
        return super.instantiateActivity(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, Intent intent) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (BootstrapApplication.rawAppComponentFactory != null) {
            return BootstrapApplication.rawAppComponentFactory.instantiateService(cl, className, intent);
        }
        return super.instantiateService(cl, className, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, Intent intent) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (BootstrapApplication.rawAppComponentFactory != null) {
            return BootstrapApplication.rawAppComponentFactory.instantiateReceiver(cl, className, intent);
        }
        return super.instantiateReceiver(cl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        if (BootstrapApplication.rawAppComponentFactory != null) {
            return BootstrapApplication.rawAppComponentFactory.instantiateProvider(cl, className);
        }
        return super.instantiateProvider(cl, className);
    }

}
