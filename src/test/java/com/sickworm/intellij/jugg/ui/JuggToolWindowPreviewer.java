package com.sickworm.intellij.jugg.ui;

import com.sickworm.intellij.jugg.deploy.JuggDeployState;
import com.sickworm.intellij.jugg.ide.toolWindow.ChangedFileInfo;
import com.sickworm.intellij.jugg.ide.toolWindow.JuggToolWindow;

import javax.swing.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Can not convert to Kotlin, or myToolWindowContent will be null.
 */
public class JuggToolWindowPreviewer {

    public static void main(String[] args) {
        JFrame frame = new JFrame("JuggToolWindow");
        JuggToolWindow juggToolWindow = new JuggToolWindow();
        frame.setContentPane(juggToolWindow.myToolWindowContent);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setVisible(true);

        juggToolWindow.onDeployStateUpdate(JuggDeployState.Companion.getREADY());

        int count = 10;
        while (count-- > 0) {
            ChangedFileInfo info = new ChangedFileInfo(
                    new File("File" + (10 - count)),
                    ChangedFileInfo.State.MODIFIED
            );
            List<ChangedFileInfo> infos = new ArrayList<>();
            infos.add(info);
            juggToolWindow.onFileStatesUpdate(infos);
        }

        List<ChangedFileInfo> infos = new ArrayList<>();
        ChangedFileInfo info = new ChangedFileInfo(
                new File("File3"),
                ChangedFileInfo.State.COMPILED
        );
        infos.add(info);
        juggToolWindow.onFileStatesUpdate(infos);
    }

}
