package com.sickworm.intellij.jugg.hotfix;

import android.app.Application;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Verifies the Application context exposed during BootstrapApplication startup.
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

    private void setRawApplication(
            BootstrapApplication bootstrapApplication,
            Application rawApplication
    ) throws Exception {
        Field field = BootstrapApplication.class.getDeclaredField("rawApplication");
        field.setAccessible(true);
        field.set(bootstrapApplication, rawApplication);
    }
}
