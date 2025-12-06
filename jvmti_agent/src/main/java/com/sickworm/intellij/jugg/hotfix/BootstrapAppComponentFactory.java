package com.sickworm.intellij.jugg.hotfix;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.*;
import android.content.pm.ApplicationInfo;

public class BootstrapAppComponentFactory extends AppComponentFactory {

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo aInfo) {
        return super.instantiateClassLoader(cl, aInfo);
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
