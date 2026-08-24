package com.sickworm.intellij.jugg.hotfix;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;

import com.sickworm.intellij.jugg.jvmti_agent.BuildConfig;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies delegation from the bootstrap factory to the application's original factory.
 */
public class BootstrapAppComponentFactoryTest {

    @After
    public void tearDown() {
        BootstrapApplication.rawAppComponentFactory = null;
        TestAppComponentFactory.reset();
    }

    @Test
    public void instantiateClassLoader_shouldReturnRawFactoryClassLoader() {
        ClassLoader defaultClassLoader = getClass().getClassLoader();
        ClassLoader rawClassLoader = new ClassLoader(defaultClassLoader) {
        };
        TestAppComponentFactory.classLoaderToReturn = rawClassLoader;

        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.className = BootstrapApplication.class.getName();
        applicationInfo.appComponentFactory = BootstrapAppComponentFactory.class.getName();
        applicationInfo.metaData = mock(Bundle.class);
        when(applicationInfo.metaData.getString(BuildConfig.META_DATA_LABEL_RAW_APPLICATION))
                .thenReturn("com.example.RawApplication");
        when(applicationInfo.metaData.getString(BuildConfig.META_DATA_LABEL_RAW_APP_COMPONENT_FACTORY))
                .thenReturn(TestAppComponentFactory.class.getName());

        ClassLoader actualClassLoader = new BootstrapAppComponentFactory()
                .instantiateClassLoader(defaultClassLoader, applicationInfo);

        assertSame(rawClassLoader, actualClassLoader);
        assertSame(defaultClassLoader, TestAppComponentFactory.receivedClassLoader);
        assertEquals("com.example.RawApplication", TestAppComponentFactory.receivedApplicationInfo.className);
        assertEquals(TestAppComponentFactory.class.getName(),
                TestAppComponentFactory.receivedApplicationInfo.appComponentFactory);
        assertSame(BootstrapApplication.rawAppComponentFactory, TestAppComponentFactory.instance);
        assertEquals(1, TestAppComponentFactory.instantiateClassLoaderCallCount);
    }

    public static class TestAppComponentFactory extends AppComponentFactory {
        private static TestAppComponentFactory instance;
        private static ClassLoader classLoaderToReturn;
        private static ClassLoader receivedClassLoader;
        private static ApplicationInfo receivedApplicationInfo;
        private static int instantiateClassLoaderCallCount;

        public TestAppComponentFactory() {
            instance = this;
        }

        @Override
        public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo aInfo) {
            instantiateClassLoaderCallCount++;
            receivedClassLoader = cl;
            receivedApplicationInfo = aInfo;
            return classLoaderToReturn;
        }

        private static void reset() {
            instance = null;
            classLoaderToReturn = null;
            receivedClassLoader = null;
            receivedApplicationInfo = null;
            instantiateClassLoaderCallCount = 0;
        }
    }
}
