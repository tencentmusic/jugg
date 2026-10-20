package com.sickworm.intellij.jugg.ide.ui;

import com.intellij.openapi.project.Project;
import com.sickworm.intellij.jugg.gradle.compile.WindowsRsyncSetupException;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import java.awt.Component;
import java.awt.Container;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WindowsRsyncSetupDialogAssertions {
    public void languageCopiesCurrentPreviewAndClipboardFailurePreservesIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            WindowsRsyncSetupException failure = new WindowsRsyncSetupException(
                    "missing_tools", "C:/tool space/rsync.exe", Collections.singletonList("ssh.exe"), "");
            WindowsRsyncSetupDialog dialog = new WindowsRsyncSetupDialog(mock(Project.class), failure);
            List<Component> components = descendants((Container) dialog.createCenterPanel());
            JComboBox<?> language = (JComboBox<?>) components.stream().filter(c -> c instanceof JComboBox).findFirst().orElseThrow();
            JTextArea preview = (JTextArea) components.stream().filter(c -> c instanceof JTextArea).findFirst().orElseThrow();
            JLabel status = (JLabel) components.stream().filter(c -> c instanceof JLabel).findFirst().orElseThrow();
            String chinese = preview.getText();
            language.setSelectedIndex(1);
            assertNotEquals(chinese, preview.getText());
            assertTrue(chinese.contains(failure.getToolPath()));
            verifyCopyAndFailure(dialog, preview, status);
        });
    }

    private void verifyCopyAndFailure(WindowsRsyncSetupDialog dialog, JTextArea preview, JLabel status) {
        String english = preview.getText();
        Clipboard clipboard = mock(Clipboard.class);
        Toolkit toolkit = spy(Toolkit.getDefaultToolkit());
        doReturn(clipboard).when(toolkit).getSystemClipboard();
        try (MockedStatic<Toolkit> mocked = mockStatic(Toolkit.class, CALLS_REAL_METHODS)) {
            mocked.when(Toolkit::getDefaultToolkit).thenReturn(toolkit);
            dialog.doOKAction();
            ArgumentCaptor<Transferable> selection = ArgumentCaptor.forClass(Transferable.class);
            verify(clipboard).setContents(selection.capture(), isNull());
            assertEquals(english, selection.getValue().getTransferData(DataFlavor.stringFlavor));
            String copied = status.getText();
            assertFalse(copied.isBlank());
            doThrow(new IllegalStateException("busy")).when(clipboard).setContents(any(), any());
            dialog.doOKAction();
            assertEquals(english, preview.getText());
            assertFalse(preview.isEditable());
            assertNotEquals(copied, status.getText());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private List<Component> descendants(Container container) {
        List<Component> result = new ArrayList<>();
        for (Component component : container.getComponents()) {
            result.add(component);
            if (component instanceof Container) result.addAll(descendants((Container) component));
        }
        return result;
    }
}
