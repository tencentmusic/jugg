package com.sickworm.intellij.jugg.hotfix;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the Application instance exposed to the app during BootstrapApplication startup.
 */
public class BootstrapApplicationTest {

    @Test
    public void getApplicationContext_shouldReturnRawApplicationWhenInitialized() throws Exception {
        BootstrapApplication bootstrapApplication = new BootstrapApplication();
        Application rawApplication = mock(Application.class);
        setRawApplication(bootstrapApplication, rawApplication);

        assertSame(rawApplication, bootstrapApplication.getApplicationContext());
    }

    @Test
    public void getApplicationContext_shouldKeepDefaultResultBeforeRawApplicationInitialized() {
        assertNull(new BootstrapApplication().getApplicationContext());
    }

    @Test
    public void activityLifecycleCallbacks_shouldRegisterAndUnregisterOnRawApplication() throws Exception {
        BootstrapApplication bootstrapApplication = new BootstrapApplication();
        Application rawApplication = mock(Application.class);
        setRawApplication(bootstrapApplication, rawApplication);
        Application.ActivityLifecycleCallbacks callbacks = mock(Application.ActivityLifecycleCallbacks.class);

        bootstrapApplication.registerActivityLifecycleCallbacks(callbacks);
        bootstrapApplication.unregisterActivityLifecycleCallbacks(callbacks);

        verify(rawApplication).registerActivityLifecycleCallbacks(callbacks);
        verify(rawApplication).unregisterActivityLifecycleCallbacks(callbacks);
    }

    @Test
    public void initRawApplicationAndAppComponentFactory_shouldIgnoreMissingMetaData() throws Exception {
        BootstrapApplication bootstrapApplication = spy(new BootstrapApplication());
        PackageManager packageManager = mock(PackageManager.class);
        ApplicationInfo applicationInfo = new ApplicationInfo();
        when(packageManager.getApplicationInfo("com.example.app", PackageManager.GET_META_DATA))
                .thenReturn(applicationInfo);
        doReturn(packageManager).when(bootstrapApplication).getPackageManager();
        doReturn("com.example.app").when(bootstrapApplication).getPackageName();

        invokeInitRawApplication(bootstrapApplication);

        assertNull(bootstrapApplication.getApplicationContext());
    }

    private void setRawApplication(
            BootstrapApplication bootstrapApplication,
            Application rawApplication
    ) throws Exception {
        Field field = BootstrapApplication.class.getDeclaredField("rawApplication");
        field.setAccessible(true);
        field.set(bootstrapApplication, rawApplication);
    }

    private void invokeInitRawApplication(BootstrapApplication bootstrapApplication) throws Exception {
        Method method = BootstrapApplication.class.getDeclaredMethod(
                "initRawApplicationAndAppComponentFactory",
                android.content.Context.class
        );
        method.setAccessible(true);
        method.invoke(bootstrapApplication, bootstrapApplication);
    }
}
